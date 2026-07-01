package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.sync.SyncSession

data class SearchResult(
    val track: Track,
    val source: String,
)

class SearchViewModel(
    private val localRepo: LocalMediaRepository,
    private val syncedRepo: SyncedTrackRepository,
    private val session: SyncSession,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var localTracks: List<Track> = emptyList()

    fun loadLocalTracks() {
        viewModelScope.launch {
            _isSearching.value = true
            val albums = withContext(Dispatchers.IO) { localRepo.loadAlbums() }
            localTracks = albums.flatMap { it.tracks }
            _isSearching.value = false
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        if (q.length < 2) {
            _results.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            val lower = q.lowercase()
            val localHits = localTracks.filter {
                it.title.lowercase().contains(lower) ||
                it.artist.lowercase().contains(lower) ||
                it.album.lowercase().contains(lower)
            }.map { SearchResult(it, "Local") }

            val synced = withContext(Dispatchers.IO) {
                syncedRepo.getAllSynced().first()
            }
            val syncedHits = synced.filter {
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

            val remoteHits = if (session.connectionState.value == SyncConnectionState.CONNECTED) {
                val syncClient = session.syncClient
                if (syncClient != null) {
                    withContext(Dispatchers.IO) {
                        syncClient.search(q)
                            .getOrNull()
                            ?.results
                            ?.map { dto ->
                                SearchResult(
                                    Track(
                                        id = dto.id,
                                        title = dto.title,
                                        artist = dto.artist,
                                        album = dto.album,
                                        duration = dto.duration.toLong(),
                                        source = TrackSource.STREAMING,
                                    ),
                                    "Remoto",
                                )
                            }
                            ?: emptyList()
                    }
                } else emptyList()
            } else emptyList()

            _results.value = (localHits + syncedHits + remoteHits).take(50)
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        _query.value = ""
        _results.value = emptyList()
    }
}
