package org.michimusic.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first as flowFirst
import kotlinx.coroutines.withContext
import org.michimusic.core.models.LocalSyncTrackEvidence
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.core.models.TrackDto
import java.io.File
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.LOCAL_SOURCE_ID
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.cache.isLocalLibrarySource
import java.security.MessageDigest

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

    /** Downloaded identity is per owner; another server may reuse the same ID. */
    open suspend fun getDownloadedIds(sourceId: String): Set<String> =
        trackDao?.getDownloadedTracksForSource(sourceId)?.let { flow ->
            flow.flowFirst().map { it.id }.toSet()
        } ?: emptySet()

    open suspend fun getDownloadedBytes(): Long =
        trackDao?.getDownloadedTracks()?.let { flow ->
            flow.flowFirst().sumOf { it.size }
        } ?: 0L

    /**
     * Structured evidence for every downloaded row of [sourceId].
     *
     * Physical existence and hashing run on IO. A managed sync copy is hashed
     * once and the verification is persisted; later plans reuse it only while
     * the file keeps its recorded size and timestamp. SAF roots are local
     * libraries, never managed sync copies, so they are not hashed here.
     */
    open suspend fun getDownloadedTrackEvidence(sourceId: String): List<LocalSyncTrackEvidence> =
        trackDao?.getDownloadedTracksForSource(sourceId)?.let { flow ->
            val tracks = flow.flowFirst()
            withContext(Dispatchers.IO) {
                tracks.map { trk ->
                    val path = trk.filepath
                    val file = path.takeIf { it.isNotBlank() }?.let(::File)
                    val physicallyVerified = file?.isFile == true
                    val physicalLength = if (physicallyVerified) file.length() else 0L
                    val complete = physicallyVerified && physicalLength > 0
                    val managedCandidate = complete && file != null &&
                        trk.sourceId.isNotEmpty() && !isLocalLibrarySource(trk.sourceId)

                    var managedCopyVerified = false
                    var checksum = ""
                    var algorithm = ""
                    if (managedCandidate) {
                        // Never infer a hash algorithm for a legacy row. Only a
                        // persisted SHA-256 record can be reused; blank or
                        // unknown algorithms are re-hashed and re-recorded.
                        val recordedValid = trk.verifiedSha256.isNotBlank() &&
                            trk.verifiedAlgorithm.equals("sha256", ignoreCase = true) &&
                            trk.verifiedSize == physicalLength &&
                            trk.verifiedAt > 0 &&
                            file.lastModified() <= trk.verifiedAt + VERIFICATION_TIMESTAMP_TOLERANCE_MS
                        if (recordedValid) {
                            managedCopyVerified = true
                            checksum = trk.verifiedSha256
                            algorithm = "sha256"
                        } else {
                            // One-time migration of a legacy managed copy. The
                            // cost is paid once per file, never per recomposition.
                            val digest = sha256Of(file)
                            if (digest != null) {
                                val verifiedAt = maxOf(System.currentTimeMillis(), file.lastModified())
                                trackDao?.markDownloadedVerified(
                                    sourceId = trk.sourceId,
                                    id = trk.id,
                                    filepath = path,
                                    size = physicalLength,
                                    sha256 = digest,
                                    verifiedAt = verifiedAt,
                                    algorithm = "sha256",
                                )
                                managedCopyVerified = true
                                checksum = digest
                                algorithm = "sha256"
                            }
                        }
                    }

                    LocalSyncTrackEvidence(
                        sourceId = trk.sourceId,
                        trackId = trk.id,
                        title = trk.title,
                        artist = trk.artist,
                        duration = trk.duration,
                        sizeBytes = if (physicallyVerified) physicalLength else trk.size,
                        checksum = checksum,
                        checksumVerified = managedCopyVerified,
                        checksumAlgorithm = algorithm,
                        managedCopyVerified = managedCopyVerified,
                        filePath = path,
                        physicallyVerified = physicallyVerified,
                        complete = complete,
                    )
                }
            }
        } ?: emptyList()

    open suspend fun markDownloadedVerified(
        sourceId: String,
        id: String,
        filepath: String,
        size: Long,
        sha256: String,
        verifiedAt: Long,
        algorithm: String = "sha256",
    ) {
        trackDao?.markDownloadedVerified(sourceId, id, filepath, size, sha256, verifiedAt, algorithm)
    }

    open suspend fun count(): Int = trackDao?.count() ?: 0

    open fun getAllSynced(): Flow<List<CachedTrack>> = trackDao?.getAllTracks() ?: kotlinx.coroutines.flow.emptyFlow()

    open fun getPagedTracks(): Flow<PagingData<CachedTrack>> = trackDao?.let { td ->
        Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false),
            pagingSourceFactory = { td.getAllTracksPagingSource() }
        ).flow
    } ?: kotlinx.coroutines.flow.emptyFlow()

    /** Exact owner lookup; a bare ID is ambiguous across paired libraries. */
    open suspend fun getById(sourceId: String, id: String): CachedTrack? =
        trackDao?.getTrackBySource(sourceId, id)

    open suspend fun markDownloaded(sourceId: String, id: String) {
        trackDao?.markDownloaded(sourceId, id)
    }

    open suspend fun markDownloadedWithPath(sourceId: String, id: String, filepath: String) {
        trackDao?.markDownloadedWithPath(sourceId, id, filepath)
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
    albumArtist = old?.albumArtist ?: "",
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
    verifiedSize = old?.verifiedSize ?: 0L,
    verifiedSha256 = old?.verifiedSha256 ?: "",
    verifiedAt = old?.verifiedAt ?: 0L,
    verifiedAlgorithm = old?.verifiedAlgorithm ?: "",
)

private const val VERIFICATION_TIMESTAMP_TOLERANCE_MS = 2_000L

private fun sha256Of(file: File): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()
