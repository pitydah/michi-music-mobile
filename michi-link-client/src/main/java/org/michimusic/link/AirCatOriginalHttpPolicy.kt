package org.michimusic.link

/**
 * A37: original-file HTTP representation gate for a pinned, grant-authorized producer.
 * Evaluate the complete response headers before opening the body or appending to .part.
 * No I/O, timers, network requests or persistent allocations.
 * SHA-256 of the full file remains mandatory; ETag never substitutes for integrity.
 */
object AirCatOriginalHttpPolicy {
    enum class Action { STREAM, RESTART_FROM_ZERO }
    data class Verdict(val action: Action? = null, val refusal: String? = null) {
        val allowed: Boolean get() = refusal == null && action != null
    }

    private val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")

    /** Strong entity tags only: rejects weak validators, controls and malformed quotes. */
    fun strongEtag(etag: String?): Boolean {
        val v = etag ?: return false
        if (v.length !in 3..512 || v.first() != '"' || v.last() != '"') return false
        return v.substring(1, v.length - 1).all { ch ->
            ch != '"' && ch.code in 0x21..0xff && ch.code != 0x7f
        }
    }

    fun evaluate(
        requestedOffset: Long,
        statusCode: Int,
        contentRange: String?,
        contentLength: Long?,
        contentEncoding: String?,
        etag: String?,
        expectedEtag: String?,
    ): Verdict {
        fun no(reason: String) = Verdict(refusal = reason)
        if (requestedOffset < 0L) return no("AIRC_HTTP_NEGATIVE_OFFSET")
        if (requestedOffset > 0L && !strongEtag(expectedEtag))
            return no("AIRC_HTTP_RESUME_ETAG_REQUIRED")
        if (contentLength != null && contentLength < 0L) return no("AIRC_HTTP_INVALID_LENGTH")
        // Compressed HTTP representations are not identical to the source asset.
        if (contentEncoding != null && !contentEncoding.trim().equals("identity", ignoreCase = true))
            return no("AIRC_HTTP_TRANSFORMED_CONTENT")
        if (!strongEtag(etag)) return no("AIRC_HTTP_STRONG_ETAG_REQUIRED")
        return when (statusCode) {
            200 -> {
                if (contentRange != null) no("AIRC_HTTP_RANGE_ON_FULL_RESPONSE")
                else if (contentLength == 0L) no("AIRC_HTTP_EMPTY_ORIGINAL")
                else Verdict(action = if (requestedOffset == 0L) Action.STREAM else Action.RESTART_FROM_ZERO)
            }
            206 -> {
                if (requestedOffset == 0L) return no("AIRC_HTTP_UNSOLICITED_PARTIAL")
                if (!strongEtag(expectedEtag) || expectedEtag != etag)
                    return no("AIRC_HTTP_ETAG_MISMATCH")
                // '*' total is not good enough to bind a resumption to one immutable file.
                val m = range.matchEntire(contentRange.orEmpty())
                    ?: return no("AIRC_HTTP_INVALID_CONTENT_RANGE")
                val first = m.groupValues[1].toLongOrNull()
                    ?: return no("AIRC_HTTP_INVALID_CONTENT_RANGE")
                val last = m.groupValues[2].toLongOrNull()
                    ?: return no("AIRC_HTTP_INVALID_CONTENT_RANGE")
                val total = m.groupValues[3].toLongOrNull()
                    ?: return no("AIRC_HTTP_INVALID_CONTENT_RANGE")
                if (first != requestedOffset || first > last || last >= total || last == Long.MAX_VALUE)
                    return no("AIRC_HTTP_WRONG_CONTENT_RANGE")
                val size = last - first + 1L
                if (contentLength != null && contentLength != size)
                    return no("AIRC_HTTP_LENGTH_RANGE_MISMATCH")
                Verdict(action = Action.STREAM)
            }
            else -> no("AIRC_HTTP_STATUS_UNEXPECTED")
        }
    }
}
