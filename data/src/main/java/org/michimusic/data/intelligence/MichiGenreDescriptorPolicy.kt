package org.michimusic.data.intelligence

/**
 * MI-28. Conservative safety gate executed before truncating a content provider FD.
 * Some SAF providers return a pipe, a non-seekable stream or no meaningful size.
 * Such descriptors are unsupported for destructive edits, not guessed writable.
 * Recovery may legitimately start from a zero-length interrupted file.
 */
internal object MichiGenreDescriptorPolicy {
    fun permitsMutation(statBytes: Long, channelBytes: Long, expectedOriginalBytes: Long): Boolean =
        expectedOriginalBytes > 0L && statBytes == expectedOriginalBytes &&
            channelBytes == expectedOriginalBytes

    fun permitsRecovery(statBytes: Long, channelBytes: Long): Boolean =
        statBytes >= 0L && channelBytes >= 0L && statBytes == channelBytes
}
