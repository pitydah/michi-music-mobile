package org.michimusic.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao

class SyncedTrackRepository(
    private val trackDao: TrackDao,
) {
    suspend fun saveLibrary(tracks: List<TrackDto>) {
        val entities = tracks.map { it.toCachedTrack() }
        trackDao.deleteAll()
        trackDao.insertAll(entities)
    }

    suspend fun count(): Int = trackDao.count()

    fun getAllSynced(): Flow<List<CachedTrack>> = trackDao.getAllTracks()

    fun getPagedTracks(): Flow<PagingData<CachedTrack>> = Pager(
        config = PagingConfig(pageSize = 50, enablePlaceholders = false),
        pagingSourceFactory = { trackDao.getAllTracksPagingSource() }
    ).flow

    suspend fun getById(id: String): CachedTrack? = trackDao.getTrackById(id)

    suspend fun markDownloaded(id: String) {
        trackDao.markDownloaded(id)
    }

    suspend fun markDownloadedWithPath(id: String, filepath: String) {
        trackDao.markDownloadedWithPath(id, filepath)
    }
}

private fun TrackDto.toCachedTrack() = CachedTrack(
    id = id,
    title = title,
    artist = artist,
    album = album,
    duration = duration.toLong(),
    size = size,
    format = format,
    bitrate = bitrate,
    sampleRate = sampleRate,
    channels = channels,
    coverId = coverId,
    trackNumber = trackNumber,
    year = year,
    downloaded = false,
)
