package org.michimusic.data.repository

/** Preserves original genre evidence. Never derives genres from artwork or external enrichment. */
internal object GenreMetadataMerge {
    fun choose(embedded: String?, provider: String?, retriever: String?): String =
        sequenceOf(embedded, provider, retriever)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull { !isPlaceholder(it) }.orEmpty()

    private fun isPlaceholder(value: String): Boolean {
        val normalized = value.trim().lowercase(java.util.Locale.ROOT)
        return normalized in setOf("unknown", "unknown genre", "<unknown>",
            "sin género", "sin genero", "sin clasificar", "n/a", "unspecified", "not specified", "?")
    }
}
