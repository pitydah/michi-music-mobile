package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AeroArtworkSizingTest {
    @Test fun compactScreenIsHeightBound() {
        assertEquals(205f, aeroArtworkSideDp(400f, 205f), 0.01f)
    }

    @Test fun largeScreenIsWidthBoundAtNormalFraction() {
        assertEquals(376f, aeroArtworkSideDp(400f, 600f), 0.01f)
    }

    @Test fun compactLayoutUsesNinetyOnePercent() {
        assertEquals(364f, aeroArtworkSideDp(400f, 600f, compact = true), 0.01f)
    }

    @Test fun negativeConstraintsNeverGenerateNegativeArt() {
        assertEquals(0f, aeroArtworkSideDp(-10f, 10f), 0.01f)
    }

    @Test fun nonFiniteConstraintsFailClosed() {
        assertEquals(0f, aeroArtworkSideDp(Float.NaN, 10f), 0.01f)
        assertEquals(0f, aeroArtworkSideDp(10f, Float.POSITIVE_INFINITY), 0.01f)
    }
}
