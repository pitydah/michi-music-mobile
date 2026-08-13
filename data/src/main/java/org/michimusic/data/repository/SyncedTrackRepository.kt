package org.michimusic.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first as flowFirst
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao

open class SyncedTrackRepository(
    private val trackDao: TrackDao? = null,
    private val playlistDao: PlaylistDao? = null,
) {
    open suspend fun saveLibrary(tracks: List<TrackDto>) {
        val td = trackDao ?: return
        val existing = td.getAllTracks().let { flow ->
            flow.flowFirst()
        }
        val existingMap = existing.associateBy { it.id }

        val entities = tracks.map { dto ->
            val old = existingMap[dto.id]
            dto.toCachedTrackPreserving(old)
        }
        td.insertAll(entities)

        val newIds = tracks.map { it.id }.toSet()
        val removed = existing.filter { it.id !in newIds && !it.downloaded }
        removed.forEach { td.delete(it) }
    }

    open suspend fun saveManifestPlaylists(playlists: List<ManifestPlaylist>) {
        val entities = playlists.map { mp ->
            CachedPlaylist(
                id = mp.playlistId,
                name = mp.name,
                trackIds = mp.trackIds.joinToString(","),
                trackCount = mp.trackIds.size,
            )
        }
        playlistDao?.let {
            it.deleteAll()
            it.insertAll(entities)
        }
    }

    open suspend fun getDownloadedIds(): Set<String> =
        trackDao?.getDownloadedTracks()?.let { flow ->
            flow.flowFirst().map { it.id }.toSet()
        } ?: emptySet()

    open suspend fun count(): Int = trackDao?.count() ?: 0

    open fun getAllSynced(): Flow<List<CachedTrack>> = trackDao?.getAllTracks() ?: kotlinx.coroutines.flow.emptyFlow()

    open fun getPagedTracks(): Flow<PagingData<CachedTrack>> = trackDao?.let { td ->
        Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false),
            pagingSourceFactory = { td.getAllTracksPagingSource() }
        ).flow
    } ?: kotlinx.coroutines.flow.emptyFlow()

    open suspend fun getById(id: String): CachedTrack? = trackDao?.getTrackById(id)

    open suspend fun markDownloaded(id: String) {
        trackDao?.markDownloaded(id)
    }

    open suspend fun markDownloadedWithPath(id: String, filepath: String) {
        trackDao?.markDownloadedWithPath(id, filepath)
    }
}

private fun TrackDto.toCachedTrackPreserving(old: CachedTrack? = null) = CachedTrack(
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
    filepath = old?.filepath ?: "",
    downloaded = old?.downloaded ?: false,
)
