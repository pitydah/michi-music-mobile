package org.michimusic.mobile.ui.theme

import org.junit.Assert.*
import org.junit.Test

class AeroOpticalEdgePolicyTest {
    @Test fun onlyFiniteAdequateSurfacesPaintRims() {
        val spec = AeroFinishSpec(.04f, .01f, .10f, 0f)
        assertTrue(aeroOpticalEdgeLight(AeroFinish.Anodized, spec, 120f, 50f).enabled)
        assertFalse(aeroOpticalEdgeLight(AeroFinish.Anodized, spec, Float.NaN, 50f).enabled)
        assertFalse(aeroOpticalEdgeLight(AeroFinish.Anodized, spec, 12f, 50f).enabled)
        assertFalse(aeroOpticalEdgeLight(AeroFinish.Plain, spec, 120f, 50f).enabled)
        assertFalse(aeroOpticalEdgeLight(AeroFinish.Inset, spec, 120f, 50f).enabled)
    }
    @Test fun edgeLightIsLimitedAndNeverCreatesExtraElevation() {
        val spec = AeroFinishSpec(0f, 0f, 9f, 0f)
        val anodized = aeroOpticalEdgeLight(AeroFinish.Anodized, spec, 200f, 80f)
        val satin = aeroOpticalEdgeLight(AeroFinish.Satin, spec, 200f, 80f)
        assertEquals(.12f, anodized.alpha, 0f)
        assertTrue(anodized.bottomAlpha in 0f..0.04f)
        assertTrue(anodized.leftAlpha in 0f..0.03f)
        assertEquals(0f, satin.bottomAlpha, 0f)
    }
}
