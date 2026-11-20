package org.michimusic.mobile.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Top-30 behavior must equal the former stable sortedWith().take(30). */
class UI8SearchTopKTest {
    private data class Hit(val rank: Int, val title: String, val id: String)
    private val order = compareBy<Hit> { it.rank }.thenBy { it.title }.thenBy { it.id }

    @Test fun exactRankingEquivalenceAtLargeCatalogSizes() {
        listOf(0, 1, 2, 29, 30, 31, 1_000, 10_000, 50_000).forEach { size ->
            val matches = List(size) { i ->
                Hit((i * 37) % 7, "Title ${(i * 23) % 67}", "id-${i % 50}")
            }
            assertEquals(matches.sortedWith(order).take(30),
                matches.asSequence().bestRankedSearchResults(30, order))
        }
    }

    @Test fun equalRanksKeepOriginalOrderEvenWithDuplicateIds() {
        val duplicates = List(100) { Hit(0, "Same", "Same") }
        // Index must be observable; otherwise equals() would mask an unstable top-K.
        val identified = duplicates.mapIndexed { index, value -> index to value }
        val byData = Comparator<Pair<Int, Hit>> { a, b -> order.compare(a.second, b.second) }
        assertEquals((0 until 30).toList(), identified.asSequence()
            .bestRankedSearchResults(30, byData).map { it.first })
    }

    @Test fun invalidLimitsAndSmallInputsStaySafe() {
        val source = sequenceOf(Hit(2, "B", "b"), Hit(1, "A", "a"))
        assertTrue(source.bestRankedSearchResults(0, order).isEmpty())
        assertTrue(source.bestRankedSearchResults(-2, order).isEmpty())
        assertEquals(listOf(Hit(1, "A", "a"), Hit(2, "B", "b")),
            source.bestRankedSearchResults(5, order))
    }

    @Test fun sourceIsConsumedOnlyOnceAndNoMoreThanKItemsSurvive() {
        var visits = 0
        val results = sequence {
            repeat(10_000) { i -> visits++; yield(Hit(10_000 - i, "x", "$i")) }
        }.bestRankedSearchResults(30, order)
        assertEquals(10_000, visits)
        assertEquals(30, results.size)
    }
}
