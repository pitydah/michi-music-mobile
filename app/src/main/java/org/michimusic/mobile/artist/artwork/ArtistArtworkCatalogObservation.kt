package org.michimusic.mobile.artist.artwork

/**
 * A pure observation over tracks already read by the existing media index.
 * No MediaStore, SAF, Room, audio I/O, provider request or scheduling occurs here.
 * This is a proposal signal, not an identity confirmation or album-credit binding.
 */
internal data class ArtistArtworkCatalogRow(
    val stableTrackId: String,
    val albumKey: String,
    val localCredit: String,
)

internal data class ArtistArtworkCatalogSnapshot(
    val libraryId: String,
    val sourceId: String,
    val sourceRevision: Long,
    val fresh: Boolean,
    val completeForActiveSource: Boolean,
    val rows: List<ArtistArtworkCatalogRow>,
)

internal sealed interface ArtistArtworkCatalogDelta {
    data object Ignore : ArtistArtworkCatalogDelta
    /** The first trustworthy observation establishes a baseline, never a bulk backfill. */
    data object BaselineOnly : ArtistArtworkCatalogDelta
    /** Only distinct new track IDs and proposed credits; caller revalidates binding in Room. */
    data class NewCredits(val credits: Set<String>) : ArtistArtworkCatalogDelta
}

internal object ArtistArtworkCatalogObservation {
    fun delta(
        before: ArtistArtworkCatalogSnapshot?,
        current: ArtistArtworkCatalogSnapshot,
    ): ArtistArtworkCatalogDelta {
        if (!current.fresh || !current.completeForActiveSource ||
            current.libraryId.isBlank() || current.libraryId == "SCOPE_ALL" ||
            current.sourceId.isBlank() || current.sourceRevision < 0L
        ) return ArtistArtworkCatalogDelta.Ignore
        // The source may be switched/rolled back independently of MediaStore updates.
        // A new active source always needs a new baseline, never implicit backfill.
        if (before == null || before.libraryId != current.libraryId ||
            before.sourceId != current.sourceId || before.sourceRevision != current.sourceRevision ||
            !before.completeForActiveSource
        ) return ArtistArtworkCatalogDelta.BaselineOnly

        val previousIds = before.rows.mapTo(HashSet()) { it.stableTrackId }
        val credits = current.rows.asSequence()
            .filter { it.stableTrackId.isNotBlank() && it.stableTrackId !in previousIds }
            .filter { it.albumKey.isNotBlank() }
            .mapNotNull { ArtistArtworkAutoWorkKey.creditKey(it.localCredit) }
            .toSet()
        return if (credits.isEmpty()) ArtistArtworkCatalogDelta.Ignore
        else ArtistArtworkCatalogDelta.NewCredits(credits)
    }
}
