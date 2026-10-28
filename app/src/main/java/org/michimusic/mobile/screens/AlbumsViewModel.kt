package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
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
    private val favoritesRepo: org.michimusic.data.repository.FavoritesRepository? = null,
) : ViewModel() {

    private val _favoriteTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteTrackIds: StateFlow<Set<String>> = _favoriteTrackIds.asStateFlow()

    private val _favoriteTracks = MutableStateFlow<List<Track>>(emptyList())
    val favoriteTracks: StateFlow<List<Track>> = _favoriteTracks.asStateFlow()

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

    private val _isLoading = MutableStateFlow(true)
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

                val favIds: Set<String> = try {
                    favoritesRepo?.observeFavoriteTrackIds()?.firstOrNull() ?: emptySet()
                } catch (_: Exception) {
                    emptySet()
                }
                _favoriteTrackIds.value = favIds
                _favoriteTracks.value = tracks.filter { it.id in favIds }

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
                _favoriteTracks.value = emptyList()
                _favoriteTrackIds.value = emptySet()
                _error.value = e.message ?: "Error al cargar la biblioteca"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private val _isCreatingPlaylist = MutableStateFlow(false)
    val isCreatingPlaylist: StateFlow<Boolean> = _isCreatingPlaylist.asStateFlow()

    private val _createPlaylistError = MutableStateFlow<String?>(null)
    val createPlaylistError: StateFlow<String?> = _createPlaylistError.asStateFlow()

    fun createPlaylist(name: String, onResult: (CreatePlaylistResult) -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
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
            try {
                val created = playlistRepo.createPlaylist(trimmed)
                val updated = playlistRepo.getAllPlaylists()
                _playlists.value = updated
                val item = PlaylistItem(id = created.id, name = created.name, trackCount = created.trackCount)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    _isCreatingPlaylist.value = false
                    onResult(CreatePlaylistResult.Success(item))
                }
            } catch (e: Exception) {
                val msg = "Error al crear la playlist: ${e.message}"
                _error.value = msg
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    _createPlaylistError.value = msg
                    _isCreatingPlaylist.value = false
                    onResult(CreatePlaylistResult.Error(msg))
                }
            }
        }
    }

    fun addAlbumToPlaylist(playlistId: String, albumTracks: List<Track>, onComplete: (Boolean) -> Unit = {}) {
        if (albumTracks.isEmpty()) {
            onComplete(false)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            var success = false
            try {
                when (val result = playlistRepo.addAlbumToPlaylist(playlistId, albumTracks)) {
                    is PlaylistRepository.PlaylistAddResult.Success -> {
                        val updated = playlistRepo.getAllPlaylists()
                        _playlists.value = updated
                        success = true
                    }
                    is PlaylistRepository.PlaylistAddResult.PlaylistNotFound -> {
                        _error.value = "La playlist seleccionada no existe"
                    }
                    is PlaylistRepository.PlaylistAddResult.EmptyTracks -> {
                        _error.value = "El álbum no contiene pistas válidas para añadir"
                    }
                    is PlaylistRepository.PlaylistAddResult.Error -> {
                        _error.value = "Error al añadir álbum: ${result.message}"
                    }
                }
            } catch (e: Exception) {
                _error.value = "Error al añadir álbum a la playlist: ${e.message}"
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onComplete(success)
                }
            }
        }
    }

    fun clearError() { _error.value = null }
}
