package org.michimusic.link

/**
 * A37: deterministic per-request allocation limit for Home authorization JSON and
 * original catalog responses. Each request owns its counter; no resident service.
 * The HTTP transport must feed this budget before each write to its output buffer.
 */
internal class AirCatMetadataBudget(private val maxBytes: Int) {
    companion object {
        const val MANIFEST_MAX_BYTES = 8 * 1024 * 1024
        const val AUTHORIZATION_MAX_BYTES = 64 * 1024
        const val ERROR_MAX_BYTES = 4 * 1024

        /** A missing length is acceptable only because the byte stream is bounded as it arrives. */
        fun contentLengthAllowed(raw: String?, maxBytes: Int): Boolean {
            if (maxBytes <= 0) return false
            if (raw == null) return true
            val n = raw.toLongOrNull() ?: return false
            return n >= 0L && n <= maxBytes.toLong()
        }
    }

    init { require(maxBytes in 1..MANIFEST_MAX_BYTES) }
    var consumedBytes: Int = 0
        private set

    /** False before mutation when input is malformed or would cross the bound. */
    fun accept(chunkBytes: Int): Boolean {
        if (chunkBytes < 0 || chunkBytes > maxBytes - consumedBytes) return false
        consumedBytes += chunkBytes
        return true
    }
}
