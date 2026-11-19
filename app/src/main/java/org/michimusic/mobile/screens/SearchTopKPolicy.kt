package org.michimusic.mobile.screens

import java.util.PriorityQueue

/**
 * Bounded stable top-K selection for large libraries: O(N log K), O(K) working memory.
 * A regular sortedWith(...).take(K) materializes and sorts every match even though
 * Search shows at most 30. Equal-ranked rows retain their original input order.
 * No persistent index, external scan, database mutation or resident service.
 */
internal fun <T> Sequence<T>.bestRankedSearchResults(
    maxResults: Int,
    comparator: Comparator<T>,
): List<T> {
    if (maxResults <= 0) return emptyList()
    data class Indexed<E>(val order: Long, val value: E)
    val stable = Comparator<Indexed<T>> { a, b ->
        val byRank = comparator.compare(a.value, b.value)
        if (byRank != 0) byRank else a.order.compareTo(b.order)
    }
    // Queue head is the worst retained candidate; replace it only with a better one.
    val worstFirst = PriorityQueue<Indexed<T>>(minOf(maxResults, 64), stable.reversed())
    var index = 0L
    for (value in this) {
        val candidate = Indexed(index++, value)
        if (worstFirst.size < maxResults) {
            worstFirst.add(candidate)
        } else if (stable.compare(candidate, worstFirst.peek()) < 0) {
            worstFirst.remove()
            worstFirst.add(candidate)
        }
    }
    return worstFirst.toList().sortedWith(stable).map { it.value }
}
