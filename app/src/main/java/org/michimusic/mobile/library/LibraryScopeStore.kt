package org.michimusic.mobile.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // MI-17: All is only a placeholder until the persisted One-library selection
    // has been verified. Intelligence must not read the placeholder as authority.
    private val _hydrated = MutableStateFlow(false)
    val hydrated: StateFlow<Boolean> = _hydrated.asStateFlow()
    private val selectionEpoch = AtomicLong()
    private val persistenceMutex = Mutex()

    init {
        scope.launch {
            loadPersistedScope()
        }
    }

    suspend fun loadPersistedScope() {
        if (_hydrated.value) return
        val expected = selectionEpoch.get()
        val saved = appDao.getSetting(KEY_ACTIVE_SCOPE)
        if (saved.isNullOrBlank() || saved == VALUE_ALL) {
            publishRestored(expected, LibraryScope.All)
            return
        }
        val currentLibraries = libraryRepository.observeLibraries().first()
        val exists = currentLibraries.any { it.id == saved }
        if (publishRestored(expected, if (exists) LibraryScope.One(saved) else LibraryScope.All) && !exists) {
            // Preserve legacy fallback persistence, but never overwrite a newer
            // user selection that raced with the asynchronous restoration.
            persistenceMutex.withLock {
                if (selectionEpoch.get() == expected) {
                    appDao.setSetting(SettingsEntity(key = KEY_ACTIVE_SCOPE, value = VALUE_ALL))
                }
            }
        }
    }

    private fun publishRestored(expected: Long, restored: LibraryScope): Boolean = synchronized(this) {
        if (expected != selectionEpoch.get()) false else {
            _activeScope.value = restored
            _hydrated.value = true
            true
        }
    }

    fun setScope(newScope: LibraryScope) {
        val issued = synchronized(this) {
            val next = selectionEpoch.incrementAndGet()
            _activeScope.value = newScope
            _hydrated.value = true
            next
        }
        scope.launch {
            val valueToStore = when (newScope) {
                is LibraryScope.All -> VALUE_ALL
                is LibraryScope.One -> newScope.libraryId
            }
            // Newer selections supersede persistence tasks not yet started.
            persistenceMutex.withLock {
                if (selectionEpoch.get() == issued) {
                    appDao.setSetting(SettingsEntity(key = KEY_ACTIVE_SCOPE, value = valueToStore))
                }
            }
        }
    }

    companion object {
        const val KEY_ACTIVE_SCOPE = "active_library_scope"
        const val VALUE_ALL = "SCOPE_ALL"
    }
}
