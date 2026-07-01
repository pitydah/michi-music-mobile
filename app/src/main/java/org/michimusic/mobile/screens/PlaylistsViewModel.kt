package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository

class PlaylistsViewModel(
    private val repo: LocalMediaRepository,
) : ViewModel() {

    private val _playlists = MutableStateFlow<List<Pair<Playlist, List<Track>>>>(emptyList())
    val playlists: StateFlow<List<Pair<Playlist, List<Track>>>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadPlaylists()
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = withContext(Dispatchers.IO) { repo.loadPlaylists() }
            _playlists.value = result
            _isLoading.value = false
        }
    }
}
