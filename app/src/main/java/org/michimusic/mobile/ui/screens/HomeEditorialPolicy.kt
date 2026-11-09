package org.michimusic.mobile.ui.screens

import java.util.Locale

/** Deterministic *local library* choices: not claims of remote recommendations. */
internal data class HomeEditorialCandidate(
    val id: String, val artist: String, val album: String = "",
)

internal fun variedHomeLibraryPickIds(
    candidates: List<HomeEditorialCandidate>,
    excludedTrackIds: Set<String>,
    limit: Int = 6,
): List<String> {
    if (limit <= 0) return emptyList()
    val eligible = candidates.asSequence().filter { it.id.isNotBlank() && it.id !in excludedTrackIds }
        .distinctBy { it.id }.toList()
    val result = ArrayList<String>(minOf(eligible.size, limit))
    val selected = HashSet<String>()
    val artists = HashSet<String>()
    val albums = HashSet<String>()
    fun albumKey(item: HomeEditorialCandidate): String =
        item.album.trim().lowercase(Locale.ROOT).ifEmpty { "unknown:${item.id}" }
    fun artistKey(item: HomeEditorialCandidate): String =
        item.artist.trim().lowercase(Locale.ROOT).ifEmpty { "unknown:${item.id}" }
    fun add(item: HomeEditorialCandidate) {
        result.add(item.id)
        selected.add(item.id)
        artists.add(artistKey(item))
        albums.add(albumKey(item))
    }
    // First pass: different artists AND albums. Never claim these are learned tastes.
    for (item in eligible) {
        if (artistKey(item) !in artists && albumKey(item) !in albums) add(item)
        if (result.size >= limit) return result
    }
    // Then admit a new album from a known artist; finally fill available space.
    for (item in eligible) {
        if (item.id !in selected && albumKey(item) !in albums) add(item)
        if (result.size >= limit) return result
    }
    for (item in eligible) {
        if (item.id !in selected) add(item)
        if (result.size >= limit) break
    }
    return result
}

/** Prefer a nonrecent, nonplaying local song to avoid duplicating the Recent carousel. */
internal fun homeFeaturedTrackId(
    availableIds: List<String>,
    recentIds: Set<String>,
    currentTrackId: String?,
): String? = availableIds.firstOrNull { it != currentTrackId && it !in recentIds }
    ?: availableIds.firstOrNull { it != currentTrackId }
    ?: availableIds.firstOrNull()
