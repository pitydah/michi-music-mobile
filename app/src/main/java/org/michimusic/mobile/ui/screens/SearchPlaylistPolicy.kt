package org.michimusic.mobile.ui.screens

import java.text.Normalizer
import java.util.Locale

/** Display-only playlist search entry. Identity always comes from the repository ID. */
internal data class SearchPlaylistEntry(
    val id: String,
    val name: String,
    val trackCount: Int,
)

private val playlistSearchSpacing = Regex("\\s+")
private val playlistSearchMarks = Regex("\\p{M}+")

/** Stable, locale-independent matching that also works for accented playlist names. */
internal fun normalizedPlaylistSearchText(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(playlistSearchMarks, "")
        .lowercase(Locale.ROOT)
        .replace(playlistSearchSpacing, " ")

/**
 * Rank exact / prefix / word-prefix / substring results, with stable tiebreakers.
 * Search is strictly by the *real* playlist name; no synthetic library or
 * playback-history rows are ever included as user-authored playlists.
 */
internal fun rankedPlaylistSearch(
    entries: List<SearchPlaylistEntry>,
    query: String,
    limit: Int = 12,
): List<SearchPlaylistEntry> {
    if (limit <= 0 || query != query.trim() || query.trim().length < 2) return emptyList()
    val needle = normalizedPlaylistSearchText(query)
    if (needle.length < 2) return emptyList()

    return entries.asSequence()
        .filter { it.id.isNotBlank() && it.name.isNotBlank() }
        .distinctBy { it.id }
        .mapNotNull { entry ->
            val name = normalizedPlaylistSearchText(entry.name)
            val position = name.indexOf(needle)
            if (position < 0) null
            else {
                val rank = when {
                    name == needle -> 0
                    position == 0 -> 1
                    position > 0 && name[position - 1] == ' ' -> 2
                    else -> 3
                }
                Triple(entry, rank, name)
            }
        }
        .sortedWith(compareBy<Triple<SearchPlaylistEntry, Int, String>>({ it.second }, { it.third }, { it.first.id }))
        .take(limit)
        .map { it.first }
        .toList()
}

/** Revalidate a click against the latest query, filter and observed playlist IDs. */
internal fun playlistSearchNavigationAllowed(
    id: String,
    currentQuery: String,
    currentFilter: String,
    entries: List<SearchPlaylistEntry>,
): Boolean =
    id.isNotBlank() && currentFilter in setOf("ALL", "PLAYLISTS") &&
        rankedPlaylistSearch(entries, currentQuery).any { it.id == id }
