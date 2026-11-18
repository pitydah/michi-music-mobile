package org.michimusic.link

import org.michimusic.link.transfer.TransferCheckpoint
import org.michimusic.link.transfer.TransferState

/**
 * A25: .part bytes are untrusted until the exact checkpoint, strong server
 * revision and deterministic paths agree. No network or background work.
 * Even a valid Range/ETag still needs the final SHA-256 check on completion.
 */
internal object TransferResumeBoundaryPolicy {
    fun strongEtag(etag: String): Boolean =
        etag.length in 3..512 && etag.first() == '"' && etag.last() == '"' &&
            etag.substring(1, etag.lastIndex).all { c ->
                c != '"' && c.code >= 0x21 && c != '\u007f'
            }

    fun mayAppend(
        checkpoint: TransferCheckpoint?,
        partPath: String,
        finalPath: String,
        expectedSize: Long,
        expectedSha256: String,
        actualPartBytes: Long,
    ): Boolean {
        val cp = checkpoint ?: return false
        return cp.state in setOf(TransferState.DOWNLOADING, TransferState.PAUSED, TransferState.FAILED) &&
            partPath.isNotBlank() && finalPath.isNotBlank() &&
            cp.tempPath == partPath && cp.finalPath == finalPath &&
            cp.expectedSize == expectedSize && cp.expectedSize > 0 &&
            cp.sha256Expected.equals(expectedSha256, ignoreCase = true) &&
            TransferIntegrityPolicy.validSha256(expectedSha256) &&
            strongEtag(cp.etag) &&
            cp.bytesVerified > 0L && cp.bytesVerified < expectedSize &&
            actualPartBytes >= cp.bytesVerified && actualPartBytes < expectedSize
    }

    /**
     * A68-10: the legacy Link `/download` protocol carries no revision validator,
     * so there is nothing an `If-Range` could be compared against. Those transfers
     * still bind the partial to its persisted checkpoint (destination paths, size
     * and SHA-256) and the final SHA-256 is still verified before anything is
     * committed, so the offset may be resumed without an ETag. AirCat originals
     * keep [mayAppend]: their contract promises a strong validator, and resuming
     * without one is a protocol violation rather than a bandwidth optimisation.
     */
    fun mayAppendWithoutValidator(
        checkpoint: TransferCheckpoint?,
        partPath: String,
        finalPath: String,
        expectedSize: Long,
        expectedSha256: String,
        actualPartBytes: Long,
    ): Boolean {
        val cp = checkpoint ?: return false
        return cp.state in setOf(TransferState.DOWNLOADING, TransferState.PAUSED, TransferState.FAILED) &&
            partPath.isNotBlank() && finalPath.isNotBlank() &&
            cp.tempPath == partPath && cp.finalPath == finalPath &&
            cp.expectedSize == expectedSize && cp.expectedSize > 0 &&
            // A legacy manifest may carry no checksum at all; then the advertised
            // size is the only claim the server makes. A hash that IS present must
            // be a real SHA-256 and must agree.
            cp.sha256Expected.equals(expectedSha256, ignoreCase = true) &&
            (expectedSha256.isBlank() || TransferIntegrityPolicy.validSha256(expectedSha256)) &&
            actualPartBytes > 0L && actualPartBytes < expectedSize
    }
}
