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

        // R16 safety fence: the legacy cache is global and contains no source/library ID.
        // Never reconcile a single peer's manifest by deleting rows from that global table.
        // A truly scoped reconciliation requires the versioned Room schema migration.
        require(tracks.map { it.id }.distinct().size == tracks.size) {
            "El manifiesto recibido repite identificadores de pista"
        }
        // Same-ID collisions across peers remain possible until the schema
        // includes a composite (sourceId, libraryId, trackId) identity.
        val entities = tracks.map { dto ->
            val old = existingMap[dto.id]
            dto.toCachedTrackPreserving(old)
        }
        td.insertAll(entities)
        // No global delete: preserve other peers and locally authored records.
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
        playlistDao?.let { dao ->
            // R16 safety fence: do not delete local/other-peer playlists. Refuse
            // ambiguous shared IDs instead of overwriting a differently owned record.
            require(entities.map { p -> p.id }.distinct().size == entities.size) {
                "Duplicated playlist IDs in incoming manifest"
            }
            val existing = dao.getAllPlaylists().associateBy { p -> p.id }
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
