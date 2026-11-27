package org.michimusic.mobile.artist

import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

/** D34. Preserve the existing editable artist screen only when its legacy
 * album.artist filter has EXACTLY the same ownership as the mosaic entry.
 * Guests, verified aliases and partial albums must use the read-only exact route.
 */
internal object ArtistExactNavigationPolicy {
    fun canUseStandardDetail(entry: ArtistPresentationEntry, scopedAlbums: List<LocalAlbum>): Boolean {
        val credit = entry.credit
        if (entry.mergedCredits != listOf(credit) || credit.isBlank() || entry.albumIds.isEmpty()) return false
        val owned = scopedAlbums.filter { it.album.id in entry.albumIds }
        val legacy = scopedAlbums.filter { it.album.artist == credit }
        // The legacy detail uses album.artist and would include every one of these
        // groups. Its set must match the mosaic entry, not merely contain it.
        if (owned.size != entry.albumIds.size || owned.map { it.album.id }.distinct().size != owned.size ||
            legacy.size != owned.size || legacy.map { it.album.id }.toSet() != entry.albumIds.toSet()
        ) return false
        if (owned.any { group ->
                group.album.artist != credit || group.tracks.any { track ->
                    track.artist.isNotBlank() && track.artist != credit
                }
            }
        ) return false
        val rows = exactArtistCatalogOccurrences(owned, entry.libraryId)
        return rows.size == entry.trackCount && rows.all { it.literalCredit == credit }
    }
}
