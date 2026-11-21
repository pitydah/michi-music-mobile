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
        val hashed = expected.version > 0 &&
            expected.observedSourceSha256.matches(Regex("[0-9a-f]{64}"))
        val unhashed = expected.version == 0L && expected.observedSourceSha256.isEmpty()
        if (!hashed && !unhashed) return false
        return database.withTransaction {
            // A stale owner, URI, scalar, size, or verified hash must reject the
            // whole transaction. Do NOT use @Insert(REPLACE) on CachedTrack.
            val success = if (hashed) dao.commitMetadataAfterVerifiedWrite(
                sourceId = indexed.sourceId, trackId = indexed.id, filepath = indexed.filepath,
                previousSize = indexed.size, previousVerifiedAt = expected.version,
                previousSha256 = expected.observedSourceSha256,
                previousTitle = indexed.title, previousArtist = indexed.artist,
                previousAlbum = indexed.album, previousAlbumArtist = indexed.albumArtist,
                previousGenre = indexed.genre, previousTrackNumber = indexed.trackNumber,
                previousYear = indexed.year, newSize = delta.candidateBytes,
                newTitle = next.title, newArtist = next.artist,
                newAlbum = next.album, newAlbumArtist = next.albumArtist,
                newGenre = next.genre, newTrackNumber = next.trackNumber, newYear = next.year,
            ) else dao.commitMetadataAfterVerifiedWriteUnhashed(
                sourceId = indexed.sourceId, trackId = indexed.id, filepath = indexed.filepath,
                previousSize = indexed.size, previousTitle = indexed.title,
                previousArtist = indexed.artist, previousAlbum = indexed.album,
                previousAlbumArtist = indexed.albumArtist, previousGenre = indexed.genre,
                previousTrackNumber = indexed.trackNumber, previousYear = indexed.year,
                newSize = delta.candidateBytes, newTitle = next.title,
                newArtist = next.artist, newAlbum = next.album,
                newAlbumArtist = next.albumArtist, newGenre = next.genre,
                newTrackNumber = next.trackNumber, newYear = next.year,
            )
            if (!success) false else {
                database.metadataPersonEvidenceDao().upsert(evidence)
                true
            }
        }
    }
}
