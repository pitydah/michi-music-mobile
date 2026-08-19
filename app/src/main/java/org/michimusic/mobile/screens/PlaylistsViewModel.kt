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
import org.michimusic.data.repository.PlaylistRepository

data class PlaylistItem(
    val id: String,
    val name: String,
    val trackCount: Int,
)

sealed interface PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState
    data class Found(val playlist: PlaylistItem) : PlaylistDetailUiState
    data object NotFound : PlaylistDetailUiState
}

class PlaylistsViewModel(
    private val repo: PlaylistRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _playlists = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val playlists: StateFlow<List<PlaylistItem>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedPlaylistState =
        MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)
    val selectedPlaylistState: StateFlow<PlaylistDetailUiState> = _selectedPlaylistState.asStateFlow()

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
            _selectedPlaylistState.value = PlaylistDetailUiState.Loading
            _selectedPlaylistState.value = try {
                val playlist = repo.getById(id)
                if (playlist != null) {
                    PlaylistDetailUiState.Found(
                        PlaylistItem(id = playlist.id, name = playlist.name, trackCount = playlist.trackCount),
                    )
                } else {
                    PlaylistDetailUiState.NotFound
                }
            } catch (_: Exception) {
                PlaylistDetailUiState.NotFound
            }
        }
    }
}
