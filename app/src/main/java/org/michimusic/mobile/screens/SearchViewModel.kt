package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository

enum class SearchFilter(val displayName: String) {
    ALL("Todo"),
    TRACKS("Canciones"),
    ARTISTS("Artistas"),
    ALBUMS("Álbumes"),
    LOSSLESS("Lossless / FLAC"),
    DOWNLOADED("Descargados"),
}

data class SearchResult(
    val track: Track,
    val source: String,
)

class SearchViewModel(
    private val localRepo: LocalMediaRepository,
    private val syncedRepo: SyncedTrackRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedFilter = MutableStateFlow(SearchFilter.ALL)
    val selectedFilter: StateFlow<SearchFilter> = _selectedFilter.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var localTracks: List<Track> = emptyList()
    private var syncedTracks: List<org.michimusic.data.cache.CachedTrack> = emptyList()
    private var searchJob: Job? = null
    private var loadJob: Job? = null

    fun loadLocalTracks() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(ioDispatcher) {
            _isSearching.value = true
            _error.value = null
            try {
                val albums = localRepo.loadAlbums()
                localTracks = albums.flatMap { it.tracks }
                syncedTracks = syncedRepo.getAllSynced().first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Error al cargar canciones"
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun setFilter(filter: SearchFilter) {
        _selectedFilter.value = filter
        if (_query.value.length >= 2) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch(ioDispatcher) {
                try {
                    executeSearch(_query.value)
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Expected when query changes
                }
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        if (q.length < 2) {
            _results.value = emptyList()
            searchJob?.cancel()
            searchJob = null
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(ioDispatcher) {
            try {
                delay(300)
                executeSearch(q)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected when search query changes
            }
        }
    }

    private fun executeSearch(q: String) {
        _isSearching.value = true
        val lower = q.lowercase()
        val filter = _selectedFilter.value

        val localMatches = localTracks.filter { track ->
            val matchesQuery = when (filter) {
                SearchFilter.ALL -> track.title.lowercase().contains(lower) ||
                        track.artist.lowercase().contains(lower) ||
                        track.album.lowercase().contains(lower)
                SearchFilter.TRACKS -> track.title.lowercase().contains(lower)
                SearchFilter.ARTISTS -> track.artist.lowercase().contains(lower)
                SearchFilter.ALBUMS -> track.album.lowercase().contains(lower)
                SearchFilter.LOSSLESS -> (track.title.lowercase().contains(lower) ||
                        track.artist.lowercase().contains(lower) ||
                        track.album.lowercase().contains(lower)) &&
                        (track.format.lowercase() in listOf("flac", "alac", "wav", "dsd") || (track.sampleRate ?: 0) >= 48000)
                SearchFilter.DOWNLOADED -> (track.title.lowercase().contains(lower) ||
                        track.artist.lowercase().contains(lower) ||
                        track.album.lowercase().contains(lower)) && track.filepath.isNotEmpty()
            }
            matchesQuery
        }.map { SearchResult(it, "Local") }

        val syncedMatches = syncedTracks.filter { cached ->
            val matchesQuery = when (filter) {
                SearchFilter.ALL -> cached.title.lowercase().contains(lower) ||
                        cached.artist.lowercase().contains(lower) ||
                        cached.album.lowercase().contains(lower)
                SearchFilter.TRACKS -> cached.title.lowercase().contains(lower)
                SearchFilter.ARTISTS -> cached.artist.lowercase().contains(lower)
                SearchFilter.ALBUMS -> cached.album.lowercase().contains(lower)
                SearchFilter.LOSSLESS -> (cached.title.lowercase().contains(lower) ||
                        cached.artist.lowercase().contains(lower) ||
                        cached.album.lowercase().contains(lower)) &&
                        (cached.format.lowercase() in listOf("flac", "alac", "wav", "dsd") || (cached.sampleRate ?: 0) >= 48000)
                SearchFilter.DOWNLOADED -> (cached.title.lowercase().contains(lower) ||
                        cached.artist.lowercase().contains(lower) ||
                        cached.album.lowercase().contains(lower)) && (cached.downloaded || cached.filepath.isNotEmpty())
            }
            matchesQuery
        }.map { cached ->
            SearchResult(
                Track(
                    id = cached.id,
                    title = cached.title,
                    artist = cached.artist,
                    album = cached.album,
                    duration = cached.duration,
                    size = cached.size,
                    format = cached.format,
                    bitrate = cached.bitrate,
                    sampleRate = cached.sampleRate,
                    channels = cached.channels,
                    coverId = cached.coverId,
                    trackNumber = cached.trackNumber,
                    year = cached.year,
                    filepath = cached.filepath,
                    source = TrackSource.SYNCED,
                ),
                "Sincronizada",
            )
        }

        _results.value = (localMatches + syncedMatches).take(50)
        _isSearching.value = false
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        loadJob?.cancel()
        loadJob = null
        _query.value = ""
        _results.value = emptyList()
        _error.value = null
    }

    fun clearError() { _error.value = null }
}
