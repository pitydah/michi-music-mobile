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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.mobile.library.MusicRootAuthority

enum class SearchFilter(val displayName: String) {
    ALL("Todo"),
    TRACKS("Canciones"),
    ARTISTS("Artistas"),
    ALBUMS("Álbumes"),
    PLAYLISTS("Playlists"),
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
    rootAuthority: MusicRootAuthority? = null,
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
    private val loadGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    val currentSearchGeneration: Long get() = searchGeneration.get()

    init {
        if (rootAuthority != null) {
            viewModelScope.launch {
                rootAuthority.revision.drop(1).collect {
                    // A confirmed root change must supersede any catalog scan that
                    // started against the previous root, even if it is still running.
                    loadLocalTracks(force = true)
                }
            }
        }
    }

    fun loadLocalTracks(force: Boolean = false) {
        // Normal retries keep an in-flight scan authoritative. A confirmed root
        // revision is different: its old scan can never publish into the new root.
        if (!force && loadJob?.isActive == true) return
        val generation = loadGeneration.incrementAndGet()
        if (force) loadJob?.cancel()
        loadJob = viewModelScope.launch(ioDispatcher) {
            _isSearching.value = true
            _error.value = null
            try {
                val albums = localRepo.loadAlbums()
                if (generation != loadGeneration.get() || !isActive) return@launch
                localTracks = albums.flatMap { it.tracks }
                val synced = localRepo.filterHiddenSynced(syncedRepo.getAllSynced().first())
                if (generation != loadGeneration.get() || !isActive) return@launch
                syncedTracks = synced
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration.get()) {
                    _error.value = e.message ?: "Error al cargar canciones"
                }
            } finally {
                // Refresh results when an asynchronous library load changes the catalog.
                // Superseded loads must not publish stale data or completion state.
                if (generation == loadGeneration.get()) {
                    if (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true &&
                        _error.value == null && _query.value.length >= 2) setQuery(_query.value)
                    else if (_error.value != null || searchJob?.isActive != true) _isSearching.value = false
                }
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
        val requestQuery = q.trim()
        searchJob?.cancel()
        _structuredResults.value = StructuredSearchResults()
        _results.value = emptyList()
        _error.value = null
        // Local playlist names/IDs are observed from PlaylistRepository in SearchScreen.
        // Do not launch a track-catalog search when PLAYLISTS owns the filter.
        if (_selectedFilter.value == SearchFilter.PLAYLISTS) {
            _structuredResults.value = StructuredSearchResults(
                requestQuery = requestQuery,
                requestFilter = SearchFilter.PLAYLISTS,
                requestGeneration = generation,
            )
            _isSearching.value = false
            searchJob = null
            return
        }
        if (normalizedSearchText(requestQuery).length < 2) {
            // Under-length queries publish an empty generation-stamped snapshot so
            // the screen can show guidance instead of a permanent loading spinner.
            _structuredResults.value = StructuredSearchResults(
                requestQuery = requestQuery,
                requestFilter = _selectedFilter.value,
                requestGeneration = generation,
            )
            _isSearching.value = false
            searchJob = null
            return
        }
        _isSearching.value = true
        val filter = _selectedFilter.value
        searchJob = viewModelScope.launch(ioDispatcher) {
            try {
                if (debounce) delay(250)
                executeSearch(requestQuery, filter, generation)
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
        if (generation != searchGeneration.get() || q != _query.value.trim() || filter != _selectedFilter.value) return
        val lower = normalizedSearchText(q)

        val allCombinedTracks = getCombinedDeduplicatedTracks()

        // Typed Artists
        val artistMatches = if (filter == SearchFilter.ALL || filter == SearchFilter.ARTISTS) {
            allCombinedTracks.filter { searchTextMatches(it.artist, lower) }
                .groupBy { it.artist }
                .map { (artistName, tracks) ->
                    SearchArtistResult(
                        artist = artistName,
                        trackCount = tracks.size,
                        sampleTrack = tracks.firstOrNull(),
                        tracks = tracks,
                    )
                }.sortedWith(compareBy<SearchArtistResult> { searchMatchRank(it.artist, lower) }
                    .thenBy { normalizedSearchText(it.artist) }).take(10)
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
                }.sortedWith(compareBy<SearchAlbumResult> { searchMatchRank(it.album, lower) }
                    .thenBy { normalizedSearchText(it.album) }
                    .thenBy { normalizedSearchText(it.artist) }).take(10)
        } else emptyList()

        // Typed Tracks
        val trackMatches = when (filter) {
            SearchFilter.ALL -> allCombinedTracks.filter { trackTitleMatches(it, lower) }
            SearchFilter.TRACKS -> allCombinedTracks.filter { trackTitleMatches(it, lower) }
            SearchFilter.ARTISTS -> emptyList()
            SearchFilter.ALBUMS -> emptyList()
            SearchFilter.PLAYLISTS -> emptyList()
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
        }.sortedWith(compareBy<SearchTrackResult> { searchMatchRank(it.track.title, lower) }
            .thenBy { normalizedSearchText(it.track.title) }
            .thenBy { it.track.id }).take(30)

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
                trackTitleMatches(it, lower) ||
                    searchTextMatches(it.artist, lower) ||
                    trackAlbumMatches(it, lower)
            }
            SearchFilter.ARTISTS -> allCombinedTracks.filter { searchTextMatches(it.artist, lower) }
            SearchFilter.ALBUMS -> allCombinedTracks.filter { trackAlbumMatches(it, lower) }
            SearchFilter.PLAYLISTS -> emptyList()
            SearchFilter.TRACKS -> allCombinedTracks.filter { trackTitleMatches(it, lower) }
            SearchFilter.LOSSLESS -> allCombinedTracks.filter {
                (trackTitleMatches(it, lower) || searchTextMatches(it.artist, lower) || trackAlbumMatches(it, lower)) &&
                    it.isLossless
            }
            SearchFilter.DOWNLOADED -> allCombinedTracks.filter {
                (trackTitleMatches(it, lower) || searchTextMatches(it.artist, lower) || trackAlbumMatches(it, lower)) &&
                    isTrackDownloaded(it)
            }
        }.map { track ->
            SearchResult(track, if (track.source == TrackSource.SYNCED) "Sincronizada" else "Local")
        }.take(50)

        // Synchronous CPU work may finish after cancel(); the epoch prevents
        // obsolete A->B->A searches from publishing into a newer request.
        if (generation == searchGeneration.get() && q == _query.value.trim() && filter == _selectedFilter.value) {
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
