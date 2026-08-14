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
)

data class StructuredSearchResults(
    val artists: List<SearchArtistResult> = emptyList(),
    val albums: List<SearchAlbumResult> = emptyList(),
    val tracks: List<SearchTrackResult> = emptyList(),
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
            _structuredResults.value = StructuredSearchResults()
            searchJob?.cancel()
            searchJob = null
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(ioDispatcher) {
            try {
                delay(250)
                executeSearch(q)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected when search query changes
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

        // Deduplicate using unique key (id or title+artist+album)
        val combined = localTracks + convertedSynced
        return combined.distinctBy { track ->
            val key = "${track.title.trim().lowercase()}___${track.artist.trim().lowercase()}___${track.album.trim().lowercase()}"
            if (key.length > 6) key else track.id
        }
    }

    private fun executeSearch(q: String) {
        _isSearching.value = true
        val lower = q.lowercase()
        val filter = _selectedFilter.value

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
            allCombinedTracks.filter { it.album.lowercase().contains(lower) }
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
            SearchFilter.ALL -> allCombinedTracks.filter { it.title.lowercase().contains(lower) }
            SearchFilter.TRACKS -> allCombinedTracks.filter { it.title.lowercase().contains(lower) }
            SearchFilter.ARTISTS -> emptyList()
            SearchFilter.ALBUMS -> emptyList()
            SearchFilter.LOSSLESS -> allCombinedTracks.filter {
                it.title.lowercase().contains(lower) &&
                    (it.format.lowercase() in listOf("flac", "alac", "wav", "dsd") || (it.sampleRate ?: 0) >= 48000)
            }
            SearchFilter.DOWNLOADED -> allCombinedTracks.filter {
                it.title.lowercase().contains(lower) && (it.filepath.isNotEmpty() || it.source == TrackSource.LOCAL)
            }
        }.map { track ->
            SearchTrackResult(
                track = track,
                source = if (track.source == TrackSource.SYNCED) "Sincronizada" else "Local",
            )
        }.take(30)

        _structuredResults.value = StructuredSearchResults(
            artists = artistMatches,
            albums = albumMatches,
            tracks = trackMatches,
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
                    (it.format.lowercase() in listOf("flac", "alac", "wav", "dsd") || (it.sampleRate ?: 0) >= 48000)
            }
            SearchFilter.DOWNLOADED -> allCombinedTracks.filter {
                (it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower) || it.album.lowercase().contains(lower)) &&
                    (it.filepath.isNotEmpty() || it.source == TrackSource.LOCAL)
            }
        }.map { track ->
            SearchResult(track, if (track.source == TrackSource.SYNCED) "Sincronizada" else "Local")
        }.take(50)

        _results.value = legacyList
        _isSearching.value = false
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        loadJob?.cancel()
        loadJob = null
        _query.value = ""
        _results.value = emptyList()
        _structuredResults.value = StructuredSearchResults()
        _error.value = null
    }

    fun clearError() { _error.value = null }
}
