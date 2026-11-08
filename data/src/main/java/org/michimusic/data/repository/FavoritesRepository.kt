package org.michimusic.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.SettingsEntity

interface FavoritesRepository {
    fun observeFavoriteTrackIds(): Flow<Set<String>>
    suspend fun isFavorite(trackId: String): Boolean
    suspend fun toggleFavorite(trackId: String): Boolean
    suspend fun addFavorite(trackId: String)
    suspend fun removeFavorite(trackId: String)
}

class DefaultFavoritesRepository(
    private val appDao: AppDao,
) : FavoritesRepository {

    private val mutex = Mutex()
    private val _favoritesFlow = MutableStateFlow<Set<String>>(emptySet())
    private var isInitialized = false

    private suspend fun ensureLoaded() {
        if (!isInitialized) {
            val raw = appDao.getSetting(SETTING_KEY_FAVORITES) ?: ""
            val set = raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            _favoritesFlow.value = set
            isInitialized = true
        }
    }

    override fun observeFavoriteTrackIds(): Flow<Set<String>> {
        // UI3 album hearts must not show a false empty set before disk is read.
        // Serialized with mutations; write failures still never publish a change.
        return _favoritesFlow.asStateFlow().onStart {
            mutex.withLock { ensureLoaded() }
        }
    }

    override suspend fun isFavorite(trackId: String): Boolean = mutex.withLock {
        ensureLoaded()
        return _favoritesFlow.value.contains(trackId)
    }

    override suspend fun toggleFavorite(trackId: String): Boolean = mutex.withLock {
        ensureLoaded()
        val current = _favoritesFlow.value
        val willBeFavorite = !current.contains(trackId)
        val updated = if (willBeFavorite) current + trackId else current - trackId
        // Write before publishing. Publishing first made a failed write invisible: the
        // heart stayed filled until the next launch, and the listener had no way to know
        // the change never reached disk.
        persist(updated)
        _favoritesFlow.value = updated
        return willBeFavorite
    }

    override suspend fun addFavorite(trackId: String) = mutex.withLock {
        ensureLoaded()
        val current = _favoritesFlow.value
        if (!current.contains(trackId)) {
            val updated = current + trackId
            persist(updated)
            _favoritesFlow.value = updated
        }
    }

    override suspend fun removeFavorite(trackId: String) = mutex.withLock {
        ensureLoaded()
        val current = _favoritesFlow.value
        if (current.contains(trackId)) {
            val updated = current - trackId
            persist(updated)
            _favoritesFlow.value = updated
        }
    }

    private suspend fun persist(favorites: Set<String>) {
        val serialized = favorites.joinToString(",")
        appDao.setSetting(SettingsEntity(key = SETTING_KEY_FAVORITES, value = serialized))
    }

    companion object {
        const val SETTING_KEY_FAVORITES = "user_favorite_tracks"
    }
}
