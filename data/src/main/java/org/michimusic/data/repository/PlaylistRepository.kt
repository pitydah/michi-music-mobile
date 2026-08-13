package org.michimusic.data.repository

import org.michimusic.core.models.Playlist

open class PlaylistRepository(
    private val playlistDao: org.michimusic.data.cache.PlaylistDao? = null,
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
}
