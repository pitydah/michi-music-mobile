package org.michimusic.data.repository

import org.michimusic.core.models.Album
import org.michimusic.core.models.Artist
import org.michimusic.core.models.Track

/**
 * MediaStore's album ID is the authoritative disc identity, so a compilation
 * stays one album even when every song has a guest artist. Tag-based libraries
 * group by album title + album artist; the track artist is only a last-resort
 * fallback when no album credit exists.
 */
fun groupAlbumsFromTracks(
    tracks: List<Track>,
): List<LocalMediaRepository.LocalAlbum> {
    val grouped = tracks.groupBy { track ->
        if (track.albumId.isNotBlank()) {
            "mediastore:${track.albumId}"
        } else {
            val albumArtist = track.albumArtist.ifBlank { track.artist }
            "tags:${track.album.trim().lowercase()}|${albumArtist.trim().lowercase()}"
        }
    }
    return grouped.map { (_, albumTracks) ->
        val first = albumTracks.first()
        val albumName = first.album.ifEmpty { "Unknown Album" }
        val artistName = first.albumArtist.ifBlank { first.artist }.ifEmpty { "Unknown" }
        val albumId = if (first.albumId.isNotBlank()) {
            "mediastore_${first.albumId}"
        } else {
            "${albumName.lowercase().replace(" ", "_")}_${artistName.lowercase().replace(" ", "_")}"
        }
        LocalMediaRepository.LocalAlbum(
            album = Album(
                id = "album_$albumId",
                title = albumName,
                artist = artistName,
                year = first.year,
                trackCount = albumTracks.size,
                coverId = first.coverId,
            ),
            tracks = albumTracks,
        )
    }.sortedBy { it.album.title }
}

/**
 * Artist identity is the album credit, never the guest artist of one song.
 * The same rule backs [LocalMediaRepository.loadArtists] and the library
 * projection so both surfaces can never drift apart.
 */
fun artistsFromAlbums(
    albums: List<LocalMediaRepository.LocalAlbum>,
): List<Pair<Artist, List<LocalMediaRepository.LocalAlbum>>> =
    albums.groupBy { it.album.artist }
        .map { (name, artistAlbums) ->
            Artist(
                id = name.lowercase().replace(" ", "_"),
                name = name.ifEmpty { "Unknown" },
                albumCount = artistAlbums.size,
                trackCount = artistAlbums.sumOf { it.tracks.size },
            ) to artistAlbums
        }
        .sortedBy { it.first.name }
