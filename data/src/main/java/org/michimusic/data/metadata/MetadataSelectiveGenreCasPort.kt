package org.michimusic.data.metadata

import android.content.Context
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataStructuredGenreRefreshPolicy
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.MetadataTrackTransactionResult
import org.michimusic.data.cache.TrackDao

/**
 * ME-11I: certified-only alternative CAS for GENRE multivalue. Unlike the
 * scalar-only port, this writes the first-value index hint and the entire
 * structured NUL-delimited evidence in ONE Room transaction, so the existing
 * UI7 Flow observer refreshes without a global scan or a second UI cache.
 * Other multivalue fields remain blocked rather than flattened.
 */
internal class MetadataSelectiveGenreCasPort(
    context: Context, private val dao: TrackDao,
    private val preimages: MetadataIndexPreimageStore? = null,
) : MetadataIndexCasCoordinator.Port {
    private val scalar = MetadataTrackDaoCasPort(dao)
    private val stamps = MetadataExactSourceStampReader(context)
    private val bytes = MetadataCurrentBytesReader(context)

    override suspend fun current(targetSource: String, targetTrack: String):
        MetadataIndexPublicationPolicy.IndexIdentity? = scalar.current(targetSource,targetTrack)

    override suspend fun updateIfVersion(expected: MetadataIndexPublicationPolicy.IndexIdentity,
        delta: MetadataSourceIndexDelta.Delta): Boolean =
        updateIfVersion(expected, delta, null)

    override suspend fun updateIfVersion(expected: MetadataIndexPublicationPolicy.IndexIdentity,
        delta: MetadataSourceIndexDelta.Delta,
        scopedPermit: MetadataWritePermit?): Boolean {
        if ((!MetadataCommitCertification.mayMutateOriginal() &&
                scopedPermit?.authorizes(delta) != true) ||
            expected.sourceId != delta.target.sourceId ||
            expected.trackId != delta.target.trackId || expected.uri != delta.target.uri)
            return false
        val policy = MetadataStructuredGenreRefreshPolicy.prepare(delta)
            as? MetadataStructuredGenreRefreshPolicy.Result.Prepared ?: return false
        val existing = dao.getTrackBySource(expected.sourceId, expected.trackId) ?: return false
        if (existing.filepath != expected.uri) return false
        val durable = preimages?.durableFor(expected, scopedPermit) ?: return false
        if (existing.asMetadataPreimage() != durable) return false
        // Scalar projections may use the FIRST genre for the index, but the
        // complete list is never lost: it is committed with the sidecar.
        val scalarDelta = delta.copy(observedValues = delta.observedValues +
            (MetadataField.GENRE to listOf(policy.primaryGenre)))
        val projected = MetadataTrackIndexProjection.propose(existing, scalarDelta) ?: return false
        // Re-read source bytes AFTER the finalizer, not just from its receipt.
        val stampBefore = stamps.read(expected.sourceId,expected.uri,delta.candidateBytes)
            ?: return false
        val physical = bytes.read(expected.sourceId,expected.uri,delta.candidateBytes)
            as? MetadataCurrentBytesReader.Result.Read ?: return false
        if (physical.fingerprint.bytes != delta.candidateBytes ||
            physical.fingerprint.sha256 != delta.sourceSha256) return false
        val stampAfter = stamps.read(expected.sourceId,expected.uri,delta.candidateBytes)
            ?: return false
        if (stampBefore != stampAfter) return false
        val evidence = evidenceFromDelta(existing, delta, policy,
            stampAfter.modifiedAtProviderUnits) ?: return false
        val request = metadataTrackTransactionRequest(
            durable, expected, projected, delta,
        ) ?: return false
        return when (dao.applyMetadataTransactionWithGenreEvidence(request, evidence)) {
            is MetadataTrackTransactionResult.Applied,
            is MetadataTrackTransactionResult.AlreadyApplied -> true
            MetadataTrackTransactionResult.Conflict -> false
        }
    }

    private fun evidenceFromDelta(row: CachedTrack, delta: MetadataSourceIndexDelta.Delta,
        policy: MetadataStructuredGenreRefreshPolicy.Result.Prepared,
        verifiedModifiedAt:Long):EmbeddedTagEvidence? {
        fun text(f:MetadataField):String? {
            val v=delta.observedValues[f] ?: return null
            return if(v.size<=1) v.firstOrNull().orEmpty() else null
        }
        fun number(f:MetadataField):Int? {
            val v=delta.observedValues[f] ?: return null
            if(v.isEmpty())return 0
            if(v.size!=1 || !v[0].matches(Regex("[0-9]{1,4}")))return null
            return v[0].toIntOrNull()?.takeIf { it in 1..9999 }
        }
        return EmbeddedTagEvidence(
            sourceId=row.sourceId,trackId=row.id,sizeBytes=delta.candidateBytes,
            modifiedAt=verifiedModifiedAt,
            parserVersion=EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
            genre=policy.primaryGenre,genreValues=policy.genreValues,
            title=text(MetadataField.TITLE) ?: return null,
            artist=text(MetadataField.ARTIST) ?: return null,
            album=text(MetadataField.ALBUM) ?: return null,
            albumArtist=text(MetadataField.ALBUM_ARTIST) ?: return null,
            composer=text(MetadataField.COMPOSER) ?: return null,
            trackNumber=number(MetadataField.TRACK_NUMBER) ?: return null,
            trackTotal=number(MetadataField.TRACK_TOTAL) ?: return null,
            discNumber=number(MetadataField.DISC_NUMBER) ?: return null,
            discTotal=number(MetadataField.DISC_TOTAL) ?: return null,
            date=text(MetadataField.RELEASE_DATE) ?: return null,
        )
    }
}
