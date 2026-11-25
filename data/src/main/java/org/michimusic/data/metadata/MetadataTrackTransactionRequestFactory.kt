package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.MetadataTrackTransactionRequest

internal fun metadataTrackTransactionRequest(
    previous: CachedTrack,
    expected: MetadataIndexPublicationPolicy.IndexIdentity,
    candidate: MetadataTrackIndexProjection.Fields,
    delta: MetadataSourceIndexDelta.Delta,
): MetadataTrackTransactionRequest? {
    if (previous.sourceId != expected.sourceId ||
        previous.id != expected.trackId ||
        previous.filepath != expected.uri ||
        previous.metadataRevision != expected.metadataRevision ||
        delta.target.sourceId != expected.sourceId ||
        delta.target.trackId != expected.trackId ||
        delta.target.uri != expected.uri
    ) return null
    val verified = previous.verifiedAt > 0L &&
        previous.verifiedAt == expected.version &&
        previous.verifiedSize == previous.size &&
        previous.verifiedAlgorithm.equals("sha256", ignoreCase = true) &&
        previous.verifiedSha256 == expected.observedSourceSha256
    val unverified = previous.verifiedAt == 0L &&
        expected.version == 0L &&
        previous.verifiedSize == 0L &&
        previous.verifiedSha256.isEmpty() &&
        previous.verifiedAlgorithm.isEmpty() &&
        expected.observedSourceSha256.isEmpty()
    if (!verified && !unverified) return null
    return MetadataTrackTransactionRequest(
        transactionId = delta.transactionId,
        sourceId = previous.sourceId,
        trackId = previous.id,
        filepath = previous.filepath,
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
}
