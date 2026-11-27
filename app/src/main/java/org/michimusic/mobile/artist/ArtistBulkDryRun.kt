package org.michimusic.mobile.artist

import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.Locale

/** D18 S4-A: a local, read-only proposal. This is never an execution authorization. */
data class ArtistBulkReadSnapshot(
    val libraryId: String,
    val sourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
    val libraryRevision: Long,
) {
    fun isValid(): Boolean = libraryId.isNotBlank() && libraryId != "SCOPE_ALL" &&
        sourceId.isNotBlank() && rootEpoch >= 0L && catalogRevision >= 0L && libraryRevision >= 0L
}

/** One precise local credit/album membership; names are display data, never MBID evidence. */
data class ArtistBulkCredit(
    val libraryId: String,
    val credit: String,
    val albumIds: List<String>,
    val trackCount: Int,
)

enum class ArtistBulkDisposition {
    ELIGIBLE_FOR_EXPLICIT_REVIEW,
    IDENTITY_REQUIRED,
    MEMBERSHIP_RECONFIRMATION,
    PROFILE_MISSING,
    IDENTITY_CONFLICT,
    INVALID_SCOPE,
    DUPLICATE_SCOPE,
    READ_ERROR,
}

data class ArtistBulkPreviewRow(
    val credit: String,
    val albumIds: List<String>,
    val trackCount: Int,
    val disposition: ArtistBulkDisposition,
    val mbid: String? = null,
    val associationId: String? = null,
    val cachedProfileStale: Boolean = false,
    /** Fingerprint for rendering/diagnosis only; NOT a confirmation ticket. */
    val diagnosticFingerprint: String? = null,
)

enum class ArtistBulkPreviewBlock { INVALID_SNAPSHOT, SCOPE_CHANGED, BUDGET_EXCEEDED, SNAPSHOT_UNAVAILABLE, DUPLICATE_INPUT }

data class ArtistBulkPreview(
    val snapshot: ArtistBulkReadSnapshot,
    val rows: List<ArtistBulkPreviewRow>,
    val blocked: ArtistBulkPreviewBlock? = null,
) {
    val eligibleCount: Int get() = rows.count { it.disposition == ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW }
    val requiresReviewCount: Int get() = rows.count {
        it.disposition != ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW
    }
    /** Bulk preview does not grant execution permission, even for eligible rows. */
    val mayExecuteWithoutConsent: Boolean get() = false
}

fun interface ArtistBulkSnapshotReader {
    /** Must return the current library+root+catalog+selection generation, not a cached UI value. */
    suspend fun current(): ArtistBulkReadSnapshot
}

/**
 * Reads only existing local Room association state. No HTTP, file writes, identity guesses,
 * profile updates, WorkManager scheduling, or implicit consent occur in this function.
 * Snapshot checks guard the report against a root/library/rescan change DURING the read.
 * Any future executor must independently validate every exact association at commit time.
 */
class ArtistBulkDryRun(
    private val store: ArtistEnrichmentSelectionStore,
    private val snapshots: ArtistBulkSnapshotReader,
    private val maxCredits: Int = 128,
    private val snapshotCheckStride: Int = 32,
) {
    init {
        require(maxCredits in 1..512)
        require(snapshotCheckStride in 1..128)
    }

    suspend fun preview(
        expected: ArtistBulkReadSnapshot,
        credits: List<ArtistBulkCredit>,
    ): ArtistBulkPreview {
        if (!expected.isValid()) return ArtistBulkPreview(expected, emptyList(), ArtistBulkPreviewBlock.INVALID_SNAPSHOT)
        if (credits.size > maxCredits) return ArtistBulkPreview(expected, emptyList(), ArtistBulkPreviewBlock.BUDGET_EXCEEDED)
        checkSnapshot(expected)?.let { return ArtistBulkPreview(expected, emptyList(), it) }
        // Ordinal-independent order, no truncation, and no merging of same-name homonyms.
        val ordered = credits.sortedWith(
            compareBy<ArtistBulkCredit> { it.credit.lowercase(Locale.ROOT) }
                .thenBy { it.credit }.thenBy { it.libraryId }
                .thenBy { it.albumIds.sorted().joinToString("\u0000") },
        )
        val keys = ordered.map { credit ->
            scopedArtistCreditKey(credit.libraryId, credit.credit, credit.albumIds)
                ?.let { (key, group) -> "${credit.libraryId}\u0000$key\u0000$group" }
        }
        val repeated = keys.filterNotNull().groupingBy { it }.eachCount()
        val output = ArrayList<ArtistBulkPreviewRow>(ordered.size)
        for ((index, credit) in ordered.withIndex()) {
            // D19: bounded verification rounds. The snapshot reader may hash a whole
            // library on IO; doing so for every credit makes a 128-item review O(n*m).
            // The terminal check below still invalidates the WHOLE result on change.
            if (index > 0 && index % snapshotCheckStride == 0) {
                checkSnapshot(expected)?.let {
                    return ArtistBulkPreview(expected, emptyList(), it)
                }
            }
            val key = keys[index]
            val valid = credit.libraryId == expected.libraryId && credit.credit.isNotBlank() &&
                credit.trackCount > 0 && credit.albumIds.isNotEmpty() &&
                credit.albumIds.all { it.isNotBlank() } &&
                credit.albumIds.distinct().size == credit.albumIds.size && key != null
            val diagnostic = if (valid) fingerprint(expected, key!!) else null
            if (!valid) {
                output += ArtistBulkPreviewRow(credit.credit, credit.albumIds, credit.trackCount,
                    ArtistBulkDisposition.INVALID_SCOPE)
                continue
            }
            if (repeated.getValue(key!!) != 1) {
                output += ArtistBulkPreviewRow(credit.credit, credit.albumIds, credit.trackCount,
                    ArtistBulkDisposition.DUPLICATE_SCOPE, diagnosticFingerprint = diagnostic)
                continue
            }
            val row = try {
                val persisted = store.load(credit.libraryId, credit.credit, credit.albumIds)
                val validMbid = canonicalArtistMbid(persisted?.profile?.mbid)
                val status = when {
                    persisted == null -> ArtistBulkDisposition.IDENTITY_REQUIRED
                    validMbid == null || persisted.profile.mbid != validMbid -> ArtistBulkDisposition.IDENTITY_CONFLICT
                    persisted.associationGroupKey.isNullOrBlank() -> ArtistBulkDisposition.IDENTITY_CONFLICT
                    persisted.needsConfirmation || persisted.unboundAlbumIds.isNotEmpty() ->
                        ArtistBulkDisposition.MEMBERSHIP_RECONFIRMATION
                    !persisted.profilePersisted -> ArtistBulkDisposition.PROFILE_MISSING
                    else -> ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW
                }
                ArtistBulkPreviewRow(credit.credit, credit.albumIds, credit.trackCount,
                    status, mbid = validMbid,
                    associationId = persisted?.associationGroupKey,
                    cachedProfileStale = persisted?.stale == true,
                    diagnosticFingerprint = diagnostic)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ArtistAssociationAmbiguousException) {
                ArtistBulkPreviewRow(credit.credit, credit.albumIds, credit.trackCount,
                    ArtistBulkDisposition.IDENTITY_CONFLICT, diagnosticFingerprint = diagnostic)
            } catch (_: Exception) {
                ArtistBulkPreviewRow(credit.credit, credit.albumIds, credit.trackCount,
                    ArtistBulkDisposition.READ_ERROR, diagnosticFingerprint = diagnostic)
            }
            output += row
        }
        checkSnapshot(expected)?.let { return ArtistBulkPreview(expected, emptyList(), it) }
        return ArtistBulkPreview(expected, output)
    }

    private suspend fun checkSnapshot(expected: ArtistBulkReadSnapshot): ArtistBulkPreviewBlock? = try {
        if (snapshots.current() == expected) null else ArtistBulkPreviewBlock.SCOPE_CHANGED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ArtistBulkPreviewBlock.SNAPSHOT_UNAVAILABLE
    }

    private fun fingerprint(snapshot: ArtistBulkReadSnapshot, scopeKey: String): String {
        val material = listOf(snapshot.libraryId, snapshot.sourceId, snapshot.rootEpoch.toString(),
            snapshot.catalogRevision.toString(), snapshot.libraryRevision.toString(), scopeKey)
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
