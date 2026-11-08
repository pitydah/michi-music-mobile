package org.michimusic.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Token-level WCAG checks; run UI contrast and accessibility tests in OpenCode as well. */
class AeroContrastAndGradientTest {
    @Test fun whiteAndBlackHaveExpectedReferenceRatio() {
        assertEquals(21.0, aeroContrastRatio(Color.White, Color.Black), 0.01)
        assertEquals(1.0, aeroContrastRatio(Color.Black, Color.Black), 0.01)
    }

    @Test fun allSemanticSurfacesSupportAReadableDefaultInk() {
        for (role in AeroSurfaceRole.entries) {
            for (highContrast in listOf(false, true)) {
                val surface = aeroMaterialStyle(role, highContrast).fill
                val ink = aeroReadableInk(TextPrimary, surface)
                assertTrue("$role highContrast=$highContrast", aeroContrastRatio(ink, surface) >= 4.5)
            }
        }
    }

    @Test fun lowOpacityInkIsRejectedRatherThanClaimedAccessible() {
        val weak = Color.White.copy(alpha = 0.1f)
        val surface = aeroMaterialStyle(AeroSurfaceRole.Raised, false).fill
        assertTrue(aeroContrastRatio(weak, surface) < 4.5)
        assertTrue(aeroContrastRatio(aeroReadableInk(weak, surface), surface) >= 4.5)
    }

    @Test fun seededAccentsRemainReadableOnBothLightAndDarkSurfaces() {
        for (seed in listOf("", "Miles Davis", "Patti Austin & James Ingram", "Michi")) {
            for (background in listOf(SurfaceObsidian, Color.White)) {
                assertTrue(aeroContrastRatio(michiAccentForOn(seed, background), background) >= 4.5)
            }
        }
    }

    @Test fun darkModeGradientCompatibilityPreservesHistoricColors() {
        assertEquals(Color(0xFF1E222B), aeroGradientStops(AeroGradientRole.Inset, false).first())
        assertEquals(Color(0xFF272D39), aeroGradientStops(AeroGradientRole.Raised, false).first())
        assertEquals(AeroGraphite, aeroGradientStops(AeroGradientRole.Hero, false).last())
        assertEquals(SurfaceObsidian, aeroGradientStops(AeroGradientRole.HeroToCanvas, false).last())
    }

    @Test fun highContrastGradientsCollapseIntoOpaqueSolid() {
        for (role in AeroGradientRole.entries) {
            val stops = aeroGradientStops(role, highContrast = true)
            assertEquals(1, stops.size)
            assertEquals(1f, stops.single().alpha, 0f)
        }
    }

    @Test fun verticalGradientPolicyUsesSolidBrushForHighContrastSingleton() {
        assertTrue(aeroVerticalGradientBrush(AeroGradientRole.Hero, highContrast = true) is SolidColor)
        assertFalse(aeroVerticalGradientBrush(AeroGradientRole.Hero, highContrast = false) is SolidColor)
    }
}
