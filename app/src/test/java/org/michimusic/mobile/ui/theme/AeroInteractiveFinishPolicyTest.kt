package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroInteractiveFinishPolicyTest {
    @Test fun idleAndHighContrastAreUnaffected() {
        val base = AeroFinishSpec(.02f, .01f, .06f, 0f)
        assertEquals(base, aeroInteractiveFinishSpec(AeroFinish.Satin, base, false, false))
        assertEquals(base, aeroInteractiveFinishSpec(AeroFinish.Satin, base, true, true))
    }
    @Test fun touchRespondsInPaintOnly() {
        val base = AeroFinishSpec(.025f, .016f, .055f, 0f)
        val active = aeroInteractiveFinishSpec(AeroFinish.Satin, base, true, false)
        assertTrue(active.topLightAlpha > base.topLightAlpha)
        assertTrue(active.edgeAlpha > base.edgeAlpha)
        assertEquals(base.grainAlpha, active.grainAlpha)
        assertEquals(base.insetShadeAlpha, active.insetShadeAlpha)
    }
    @Test fun keyboardFocusHasVisibleButBoundedStroke() {
        assertEquals(0f, aeroFocusStrokeWidthDp(false, true, false))
        assertEquals(0f, aeroFocusStrokeWidthDp(true, false, false))
        assertEquals(1.2f, aeroFocusStrokeWidthDp(true, true, false))
        assertEquals(2f, aeroFocusStrokeWidthDp(true, true, true))
    }
    @Test fun plainInsetAndMaximumOpacityAreSafe() {
        val base = AeroFinishSpec(.119f, .01f, .12f, .12f)
        assertEquals(base, aeroInteractiveFinishSpec(AeroFinish.Plain, base, true, false))
        assertEquals(base, aeroInteractiveFinishSpec(AeroFinish.Inset, base, true, false))
        val active = aeroInteractiveFinishSpec(AeroFinish.Smoked, base, true, false)
        assertEquals(.12f, active.topLightAlpha)
        assertEquals(.12f, active.edgeAlpha)
    }
}
