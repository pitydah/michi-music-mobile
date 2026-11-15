package org.michimusic.data.repository

import org.michimusic.core.intelligence.MichiGenreAbsencePolicy

/** Preserves original genre evidence. Never derives genres from artwork or external enrichment. */
internal object GenreMetadataMerge {
    fun choose(embedded: String?, provider: String?, retriever: String?): String =
        sequenceOf(embedded, provider, retriever)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull { MichiGenreAbsencePolicy.isAssignableLabel(it) }.orEmpty()
}
