package org.michimusic.data.repository

import org.michimusic.core.models.Playlist

class PlaylistRepository(
    private val playlistDao: org.michimusic.data.cache.PlaylistDao? = null,
) {
    suspend fun getAllPlaylists(): List<Playlist> {
        if (playlistDao == null) return emptyList()
        val cached = playlistDao.getAllPlaylists()
        return cached.map {
            Playlist(id = it.id, name = it.name, trackCount = it.trackCount)
        }
    }
}
