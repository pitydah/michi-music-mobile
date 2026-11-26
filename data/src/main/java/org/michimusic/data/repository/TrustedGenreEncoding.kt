package org.michimusic.data.repository

/**
 * Trust no truncated, malformed or silently split genre sidecar; literal ';' stays intact.
 *
 * Two committed scalar representations are valid:
 * - the active Metadata Editor sidecar stores the primary/first genre in the
 *   scalar compatibility column and the complete ordered NUL list in
 *   [rawValues] (`genre = "Rock"`, `rawValues = "Rock\0Jazz\0Blues"`);
 * - the legacy tag/index sidecar stores the full `"; "` join in the scalar
 *   and the same ordered values in the NUL list.
 * A scalar that describes neither representation is rejected.
 */
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
        // Active writer: primary genre. Legacy/index writer: full "; " join.
        // Everything else is a contradiction, not something to reinterpret.
        val scalarMatches = values.first() == genre || values.joinToString("; ") == genre
        if (!scalarMatches) return emptyList()
        return values
    }
}
