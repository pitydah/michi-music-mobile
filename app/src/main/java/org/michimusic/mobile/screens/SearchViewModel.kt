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

data class SearchArtistResult(
    val artist: String,
    val trackCount: Int,
    val sampleTrack: Track?,
    val tracks: List<Track> = emptyList(),
)

data class SearchAlbumResult(
    val album: String,
    val artist: String,
    val trackCount: Int,
    val sampleTrack: Track?,
    val tracks: List<Track> = emptyList(),
)

data class SearchTrackResult(
    val track: Track,
    val source: String,
    val availableLocations: List<String> = emptyList(),
)

data class StructuredSearchResults(
    val artists: List<SearchArtistResult> = emptyList(),
    val albums: List<SearchAlbumResult> = emptyList(),
    val tracks: List<SearchTrackResult> = emptyList(),
    val requestQuery: String = "",
    val requestFilter: SearchFilter = SearchFilter.ALL,
    val requestGeneration: Long = -1L,
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

    private val _structuredResults = MutableStateFlow(StructuredSearchResults())
    val structuredResults: StateFlow<StructuredSearchResults> = _structuredResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var localTracks: List<Track> = emptyList()
    private var syncedTracks: List<org.michimusic.data.cache.CachedTrack> = emptyList()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private val searchGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    val currentSearchGeneration: Long get() = searchGeneration.get()

    fun loadLocalTracks() {
        // A retry that arrives while a catalog scan is still running would cancel
        // that scan and restart it, leaving the first request's cancellation to
        // settle state owned by the second one. The in-flight scan already reads
        // the live catalog, so keep the first request authoritative.
        if (loadJob?.isActive == true) return
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
                // Refresh results when an asynchronous library load changes the catalog.
                // Cancelled loads must not publish stale data.
                if (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true &&
                    _error.value == null && _query.value.length >= 2) setQuery(_query.value)
                else if (_error.value != null || searchJob?.isActive != true) _isSearching.value = false
            }
        }
    }

    fun setFilter(filter: SearchFilter) {
        if (_selectedFilter.value == filter) return
        _selectedFilter.value = filter
        scheduleSearch(_query.value, debounce = false)
    }

    fun setQuery(q: String) {
        _query.value = q
        scheduleSearch(q, debounce = true)
    }

    private fun scheduleSearch(q: String, debounce: Boolean) {
        val generation = searchGeneration.incrementAndGet()
        searchJob?.cancel()
        _structuredResults.value = StructuredSearchResults()
        _results.value = emptyList()
        _error.value = null
        if (q.length < 2 || q.isBlank()) {
            _isSearching.value = false
            searchJob = null
            return
        }
        _isSearching.value = true
        val filter = _selectedFilter.value
        searchJob = viewModelScope.launch(ioDispatcher) {
            try {
                if (debounce) delay(250)
                executeSearch(q, filter, generation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchGeneration.get()) {
                    _error.value = e.message ?: "Error al buscar música"
                    _isSearching.value = false
                }
            }
        }
    }

    private fun getCombinedDeduplicatedTracks(): List<Track> {
        val convertedSynced = syncedTracks.map { cached ->
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
            )
        }

        // Metadata are not a content identity: two different masters can share
        // title/artist/album/format/sample rate. Never hide a distinct source here.
        val combined = localTracks + convertedSynced
        return combined.distinctBy { track -> Triple(track.source, track.id, track.filepath) }
    }

    private fun executeSearch(q: String, filter: SearchFilter, generation: Long) {
        if (generation != searchGeneration.get() || q != _query.value || filter != _selectedFilter.value) return
        val lower = q.lowercase()

        val allCombinedTracks = getCombinedDeduplicatedTracks()

        // Typed Artists
        val artistMatches = if (filter == SearchFilter.ALL || filter == SearchFilter.ARTISTS) {
            allCombinedTracks.filter { it.artist.lowercase().contains(lower) }
                .groupBy { it.artist }
                .map { (artistName, tracks) ->
                    SearchArtistResult(
                        artist = artistName,
                        trackCount = tracks.size,
                        sampleTrack = tracks.firstOrNull(),
                        tracks = tracks,
                    )
                }.take(10)
        } else emptyList()

        // Typed Albums
        val albumMatches = if (filter == SearchFilter.ALL || filter == SearchFilter.ALBUMS) {
            allCombinedTracks.filter { trackAlbumMatches(it, lower) }
                .groupBy { "${it.album}___${it.artist}" }
                .map { (_, tracks) ->
                    val first = tracks.first()
                    SearchAlbumResult(
                        album = first.album,
                        artist = first.artist,
                        trackCount = tracks.size,
                        sampleTrack = first,
                        tracks = tracks,
                    )
                }.take(10)
        } else emptyList()

        // Typed Tracks
        val trackMatches = when (filter) {
            SearchFilter.ALL -> allCombinedTracks.filter { trackTitleMatches(it, lower) }
            SearchFilter.TRACKS -> allCombinedTracks.filter { trackTitleMatches(it, lower) }
            SearchFilter.ARTISTS -> emptyList()
            SearchFilter.ALBUMS -> emptyList()
            SearchFilter.LOSSLESS -> allCombinedTracks.filter {
                trackTitleMatches(it, lower) && it.isLossless
            }
            SearchFilter.DOWNLOADED -> allCombinedTracks.filter {
                trackTitleMatches(it, lower) && isTrackDownloaded(it)
            }
        }.map { track ->
            val locations = mutableListOf<String>()
            if (track.filepath.startsWith("content://")) locations.add("MediaStore")
            else if (track.filepath.startsWith("file://") || track.source == TrackSource.SYNCED) locations.add("Sincronizada")
            else if (track.filepath.isNotEmpty()) locations.add("Local")

            SearchTrackResult(
                track = track,
                source = if (track.source == TrackSource.SYNCED) "Sincronizada" else "Local",
                availableLocations = locations,
            )
        }.take(30)

        val newStructuredResults = StructuredSearchResults(
            artists = artistMatches,
            albums = albumMatches,
            tracks = trackMatches,
            requestQuery = q,
            requestFilter = filter,
            requestGeneration = generation,
        )

        // Compatibility list
        val legacyList = when (filter) {
            SearchFilter.ALL -> allCombinedTracks.filter {
                it.title.lowercase().contains(lower) ||
                    it.artist.lowercase().contains(lower) ||
                    it.album.lowercase().contains(lower)
            }
            SearchFilter.ARTISTS -> allCombinedTracks.filter { it.artist.lowercase().contains(lower) }
            SearchFilter.ALBUMS -> allCombinedTracks.filter { it.album.lowercase().contains(lower) }
            SearchFilter.TRACKS -> allCombinedTracks.filter { it.title.lowercase().contains(lower) }
            SearchFilter.LOSSLESS -> allCombinedTracks.filter {
                (it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower) || it.album.lowercase().contains(lower)) &&
                    it.isLossless
            }
            SearchFilter.DOWNLOADED -> allCombinedTracks.filter {
                (it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower) || it.album.lowercase().contains(lower)) &&
                    isTrackDownloaded(it)
            }
        }.map { track ->
            SearchResult(track, if (track.source == TrackSource.SYNCED) "Sincronizada" else "Local")
        }.take(50)

        // Synchronous CPU work may finish after cancel(); the epoch prevents
        // obsolete A->B->A searches from publishing into a newer request.
        if (generation == searchGeneration.get() && q == _query.value && filter == _selectedFilter.value) {
            _structuredResults.value = newStructuredResults
            _results.value = legacyList
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        searchJob = null
        _query.value = ""
        _results.value = emptyList()
        _structuredResults.value = StructuredSearchResults()
        _isSearching.value = false
        _error.value = null
    }

    fun clearError() { _error.value = null }

    private fun isTrackDownloaded(track: Track): Boolean {
        return org.michimusic.core.models.TrackAvailabilityChecker.isTrackDownloaded(track)
    }
}
