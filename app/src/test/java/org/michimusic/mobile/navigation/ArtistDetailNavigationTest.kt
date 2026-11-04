package org.michimusic.mobile.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.ui.screens.ArtistDetailBackDestination
import org.michimusic.mobile.ui.screens.artistDetailBackDestination

/** Regression: artist is a genuine Library detail route, not a dialog or immersive player. */
class ArtistDetailNavigationTest {
    @Test fun artistRouteKeepsLibraryIdentityAndPlayerChrome() {
        for (route in listOf("artist/{artistKey}", "artist/{artistKey}?enrich={enrich}",
            "artist/aTWlkbmlnaHQgT2ls?enrich=true")) {
            val state = navigationPresentationFor(route)
            assertEquals(MichiScreenLevel.DETAIL, state.level)
            assertEquals("library", state.selectedPrimaryTab)
            assertTrue(state.showDock)
            assertTrue(state.showMiniPlayer)
        }
        assertFalse(navigationPresentationFor("nowplaying").showDock)
    }

    @Test fun artistRoutePreservesCreditIncludingCollaborationsAndUnknowns() {
        for (credit in listOf("Midnight Oil", "Patti Austin & James Ingram", "AC/DC", "", "Björk")) {
            assertEquals(credit, decodeArtistCredit(encodeArtistCredit(credit)))
        }
        assertNull(decodeArtistCredit("%invalid%"))
    }

    @Test fun codecRejectsMalformedUtf8NonCanonicalSegmentsAndOversizedRoutes() {
        // /w is URL-safe Base64 for the single invalid UTF-8 byte 0xff.
        assertNull(decodeArtistCredit("a_w"))
        assertNull(decodeArtistCredit("aTWlkbmlnaHQgT2ls="))
        assertNull(decodeArtistCredit("a" + "Z".repeat(2050)))
        assertNull(decodeArtistCredit(encodeArtistCredit("Long title ".repeat(500))))
        assertNull(decodeArtistCredit("aA/AA"))
        assertNull(decodeArtistCredit("a00")) // non-canonical encoding for 0xd3; invalid UTF-8
        assertEquals("", decodeArtistCredit("a"))
        val credits = listOf("Árbol & Mar", "Sigur Rós", "東京", "/ ", "Muse + Radiohead")
        credits.forEach { credit -> assertEquals(credit, decodeArtistCredit(encodeArtistCredit(credit))) }
    }

    @Test fun backPolicyReturnsToBiographyBeforeLeavingLibrary() {
        assertEquals(ArtistDetailBackDestination.OVERVIEW, artistDetailBackDestination(true))
        assertEquals(ArtistDetailBackDestination.LIBRARY, artistDetailBackDestination(false))
    }
}
