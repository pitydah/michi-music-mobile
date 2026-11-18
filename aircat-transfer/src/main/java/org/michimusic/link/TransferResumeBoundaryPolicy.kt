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
     * A68-10/A69-02: the legacy Link `/download` protocol carries no revision
     * validator, so there is nothing an `If-Range` could be compared against. Two
     * distinct cases share this entry point, and only one of them may append:
     *
     * - Case B, a manifest that declares a SHA-256: the partial is bound to its
     *   persisted checkpoint (destination paths, size, digest) and the assembled
     *   file is verified against that digest before commit, so the offset may be
     *   resumed without an ETag.
     * - Case C, a manifest with no digest: nothing can authenticate either the
     *   bytes already on disk or the bytes about to arrive, so this refuses and
     *   the caller restarts from zero. That download still works, it is simply
     *   never described as cryptographically verified.
     *
     * AirCat originals keep [mayAppend]: their contract promises a strong
     * validator, and resuming without one is a protocol violation rather than a
     * bandwidth optimisation.
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
            // A69-02: appending to bytes nobody can authenticate is not a resume,
            // it is blind concatenation. The legacy /download protocol publishes no
            // revision validator, so the final SHA-256 is the ONLY thing that can
            // prove the assembled file is the one the manifest described. Without a
            // real digest there is nothing to verify against, so the caller must
            // restart from zero: the size a server advertises is a length, not an
            // identity. A manifest that does carry a digest must match it exactly.
            cp.sha256Expected.equals(expectedSha256, ignoreCase = true) &&
            TransferIntegrityPolicy.validSha256(expectedSha256) &&
            actualPartBytes > 0L && actualPartBytes < expectedSize
    }
}
