package org.michimusic.data.cache

data class MetadataTrackTransactionRequest(
    val transactionId: String,
    val sourceId: String,
    val trackId: String,
    val filepath: String,
    val previousRevision: Long,
    val previousSize: Long,
    val previousVerifiedAt: Long,
    val previousSha256: String,
    val previousTitle: String,
    val previousArtist: String,
    val previousAlbum: String,
    val previousAlbumArtist: String,
    val previousGenre: String,
    val previousTrackNumber: Int,
    val previousYear: Int,
    val newSize: Long,
    val newTitle: String,
    val newArtist: String,
    val newAlbum: String,
    val newAlbumArtist: String,
    val newGenre: String,
    val newTrackNumber: Int,
    val newYear: Int,
    val candidateSha256: String,
    val candidateBytes: Long,
    val appliedAt: Long,
)

sealed interface MetadataTrackTransactionResult {
    data class Applied(val marker: MetadataAppliedTransaction) :
        MetadataTrackTransactionResult

    data class AlreadyApplied(val marker: MetadataAppliedTransaction) :
        MetadataTrackTransactionResult

    data object Conflict : MetadataTrackTransactionResult
}
