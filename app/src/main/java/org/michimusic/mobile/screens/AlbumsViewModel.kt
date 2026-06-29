package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

class AlbumsViewModel(
    private val repo: LocalMediaRepository,
) : ViewModel() {

    private val _albums = MutableStateFlow<List<LocalAlbum>>(emptyList())
    val albums: StateFlow<List<LocalAlbum>> = _albums.asStateFlow()

    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadMedia()
    }

    fun loadMedia() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) { repo.loadAlbums() }
                _albums.value = result
                _allTracks.value = result.flatMap { it.tracks }
            } catch (_: Exception) {
                _albums.value = emptyList()
                _allTracks.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
