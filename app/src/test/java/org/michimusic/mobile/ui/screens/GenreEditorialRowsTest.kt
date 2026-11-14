package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editorial wall rhythm — the latest distribution scheme, not the historical one:
 *
 * ```
 * COVER(1) -> PAIR(2) -> PAIR(2) -> COVER(1) -> repeat
 * ```
 *
 * A trailing single genre is promoted to COVER instead of leaving an empty half
 * row. Each card is independent; there is no composite four-card block.
 */
class GenreEditorialRowsTest {
    private fun genre(title: String) =
        LibraryGenre(title.lowercase(), title, emptyList(), albumCount = 0)

    private fun genres() = listOf("Ambient", "Blues", "Clásica", "Folk", "Jazz", "Metal",
        "Pop", "Reggae", "Rock", "Soul", "Tango").map(::genre)

    private fun shape(rows: List<EditorialRow>): String = rows.joinToString(",") {
        when (it) {
            is EditorialRow.Cover -> "cover"
            is EditorialRow.Pair -> "pair"
        }
    }

    @Test fun sixGenresFollowCoverPairPairCover() {
        val rows = editorialRows(genres().take(6), columns = 2)
        assertEquals("cover,pair,pair,cover", shape(rows))
        assertEquals(
            listOf("Ambient", "Blues", "Clásica", "Folk", "Jazz", "Metal"),
            rows.flatMap { row ->
                when (row) {
                    is EditorialRow.Cover -> listOf(row.genre.title)
                    is EditorialRow.Pair -> listOf(row.leading.title, row.trailing.title)
                }
            },
        )
    }

    @Test fun elevenGenresFollowTheLiteralCycleWithoutOrphans() {
        val rows = editorialRows(genres(), columns = 2)
        assertEquals("cover,pair,pair,cover,cover,pair,pair", shape(rows))
        assertEquals(3, rows.filterIsInstance<EditorialRow.Cover>().size)
        assertEquals(4, rows.filterIsInstance<EditorialRow.Pair>().size)
        assertEquals(11, rows.map { if (it is EditorialRow.Pair) 2 else 1 }.sum())
        // Every pair has two distinct real genres: no hollow right column.
        rows.filterIsInstance<EditorialRow.Pair>().forEach {
            assertTrue(it.leading.id != it.trailing.id)
        }
        // The last pair is the trailing 10-11, never a cover plus an orphan.
        val last = rows.last() as EditorialRow.Pair
        assertEquals("Soul", last.leading.title)
        assertEquals("Tango", last.trailing.title)
    }

    @Test fun aTrailingSingleGenreIsPromotedToCover() {
        assertEquals("cover,pair,cover", shape(editorialRows(genres().take(4), columns = 2)))
        assertEquals("cover,pair", shape(editorialRows(genres().take(3), columns = 2)))
        assertEquals("cover", shape(editorialRows(genres().take(1), columns = 2)))
        // Five genres: cover, pair, pair (remaining two), never a half-empty row.
        assertEquals("cover,pair,pair", shape(editorialRows(genres().take(5), columns = 2)))
    }

    @Test fun singleColumnModeRendersEveryGenreAsACover() {
        val rows = editorialRows(genres(), columns = 1)
        assertEquals(11, rows.size)
        assertTrue(rows.all { it is EditorialRow.Cover })
        assertEquals(genres().map { it.title }, rows.map { (it as EditorialRow.Cover).genre.title })
    }

    @Test fun stableRowKeysFollowTheGenresNotTheViewport() {
        val rows = editorialRows(genres(), columns = 2)
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys, editorialRows(genres(), columns = 2).map { it.key })
        assertTrue(keys.all { it.startsWith("cover:") || it.startsWith("pair:") })
    }

    @Test fun emptyLibraryHasNoRows() {
        assertTrue(editorialRows(emptyList(), columns = 2).isEmpty())
        assertTrue(editorialRows(emptyList(), columns = 1).isEmpty())
    }
}
