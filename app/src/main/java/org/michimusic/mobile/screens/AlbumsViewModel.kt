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
import org.michimusic.data.cache.AppDao
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

class AlbumsViewModel(
    private val repo: LocalMediaRepository,
    private val appDao: AppDao,
) : ViewModel() {

    private val _albums = MutableStateFlow<List<LocalAlbum>>(emptyList())
    val albums: StateFlow<List<LocalAlbum>> = _albums.asStateFlow()

    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()

    private val _topTracks = MutableStateFlow<List<Track>>(emptyList())
    val topTracks: StateFlow<List<Track>> = _topTracks.asStateFlow()

    private val _recentTracks = MutableStateFlow<List<Track>>(emptyList())
    val recentTracks: StateFlow<List<Track>> = _recentTracks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadMedia() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = withContext(Dispatchers.IO) { repo.loadAlbums() }
                val tracks = result.flatMap { it.tracks }
                val trackById = tracks.associateBy { it.id }
                val smartLists = withContext(Dispatchers.IO) {
                    val top = appDao.getTopTracks(12).mapNotNull { trackById[it.trackId] }
                    val recent = appDao.getRecentHistory(20)
                        .mapNotNull { trackById[it.trackId] }
                        .distinctBy { it.id }
                        .take(12)
                    top to recent
                }
                _albums.value = result
                _allTracks.value = tracks
                _topTracks.value = smartLists.first
                _recentTracks.value = smartLists.second
            } catch (e: Exception) {
                _albums.value = emptyList()
                _allTracks.value = emptyList()
                _topTracks.value = emptyList()
                _recentTracks.value = emptyList()
                _error.value = e.message ?: "Error al cargar la biblioteca"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() { _error.value = null }
}
