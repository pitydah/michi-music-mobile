package org.michimusic.data.repository

import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack

/**
 * After a process restart the MediaStore provider may still expose old tag
 * values. The durable CachedTrack row proves what this app last committed, so
 * it overlays the provider row only when the physical size agrees. Artwork,
 * source and other provider identity remain untouched.
 */
object MetadataLibraryDurableOverlay {
    fun apply(tracks: List<Track>, durable: List<CachedTrack>): List<Track> {
        if (tracks.isEmpty() || durable.isEmpty()) return tracks
        val byId = durable.associateBy { it.id }
        return tracks.map { track ->
            val row = byId[track.id] ?: return@map track
            if (row.filepath != track.filepath || row.metadataRevision <= 0L ||
                row.size <= 0L || row.size != track.size
            ) return@map track
            val albumChanged = row.album != track.album ||
                row.albumArtist != track.albumArtist
            track.copy(
                title = row.title,
                artist = row.artist,
                album = row.album,
                albumId = if (albumChanged) "" else track.albumId,
                albumArtist = row.albumArtist,
                genre = row.genre,
                genreValues = if (row.genre.isBlank()) emptyList() else listOf(row.genre),
                trackNumber = row.trackNumber,
                year = row.year,
                size = row.size,
                contentHash = row.verifiedSha256.ifBlank { track.contentHash },
            )
        }
    }
}
