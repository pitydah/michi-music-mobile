package org.michimusic.mobile.artist

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumEnrichmentModelsTest {
    private val candidate = AlbumHistoryCandidate(
        mbid = "a5ec1cd7-52e3-4a85-a04e-62fe0c5f216f",
        title = "Diesel and Dust",
        creditedArtist = "Midnight Oil",
        firstReleaseDate = "1987-08-01",
        disambiguation = "",
    )

    @Test fun exactCreditsMatchIndependentOfCaseAndWhitespace() {
        assertTrue(albumCandidateMatchesLocal("  diesel  and dust ", "midnight oil", candidate))
        assertFalse(albumCandidateMatchesLocal("Diesel and Dust (Remix)", "Midnight Oil", candidate))
        assertFalse(albumCandidateMatchesLocal("Diesel and Dust", "Another Band", candidate))
    }

    @Test fun confirmedSelectionMustComeFromDisplayedCandidate() {
        assertEquals(candidate.mbid, selectedReleaseGroupMbid(listOf(candidate), candidate.mbid))
        assertNull(selectedReleaseGroupMbid(emptyList(), candidate.mbid))
        assertNull(selectedReleaseGroupMbid(listOf(candidate), "00000000-0000-0000-0000-000000000000"))
    }

    @Test fun albumEnrichmentIdentityNeverAliasesAnotherLibraryOrTrackSet() {
        val a = albumEnrichmentScopeKey("library_A", "album_X", listOf("track_2", "track_1"), "Diesel and Dust", "Midnight Oil")
        assertEquals(a, albumEnrichmentScopeKey("library_A", "album_X", listOf("track_1", "track_2"), "Diesel and Dust", "Midnight Oil"))
        assertNotEquals(a, albumEnrichmentScopeKey("library_B", "album_X", listOf("track_1", "track_2"), "Diesel and Dust", "Midnight Oil"))
        assertNotEquals(a, albumEnrichmentScopeKey("library_A", "album_X", listOf("track_1"), "Diesel and Dust", "Midnight Oil"))
        assertNotEquals(a, albumEnrichmentScopeKey("library_A", "album_Y", listOf("track_1", "track_2"), "Diesel and Dust", "Midnight Oil"))
        assertNull(albumEnrichmentScopeKey(null, "album_X", listOf("track_1"), "Diesel and Dust", "Midnight Oil"))
        assertNull(albumEnrichmentScopeKey("SCOPE_ALL", "album_X", listOf("track_1"), "Diesel and Dust", "Midnight Oil"))
        assertNull(albumEnrichmentScopeKey("library_A", "album_X", emptyList(), "Diesel and Dust", "Midnight Oil"))
        assertNull(albumEnrichmentScopeKey("library_A", "album_X", listOf(""), "Diesel and Dust", "Midnight Oil"))
        assertNotEquals(a, albumEnrichmentScopeKey("library_A", "album_X", listOf("track_1", "track_2"),
            "Diesel and Dust (Remaster)", "Midnight Oil"))
        assertNotEquals(a, albumEnrichmentScopeKey("library_A", "album_X", listOf("track_1", "track_2"),
            "Diesel and Dust", "Different Artist"))
        assertNull(albumEnrichmentScopeKey("library_A", "album_X", listOf("track_1"), "", "Midnight Oil"))
    }

    @Test fun unresolvedScopePanelIsNotReusedAcrossDifferentLocalAlbums() {
        val a = albumEnrichmentPanelKey(null, "album-a", listOf("track-1", "track-2"), "Homónimo", "Artista")
        assertEquals(a, albumEnrichmentPanelKey(null, "album-a", listOf("track-2", "track-1"), "Homónimo", "Artista"))
        assertNotEquals(a, albumEnrichmentPanelKey(null, "album-b", listOf("track-1", "track-2"), "Homónimo", "Artista"))
        assertNotEquals(a, albumEnrichmentPanelKey(null, "album-a", listOf("track-1"), "Homónimo", "Artista"))
        assertNotEquals(a, albumEnrichmentPanelKey("library-a", "album-a", listOf("track-1", "track-2"), "Homónimo", "Artista"))
        assertNotEquals(a, albumEnrichmentPanelKey(null, "album-a", listOf("track-1", "track-2"), "Otra obra", "Artista"))
    }

    @Test fun musicBrainzQueryEscapesOperatorsWithoutLosingTitleIdentity() {
        assertEquals("releasegroup:\"AC\\/DC \\+ Live\" AND artist:\"A\\&B\"",
            musicBrainzAlbumQuery("AC/DC + Live", "A&B"))
    }

    @Test fun albumMbidHasStrictUuidShape() {
        assertEquals(candidate.mbid, canonicalAlbumMbid(candidate.mbid.uppercase()))
        assertNull(canonicalAlbumMbid("../../other"))
        assertNull(canonicalAlbumMbid(""))
        assertNull(canonicalAlbumMbid("not-a-release-group"))
    }
}
