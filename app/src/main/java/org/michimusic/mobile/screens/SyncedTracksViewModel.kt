package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.repository.SyncedTrackRepository

class SyncedTracksViewModel(
    private val repository: SyncedTrackRepository,
) : ViewModel() {

    val pagedTracks: Flow<PagingData<CachedTrack>> = repository.getPagedTracks()
        .cachedIn(viewModelScope)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun toTrack(cached: CachedTrack): Track = Track(
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

    suspend fun getPlayableTracks(): List<Track> = try {
        repository.getAllSynced().first { true }
            .filter { it.filepath.isNotEmpty() }
            .map { toTrack(it) }
    } catch (_: Exception) {
        emptyList()
    }
}
