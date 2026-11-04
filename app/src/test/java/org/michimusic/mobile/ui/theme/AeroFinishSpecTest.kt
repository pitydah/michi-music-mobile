package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Material recipe gates: functional UI roles remain unchanged. */
class AeroFinishSpecTest {
    @Test fun highContrastHasNoOpticalDecoration() {
        AeroFinish.entries.forEach { finish ->
            assertEquals(AeroFinishSpec(0f, 0f, 0f, 0f), aeroFinishSpec(finish, true))
        }
        AeroSurfaceRole.entries.forEach { role ->
            AeroFinish.entries.forEach { finish ->
                assertEquals(AeroFinish.Plain, aeroResolvedFinish(role, finish, true))
            }
        }
    }

    @Test fun finishesAreBoundedAndSilentOnPlain() {
        assertEquals(AeroFinishSpec(0f, 0f, 0f, 0f), aeroFinishSpec(AeroFinish.Plain, false))
        AeroFinish.entries.forEach {
            val v = aeroFinishSpec(it, false)
            listOf(v.topLightAlpha, v.grainAlpha, v.edgeAlpha, v.insetShadeAlpha).forEach { alpha ->
                assertTrue("$it: alpha $alpha", alpha >= 0f && alpha <= 0.12f)
            }
        }
    }

    @Test fun onlySemanticallyAllowedCombinationsArePainted() {
        val allowed = mapOf(
            AeroSurfaceRole.Canvas to setOf(AeroFinish.Plain),
            AeroSurfaceRole.Content to setOf(AeroFinish.Plain, AeroFinish.Satin, AeroFinish.Inset),
            AeroSurfaceRole.Raised to setOf(AeroFinish.Plain, AeroFinish.Satin, AeroFinish.Anodized),
            AeroSurfaceRole.Floating to setOf(AeroFinish.Plain, AeroFinish.Smoked),
        )
        AeroSurfaceRole.entries.forEach { role ->
            AeroFinish.entries.forEach { finish ->
                assertEquals(
                    if (finish in allowed.getValue(role)) finish else AeroFinish.Plain,
                    aeroResolvedFinish(role, finish, false),
                )
            }
        }
    }
}
