package org.michimusic.mobile.artist.artwork

import java.util.Locale

/** Search variants are only allowed after the user presses the manual action. */
internal object OpenverseManualQueryPolicy {
    const val MAX_TERMS = 2
    const val MAX_TERM_CHARS = 120

    fun queries(userQuery: String, confirmedDisplayCredit: String): List<String> {
        fun clean(raw: String): String = raw.asSequence()
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim().replace(Regex("\\s+"), " ")
            .take(MAX_TERM_CHARS).trim()
        val primary = clean(userQuery)
        if (primary.isBlank()) return emptyList() // never silently send artist name
        val variant = clean(confirmedDisplayCredit)
        return listOf(primary, variant).filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_TERMS)
    }
}
