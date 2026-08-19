package org.michimusic.data.repository

import java.util.UUID
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

open class PlaylistRepository(
    private val playlistDao: PlaylistDao? = null,
    private val trackDao: TrackDao,
) {
    open suspend fun getAllPlaylists(): List<Playlist> {
        if (playlistDao == null) return emptyList()
        val cached = playlistDao.getAllPlaylists()
        return cached.map {
            Playlist(id = it.id, name = it.name, trackIds = parseTrackIds(it.trackIds), trackCount = it.trackCount)
        }
    }

    open suspend fun getById(id: String): Playlist? {
        val cached = playlistDao?.getAllPlaylists()?.find { it.id == id } ?: return null
        return Playlist(id = cached.id, name = cached.name, trackIds = parseTrackIds(cached.trackIds), trackCount = cached.trackCount)
    }

    open suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Playlist {
        val id = "playlist_${java.util.UUID.randomUUID().toString().take(8)}"
        val entity = CachedPlaylist(
            id = id,
            name = name.trim(),
            trackIds = trackIds.filter { it.isNotBlank() }.joinToString(","),
            trackCount = trackIds.size,
        )
        playlistDao?.insert(entity)
        return Playlist(id = id, name = name.trim(), trackIds = trackIds.filter { it.isNotBlank() }, trackCount = trackIds.size)
    }

    // New Flow-based observation of playlists
    open fun observePlaylists(): Flow<List<Playlist>> {
        return playlistDao?.observeAll()?.map { cachedList ->
            cachedList.map { CachedPlaylist ->
                Playlist(
                    id = CachedPlaylist.id,
                    name = CachedPlaylist.name,
                    trackIds = parseTrackIds(CachedPlaylist.trackIds),
                    trackCount = CachedPlaylist.trackCount
                )
            }
        } ?: flowOf(emptyList())
    }

    // New method: add tracks to existing playlist
    open suspend fun addTracksToPlaylist(id: String, newTrackIds: List<String>) {
        if (playlistDao == null) return
        val cached = playlistDao.getAllPlaylists().find { it.id == id } ?: return
        val existingIds = parseTrackIds(cached.trackIds)
        val incomingIds = newTrackIds.map { it.trim() }.filter { it.isNotBlank() }
        val combined = (existingIds + incomingIds).distinct()
        val updated = cached.copy(
            trackIds = combined.joinToString(","),
            trackCount = combined.size,
        )
        playlistDao.insert(updated)
    }

    // New method: remove a single track from playlist
    open suspend fun removeTrackFromPlaylist(id: String, trackId: String) {
        if (playlistDao == null) return
        val cached = playlistDao.getAllPlaylists().find { it.id == id } ?: return
        val target = trackId.trim()
        val existingIds = parseTrackIds(cached.trackIds).filter { it != target }
        val updated = cached.copy(
            trackIds = existingIds.joinToString(","),
            trackCount = existingIds.size,
        )
        playlistDao.insert(updated)
    }

    open suspend fun deletePlaylist(id: String) {
        playlistDao?.deleteById(id)
    }

    // Resolves a playlist's trackIds to their Track objects, preserving the stored order.
    // IDs with no matching cached track (e.g. deleted from device) are silently skipped,
    // not removed from the playlist.
    open suspend fun getTracksForPlaylist(playlist: Playlist): List<Track> =
        playlist.trackIds.mapNotNull { id -> trackDao.getTrackById(id)?.toTrack() }

    // Parses the stored CSV of track IDs, trimming whitespace and dropping blanks,
    // while preserving the original stored order.
    private fun parseTrackIds(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
}

private fun CachedTrack.toTrack(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    duration = duration,
    size = size,
    format = format,
    bitrate = bitrate,
    sampleRate = sampleRate,
    channels = channels,
    coverId = coverId,
    trackNumber = trackNumber,
    year = year,
    filepath = filepath,
)
