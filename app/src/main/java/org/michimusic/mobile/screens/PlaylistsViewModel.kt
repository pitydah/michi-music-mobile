package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.data.repository.PlaylistRepository

data class PlaylistItem(
    val id: String,
    val name: String,
    val trackCount: Int,
)

class PlaylistsViewModel(
    private val repo: PlaylistRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _playlists = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val playlists: StateFlow<List<PlaylistItem>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadPlaylists() {
        viewModelScope.launch(ioDispatcher) {
            _isLoading.value = true
            try {
                val items = repo.getAllPlaylists()
                _playlists.value = items.map {
                    PlaylistItem(id = it.id, name = it.name, trackCount = it.trackCount)
                }
            } catch (_: Exception) {
                _playlists.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Create a new playlist and refresh the list */
    suspend fun createPlaylist(name: String) = withContext(ioDispatcher) {
        repo.createPlaylist(name)
        loadPlaylists()
    }

    /** Add tracks to an existing playlist and refresh */
    suspend fun addTracksToPlaylist(id: String, trackIds: List<String>) = withContext(ioDispatcher) {
        repo.addTracksToPlaylist(id, trackIds)
        loadPlaylists()
    }



}
