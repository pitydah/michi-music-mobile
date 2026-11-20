package org.michimusic.mobile.screens

/**
 * UI8-DEV-11. The legacy API exposes the first 50 matching sources in input
 * order, not ranked top-30 hits. Filter lazily before limiting, and only then
 * project to SearchResult. Avoid O(matches) intermediate lists/allocations.
 * No persistent index, file I/O, or additional background work.
 */
internal fun <T, R> Sequence<T>.projectSearchCompatibilityPage(
    maxResults: Int,
    project: (T) -> R,
): List<R> = if (maxResults <= 0) emptyList() else take(maxResults).map(project).toList()
