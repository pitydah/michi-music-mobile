package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroMaterialPolicyTest {
    @Test fun rolesHavePredictableElevation() {
        assertEquals(0, aeroMaterialRule(AeroSurfaceRole.Canvas, false).elevationDp)
        assertEquals(0, aeroMaterialRule(AeroSurfaceRole.Content, false).elevationDp)
        assertEquals(1, aeroMaterialRule(AeroSurfaceRole.Raised, false).elevationDp)
        assertEquals(5, aeroMaterialRule(AeroSurfaceRole.Floating, false).elevationDp)
    }
    @Test fun highContrastEliminatesShadowsAndStrengthensEdges() {
        AeroSurfaceRole.entries.filter { it != AeroSurfaceRole.Canvas }.forEach {
            val normal = aeroMaterialRule(it, false)
            val contrast = aeroMaterialRule(it, true)
            assertEquals(0, contrast.elevationDp)
            assertTrue(contrast.borderOpacity >= normal.borderOpacity)
            assertTrue(contrast.borderWidthDp >= normal.borderWidthDp)
        }
    }
    @Test fun canvasNeverCreatesDecorativeBorder() {
        assertEquals(0f, aeroMaterialRule(AeroSurfaceRole.Canvas, false).borderWidthDp)
        assertEquals(0f, aeroMaterialRule(AeroSurfaceRole.Canvas, true).borderWidthDp)
    }
}
