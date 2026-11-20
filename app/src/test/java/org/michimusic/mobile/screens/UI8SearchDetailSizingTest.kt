package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UI8SearchDetailSizingTest {
    @Test fun shortArtistListDoesNotReserveFullHeight() {
        assertEquals(138f, searchDetailHeight(3, 1f, hasSubtitle = true).value, 0.01f)
        assertEquals(0f, searchDetailHeight(0, 1f, hasSubtitle = true).value, 0.01f)
    }

    @Test fun largeListsScrollWithinBoundAndLargerTextGetsRoom() {
        assertEquals(252f, searchDetailHeight(30, 1f, hasSubtitle = false).value, 0.01f)
        assertEquals(120f, searchDetailHeight(30, 1f, hasSubtitle = false,
            availableViewport = androidx.compose.ui.unit.Dp(240f)).value, 0.01f)
        assertEquals(360f, searchDetailHeight(30, 1f, hasSubtitle = false,
            availableViewport = androidx.compose.ui.unit.Dp(1200f)).value, 0.01f)
        assertTrue(searchDetailHeight(3, 2f, hasSubtitle = true) >
            searchDetailHeight(3, 1f, hasSubtitle = true))
    }

    @Test fun nonFiniteFontScaleFallsBackSafely() {
        assertEquals(138f, searchDetailHeight(3, Float.NaN, hasSubtitle = true).value, 0.01f)
    }
}
