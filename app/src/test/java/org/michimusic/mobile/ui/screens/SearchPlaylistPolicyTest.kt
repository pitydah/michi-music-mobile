package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlaylistPolicyTest {
    private val entries = listOf(
        SearchPlaylistEntry("id3", "Noches de Música", 12),
        SearchPlaylistEntry("id2", "Música para viajar", 7),
        SearchPlaylistEntry("id1", "Música", 3),
        SearchPlaylistEntry("id4", "Electromúsica", 4),
    )

    @Test fun `exact prefix and word prefix take precedence`() {
        assertEquals(listOf("id1", "id2", "id3", "id4"),
            rankedPlaylistSearch(entries, "musica").map { it.id })
    }

    @Test fun `accent and case insensitivity is locale stable`() {
        assertEquals(listOf("id3"), rankedPlaylistSearch(entries, "NOCHES DE MUSICA").map { it.id })
        assertEquals("manana", normalizedPlaylistSearchText(" Mañana "))
    }

    @Test fun `blank and one letter queries cannot open matches`() {
        listOf("", " ", "m", "m ").forEach { q ->
            assertTrue(rankedPlaylistSearch(entries, q).isEmpty())
        }
    }

    @Test fun `duplicates are removed by real playlist id only`() {
        val duplicated = entries + SearchPlaylistEntry("id1", "Otra", 0) + SearchPlaylistEntry("new", "Música", 1)
        assertEquals(listOf("id1", "new"), rankedPlaylistSearch(duplicated, "musica", 2).map { it.id })
    }

    @Test fun `stable tie break and bounded result list`() {
        val sameNames = listOf(SearchPlaylistEntry("b", "Mix", 9), SearchPlaylistEntry("a", "Mix", 8))
        assertEquals(listOf("a", "b"), rankedPlaylistSearch(sameNames, "mix").map { it.id })
        assertEquals(listOf("a"), rankedPlaylistSearch(sameNames, "mix", 1).map { it.id })
        assertTrue(rankedPlaylistSearch(sameNames, "mix", 0).isEmpty())
    }

    @Test fun `click guard rejects stale query filter and id`() {
        assertTrue(playlistSearchNavigationAllowed("id1", "música", "PLAYLISTS", entries))
        assertTrue(playlistSearchNavigationAllowed("id1", "musica", "ALL", entries))
        assertFalse(playlistSearchNavigationAllowed("id1", "noches", "ALL", entries))
        assertFalse(playlistSearchNavigationAllowed("id1", "musica", "TRACKS", entries))
        assertFalse(playlistSearchNavigationAllowed("missing", "musica", "ALL", entries))
    }

    @Test fun `empty and invalid ids never become navigation destinations`() {
        assertTrue(rankedPlaylistSearch(listOf(SearchPlaylistEntry("", "Mix", 2)), "mix").isEmpty())
        assertFalse(playlistSearchNavigationAllowed("", "mix", "ALL", entries))
    }
}
