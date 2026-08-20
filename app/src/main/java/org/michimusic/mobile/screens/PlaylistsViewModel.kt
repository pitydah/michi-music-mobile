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

    // Loads a single playlist by id directly from the repository, independent of
    // whether the reactive `playlists` list has finished loading yet.
    fun loadPlaylistDetail(id: String) {
        viewModelScope.launch(ioDispatcher) {
            refreshPlaylistDetail(id)
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
                repo.addTracksToPlaylist(playlistId, trackIds)
                refreshPlaylistDetail(playlistId)
                success = true
            } catch (e: Exception) {
                _addTracksError.value = e.message ?: "No se pudieron añadir las canciones"
            } finally {
                _isAddingTracks.value = false
                onComplete(success)
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
        } catch (_: Exception) {
            PlaylistDetailUiState.NotFound
        }
    }
}
