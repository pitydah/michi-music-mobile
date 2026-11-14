package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editorial's grouped row rhythm. These are layout contracts, not artwork:
 * a full-width COVER followed by an asymmetric portrait pair, with a trailing
 * pair used instead of an orphan card.
 */
class GenreEditorialRowsTest {
    private fun genre(title: String) =
        LibraryGenre(title.lowercase(), title, emptyList(), albumCount = 0)

    private fun genres() = listOf("Ambient", "Blues", "Clásica", "Folk", "Jazz", "Metal",
        "Pop", "Reggae", "Rock", "Soul", "Tango").map(::genre)

    @Test fun elevenGenresProduceCoversAndPairsNeverElevenBanners() {
        val rows = editorialRows(genres(), columns = 2)
        val covers = rows.filterIsInstance<EditorialRow.Cover>()
        val pairs = rows.filterIsInstance<EditorialRow.Pair>()
        assertEquals(3, covers.size)
        assertEquals(4, pairs.size)
        // 3 covers + 4 pairs == 3 + 8 == 11 cards.
        assertEquals(11, covers.size + pairs.size * 2)
        // A cover is followed by an asymmetric portrait pair, and the trailing
        // two genres become a pair rather than a cover plus an orphan.
        assertTrue("cover/pair rhythm lost", pairs.isNotEmpty())
        assertEquals("Ambient", covers.first().genre.title)
        assertEquals(listOf("Ambient", "Blues", "Clásica", "Folk", "Jazz", "Metal",
            "Pop", "Reggae", "Rock", "Soul", "Tango"), rows.flatMap { row ->
            when (row) {
                is EditorialRow.Cover -> listOf(row.genre.title)
                is EditorialRow.Pair -> listOf(row.leading.title, row.trailing.title)
            }
        })
    }

    @Test fun trailingSingleGenreIsPromotedToACoverInsteadOfAnOrphanSlot() {
        val rows = editorialRows(genres().take(4), columns = 2)
        assertEquals(2, rows.filterIsInstance<EditorialRow.Cover>().size)
        assertEquals(1, rows.filterIsInstance<EditorialRow.Pair>().size)
        val single = editorialRows(genres().take(3), columns = 2)
        assertEquals(1, single.filterIsInstance<EditorialRow.Cover>().size)
        assertEquals(1, single.filterIsInstance<EditorialRow.Pair>().size)
        assertEquals(1, editorialRows(genres().take(1), columns = 2).size)
        assertTrue(editorialRows(genres().take(1), columns = 2).first() is EditorialRow.Cover)
    }

    @Test fun singleColumnModeRendersEveryGenreAsACover() {
        val rows = editorialRows(genres(), columns = 1)
        assertEquals(11, rows.size)
        assertTrue(rows.all { it is EditorialRow.Cover })
    }

    @Test fun stableRowKeysFollowTheGenresNotTheViewport() {
        val rows = editorialRows(genres(), columns = 2)
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // Keys must not depend on card height/width, so restoration survives a
        // rotation or a split-screen resize.
        assertEquals(keys, editorialRows(genres(), columns = 2).map { it.key })
        assertTrue(keys.all { it.startsWith("cover:") || it.startsWith("pair:") })
    }

    @Test fun emptyLibraryHasNoRows() {
        assertTrue(editorialRows(emptyList(), columns = 2).isEmpty())
    }
}
