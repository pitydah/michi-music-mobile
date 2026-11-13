package org.michimusic.data.repository

/** Trust no truncated, malformed or silently split genre sidecar; literal ';' stays intact. */
object TrustedGenreEncoding {
    fun decode(genre: String, rawValues: String): List<String> {
        if (rawValues.isEmpty() || rawValues.length > 8_192 || genre.length > 8_192) return emptyList()
        if (rawValues.count { it == '\u0000' } >= 32) return emptyList()
        val values = rawValues.split('\u0000')
        if (values.isEmpty() || values.size > 32 || values.any { value ->
                value.isBlank() || value.length > 2_048 ||
                    value != value.trim() || '\uFFFD' in value ||
                    value.any { it.isISOControl() }
            }) return emptyList()
        if (values.sumOf(String::length) > 8_192) return emptyList()
        // The compatibility field must describe exactly the verified individual tags.
        if (values.joinToString("; ") != genre) return emptyList()
        return values
    }
}
