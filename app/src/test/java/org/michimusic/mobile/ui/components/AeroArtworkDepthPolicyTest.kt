package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroArtworkDepthPolicyTest {
    @Test fun standardDenseRowsHaveNoContactElevation() {
        val depth = aeroArtworkDepth(AeroArtworkEmphasis.Standard, highContrast = false)
        assertEquals(0, depth.shadowElevationDp)
        assertTrue(depth.innerLightAlpha in 0f..0.12f)
        assertTrue(depth.innerShadeAlpha in 0f..0.4f)
    }

    @Test fun featuredAndHeroAreDistinctAndBounded() {
        val featured = aeroArtworkDepth(AeroArtworkEmphasis.Featured, false)
        val hero = aeroArtworkDepth(AeroArtworkEmphasis.Hero, false)
        assertTrue(featured.shadowElevationDp in 1..4)
        assertTrue(hero.shadowElevationDp in 5..8)
        assertTrue(hero.innerLightAlpha > featured.innerLightAlpha)
        assertTrue(hero.innerShadeAlpha > featured.innerShadeAlpha)
        assertTrue(hero.shadowElevationDp > featured.shadowElevationDp)
    }

    @Test fun highContrastDisablesEveryDecorativeDepthLayer() {
        for (emphasis in AeroArtworkEmphasis.entries) {
            val depth = aeroArtworkDepth(emphasis, highContrast = true)
            assertEquals(0, depth.shadowElevationDp)
            assertEquals(0f, depth.innerLightAlpha, 0f)
            assertEquals(0f, depth.innerShadeAlpha, 0f)
        }
    }
}
