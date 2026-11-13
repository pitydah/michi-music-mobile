package org.michimusic.data.repository

/** Local bounded genre provenance; no audio reads, guessing or partial memberships. */
internal object TagGenreBounds {
    private const val MAX_VALUES = 32
    private const val MAX_FIELD = 2_048
    private const val MAX_TOTAL = 8_192
    private const val MAX_RAW_FIELDS = 256

    /** null = rejected payload; empty = no genre tag. Never silently truncate. */
    fun safeValues(fields: EmbeddedTagFields?): List<String>? {
        val raw = fields?.genres.orEmpty()
        if (raw.size > MAX_RAW_FIELDS) return null
        val values = LinkedHashSet<String>()
        var characters = 0
        for (candidate in raw) {
            val label = candidate.trim()
            if (label.isBlank()) continue
            if (label.length > MAX_FIELD || '\uFFFD' in label ||
                label.any { it.code < 32 || it.code == 127 }) return null
            characters += label.length
            if (characters > MAX_TOTAL) return null
            values.add(label)
            if (values.size > MAX_VALUES) return null
        }
        return values.toList()
    }
}
