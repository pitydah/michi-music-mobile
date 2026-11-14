package org.michimusic.core.intelligence

/**
 * MI-03G: metadata-only genre keys, compatible with UI7's semicolon/NUL tags.
 * The source catalog owns identity and scoping; this helper neither reads files
 * nor guesses genres from paths, album art, user history or external providers.
 */
object MichiGenreIntelligencePolicy {
    const val MAX_TAGS_PER_TRACK = 4
    private const val MAX_RAW_LENGTH = 512
    private const val MAX_TAG_LENGTH = 96

    /** A slash is NOT a delimiter: UI7 preserves such labels as Rock/Pop. */
    fun keys(raw: String): List<String> {
        if (raw.isEmpty() || raw.length > MAX_RAW_LENGTH) return emptyList()
        return raw.split('\u0000', '\u001f').asSequence()
            .map { MichiGenreDisambiguationPolicy.key(it) }
            .filter { it.isNotBlank() && it.length <= MAX_TAG_LENGTH }
            .distinct()
            .take(MAX_TAGS_PER_TRACK)
            .toList()
    }

    /**
     * C12 authoritative multiformat tags are literal values. A semicolon inside
     * ONE verified tag is not a separator; legacy raw strings retain MI parsing.
     * Reject absurd lists without scanning every entry; never infer values.
     */
    fun keys(raw: String, verifiedValues: List<String>): List<String> {
        if (verifiedValues.isEmpty()) return keys(raw)
        val normalized = LinkedHashSet<String>(MAX_TAGS_PER_TRACK)
        for (tag in verifiedValues.take(16)) {
            if (tag.isBlank() || tag.length > MAX_RAW_LENGTH) continue
            val value = MichiGenreDisambiguationPolicy.key(tag)
            if (value.isBlank() || value.length > MAX_TAG_LENGTH) continue
            normalized.add(value)
            if (normalized.size == MAX_TAGS_PER_TRACK) break
        }
        return normalized.toList()
    }

    /** No inferred genre if the authoritative UI7 owner evidence is ambiguous. */
    fun verifiedKeys(raw: String, ownerVerified: Boolean, available: Boolean): List<String> =
        if (ownerVerified && available) keys(raw) else emptyList()
}
