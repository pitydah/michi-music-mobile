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

    // Only a completed load owns executable results. A root revision invalidates
    // the previous source synchronously, before any asynchronous repository work.
    private val catalogGate = SearchCatalogGate()
    // One monitor serializes query invalidation, root revocation and emissions.
    // A generation check followed by separate Flow writes is not atomic by itself.
    private val publicationFence = SearchPublicationFence()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private val searchGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    val currentSearchGeneration: Long get() = searchGeneration.get()

    // This collector is scoped to the Search ViewModel and reuses the already
    // existing Room Flow. It never starts a timer, rescan, network call or service.
    private var syncedObservationJob: Job? = null
    private val syncedRoomRevision = java.util.concurrent.atomic.AtomicLong(0L)
    // Store only the last successfully published Room rows. Compare before
    // revoking a catalog: Room may notify without a semantic row change.
    private var lastPublishedSyncedRows: List<org.michimusic.data.cache.CachedTrack>? = null
    private var syncedObservationFailed = false

    private fun observeSyncedUpdatesOnce() {
        if (syncedObservationJob != null) return
        syncedObservationJob = viewModelScope.launch(ioDispatcher) {
            // The first Flow emission is consumed by loadLocalTracks(). Subsequent
            // emissions are confirmed database changes, not search keystrokes.
            var firstEmission = true
            try {
                syncedRepo.getAllSynced().collect { changed ->
                    syncedRoomRevision.incrementAndGet()
                    publicationFence.write { syncedObservationFailed = false }
                    if (firstEmission) {
                        firstEmission = false
                        // The observer and the initial .first() are independent
                        // subscriptions. The first observer emission can be older
                        // than a *published* load: reconfirm via current Room data.
                        val initial = publicationFence.write { lastPublishedSyncedRows }
                        if (initial != null) {
                            if (changed == initial) return@collect
                            refreshSyncedFromRoom(syncedRepo.getAllSynced().first())
                            return@collect
                        }
                    }
                    refreshSyncedFromRoom(changed)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A dead Room collector can no longer certify the catalog's
                // freshness. Revoke executable results before showing the error.
                publicationFence.write {
                    syncedObservationFailed = true
                    failSyncedCatalogLocked("No se pudo observar la biblioteca sincronizada")
                    syncedObservationJob = null // explicit reload may subscribe again
                }
            }
        }
    }

    /** Called with publicationFence held; never fails an unrelated root reload. */
    private fun failSyncedCatalogLocked(
        reason: String,
        expectedGeneration: Long? = null,
    ) {
        val ready = catalogGate.current() as? SearchCatalogLoad.Ready ?: return
        if (expectedGeneration != null && ready.generation != expectedGeneration) return
        val ticket = catalogGate.begin()
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        searchJob = null
        _results.value = emptyList()
        _structuredResults.value = StructuredSearchResults()
        _isSearching.value = false
        catalogGate.fail(ticket, reason)
        _error.value = reason
    }

    private fun cachedSyncedAsTrack(cached: org.michimusic.data.cache.CachedTrack) = Track(
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
        sourceId = cached.sourceId,
    )

    private suspend fun refreshSyncedFromRoom(
        rows: List<org.michimusic.data.cache.CachedTrack>,
    ) {
        // Revoke old results before starting the replacement. A root load in
        // progress owns its own snapshot and already reads the latest Room data.
        val refresh = publicationFence.write {
            val previous = catalogGate.current() as? SearchCatalogLoad.Ready
                ?: return@write null
            if (rows == lastPublishedSyncedRows) return@write null
            val ticket = catalogGate.begin()
            searchGeneration.incrementAndGet()
            searchJob?.cancel()
            searchJob = null
            _structuredResults.value = StructuredSearchResults()
            _results.value = emptyList()
            _error.value = null
            _isSearching.value = searchCatalogNeedsLoading(_query.value,
                _selectedFilter.value == SearchFilter.PLAYLISTS)
            previous to ticket
        } ?: return
        val (previous, ticket) = refresh
        try {
            val filtered = localRepo.filterHiddenSynced(rows)
            val updated = replaceSearchSyncedTracks(previous, filtered.map(::cachedSyncedAsTrack))
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return
            publicationFence.write {
                if (catalogGate.complete(ticket, updated)) {
                    lastPublishedSyncedRows = rows
                    // No new local load. Re-evaluate the current query against the
                    // new immutable catalog and preserve its exact source identity.
                    scheduleSearch(_query.value, debounce = false)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            publicationFence.write {
                if (catalogGate.fail(ticket, e.message ?: "No se pudo actualizar el catálogo")) {
                    _error.value = "No se pudo actualizar el catálogo sincronizado"
                    _isSearching.value = false
                }
            }
        }
    }

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
        publicationFence.write {
        observeSyncedUpdatesOnce()
        // Normal retries keep an in-flight scan authoritative. A confirmed root
        // revision is different: its old scan can never publish into the new root.
        if (!force && loadJob?.isActive == true) return@write
        val ticket = catalogGate.begin()
        // Revocation and query invalidation happen before launch; even if the old
        // search is CPU-bound and ignores cancellation, its epoch cannot publish.
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        searchJob = null
        _structuredResults.value = StructuredSearchResults()
        _results.value = emptyList()
        // An empty query is guidance, not a search operation during SAF reload.
        _isSearching.value = searchCatalogNeedsLoading(_query.value,
            _selectedFilter.value == SearchFilter.PLAYLISTS)
        _error.value = null
        if (force) loadJob?.cancel()
        loadJob = viewModelScope.launch(ioDispatcher) {
            var published = false
            var syncedVersionAtRead = syncedRoomRevision.get()
            try {
                val albums = localRepo.loadAlbums()
                if (catalogGate.current() !== ticket || !isActive) return@launch
                val local = albums.flatMap { it.tracks }
                syncedVersionAtRead = syncedRoomRevision.get()
                val syncedRows = syncedRepo.getAllSynced().first()
                val synced = localRepo.filterHiddenSynced(syncedRows)
                if (catalogGate.current() !== ticket || !isActive) return@launch
                val convertedSynced = synced.map(::cachedSyncedAsTrack)
                val nextSnapshot = buildSearchCatalogSnapshot(local, convertedSynced)
                // Only the latest load can publish. A cancelled root scan must not
                // replace the catalog with an older root's completed conversion.
                if (!isActive) return@launch
                published = publicationFence.write {
                    if (syncedObservationFailed) {
                        // A one-shot read succeeding after a dead collector does
                        // not establish continuous catalog freshness.
                        val reason = "No se pudo observar la biblioteca sincronizada"
                        if (catalogGate.fail(ticket, reason)) _error.value = reason
                        false
                    } else if (catalogGate.complete(ticket, nextSnapshot)) {
                        lastPublishedSyncedRows = syncedRows
                        true
                    } else false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: "Error al cargar canciones"
                publicationFence.write {
                    if (catalogGate.fail(ticket, reason)) _error.value = reason
                }
            } finally {
                // No stale load may publish completion or overwrite a later query.
                publicationFence.write {
                    if (catalogGate.current().generation == ticket.generation) {
                        if (published && normalizedSearchText(_query.value).length >= 2 &&
                            _selectedFilter.value != SearchFilter.PLAYLISTS) {
                            setQuery(_query.value)
                        } else {
                            _isSearching.value = false
                        }
                    }
                }
                // Room event raced the initial DAO read: reconcile the latest
                // snapshot once without initiating a MediaStore/SAF scan.
                // A failed reconciliation must not escape unhandled from finally,
                // or revoke a newer root/load that already superseded this ticket.
                if (published && syncedRoomRevision.get() != syncedVersionAtRead && isActive) {
                    try {
                        refreshSyncedFromRoom(syncedRepo.getAllSynced().first())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        publicationFence.write {
                            failSyncedCatalogLocked("No se pudo reconciliar la biblioteca sincronizada",
                                expectedGeneration = ticket.generation)
                        }
                    }
                }
            }
        }
        }
    }

    fun setFilter(filter: SearchFilter) {
        publicationFence.write {
            if (_selectedFilter.value == filter) return@write
            _selectedFilter.value = filter
            scheduleSearch(_query.value, debounce = false)
        }
    }

    fun setQuery(q: String) {
        publicationFence.write {
            _query.value = q
            scheduleSearch(q, debounce = true)
        }
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
        // A failed root load must not be hidden by changing the query/filter.
        // Never dispatch a search against a failed or still-loading generation.
        val catalog = catalogGate.current()
        if (catalog is SearchCatalogLoad.Failed) {
            _error.value = catalog.reason
            _isSearching.value = false
            searchJob = null
            return
        }
        // Let an empty/one-letter query reach the existing guidance branch even
        // if the catalog has not loaded. Only actionable queries wait for a root.
        if (catalog !is SearchCatalogLoad.Ready &&
            searchCatalogNeedsLoading(requestQuery, false)) {
            _isSearching.value = true
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
                publicationFence.write {
                    if (generation == searchGeneration.get() &&
                        catalogGate.current() is SearchCatalogLoad.Ready) {
                        _error.value = e.message ?: "Error al buscar música"
                        _isSearching.value = false
                    }
                }
            }
        }
    }

    private fun executeSearch(q: String, filter: SearchFilter, generation: Long) {
        if (generation != searchGeneration.get() || q != _query.value.trim() || filter != _selectedFilter.value) return
        val catalog = catalogGate.current() as? SearchCatalogLoad.Ready ?: return
        val lower = normalizedSearchText(q)
        val allCombinedTracks = catalog.tracks

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
                .let(::groupSearchAlbums)
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
            // A song belongs in search results when its real title, artist OR album matches.
            // Keep source identity and audio format filtering unchanged.
            SearchFilter.ALL, SearchFilter.TRACKS -> allCombinedTracks.asSequence().filter { trackSearchMatches(it, lower) }
            SearchFilter.ARTISTS, SearchFilter.ALBUMS, SearchFilter.PLAYLISTS -> emptySequence()
            SearchFilter.LOSSLESS -> allCombinedTracks.asSequence().filter {
                trackSearchMatches(it, lower) && it.isLossless
            }
            SearchFilter.DOWNLOADED -> allCombinedTracks.asSequence().filter {
                trackSearchMatches(it, lower) && isTrackDownloaded(it)
            }
        }.bestRankedSearchResults(
            30,
            compareBy<Track> { trackSearchRank(it, lower) }
                .thenBy { normalizedSearchText(it.title) }
                .thenBy { it.id },
        ).map { track ->
            // Project labels only for the selected top-30, never for every hit.
            val locations = mutableListOf<String>()
            if (track.filepath.startsWith("content://")) locations.add("MediaStore")
            else if (track.filepath.startsWith("file://") || track.source == TrackSource.SYNCED) locations.add("Sincronizada")
            else if (track.filepath.isNotEmpty()) locations.add("Local")

            SearchTrackResult(
                track = track,
                source = if (track.source == TrackSource.SYNCED) "Sincronizada" else "Local",
                availableLocations = locations,
            )
        }

        val newStructuredResults = StructuredSearchResults(
            artists = artistMatches,
            albums = albumMatches,
            tracks = trackMatches,
            requestQuery = q,
            requestFilter = filter,
            requestGeneration = generation,
        )

        // Compatibility snapshot MUST use the same predicate as structured results.
        // No hidden artist/album results that UI cannot render or safely activate.
        val legacyList = when (filter) {
            SearchFilter.ALL, SearchFilter.TRACKS -> allCombinedTracks.asSequence().filter { trackSearchMatches(it, lower) }
            SearchFilter.ARTISTS -> allCombinedTracks.asSequence().filter { searchTextMatches(it.artist, lower) }
            SearchFilter.ALBUMS -> allCombinedTracks.asSequence().filter { trackAlbumMatches(it, lower) }
            SearchFilter.PLAYLISTS -> emptySequence()
            SearchFilter.LOSSLESS -> allCombinedTracks.asSequence().filter {
                trackSearchMatches(it, lower) && it.isLossless
            }
            SearchFilter.DOWNLOADED -> allCombinedTracks.asSequence().filter {
                trackSearchMatches(it, lower) && isTrackDownloaded(it)
            }
        }.projectSearchCompatibilityPage(50) { track ->
            SearchResult(track, if (track.source == TrackSource.SYNCED) "Sincronizada" else "Local")
        }

        // Synchronous CPU work may finish after cancel(); the epoch prevents
        // obsolete A->B->A searches from publishing into a newer request.
        publicationFence.write {
            if (generation == searchGeneration.get() && catalogGate.current() === catalog &&
                q == _query.value.trim() && filter == _selectedFilter.value) {
                _structuredResults.value = newStructuredResults
                _results.value = legacyList
                _isSearching.value = false
            }
        }
    }

    fun clearSearch() {
        publicationFence.write {
            searchGeneration.incrementAndGet()
            searchJob?.cancel()
            searchJob = null
            _query.value = ""
            _results.value = emptyList()
            _structuredResults.value = StructuredSearchResults()
            _isSearching.value = false
            _error.value = null
        }
    }

    fun clearError() { publicationFence.write { _error.value = null } }

    private fun isTrackDownloaded(track: Track): Boolean {
        return org.michimusic.core.models.TrackAvailabilityChecker.isTrackDownloaded(track)
    }
}
