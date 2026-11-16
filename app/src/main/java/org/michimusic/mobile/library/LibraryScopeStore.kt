package org.michimusic.mobile.library

import kotlinx.coroutines.CancellationException
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
    // K48-R02: a failed read is not a decision. The UI and Intelligence need to
    // distinguish "no persisted selection" from "the provider failed", and a
    // temporary failure must never be published as an explicit All selection.
    private val _resolution = MutableStateFlow<LibraryScopeResolution>(LibraryScopeResolution.Loading)
    val resolution: StateFlow<LibraryScopeResolution> = _resolution.asStateFlow()
    private val selectionEpoch = AtomicLong()
    private val persistenceMutex = Mutex()

    init {
        scope.launch {
            // K48: the owner scope is long lived (application/IO). A failing
            // settings or library provider must never surface as an uncaught
            // coroutine exception, which is exactly what made the F-Droid CI
            // run flaky. Degrade to All and stay hydrated instead.
            try {
                loadPersistedScope()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // K48-R02: never publish All as if the user had chosen it. Keep
                // the placeholder unauthorised (hydrated = false) and expose a
                // retryable failure to the selector.
                reportReadFailure(LibraryScopeFailure.SETTINGS_UNAVAILABLE, selectionEpoch.get())
            }
        }
    }

    suspend fun loadPersistedScope() {
        if (_hydrated.value) return
        val expected = selectionEpoch.get()
        val saved = try {
            appDao.getSetting(KEY_ACTIVE_SCOPE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportReadFailure(LibraryScopeFailure.SETTINGS_UNAVAILABLE, expected)
            return
        }
        if (saved.isNullOrBlank() || saved == VALUE_ALL) {
            publishRestored(expected, LibraryScope.All)
            return
        }
        val currentLibraries = try {
            libraryRepository.observeLibraries().first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportReadFailure(LibraryScopeFailure.LIBRARIES_UNAVAILABLE, expected)
            return
        }
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

    /** K48-R02: explicit retry for a selector that failed to restore. */
    fun retryLoading() {
        scope.launch {
            try {
                loadPersistedScope()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportReadFailure(LibraryScopeFailure.SETTINGS_UNAVAILABLE, selectionEpoch.get())
            }
        }
    }

    /** K48-R02: explicit retry for a selection that could not be persisted. */
    fun retryPersist() {
        val current = synchronized(this) { _activeScope.value }
        persist(current)
    }

    private fun reportReadFailure(reason: LibraryScopeFailure, expected: Long) {
        synchronized(this) {
            if (expected != selectionEpoch.get()) return
            _resolution.value = LibraryScopeResolution.ReadFailed(reason)
        }
    }

    private fun publishRestored(expected: Long, restored: LibraryScope): Boolean = synchronized(this) {
        if (expected != selectionEpoch.get()) false else {
            _activeScope.value = restored
            _hydrated.value = true
            _resolution.value = LibraryScopeResolution.Ready(restored, persisted = true)
            true
        }
    }

    fun setScope(newScope: LibraryScope) {
        synchronized(this) {
            selectionEpoch.incrementAndGet()
            _activeScope.value = newScope
            _hydrated.value = true
            // Published immediately, but not persisted yet: the selector must not
            // claim the choice was saved until the write succeeded (K48-R02).
            _resolution.value = LibraryScopeResolution.Ready(newScope, persisted = false)
        }
        persist(newScope)
    }

    private fun persist(scopeToStore: LibraryScope) {
        val issued = selectionEpoch.get()
        scope.launch {
            val valueToStore = when (scopeToStore) {
                is LibraryScope.All -> VALUE_ALL
                is LibraryScope.One -> scopeToStore.libraryId
            }
            // Newer selections supersede persistence tasks not yet started.
            // K48: persistence failures stay contained; the in-memory selection
            // is already published, and an escaping exception would crash the
            // host scope (and flake instrumented/unit runs).
            try {
                persistenceMutex.withLock {
                    if (selectionEpoch.get() == issued) {
                        appDao.setSetting(SettingsEntity(key = KEY_ACTIVE_SCOPE, value = valueToStore))
                    }
                }
                synchronized(this@LibraryScopeStore) {
                    if (selectionEpoch.get() == issued) {
                        _resolution.value = LibraryScopeResolution.Ready(scopeToStore, persisted = true)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the published selection and say so honestly: the choice is
                // active but was not stored. retryPersist() is the only retry.
                synchronized(this@LibraryScopeStore) {
                    if (selectionEpoch.get() == issued) {
                        _resolution.value = LibraryScopeResolution.WriteFailed(
                            activeScope = scopeToStore,
                            reason = LibraryScopeFailure.WRITE_UNAVAILABLE,
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_ACTIVE_SCOPE = "active_library_scope"
        const val VALUE_ALL = "SCOPE_ALL"
    }
}

/**
 * K48-R02 — honest, retryable outcome of restoring or saving the library scope.
 *
 * A temporary provider failure is not a user decision: it stays [ReadFailed] and
 * keeps `hydrated` false so membership-sensitive work cannot treat the `All`
 * placeholder as authority.
 */
sealed interface LibraryScopeResolution {
    data object Loading : LibraryScopeResolution
    data class Ready(val scope: LibraryScope, val persisted: Boolean) : LibraryScopeResolution
    data class ReadFailed(val reason: LibraryScopeFailure) : LibraryScopeResolution
    data class WriteFailed(val activeScope: LibraryScope, val reason: LibraryScopeFailure) : LibraryScopeResolution
}

/** Safe failure codes: no paths, identifiers or provider text. */
enum class LibraryScopeFailure {
    SETTINGS_UNAVAILABLE,
    LIBRARIES_UNAVAILABLE,
    WRITE_UNAVAILABLE,
}
