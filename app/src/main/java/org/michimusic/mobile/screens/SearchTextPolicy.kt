package org.michimusic.mobile.screens

import java.text.Normalizer
import java.util.Locale

private val searchCombiningMarks = Regex("\\p{M}+")
private val searchWhiteSpace = Regex("\\s+")

/** The same normalization must be used by the ViewModel and the live click guard. */
internal fun normalizedSearchText(text: String): String {
    val trimmed = text.trim()
    // SearchBenchmarkHarnessTest scans 50k titles: common ASCII must avoid
    // the Unicode decomposition and regex paths on every item.
    val folded = if (trimmed.all { it.code < 128 }) trimmed.lowercase(Locale.ROOT)
        else Normalizer.normalize(trimmed, Normalizer.Form.NFD)
            .replace(searchCombiningMarks, "")
            .lowercase(Locale.ROOT)
    return if (folded.any { it.isWhitespace() })
        folded.replace(searchWhiteSpace, " ") else folded
}

/** Matching is literal, accent-insensitive and never uses a user-supplied regex. */
internal fun searchTextMatches(text: String, query: String): Boolean {
    val needle = normalizedSearchText(query)
    return needle.length >= 2 && normalizedSearchText(text).contains(needle)
}

/** Lower scores rank first. None of these scores represent listening history. */
internal fun searchMatchRank(text: String, query: String): Int {
    val needle = normalizedSearchText(query)
    if (needle.length < 2) return Int.MAX_VALUE
    val haystack = normalizedSearchText(text)
    val position = haystack.indexOf(needle)
    return when {
        position < 0 -> Int.MAX_VALUE
        haystack == needle -> 0
        position == 0 -> 1
        position > 0 && haystack[position - 1] == ' ' -> 2
        else -> 3
    }
}
