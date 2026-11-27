package org.michimusic.mobile.library

import java.io.InputStream
import java.security.MessageDigest

/** A proof of actual bytes observed under explicit user confirmation, not a metadata guess. */
internal data class ArtistTrackByteProof(val sha256: String, val byteCount: Long)

internal object ArtistTrackSourceProof {
    // Limit the cost of a synchronous, deliberate confirmation inside a Room transaction.
    // Large sources are refused, not partially hashed or silently accepted.
    const val MAX_BYTES = 512L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024

    /** A pre-existing trusted SHA-256 in the catalogue is an additional anchor. */
    fun consistentWithCatalog(proof: ArtistTrackByteProof, contentSignature: String): Boolean {
        val hash = contentSignature.substringBefore('\u0000').lowercase()
        return if (Regex("[a-f0-9]{64}").matches(hash)) proof.sha256 == hash else true
    }

    fun digest(maxBytes: Long = MAX_BYTES, onChunk: () -> Unit = {}, open: () -> InputStream?): ArtistTrackByteProof? {
        if (maxBytes <= 0 || maxBytes > MAX_BYTES) return null
        return try {
            val input = open() ?: return null
            input.use { bytes ->
                val md = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BUFFER_SIZE)
                var size = 0L
                var consecutiveEmptyReads = 0
                while (true) {
                    onChunk()
                    if (Thread.currentThread().isInterrupted) return null
                    val count = bytes.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        if (++consecutiveEmptyReads >= 8) return null
                        continue
                    }
                    consecutiveEmptyReads = 0
                    size += count
                    if (size > maxBytes) return null
                    md.update(buffer, 0, count)
                }
                if (size <= 0L) return null
                ArtistTrackByteProof(
                    md.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                    size,
                )
            }
        } catch (_: java.io.IOException) { null }
        catch (_: SecurityException) { null }
    }
}
