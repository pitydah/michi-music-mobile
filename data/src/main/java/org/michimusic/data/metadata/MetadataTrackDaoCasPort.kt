package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataUnverifiedIndexRowPolicy
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.MetadataTrackTransactionRequest
import org.michimusic.data.cache.MetadataTrackTransactionResult
import org.michimusic.data.cache.TrackDao

/**
 * ME-11A: real :data -> existing TrackDao bridge, but ONLY for rows with
 * a preexisting verified SHA and durable verifiedAt identity. No synthetic
 * Row version or guessed hashes. Other library rows fail closed and require
 * normal reindex when the writer is eventually certified.
 *
 * The DAO compare-and-swap is a single Room transaction with a conditional
 * update of ALL source-facing cached scalar fields and deletion of the stale
 * embedded-tag sidecar. It never invents multivalue scalar flattening.
 */
internal class MetadataTrackDaoCasPort(
    private val dao: TrackDao,
    private val permit: MetadataWritePermit? = null,
    private val preimageStore: MetadataIndexPreimageStore? = null,
    private val evidenceFor:
        (suspend (MetadataSourceIndexDelta.Delta) -> EmbeddedTagEvidence?)? = null,
) : MetadataIndexCasCoordinator.Port {
    private val hex=Regex("[a-f0-9]{64}")

    override suspend fun current(targetSource: String,targetTrack: String):
        MetadataIndexPublicationPolicy.IndexIdentity? {
        val row=dao.getTrackBySource(targetSource,targetTrack) ?: return null
        return when {
            supportedRow(row) -> MetadataIndexPublicationPolicy.IndexIdentity(row.sourceId,
                row.id,row.filepath,row.verifiedAt,row.verifiedSha256,row.metadataRevision)
            missingHistoricProof(row) -> MetadataIndexPublicationPolicy.IndexIdentity(row.sourceId,
                row.id,row.filepath,0L,"",row.metadataRevision)
            else -> null
        }
    }

    private fun supportedRow(row: CachedTrack): Boolean =
        row.filepath.startsWith("content://") && row.verifiedAt > 0L &&
        row.verifiedSize == row.size && row.size > 0L &&
        row.verifiedAlgorithm.lowercase() == "sha256" && hex.matches(row.verifiedSha256)

    private fun missingHistoricProof(row: CachedTrack): Boolean =
        row.filepath.startsWith("content://") && row.size in 12..(1024L*1024*1024) &&
            row.verifiedSize == 0L && row.verifiedAt == 0L &&
            row.verifiedSha256.isEmpty() && row.verifiedAlgorithm.isEmpty()

    override suspend fun updateIfVersion(expected: MetadataIndexPublicationPolicy.IndexIdentity,
        delta: MetadataSourceIndexDelta.Delta): Boolean {
        if (permit == null && !MetadataCommitCertification.mayMutateOriginal()) return false
        if (permit != null && (permit.transactionId != delta.transactionId ||
                permit.candidateSha256 != delta.sourceSha256 ||
                permit.stageBytes != delta.candidateBytes ||
                permit.uri != delta.target.uri || permit.sourceId != delta.target.sourceId ||
                permit.trackId != delta.target.trackId)) return false
        if (expected.sourceId != delta.target.sourceId ||
            expected.trackId != delta.target.trackId || expected.uri != delta.target.uri ||
            !hex.matches(delta.sourceSha256) || delta.candidateBytes !in 12..(1024L*1024*1024))
            return false
        val row=dao.getTrackBySource(expected.sourceId,expected.trackId) ?: return false
        if (row.filepath != expected.uri || row.metadataRevision != expected.metadataRevision)
            return false
        val candidate=MetadataTrackIndexProjection.propose(row,delta) ?: return false
        val evidence = if (evidenceFor == null) null else
            (evidenceFor.invoke(delta) ?: return false)
        val durable = preimageStore?.let { store ->
            val probe = MetadataRecoveryJournalV1.Entry(
                permit!!.transactionId, MetadataRecoveryPhase.CLEANUP_SAFE,
                expected.sourceId, expected.trackId, expected.uri, 0L, 0L,
            )
            val stored = when (val read = store.read(probe)) {
                is MetadataIndexPreimageStore.Result.Read -> read.preimage
                is MetadataIndexPreimageStore.Result.Refused -> return false
            }
            if (stored !is MetadataIndexPreimage.Existing) return false
            // The live row may only prove that the captured preimage is still
            // current; it may never supply the previous values of the CAS.
            if (row.asMetadataPreimage() != stored) return false
            stored
        }
        if (durable != null) {
            if (durable.sourceId != expected.sourceId || durable.trackId != expected.trackId ||
                durable.uri != expected.uri || durable.size <= 0L ||
                durable.metadataRevision != expected.metadataRevision) return false
            val verified = durable.verifiedAt > 0L &&
                durable.verifiedSha256 == expected.observedSourceSha256 &&
                durable.verifiedSize == durable.size &&
                durable.verifiedAlgorithm.lowercase() == "sha256"
            val unhashed = durable.verifiedAt == 0L && durable.verifiedSha256.isEmpty() &&
                durable.verifiedSize == 0L && durable.verifiedAlgorithm.isEmpty()
            if (!verified && !unhashed) return false
            if (unhashed) {
                if (expected.version != 0L || expected.observedSourceSha256.isNotEmpty())
                    return false
                return apply(durable, candidate, delta, evidence)
            }
            if (durable.verifiedAt != expected.version ||
                durable.verifiedSha256 != expected.observedSourceSha256) return false
            return apply(durable, candidate, delta, evidence)
        }
        if (missingHistoricProof(row)) {
            if (expected.version != 0L || expected.observedSourceSha256.isNotEmpty()) return false
            return apply(row.asMetadataPreimage(), candidate, delta, evidence)
        }
        if (!supportedRow(row) || row.verifiedAt != expected.version ||
            row.verifiedSha256 != expected.observedSourceSha256) return false
        return apply(row.asMetadataPreimage(), candidate, delta, evidence)
    }

    private suspend fun apply(
        previous: MetadataIndexPreimage.Existing,
        candidate: MetadataTrackIndexProjection.Fields,
        delta: MetadataSourceIndexDelta.Delta,
        evidence: EmbeddedTagEvidence?,
    ): Boolean {
        val request = MetadataTrackTransactionRequest(
            transactionId = delta.transactionId,
            sourceId = previous.sourceId,
            trackId = previous.trackId,
            filepath = previous.uri,
            previousRevision = previous.metadataRevision,
            previousSize = previous.size,
            previousVerifiedAt = previous.verifiedAt,
            previousSha256 = previous.verifiedSha256,
            previousTitle = previous.title,
            previousArtist = previous.artist,
            previousAlbum = previous.album,
            previousAlbumArtist = previous.albumArtist,
            previousGenre = previous.genre,
            previousTrackNumber = previous.trackNumber,
            previousYear = previous.year,
            newSize = delta.candidateBytes,
            newTitle = candidate.title,
            newArtist = candidate.artist,
            newAlbum = candidate.album,
            newAlbumArtist = candidate.albumArtist,
            newGenre = candidate.genre,
            newTrackNumber = candidate.trackNumber,
            newYear = candidate.year,
            candidateSha256 = delta.sourceSha256,
            candidateBytes = delta.candidateBytes,
            appliedAt = System.currentTimeMillis().coerceAtLeast(1L),
        )
        val result = if (evidence == null) dao.applyMetadataTransaction(request)
            else dao.applyMetadataTransactionWithGenreEvidence(request, evidence)
        return when (result) {
            is MetadataTrackTransactionResult.Applied,
            is MetadataTrackTransactionResult.AlreadyApplied -> true
            MetadataTrackTransactionResult.Conflict -> false
        }
    }
}

/** Exact scalar projection. Multi-value evidence is never joined with ";" or "/". */
internal object MetadataTrackIndexProjection {
    data class Fields(val title:String,val artist:String,val album:String,
        val albumArtist:String,val genre:String,val trackNumber:Int,val year:Int)
    fun propose(row:CachedTrack,delta:MetadataSourceIndexDelta.Delta):Fields? {
        if (delta.target.sourceId != row.sourceId || delta.target.trackId != row.id ||
            delta.target.uri != row.filepath) return null
        val values=delta.observedValues
        fun single(field:MetadataField,current:String):String? {
            val candidate=values[field] ?: return current
            return if (candidate.size <= 1) candidate.firstOrNull().orEmpty() else null
        }
        fun number(field:MetadataField,current:Int):Int? {
            val candidate=values[field] ?: return current
            if (candidate.isEmpty()) return 0
            if (candidate.size != 1 || !candidate[0].matches(Regex("[0-9]{1,4}"))) return null
            return candidate[0].toIntOrNull()?.takeIf { it in 1..9999 }
        }
        val year=values[MetadataField.RELEASE_DATE]?.let { date ->
            if (date.isEmpty()) 0
            else if (date.size == 1 && date[0].matches(Regex("[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?")))
                date[0].take(4).toIntOrNull()
            else null
        } ?: if (MetadataField.RELEASE_DATE !in values) row.year else return null
        return Fields(
            single(MetadataField.TITLE,row.title) ?: return null,
            single(MetadataField.ARTIST,row.artist) ?: return null,
            single(MetadataField.ALBUM,row.album) ?: return null,
            single(MetadataField.ALBUM_ARTIST,row.albumArtist) ?: return null,
            single(MetadataField.GENRE,row.genre) ?: return null,
            number(MetadataField.TRACK_NUMBER,row.trackNumber) ?: return null,
            year)
    }
}
