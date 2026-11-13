package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreBackfillTest {
    @Test fun repeatedBatchesReachAllTracksWithoutStarvation() {
        val eligible = MutableList(4096) { true }
        var cursor = 0
        var processed = 0
        repeat(128) {
            val next = GenreBackfill.nextBatch(eligible, cursor, 32)
            assertEquals(32, next.indices.size)
            for (i in next.indices) {
                assertTrue(eligible[i])
                eligible[i] = false
                processed++
            }
            cursor = next.nextCursor
        }
        assertEquals(4096, processed)
        assertTrue(eligible.none { it })
        assertTrue(GenreBackfill.nextBatch(eligible, cursor, 32).indices.isEmpty())
    }
    @Test fun suppliedRemainingHintDoesNotChangeSelectionOrCursor() {
        val eligible = List(20_000) { it % 3 == 0 }
        val baseline = GenreBackfill.nextBatch(eligible, 15000, 32)
        val optimized = GenreBackfill.nextBatch(eligible, 15000, 32, eligible.count { it })
        assertEquals(baseline, optimized)
    }

    @Test fun skipsAlreadyResolvedAndHandlesInvalidBudget() {
        assertEquals(listOf(1, 3), GenreBackfill.nextBatch(listOf(false, true, false, true), 0, 2).indices)
        assertTrue(GenreBackfill.nextBatch(listOf(true), 0, 0).indices.isEmpty())
    }
}
