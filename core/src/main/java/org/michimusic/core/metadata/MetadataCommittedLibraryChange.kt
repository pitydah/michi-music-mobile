package org.michimusic.core.metadata

/**
 * Exact, post-readback library projection of one committed metadata transaction.
 * It is a notification, never write authority.
 */
data class MetadataCommittedLibraryChange(
    val transactionId: String,
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val changedFields: Set<MetadataField>,
    val resourceOperations: Set<MetadataResourceOperation>,
    val snapshot: MetadataSnapshot,
    val previousRevision: Long,
    val committedRevision: Long,
    val committedBytes: Long,
    val committedSha256: String,
) {
    init {
        require(transactionId.isNotBlank())
        require(trackId.isNotBlank() && uri.isNotBlank())
        require(changedFields.isNotEmpty() || resourceOperations.isNotEmpty())
        require(previousRevision >= 0L && committedRevision > previousRevision)
        require(committedBytes > 0L)
        require(committedSha256.matches(Regex("[0-9a-f]{64}")))
        require(snapshot.target.sourceId == sourceId)
        require(snapshot.target.trackId == trackId)
        require(snapshot.target.uri == uri)
    }
}
