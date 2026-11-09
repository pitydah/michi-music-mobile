package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.AppDao
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.data.repository.MediaResult
import org.michimusic.data.repository.PlaylistRepository
import org.michimusic.mobile.library.MusicRootAuthority

class AlbumsViewModel(
    private val repo: LocalMediaRepository,
    private val appDao: AppDao,
    private val playlistRepo: PlaylistRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val favoritesRepo: org.michimusic.data.repository.FavoritesRepository? = null,
    rootAuthority: MusicRootAuthority? = null,
) : ViewModel() {

    private val _favoriteTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteTrackIds: StateFlow<Set<String>> = _favoriteTrackIds.asStateFlow()

    private val _downloadedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedTrackIds: StateFlow<Set<String>> = _downloadedTrackIds.asStateFlow()

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

    private val _mediaState = MutableStateFlow(MediaLibraryState.LOADING)
    /** Truthful local-media state: EMPTY only after a successful query with zero tracks. */
    val mediaState: StateFlow<MediaLibraryState> = _mediaState.asStateFlow()

    private var loadJob: Job? = null
    private val loadEpoch = java.util.concurrent.atomic.AtomicLong()

    init {
        if (rootAuthority != null) {
            viewModelScope.launch {
                rootAuthority.revision.drop(1).collect {
                    loadMedia(forceRefresh = true)
                }
            }
        }
    }

    fun cancelLoading() {
        loadEpoch.incrementAndGet()
        loadJob?.cancel()
        loadJob = null
        _isLoading.value = false
    }

    fun loadMedia(forceRefresh: Boolean = false) {
        val epoch = loadEpoch.incrementAndGet()
        loadJob?.cancel()
        loadJob = viewModelScope.launch(ioDispatcher) {
            _isLoading.value = true
            _error.value = null
            _mediaState.value = MediaLibraryState.LOADING
            // A permission change or a manual retry must never be answered from the cache
            // that was filled while MediaStore was unreadable.
            if (forceRefresh) repo.invalidateCache()
            val albumsResult = try {
                repo.fetchAlbumsResult()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch != loadEpoch.get()) return@launch
                _error.value = e.message ?: "Error al cargar la biblioteca"
                _mediaState.value = MediaLibraryState.QUERY_ERROR
                _isLoading.value = false
                return@launch
            }
            if (albumsResult !is MediaResult.Success) {
                // Media truth: a denied permission or a failed query keeps whatever the app
                // already knows (remote libraries, playlists, favourites) and reports the
                // real cause instead of pretending the library is empty.
                if (epoch != loadEpoch.get()) return@launch
                _mediaState.value = mediaStateFor(albumsResult, 0, false)
                if (albumsResult is MediaResult.QueryFailed) {
                    _error.value = albumsResult.cause.message
                        ?: "No se pudo leer la música del dispositivo"
                }
                _isLoading.value = false
                return@launch
            }
            val result = albumsResult.data
            try {
                val tracks = result.flatMap { it.tracks }
                val trackById = tracks.associateBy { it.id }
                val smartLists = try {
                    val top = appDao.getTopTracks(12).mapNotNull { trackById[it.trackId] }
                    val recent = appDao.getRecentHistory(20)
                        .mapNotNull { trackById[it.trackId] }
                        .distinctBy { it.id }
                        .take(12)
                    top to recent
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList<Track>() to emptyList<Track>()
                }
                val loadedPlaylists = try {
                    playlistRepo.getAllPlaylists()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }

                val favIds: Set<String> = try {
                    favoritesRepo?.observeFavoriteTrackIds()?.firstOrNull() ?: emptySet()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptySet()
                }
                if (epoch != loadEpoch.get()) return@launch
                _favoriteTrackIds.value = favIds
                _favoriteTracks.value = tracks.filter { it.id in favIds }

                val downloaded = tracks.filter { track ->
                    org.michimusic.core.models.TrackAvailabilityChecker.isTrackDownloaded(track)
                }.map { it.id }.toSet()
                _downloadedTrackIds.value = downloaded

                _albums.value = result
                _allTracks.value = tracks
                _topTracks.value = smartLists.first
                _recentTracks.value = smartLists.second
                _playlists.value = loadedPlaylists
                _mediaState.value = mediaStateFor(albumsResult, result.size, loadedPlaylists.isNotEmpty())
                // When SAF remains available but Android's broader MediaStore query is
                // denied or fails, do not tell the user the catalog is complete.
                _error.value = when (albumsResult.coverage) {
                    org.michimusic.data.repository.MediaCoverage.FULL -> null
                    org.michimusic.data.repository.MediaCoverage.SAF_ONLY_PERMISSION_DENIED ->
                        "Solo se muestra la música importada por archivos. El acceso a toda tu música está pendiente: revísalo en los permisos de Android."
                    org.michimusic.data.repository.MediaCoverage.SAF_ONLY_QUERY_FAILED ->
                        "Solo se muestra la música importada por archivos: Android no pudo consultar el resto de la biblioteca."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch != loadEpoch.get()) return@launch
                _albums.value = emptyList()
                _allTracks.value = emptyList()
                _topTracks.value = emptyList()
                _recentTracks.value = emptyList()
                _playlists.value = emptyList()
                _favoriteTracks.value = emptyList()
                _favoriteTrackIds.value = emptySet()
                _downloadedTrackIds.value = emptySet()
                _error.value = e.message ?: "Error al cargar la biblioteca"
                _mediaState.value = MediaLibraryState.QUERY_ERROR
            } finally {
                if (epoch == loadEpoch.get()) _isLoading.value = false
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
