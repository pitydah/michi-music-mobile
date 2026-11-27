package org.michimusic.mobile.ui.components

import org.michimusic.mobile.artist.ArtistBulkCredit

/**
 * Single-library UI adapter. ALL-libraries mosaics must first resolve each proven
 * ArtistProfileScope independently; never derive a library from a display credit.
 * A mismatch produces an invalid preview row instead of silently changing ownership.
 */
internal fun artistBulkCreditsForOneLibrary(
    selectedLibraryId: String,
    entries: List<ArtistMosaicEntry>,
): List<ArtistBulkCredit> = entries.map { entry ->
    val explicit = entry.profileScopes
    val one = explicit.singleOrNull()
    ArtistBulkCredit(
        libraryId = when {
            explicit.isEmpty() -> selectedLibraryId
            one != null && one.credit == entry.credit -> one.libraryId
            else -> "" // The visible credit must match the proven membership credit exactly.
        },
        credit = entry.credit,
        // ALL-library entries can contain a union of albums. Trust only the verified
        // one-library scope's own list, not the aggregate display album IDs.
        albumIds = if (explicit.isEmpty()) entry.albumIds else one?.albumIds.orEmpty(),
        trackCount = entry.trackCount,
    )
}

/**
 * D19 S4-A2: exact observed membership, BEFORE grouping in ArtistMosaicEntry.
 * Case variants that share one display card remain separate Room lookups. An
 * out-of-scope track, or a duplicated track with contradictory credits, blocks the
 * entire proposal instead of being silently dropped or assigned to the first name.
 */
internal sealed interface ArtistBulkObservedInputs {
    data class Ready(val credits: List<ArtistBulkCredit>) : ArtistBulkObservedInputs
    data object InvalidMembership : ArtistBulkObservedInputs
}

internal fun artistBulkCreditsFromOccurrences(
    libraryId: String,
    occurrences: List<org.michimusic.mobile.artist.ArtistMembershipOccurrence>,
): ArtistBulkObservedInputs {
    if (libraryId.isBlank() || libraryId == "SCOPE_ALL" || occurrences.any {
            it.libraryId != libraryId || it.literalCredit.isBlank() ||
                it.albumId.isBlank() || it.trackIdentity.isBlank()
        }
    ) return ArtistBulkObservedInputs.InvalidMembership
    // Same track identity cannot be silently assigned to two conflicting credits.
    if (occurrences.groupBy { it.trackIdentity }.values.any { group ->
            group.map { it.literalCredit }.distinct().size > 1 ||
                group.map { it.albumId }.distinct().size > 1
        }
    ) return ArtistBulkObservedInputs.InvalidMembership
    val credits = occurrences.groupBy { it.literalCredit }.map { (credit, group) ->
        ArtistBulkCredit(
            libraryId = libraryId,
            credit = credit,
            albumIds = group.map { it.albumId }.distinct().sorted(),
            trackCount = group.map { it.trackIdentity }.distinct().size,
        )
    }.sortedWith(compareBy<ArtistBulkCredit> { it.credit.lowercase(java.util.Locale.ROOT) }
        .thenBy { it.credit })
    return ArtistBulkObservedInputs.Ready(credits)
}
