package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeEditorialPolicyTest {
    @Test fun excludesRecentAndCurrentAndAvoidsArtistMonotony() {
        val tracks = listOf(
            HomeEditorialCandidate("a", "A"), HomeEditorialCandidate("b", "A"),
            HomeEditorialCandidate("c", "B"), HomeEditorialCandidate("d", "C"),
            HomeEditorialCandidate("c", "B"),
        )
        assertEquals(listOf("b", "c", "d"), variedHomeLibraryPickIds(tracks, setOf("a")))
    }
    @Test fun emptyAndSingleCollectionRemainTruthful() {
        assertEquals(emptyList<String>(), variedHomeLibraryPickIds(emptyList(), emptySet()))
        assertEquals(emptyList<String>(), variedHomeLibraryPickIds(listOf(HomeEditorialCandidate("one", "Solo")), setOf("one")))
        assertNull(homeFeaturedTrackId(emptyList(), emptySet(), null))
        assertEquals("one", homeFeaturedTrackId(listOf("one"), setOf("one"), "one"))
    }
    @Test fun prefersDifferentAlbumsEvenWhenArtistsRepeat() {
        val tracks = listOf(
            HomeEditorialCandidate("one", "A", "Shared"),
            HomeEditorialCandidate("two", "B", "Shared"),
            HomeEditorialCandidate("three", "B", "Other"),
        )
        assertEquals(listOf("one", "three", "two"), variedHomeLibraryPickIds(tracks, emptySet()))
    }
    @Test fun heroPrefersUnheardWhenAvailable() {
        assertEquals("other", homeFeaturedTrackId(listOf("recent", "other"), setOf("recent"), null))
        assertEquals("recent", homeFeaturedTrackId(listOf("recent"), setOf("recent"), null))
    }
}
