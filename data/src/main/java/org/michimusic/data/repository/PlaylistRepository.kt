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
        val id = "playlist_${UUID.randomUUID().toString().take(8)}"
        val entity = CachedPlaylist(
            id = id,
            name = name.trim(),
            trackIds = trackIds.joinToString(","),
            trackCount = trackIds.size,
        )
        playlistDao?.insert(entity)
        return Playlist(id = id, name = name.trim(), trackCount = trackIds.size)
    }

    open suspend fun deletePlaylist(id: String) {
        playlistDao?.deleteById(id)
    }
}
