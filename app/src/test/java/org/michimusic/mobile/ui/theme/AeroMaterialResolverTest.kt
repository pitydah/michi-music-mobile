package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroMaterialResolverTest {
    @Test fun resolvedFinishRejectsIncompatibleRoleAndFinishPairs() {
        val resolved = aeroResolveMaterial(
            role = AeroSurfaceRole.Canvas,
            requestedFinish = AeroFinish.Anodized,
            profile = AeroAppearanceProfile.EQUILIBRADO,
            highContrast = false,
            microtexturesEnabled = true,
        )
        assertEquals(AeroFinish.Plain, resolved.finish)
    }

    @Test fun highContrastWinsOverProfileAndMicrotextures() {
        for (profile in AeroAppearanceProfile.entries) {
            val resolved = aeroResolveMaterial(
                role = AeroSurfaceRole.Raised,
                requestedFinish = AeroFinish.Anodized,
                profile = profile,
                highContrast = true,
                microtexturesEnabled = true,
            )
            assertEquals(AeroFinish.Plain, resolved.finish)
            assertEquals(AeroFinishSpec(0f, 0f, 0f, 0f), resolved.spec)
        }
    }

    @Test fun microtexturesOffRemovesGrainButKeepsEdgeLight() {
        val on = aeroResolveMaterial(
            AeroSurfaceRole.Raised, AeroFinish.Anodized,
            AeroAppearanceProfile.EQUILIBRADO, false, microtexturesEnabled = true,
        )
        val off = aeroResolveMaterial(
            AeroSurfaceRole.Raised, AeroFinish.Anodized,
            AeroAppearanceProfile.EQUILIBRADO, false, microtexturesEnabled = false,
        )
        assertTrue(on.spec.grainAlpha > 0f)
        assertEquals(0f, off.spec.grainAlpha, 0f)
        assertEquals(on.spec.edgeAlpha, off.spec.edgeAlpha, 0f)
        assertEquals(on.spec.topLightAlpha, off.spec.topLightAlpha, 0f)
    }

    @Test fun interactionOnlyRaisesTheFinitePressedFeedback() {
        val idle = aeroResolveMaterial(
            AeroSurfaceRole.Raised, AeroFinish.Satin,
            AeroAppearanceProfile.EQUILIBRADO, false, microtexturesEnabled = true,
        )
        val pressed = aeroResolveMaterial(
            AeroSurfaceRole.Raised, AeroFinish.Satin,
            AeroAppearanceProfile.EQUILIBRADO, false, microtexturesEnabled = true,
            interactionActive = true,
        )
        assertTrue(pressed.spec.topLightAlpha > idle.spec.topLightAlpha)
        assertTrue(pressed.spec.edgeAlpha > idle.spec.edgeAlpha)
        assertTrue(pressed.spec.topLightAlpha <= 0.12f)
        assertFalse(pressed.spec == idle.spec)
    }

    @Test fun profilesNeverExceedTheCanonicalAlphaCeiling() {
        for (profile in AeroAppearanceProfile.entries) {
            for (finish in AeroFinish.entries) {
                val resolved = aeroResolveMaterial(
                    AeroSurfaceRole.Floating, finish, profile,
                    highContrast = false, microtexturesEnabled = true,
                )
                assertTrue(resolved.spec.topLightAlpha in 0f..0.12f)
                assertTrue(resolved.spec.grainAlpha in 0f..0.12f)
                assertTrue(resolved.spec.edgeAlpha in 0f..0.12f)
            }
        }
    }
}
