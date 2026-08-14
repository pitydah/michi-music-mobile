package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.AppDao
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.data.repository.PlaylistRepository

class AlbumsViewModel(
    private val repo: LocalMediaRepository,
    private val appDao: AppDao,
    private val playlistRepo: PlaylistRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _albums = MutableStateFlow<List<LocalAlbum>>(emptyList())
    val albums: StateFlow<List<LocalAlbum>> = _albums.asStateFlow()

    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()

    private val _topTracks = MutableStateFlow<List<Track>>(emptyList())
    val topTracks: StateFlow<List<Track>> = _topTracks.asStateFlow()

    private val _recentTracks = MutableStateFlow<List<Track>>(emptyList())
    val recentTracks: StateFlow<List<Track>> = _recentTracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loadJob: Job? = null

    fun cancelLoading() {
        loadJob?.cancel()
        loadJob = null
    }

    fun loadMedia() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(ioDispatcher) {
            _isLoading.value = true
            _error.value = null
            try {
                val result = try {
                    repo.loadAlbums()
                } catch (e: Exception) {
                    _error.value = e.message ?: "Error al cargar la biblioteca"
                    emptyList()
                }
                val tracks = result.flatMap { it.tracks }
                val trackById = tracks.associateBy { it.id }
                val smartLists = try {
                    val top = appDao.getTopTracks(12).mapNotNull { trackById[it.trackId] }
                    val recent = appDao.getRecentHistory(20)
                        .mapNotNull { trackById[it.trackId] }
                        .distinctBy { it.id }
                        .take(12)
                    top to recent
                } catch (e: Exception) {
                    emptyList<Track>() to emptyList<Track>()
                }
                val loadedPlaylists = try {
                    playlistRepo.getAllPlaylists()
                } catch (_: Exception) {
                    emptyList()
                }

                _albums.value = result
                _allTracks.value = tracks
                _topTracks.value = smartLists.first
                _recentTracks.value = smartLists.second
                _playlists.value = loadedPlaylists
            } catch (e: Exception) {
                _albums.value = emptyList()
                _allTracks.value = emptyList()
                _topTracks.value = emptyList()
                _recentTracks.value = emptyList()
                _playlists.value = emptyList()
                _error.value = e.message ?: "Error al cargar la biblioteca"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createPlaylist(name: String, onComplete: () -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch(ioDispatcher) {
            try {
                playlistRepo.createPlaylist(name)
                val updated = playlistRepo.getAllPlaylists()
                _playlists.value = updated
            } catch (e: Exception) {
                _error.value = "Error al crear la playlist: ${e.message}"
            } finally {
                onComplete()
            }
        }
    }

    fun clearError() { _error.value = null }
}
