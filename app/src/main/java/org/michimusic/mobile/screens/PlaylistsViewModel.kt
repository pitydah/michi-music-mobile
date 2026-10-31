package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.MoveDirection
import org.michimusic.data.repository.PlaylistRepository

data class PlaylistItem(
    val id: String,
    val name: String,
    val trackCount: Int,
    val tracks: List<Track> = emptyList(),
    val trackIds: List<String> = emptyList(),
)

sealed interface PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState
    data class Found(val playlist: PlaylistItem) : PlaylistDetailUiState
    data object NotFound : PlaylistDetailUiState
    data class Error(val message: String) : PlaylistDetailUiState
}

sealed interface PlayPlaylistResult {
    data class Success(val trackCount: Int) : PlayPlaylistResult
    data object Empty : PlayPlaylistResult
    data object NotFound : PlayPlaylistResult
    data class Error(val message: String) : PlayPlaylistResult
}

sealed interface CreatePlaylistResult {
    data class Success(val playlist: PlaylistItem) : CreatePlaylistResult
    data object BlankName : CreatePlaylistResult
    data object AlreadyInProgress : CreatePlaylistResult
    data class Error(val message: String) : CreatePlaylistResult
}

class PlaylistsViewModel(
    private val repo: PlaylistRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _playlists = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val playlists: StateFlow<List<PlaylistItem>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isCreatingPlaylist = MutableStateFlow(false)
    val isCreatingPlaylist: StateFlow<Boolean> = _isCreatingPlaylist.asStateFlow()

    private val _createPlaylistError = MutableStateFlow<String?>(null)
    val createPlaylistError: StateFlow<String?> = _createPlaylistError.asStateFlow()

    private val _selectedPlaylistState =
        MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)
    val selectedPlaylistState: StateFlow<PlaylistDetailUiState> = _selectedPlaylistState.asStateFlow()

    // Tracks available on this device to be picked from AddTracksDialog. Loaded on demand
    // when the dialog opens, not filtered here - the screen filters out tracks already
    // present in the playlist so this stays reusable regardless of which playlist is open.
    private val _availableTracks = MutableStateFlow<List<Track>>(emptyList())
    val availableTracks: StateFlow<List<Track>> = _availableTracks.asStateFlow()

    private val _isLoadingAvailableTracks = MutableStateFlow(false)
    val isLoadingAvailableTracks: StateFlow<Boolean> = _isLoadingAvailableTracks.asStateFlow()

    private val _isAddingTracks = MutableStateFlow(false)
    val isAddingTracks: StateFlow<Boolean> = _isAddingTracks.asStateFlow()

    private val _addTracksError = MutableStateFlow<String?>(null)
    val addTracksError: StateFlow<String?> = _addTracksError.asStateFlow()

    private val _isRemovingTrack = MutableStateFlow(false)
    val isRemovingTrack: StateFlow<Boolean> = _isRemovingTrack.asStateFlow()

    private val _isDeletingPlaylist = MutableStateFlow(false)
    val isDeletingPlaylist: StateFlow<Boolean> = _isDeletingPlaylist.asStateFlow()

    private val _isRenamingPlaylist = MutableStateFlow(false)
    val isRenamingPlaylist: StateFlow<Boolean> = _isRenamingPlaylist.asStateFlow()

    private val _isReorderingTrack = MutableStateFlow(false)
    val isReorderingTrack: StateFlow<Boolean> = _isReorderingTrack.asStateFlow()

    init {
        viewModelScope.launch(ioDispatcher) {
            _isLoading.value = true
            repo.observePlaylists().collect { cachedList ->
                _playlists.value = cachedList.map { PlaylistItem(id = it.id, name = it.name, trackCount = it.trackCount) }
                _isLoading.value = false
            }
        }
    }

    // Retained for compatibility; can be removed later
    fun loadPlaylists() {
        viewModelScope.launch(ioDispatcher) {
            _isLoading.value = true
            try {
                val items = repo.getAllPlaylists()
                _playlists.value = items.map { PlaylistItem(id = it.id, name = it.name, trackCount = it.trackCount) }
            } catch (_: Exception) {
                _playlists.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createPlaylist(name: String, onResult: (CreatePlaylistResult) -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(CreatePlaylistResult.BlankName)
            return
        }
        if (_isCreatingPlaylist.value) {
            onResult(CreatePlaylistResult.AlreadyInProgress)
            return
        }
        _isCreatingPlaylist.value = true
        _createPlaylistError.value = null
        viewModelScope.launch(ioDispatcher) {
            _isLoading.value = true
            try {
                val created = repo.createPlaylist(trimmed)
                val item = PlaylistItem(id = created.id, name = created.name, trackCount = created.trackCount)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    _isCreatingPlaylist.value = false
                    onResult(CreatePlaylistResult.Success(item))
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Error al crear la playlist"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    _createPlaylistError.value = msg
                    _isCreatingPlaylist.value = false
                    onResult(CreatePlaylistResult.Error(msg))
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Loads a single playlist by id directly from the repository, independent of
    // whether the reactive `playlists` list has finished loading yet.
    fun loadPlaylistDetail(id: String) {
        viewModelScope.launch(ioDispatcher) {
            refreshPlaylistDetail(id)
        }
    }
    /**
     * Resolves all tracks for a given playlist and immediately initiates real playback
     * through the provided AudioController at index 0. Reports accurate status via onResult.
     */
    fun playPlaylist(
        id: String,
        audioController: org.michimusic.player.AudioController? = null,
        sessionManager: org.michimusic.mobile.playback.PlaybackSessionManager? = null,
        onResult: (PlayPlaylistResult) -> Unit = {}
    ) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val playlist = repo.getById(id)
                if (playlist == null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        onResult(PlayPlaylistResult.NotFound)
                    }
                    return@launch
                }
                val tracks = repo.getTracksForPlaylist(playlist)
                if (tracks.isEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        onResult(PlayPlaylistResult.Empty)
                    }
                    return@launch
                }
                if (sessionManager != null) {
                    val outcome = sessionManager.requestQueuePlayback(tracks, 0)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        when (outcome) {
                            is org.michimusic.mobile.playback.QueuePlaybackOutcome.Confirmed,
                            is org.michimusic.mobile.playback.QueuePlaybackOutcome.Accepted -> {
                                onResult(PlayPlaylistResult.Success(tracks.size))
                            }
                            is org.michimusic.mobile.playback.QueuePlaybackOutcome.Rejected -> {
                                onResult(PlayPlaylistResult.Error(outcome.reason))
                            }
                            is org.michimusic.mobile.playback.QueuePlaybackOutcome.Failed -> {
                                onResult(PlayPlaylistResult.Error(outcome.error))
                            }
                            is org.michimusic.mobile.playback.QueuePlaybackOutcome.Disconnected -> {
                                onResult(PlayPlaylistResult.Error(outcome.reason))
                            }
                            is org.michimusic.mobile.playback.QueuePlaybackOutcome.Obsolete -> {
                                onResult(PlayPlaylistResult.Error(outcome.reason))
                            }
                        }
                    }
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        audioController?.playQueue(tracks, 0)
                        onResult(PlayPlaylistResult.Success(tracks.size))
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onResult(PlayPlaylistResult.Error(e.message ?: "Error al reproducir playlist"))
                }
            }
        }
    }

    // Loads the device's local track library for the "add tracks" picker. Reuses
    // LocalMediaRepository, the same source AlbumsScreen/AlbumsViewModel already use,
    // instead of querying MediaStore a second time.
    fun loadAvailableTracks() {
        viewModelScope.launch(ioDispatcher) {
            _isLoadingAvailableTracks.value = true
            _availableTracks.value = try {
                localMediaRepository.loadTracks()
            } catch (_: Exception) {
                emptyList()
            } finally {
                _isLoadingAvailableTracks.value = false
            }
        }
    }

    // Adds tracks to a playlist (preserving the caller's selection order, deduped by the
    // Repository) and refreshes the detail state so the new tracks appear immediately.
    // onComplete reports success/failure so the caller (the AddTracksDialog host) can
    // decide when it's safe to dismiss - not just when the operation reaches "idle".
    fun addTracksToPlaylist(playlistId: String, trackIds: List<String>, onComplete: (success: Boolean) -> Unit = {}) {
        if (trackIds.isEmpty() || _isAddingTracks.value) {
            onComplete(false)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            _isAddingTracks.value = true
            _addTracksError.value = null
            var success = false
            try {
                when (val result = repo.addTracksToPlaylist(playlistId, trackIds)) {
                    is PlaylistRepository.PlaylistAddResult.Success -> {
                        refreshPlaylistDetail(playlistId)
                        success = true
                    }
                    is PlaylistRepository.PlaylistAddResult.PlaylistNotFound -> {
                        _addTracksError.value = "La playlist especificada no existe"
                    }
                    is PlaylistRepository.PlaylistAddResult.EmptyTracks -> {
                        _addTracksError.value = "No se seleccionaron canciones válidas"
                    }
                    is PlaylistRepository.PlaylistAddResult.Error -> {
                        _addTracksError.value = result.message
                    }
                }
            } catch (e: Exception) {
                _addTracksError.value = e.message ?: "No se pudieron añadir las canciones"
            } finally {
                _isAddingTracks.value = false
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onComplete(success)
                }
            }
        }
    }

    fun addAlbumToPlaylist(playlistId: String, albumTracks: List<Track>, onComplete: (success: Boolean) -> Unit = {}) {
        if (albumTracks.isEmpty() || _isAddingTracks.value) {
            onComplete(false)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            _isAddingTracks.value = true
            _addTracksError.value = null
            var success = false
            try {
                when (val result = repo.addAlbumToPlaylist(playlistId, albumTracks)) {
                    is PlaylistRepository.PlaylistAddResult.Success -> {
                        refreshPlaylistDetail(playlistId)
                        success = true
                    }
                    is PlaylistRepository.PlaylistAddResult.PlaylistNotFound -> {
                        _addTracksError.value = "La playlist especificada no existe"
                    }
                    is PlaylistRepository.PlaylistAddResult.EmptyTracks -> {
                        _addTracksError.value = "El álbum no contiene canciones válidas"
                    }
                    is PlaylistRepository.PlaylistAddResult.Error -> {
                        _addTracksError.value = result.message
                    }
                }
            } catch (e: Exception) {
                _addTracksError.value = e.message ?: "No se pudo añadir el álbum"
            } finally {
                _isAddingTracks.value = false
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onComplete(success)
                }
            }
        }
    }

    fun clearAddTracksError() {
        _addTracksError.value = null
    }

    // Removes a single track from a playlist and refreshes the detail state so the row
    // disappears and the count drops immediately. Reuses the same error/snackbar flow as
    // addTracksToPlaylist rather than introducing a parallel one. Guarded by isRemovingTrack
    // so a double-tap on the confirm button can't fire two overlapping removals.
    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        if (_isRemovingTrack.value) return
        viewModelScope.launch(ioDispatcher) {
            _isRemovingTrack.value = true
            _addTracksError.value = null
            try {
                repo.removeTrackFromPlaylist(playlistId, trackId)
                refreshPlaylistDetail(playlistId)
            } catch (e: Exception) {
                _addTracksError.value = e.message ?: "No se pudo quitar la canción"
            } finally {
                _isRemovingTrack.value = false
            }
        }
    }

    // Deletes the playlist itself only - never its tracks or their underlying media files.
    // Guarded by isDeletingPlaylist so a double-tap on the confirm button can't fire two
    // overlapping deletions. onComplete reports success/failure so the caller (PlaylistDetailScreen)
    // knows when it's safe to navigate back - never before the delete has actually finished.
    fun deletePlaylist(playlistId: String, onComplete: (success: Boolean) -> Unit = {}) {
        if (_isDeletingPlaylist.value) return
        viewModelScope.launch(ioDispatcher) {
            _isDeletingPlaylist.value = true
            _addTracksError.value = null
            var success = false
            try {
                repo.deletePlaylist(playlistId)
                success = true
            } catch (e: Exception) {
                _addTracksError.value = e.message ?: "No se pudo eliminar la playlist"
            } finally {
                _isDeletingPlaylist.value = false
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onComplete(success)
                }
            }
        }
    }

    // Moves a track one step up/down within the playlist's currently resolvable/visible
    // order (see PlaylistRepository.moveTrack for the exact policy on unresolvable ids).
    // Guarded by isReorderingTrack against overlapping taps - up/down are rapid-fire UI
    // actions more likely to be double-tapped than the confirmation-gated operations above.
    fun moveTrack(playlistId: String, trackId: String, direction: MoveDirection) {
        if (_isReorderingTrack.value) return
        viewModelScope.launch(ioDispatcher) {
            _isReorderingTrack.value = true
            _addTracksError.value = null
            try {
                repo.moveTrack(playlistId, trackId, direction)
                refreshPlaylistDetail(playlistId)
            } catch (e: Exception) {
                _addTracksError.value = e.message ?: "No se pudo reordenar la canción"
            } finally {
                _isReorderingTrack.value = false
            }
        }
    }

    // Renames a playlist, reusing the same non-blank/trim rule createPlaylist already applies
    // (no new validation invented here). Guarded by isRenamingPlaylist against a double-tap,
    // and refreshes the detail state on success so PlaylistDetail shows the new name without
    // requiring a separate reactive Flow for the single selected playlist.
    fun renamePlaylist(playlistId: String, newName: String, onComplete: (success: Boolean) -> Unit = {}) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || _isRenamingPlaylist.value) {
            onComplete(false)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            _isRenamingPlaylist.value = true
            _addTracksError.value = null
            var success = false
            try {
                repo.renamePlaylist(playlistId, trimmed)
                refreshPlaylistDetail(playlistId)
                success = true
            } catch (e: Exception) {
                _addTracksError.value = e.message ?: "No se pudo renombrar la playlist"
            } finally {
                _isRenamingPlaylist.value = false
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onComplete(success)
                }
            }
        }
    }

    private suspend fun refreshPlaylistDetail(id: String) {
        _selectedPlaylistState.value = PlaylistDetailUiState.Loading
        _selectedPlaylistState.value = try {
            val playlist = repo.getById(id)
            if (playlist != null) {
                val tracks = repo.getTracksForPlaylist(playlist)
                PlaylistDetailUiState.Found(
                    PlaylistItem(
                        id = playlist.id,
                        name = playlist.name,
                        trackCount = playlist.trackCount,
                        tracks = tracks,
                        trackIds = playlist.trackIds,
                    ),
                )
            } else {
                PlaylistDetailUiState.NotFound
            }
        } catch (e: Exception) {
            PlaylistDetailUiState.Error(e.message ?: "No se pudo cargar la playlist")
        }
    }
}
