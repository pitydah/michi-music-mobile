package org.michimusic.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first as flowFirst
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.LOCAL_SOURCE_ID
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao

open class SyncedTrackRepository(
    private val trackDao: TrackDao? = null,
    private val playlistDao: PlaylistDao? = null,
) {
    /**
     * Saves one peer's manifest under [sourceId]. The composite cache key means a
     * manifest can no longer overwrite a locally authored row or another peer's
     * row that happens to share a track ID (K16-01).
     */
    open suspend fun saveLibrary(tracks: List<TrackDto>, sourceId: String = LOCAL_SOURCE_ID) {
        val td = trackDao ?: return
        val existing = td.getTracksForSource(sourceId).let { flow ->
            flow.flowFirst()
        }
        val existingMap = existing.associateBy { it.id }

        // Never reconcile a single peer's manifest by deleting rows from the
        // shared cache: the absence of a row only proves this peer did not send
        // it, not that another owner stopped owning it.
        require(tracks.map { it.id }.distinct().size == tracks.size) {
            "El manifiesto recibido repite identificadores de pista"
        }
        val entities = tracks.map { dto ->
            val old = existingMap[dto.id]
            dto.toCachedTrackPreserving(old, sourceId)
        }
        td.insertAll(entities)
    }

    /** Saves one peer's playlists under [sourceId] (K16-02). */
    open suspend fun saveManifestPlaylists(
        playlists: List<ManifestPlaylist>,
        sourceId: String = LOCAL_SOURCE_ID,
    ) {
        val entities = playlists.map { mp ->
            CachedPlaylist(
                id = mp.playlistId,
                name = mp.name,
                trackIds = mp.trackIds.joinToString(","),
                trackCount = mp.trackIds.size,
                sourceId = sourceId,
            )
        }
        playlistDao?.let { dao ->
            // R16 safety fence: do not delete local/other-peer playlists. Refuse
            // ambiguous shared IDs instead of overwriting a differently owned record.
            require(entities.map { p -> p.id }.distinct().size == entities.size) {
                "Duplicated playlist IDs in incoming manifest"
            }
            val existing = dao.getAllPlaylists()
                .filter { it.sourceId == sourceId }
                .associateBy { p -> p.id }
            require(entities.none { incoming ->
                existing[incoming.id]?.let { prior ->
                    prior.name != incoming.name || prior.trackIds != incoming.trackIds
                } == true
            }) { "La lista heredada usa un identificador que ya pertenece a otro origen; no se puede atribuir" }
            dao.insertAll(entities)
        }
    }

    open suspend fun getDownloadedIds(): Set<String> =
        trackDao?.getDownloadedTracks()?.let { flow ->
            flow.flowFirst().map { it.id }.toSet()
        } ?: emptySet()

    open suspend fun getDownloadedBytes(): Long =
        trackDao?.getDownloadedTracks()?.let { flow ->
            flow.flowFirst().sumOf { it.size }
        } ?: 0L

    open suspend fun getDownloadedTrackFingerprints(): Set<String> =
        trackDao?.getDownloadedTracks()?.let { flow ->
            val tracks = flow.flowFirst()
            val ids = tracks.map { it.id }
            val fingerprints = tracks.mapNotNull { trk ->
                if (trk.title.isNotEmpty() && trk.artist.isNotEmpty()) {
                    "${trk.title.trim().lowercase()}_${trk.artist.trim().lowercase()}_${trk.duration}"
                } else null
            }
            (ids + fingerprints).toSet()
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

private fun TrackDto.toCachedTrackPreserving(
    old: CachedTrack? = null,
    sourceId: String = LOCAL_SOURCE_ID,
) = CachedTrack(
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
    sourceId = sourceId,
)
