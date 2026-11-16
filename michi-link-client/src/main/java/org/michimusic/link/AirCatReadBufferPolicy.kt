package org.michimusic.link

/** A30: bound memory and avoid zero-capacity read loops for pinned original bytes. */
internal object AirCatReadBufferPolicy {
    const val MIN_BYTES = 1024
    const val MAX_BYTES = 1024 * 1024

    fun refusal(requested: Int): String? = when {
        requested < MIN_BYTES -> "AIRC_BUFFER_TOO_SMALL"
        requested > MAX_BYTES -> "AIRC_BUFFER_TOO_LARGE"
        else -> null
    }
}
