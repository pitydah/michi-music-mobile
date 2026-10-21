package org.michimusic.mobile.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.michimusic.core.models.LibraryScope
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.SettingsEntity
import org.michimusic.data.repository.MusicLibraryRepository

class LibraryScopeStore(
    private val appDao: AppDao,
    private val libraryRepository: MusicLibraryRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val _activeScope = MutableStateFlow<LibraryScope>(LibraryScope.All)
    val activeScope: StateFlow<LibraryScope> = _activeScope.asStateFlow()

    init {
        scope.launch {
            loadPersistedScope()
        }
    }

    suspend fun loadPersistedScope() {
        val saved = appDao.getSetting(KEY_ACTIVE_SCOPE)
        if (saved.isNullOrBlank() || saved == VALUE_ALL) {
            _activeScope.value = LibraryScope.All
            return
        }

        // Verify if the library still exists
        val currentLibraries = libraryRepository.observeLibraries().first()
        val exists = currentLibraries.any { it.id == saved }
        if (exists) {
            _activeScope.value = LibraryScope.One(saved)
        } else {
            // Fallback safely to All if deleted or missing
            _activeScope.value = LibraryScope.All
            appDao.setSetting(SettingsEntity(key = KEY_ACTIVE_SCOPE, value = VALUE_ALL))
        }
    }

    fun setScope(newScope: LibraryScope) {
        _activeScope.value = newScope
        scope.launch {
            val valueToStore = when (newScope) {
                is LibraryScope.All -> VALUE_ALL
                is LibraryScope.One -> newScope.libraryId
            }
            appDao.setSetting(SettingsEntity(key = KEY_ACTIVE_SCOPE, value = valueToStore))
        }
    }

    companion object {
        const val KEY_ACTIVE_SCOPE = "active_library_scope"
        const val VALUE_ALL = "SCOPE_ALL"
    }
}
