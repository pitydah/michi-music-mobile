package org.michimusic.mobile.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI8-DEV-10: map the first 30 tracks, never build UI rows for all matches. */
class UI8SearchProjectionCostTest {
    private data class Candidate(val rank: Int, val title: String, val id: Int)
    private data class Row(val candidate: Candidate, val label: String)
    private val order = compareBy<Candidate> { it.rank }.thenBy { it.title }.thenBy { it.id }
    private val rowOrder = compareBy<Row> { it.candidate.rank }
        .thenBy { it.candidate.title }.thenBy { it.candidate.id }

    @Test fun lazyProjectionMatchesFormerEagerProjectionExactly() {
        listOf(0, 1, 2, 29, 30, 31, 1_000, 10_000).forEach { count ->
            val items = List(count) { i -> Candidate((i * 19) % 17, "title ${i % 31}", i) }
            val eager = items.asSequence().map { Row(it, "${it.id}") }
                .bestRankedSearchResults(30, rowOrder)
            val delayed = items.asSequence().bestRankedSearchResults(30, order)
                .map { Row(it, "${it.id}") }
            assertEquals(eager, delayed)
        }
    }

    @Test fun projectionCountIsBoundedByThirtyEvenWithFiftyThousandCandidates() {
        val items = (1..50_000).asSequence().map { Candidate(it % 17, "title $it", it) }
        var projections = 0
        val rows = items.bestRankedSearchResults(30, order).map { item ->
            projections++
            Row(item, "source-${item.id}")
        }
        assertEquals(30, rows.size)
        assertEquals(30, projections)
        assertTrue(rows.zipWithNext().all { (a, b) -> rowOrder.compare(a, b) <= 0 })
    }
}
