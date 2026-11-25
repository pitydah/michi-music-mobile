package org.michimusic.data.metadata

import android.content.Context
import androidx.room.withTransaction
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataStructuredPeopleRefreshPolicy
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.MetadataPersonEvidence
import org.michimusic.data.cache.MetadataTrackTransactionResult
import org.michimusic.data.cache.TrackDao

/**
 * ME-11K: existing postcheck-certified CAS for ARTIST/ALBUM_ARTIST multivalue.
 * A single Room transaction updates scalar display hints AND the exact lists;
 * if either operation fails, both roll back. No source file is opened for write.
 */
internal class MetadataSelectivePeopleCasPort(
    context: Context,
    private val database: MichiDatabase,
) : MetadataIndexCasCoordinator.Port {
    private val dao: TrackDao = database.trackDao()
    private val scalar = MetadataTrackDaoCasPort(dao)
    private val stamps = MetadataExactSourceStampReader(context)
    private val bytes = MetadataCurrentBytesReader(context)

    override suspend fun current(targetSource: String, targetTrack: String):
        MetadataIndexPublicationPolicy.IndexIdentity? = scalar.current(targetSource, targetTrack)

    override suspend fun updateIfVersion(
        expected: MetadataIndexPublicationPolicy.IndexIdentity,
        delta: MetadataSourceIndexDelta.Delta,
    ): Boolean {
        if (!MetadataCommitCertification.mayMutateOriginal() ||
            expected.sourceId != delta.target.sourceId ||
            expected.trackId != delta.target.trackId || expected.uri != delta.target.uri)
            return false
        val prepared = MetadataStructuredPeopleRefreshPolicy.inspect(delta)
            as? MetadataStructuredPeopleRefreshPolicy.Result.Prepared ?: return false
        val indexed = dao.getTrackBySource(expected.sourceId, expected.trackId) ?: return false
        if (indexed.filepath != expected.uri) return false
        val scalarDelta = delta.copy(observedValues = delta.observedValues + mapOf(
            MetadataField.ARTIST to listOfNotNull(prepared.artist.takeIf { it.isNotEmpty() }),
            MetadataField.ALBUM_ARTIST to listOfNotNull(prepared.albumArtist.takeIf { it.isNotEmpty() }),
        ))
        val next = MetadataTrackIndexProjection.propose(indexed, scalarDelta) ?: return false
        val before = stamps.read(expected.sourceId, expected.uri, delta.candidateBytes) ?: return false
        val physical = bytes.read(expected.sourceId, expected.uri, delta.candidateBytes)
            as? MetadataCurrentBytesReader.Result.Read ?: return false
        if (physical.fingerprint.bytes != delta.candidateBytes ||
            physical.fingerprint.sha256 != delta.sourceSha256) return false
        val after = stamps.read(expected.sourceId, expected.uri, delta.candidateBytes) ?: return false
        if (after != before) return false
        val evidence = MetadataPersonEvidence(
            indexed.sourceId, indexed.id, delta.candidateBytes,
            after.modifiedAtProviderUnits, delta.sourceSha256,
            prepared.artists.size, prepared.artistsPacked,
            prepared.albumArtists.size, prepared.albumArtistsPacked,
        )
        if (!evidence.structurallyValid()) return false
        val request = metadataTrackTransactionRequest(
            indexed, expected, next, delta,
        ) ?: return false
        return database.withTransaction {
            when (dao.applyMetadataTransaction(request)) {
                is MetadataTrackTransactionResult.Applied,
                is MetadataTrackTransactionResult.AlreadyApplied -> {
                database.metadataPersonEvidenceDao().upsert(evidence)
                true
                }
                MetadataTrackTransactionResult.Conflict -> false
            }
        }
    }
}
