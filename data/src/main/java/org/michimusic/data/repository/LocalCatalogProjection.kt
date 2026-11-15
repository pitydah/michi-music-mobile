package org.michimusic.data.repository

import org.michimusic.core.models.Track

/**
 * Read-only projection of the existing MediaStore/SAF result, without constructing,
 * sorting, or retaining a second hierarchy of LocalAlbum objects. Album keys match
 * LocalMediaRepository.groupAlbums(), including first-track identity semantics.
 */
internal object LocalCatalogProjection {
    fun fromTracks(tracks: List<Track>): List<LocalCatalogRow> {
        val groupToAlbumKey = HashMap<String, String>()
        val output = ArrayList<LocalCatalogRow>(tracks.size.coerceAtMost(150_000))
        for (track in tracks) {
            val albumArtist = track.albumArtist.ifBlank { track.artist }
            val groupKey = if (track.albumId.isNotBlank()) {
                "mediastore:${track.albumId}"
            } else {
                "tags:${track.album.trim().lowercase()}|${albumArtist.trim().lowercase()}"
            }
            val albumKey = groupToAlbumKey.getOrPut(groupKey) {
                if (track.albumId.isNotBlank()) {
                    "album_mediastore_${track.albumId}"
                } else {
                    val albumName = track.album.ifEmpty { "Unknown Album" }
                    val artistName = albumArtist.ifEmpty { "Unknown" }
                    "album_${albumName.lowercase().replace(" ", "_")}_${artistName.lowercase().replace(" ", "_")}"
                }
            }
            // Album artist and guest track artist are proposals, never identity proof.
            val credits = linkedSetOf(track.artist, track.albumArtist, albumArtist)
            for (credit in credits) {
                if (credit.isNotBlank()) output += LocalCatalogRow(
                    track.id, albumKey, credit,
                    // The two roles remain separate even if their display labels agree.
                    observedArtistMbids = if (credit == track.artist) track.observedArtistMbids else emptyList(),
                    observedAlbumArtistMbids = if (credit == track.albumArtist) track.observedAlbumArtistMbids else emptyList(),
                    observedRecordingMbids = track.observedRecordingMbids,
                    observedReleaseMbids = track.observedReleaseMbids,
                    structuredTagsRead = track.observedTagsParsed,
                )
            }
        }
        return output
    }
}
