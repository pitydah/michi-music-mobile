package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.intelligence.MichiGenreIntelligencePolicy
import org.michimusic.core.models.Track

class GenreGroupingCapacityTest {
    private fun track(values: List<String>) = Track(
        id = "capacity-1",
        title = "Capacity",
        genre = "",
        genreValues = values,
        sourceId = "local",
        filepath = "capacity.flac",
    )

    @Test
    fun ui7KeepsFiveVerifiedGenres() {
        val values = (1..5).map { "Capacity Genre $it" }
        assertEquals(5, groupLibraryGenres(listOf(track(values))).size)
    }

    @Test
    fun ui7KeepsAllThirtyTwoVerifiedGenresWhileMiRankingStaysBounded() {
        val values = (1..32).map { "Capacity Genre $it" }
        val groups = groupLibraryGenres(listOf(track(values)))
        assertEquals(32, groups.size)
        assertEquals(32, groups.sumOf { it.tracks.size })
        assertEquals(4, MichiGenreIntelligencePolicy.keys("", values).size)
    }
}
