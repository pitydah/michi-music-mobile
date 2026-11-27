package org.michimusic.mobile.artist

import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * D20 S4-A3: bounded, read-only paging over ONE authoritative library snapshot.
 * Nothing is exposed until every page succeeds and the final witness still matches.
 * A page is not a work unit or an authorization ticket. No HTTP or writes occur here.
 */
class ArtistBulkPagedDryRun(
    private val store: ArtistEnrichmentSelectionStore,
    private val snapshots: ArtistBulkSnapshotReader,
    private val pageSize: Int = 128,
    private val maxCredits: Int = 2048,
) {
    init {
        require(pageSize in 1..128) { "Page size must be bounded" }
        require(maxCredits in 1..4096) { "Maximum credit budget must be bounded" }
    }

    suspend fun preview(
        expected: ArtistBulkReadSnapshot,
        credits: List<ArtistBulkCredit>,
    ): ArtistBulkPreview {
        if (!expected.isValid()) return blocked(expected, ArtistBulkPreviewBlock.INVALID_SNAPSHOT)
        if (credits.size > maxCredits) return blocked(expected, ArtistBulkPreviewBlock.BUDGET_EXCEEDED)
        // D18 checks duplicates within one page. Detect the same normalized association
        // across page boundaries before the first Room read; never silently collapse it.
        val keys = credits.mapNotNull { credit ->
            scopedArtistCreditKey(credit.libraryId, credit.credit, credit.albumIds)?.let { (key, group) ->
                "${credit.libraryId}\u0000$key\u0000$group"
            }
        }
        if (keys.toSet().size != keys.size) return blocked(expected, ArtistBulkPreviewBlock.DUPLICATE_INPUT)
        val ordered = credits.sortedWith(
            compareBy<ArtistBulkCredit> { it.credit.lowercase(Locale.ROOT) }
                .thenBy { it.credit }.thenBy { it.libraryId }
                .thenBy { it.albumIds.sorted().joinToString("\u0000") },
        )
        val rows = ArrayList<ArtistBulkPreviewRow>(ordered.size)
        val engine = ArtistBulkDryRun(
            store, snapshots, maxCredits = pageSize, snapshotCheckStride = pageSize,
        )
        for (page in ordered.chunked(pageSize)) {
            val result = engine.preview(expected, page)
            if (result.blocked != null) return blocked(expected, result.blocked)
            // A page may have been changed by a future implementation: accept no
            // partial or reordered output even if it labels itself successful.
            if (result.rows.size != page.size) {
                return blocked(expected, ArtistBulkPreviewBlock.SNAPSHOT_UNAVAILABLE)
            }
            rows.addAll(result.rows)
        }
        // Covers empty libraries and the time between the last page and publication.
        val lastWitness = try {
            snapshots.current()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return blocked(expected, ArtistBulkPreviewBlock.SNAPSHOT_UNAVAILABLE)
        }
        if (lastWitness != expected) return blocked(expected, ArtistBulkPreviewBlock.SCOPE_CHANGED)
        return ArtistBulkPreview(expected, rows)
    }

    private fun blocked(
        expected: ArtistBulkReadSnapshot,
        reason: ArtistBulkPreviewBlock,
    ) = ArtistBulkPreview(expected, emptyList(), reason)
}
