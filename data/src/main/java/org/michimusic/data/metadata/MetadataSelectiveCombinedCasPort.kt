package org.michimusic.data.metadata

import android.content.Context
import androidx.room.withTransaction
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataStructuredCombinedRefreshPolicy
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.MetadataPersonEvidence
import org.michimusic.data.cache.MetadataTrackTransactionResult
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.TrackDao

/**
 * ME-11L: certified-only COMBINED genre+person CAS. Two sequential updates are
 * wrong: the first invalidates the second's expected source row and can leave
 * one sidecar missing. This class does a single conditional row update and two
 * sidecar writes in the SAME Room transaction, after a cold SHA and stamp check.
 * No original bytes are mutated here. Global writer certification remains OFF.
 */
internal class MetadataSelectiveCombinedCasPort(
    context: Context,
    private val database: MichiDatabase,
    private val preimages: MetadataIndexPreimageStore? = null,
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
    ): Boolean = updateIfVersion(expected, delta, null)

    override suspend fun updateIfVersion(
        expected: MetadataIndexPublicationPolicy.IndexIdentity,
        delta: MetadataSourceIndexDelta.Delta,
        scopedPermit: MetadataWritePermit?,
    ): Boolean {
        if ((!MetadataCommitCertification.mayMutateOriginal() &&
                scopedPermit?.authorizes(delta) != true) ||
            expected.sourceId != delta.target.sourceId ||
            expected.trackId != delta.target.trackId || expected.uri != delta.target.uri)
            return false
        val prepared = MetadataStructuredCombinedRefreshPolicy.inspect(delta)
            as? MetadataStructuredCombinedRefreshPolicy.Result.Prepared ?: return false
        val indexed = dao.getTrackBySource(expected.sourceId, expected.trackId) ?: return false
        if (indexed.filepath != expected.uri) return false
        val durable = preimages?.durableFor(expected, scopedPermit) ?: return false
        if (indexed.asMetadataPreimage() != durable) return false
        val scalarDelta = delta.copy(observedValues = delta.observedValues + mapOf(
            MetadataField.GENRE to listOf(prepared.genre),
            MetadataField.ARTIST to listOfNotNull(prepared.artist.takeIf(String::isNotEmpty)),
            MetadataField.ALBUM_ARTIST to listOfNotNull(prepared.albumArtist.takeIf(String::isNotEmpty)),
        ))
        val projected = MetadataTrackIndexProjection.propose(indexed, scalarDelta) ?: return false
        val before = stamps.read(expected.sourceId, expected.uri, delta.candidateBytes)
            ?: return false
        val readback = bytes.read(expected.sourceId, expected.uri, delta.candidateBytes)
            as? MetadataCurrentBytesReader.Result.Read ?: return false
        if (readback.fingerprint.bytes != delta.candidateBytes ||
            readback.fingerprint.sha256 != delta.sourceSha256) return false
        val after = stamps.read(expected.sourceId, expected.uri, delta.candidateBytes)
            ?: return false
        if (before != after) return false
        val genre = genreEvidence(indexed, delta, prepared, after.modifiedAtProviderUnits)
            ?: return false
        val people = MetadataPersonEvidence(
            sourceId = indexed.sourceId, trackId = indexed.id,
            sizeBytes = delta.candidateBytes, modifiedAt = after.modifiedAtProviderUnits,
            sourceSha256 = delta.sourceSha256,
            artistCount = prepared.artists.size, artistValues = prepared.artistValues,
            albumArtistCount = prepared.albumArtists.size,
            albumArtistValues = prepared.albumArtistValues,
        )
        if (!people.structurallyValid() || genre.genre != projected.genre ||
            genre.artist != projected.artist || genre.albumArtist != projected.albumArtist ||
            genre.title != projected.title || genre.album != projected.album ||
            genre.trackNumber != projected.trackNumber) return false
        val request = metadataTrackTransactionRequest(
            durable, expected, projected, delta,
        ) ?: return false
        return database.withTransaction {
            when (dao.applyMetadataTransaction(request)) {
                is MetadataTrackTransactionResult.Applied -> Unit
                is MetadataTrackTransactionResult.AlreadyApplied ->
                    return@withTransaction true
                MetadataTrackTransactionResult.Conflict ->
                    return@withTransaction false
            }
            // The same transaction invalidated stale evidence. Restore BOTH new
            // verified observations atomically; any exception rolls back all 3.
            dao.putTagEvidence(listOf(genre))
            database.metadataPersonEvidenceDao().upsert(people)
            true
        }
    }

    private fun genreEvidence(
        row: CachedTrack,
        delta: MetadataSourceIndexDelta.Delta,
        values: MetadataStructuredCombinedRefreshPolicy.Result.Prepared,
        modified: Long,
    ): EmbeddedTagEvidence? {
        fun single(field: MetadataField): String? {
            val v = delta.observedValues[field] ?: return null
            return if (v.size <= 1) v.firstOrNull().orEmpty() else null
        }
        fun number(field: MetadataField): Int? {
            val v = delta.observedValues[field] ?: return null
            if (v.isEmpty()) return 0
            if (v.size != 1 || !v[0].matches(Regex("[0-9]{1,4}"))) return null
            return v[0].toIntOrNull()?.takeIf { it in 1..9999 }
        }
        return EmbeddedTagEvidence(
            sourceId = row.sourceId, trackId = row.id, sizeBytes = delta.candidateBytes,
            modifiedAt = modified, parserVersion = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
            genre = values.genre, genreValues = values.genreValues,
            title = single(MetadataField.TITLE) ?: return null,
            artist = values.artist,
            album = single(MetadataField.ALBUM) ?: return null,
            albumArtist = values.albumArtist,
            composer = single(MetadataField.COMPOSER) ?: return null,
            trackNumber = number(MetadataField.TRACK_NUMBER) ?: return null,
            trackTotal = number(MetadataField.TRACK_TOTAL) ?: return null,
            discNumber = number(MetadataField.DISC_NUMBER) ?: return null,
            discTotal = number(MetadataField.DISC_TOTAL) ?: return null,
            date = single(MetadataField.RELEASE_DATE) ?: return null,
        )
    }
}
