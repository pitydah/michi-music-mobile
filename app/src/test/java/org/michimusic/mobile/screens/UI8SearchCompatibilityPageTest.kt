package org.michimusic.mobile.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI8-DEV-11. No Android/Gradle runtime is required to test this pure policy. */
class UI8SearchCompatibilityPageTest {
    @Test fun preservesFirstFiftyAndInputOrderAtScale() {
        listOf(0, 1, 29, 49, 50, 51, 1000, 50_000).forEach { size ->
            val source = List(size) { it * 7 % 113 }
            val expected = source.filter { it % 2 == 0 }.map { "song-$it" }.take(50)
            val actual = source.asSequence().filter { it % 2 == 0 }
                .projectSearchCompatibilityPage(50) { "song-$it" }
            assertEquals(expected, actual)
        }
    }

    @Test fun stopsFilteringAfterFiftiethMatchingRow() {
        var examined = 0
        var projected = 0
        val results = (1..50_000).asSequence().onEach { examined++ }
            .filter { it % 2 == 0 }.projectSearchCompatibilityPage(50) {
                projected++
                it.toString()
            }
        assertEquals(100, examined)
        assertEquals(50, projected)
        assertEquals("2", results.first())
        assertEquals("100", results.last())
    }

    @Test fun zeroAndNegativeLimitsDoNotEvaluateSource() {
        var visited = 0
        val source = sequence { while (true) { visited++; yield(visited) } }
        assertTrue(source.projectSearchCompatibilityPage(0) { it }.isEmpty())
        assertTrue(source.projectSearchCompatibilityPage(-1) { it }.isEmpty())
        assertEquals(0, visited)
    }

    @Test fun aShortCatalogProjectsOnlyWhatExists() {
        val actual = sequenceOf("local", "synced", "USB")
            .projectSearchCompatibilityPage(50) { "$it-file" }
        assertEquals(listOf("local-file", "synced-file", "USB-file"), actual)
    }

    @Test fun duplicatedIdsRemainIndependentEntries() {
        data class Source(val id: String, val owner: String)
        val items = sequenceOf(Source("1", "micro-a"), Source("1", "micro-b"))
        assertEquals(listOf("micro-a/1", "micro-b/1"),
            items.projectSearchCompatibilityPage(50) { "${it.owner}/${it.id}" })
    }
}
