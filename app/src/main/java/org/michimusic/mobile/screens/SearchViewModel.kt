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
import kotlinx.coroutines.withContext
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository

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
            } catch (e: Exception) {
                _error.value = e.message ?: "Error al cargar canciones"
            } finally {
                _isSearching.value = false
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
            delay(300)
            _isSearching.value = true
            val lower = q.lowercase()
            val localHits = localTracks.filter {
                it.title.lowercase().contains(lower) ||
                it.artist.lowercase().contains(lower) ||
                it.album.lowercase().contains(lower)
            }.map { SearchResult(it, "Local") }

            val syncedHits = syncedTracks.filter {
                it.title.lowercase().contains(lower) ||
                it.artist.lowercase().contains(lower) ||
                it.album.lowercase().contains(lower)
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

            _results.value = (localHits + syncedHits).take(50)
            _isSearching.value = false
        }
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
