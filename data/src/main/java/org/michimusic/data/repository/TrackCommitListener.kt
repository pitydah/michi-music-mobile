package org.michimusic.data.repository

/**
 * Non-authoritative post-commit signal. The caller has already committed a library
 * membership in Room; listener failures must never roll back or report that commit
 * as failed. There is no audio/file access in this contract.
 */
fun interface TrackCommitListener {
    suspend fun onCommitted(libraryId: String, artistCredit: String)
}
