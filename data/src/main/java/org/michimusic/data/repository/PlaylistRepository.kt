package org.michimusic.data.repository

import java.util.UUID
import org.michimusic.core.models.Playlist
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.PlaylistDao

open class PlaylistRepository(
    private val playlistDao: PlaylistDao? = null,
) {
    open suspend fun getAllPlaylists(): List<Playlist> {
        if (playlistDao == null) return emptyList()
        val cached = playlistDao.getAllPlaylists()
        return cached.map {
            Playlist(id = it.id, name = it.name, trackCount = it.trackCount)
        }
    }

    open suspend fun getById(id: String): Playlist? {
        val cached = playlistDao?.getAllPlaylists()?.find { it.id == id } ?: return null
        return Playlist(id = cached.id, name = cached.name, trackCount = cached.trackCount)
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
        return Playlist(id = id, name = name.trim(), trackCount = trackIds.size)
    }

    // New method: add tracks to existing playlist
    open suspend fun addTracksToPlaylist(id: String, newTrackIds: List<String>) {
        if (playlistDao == null) return
        val cached = playlistDao.getAllPlaylists().find { it.id == id } ?: return
        val existingIds = cached.trackIds.split(",").filter { it.isNotBlank() }
        val combined = (existingIds + newTrackIds.filter { it.isNotBlank() }).distinct()
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
        val existingIds = cached.trackIds.split(",").filter { it.isNotBlank() && it != trackId }
        val updated = cached.copy(
            trackIds = existingIds.joinToString(","),
            trackCount = existingIds.size,
        )
        playlistDao.insert(updated)
    }

    open suspend fun deletePlaylist(id: String) {
        playlistDao?.deleteById(id)
    }
}
