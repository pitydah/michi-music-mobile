package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroAppearanceProfilePolicyTest {
    @Test fun accessibleHighContrastNeverPaintsDecorativeLayers() {
        for (preset in AeroAppearanceProfile.values()) {
            for (finish in AeroFinish.values()) {
                assertEquals(
                    AeroFinishSpec(0f, 0f, 0f, 0f),
                    aeroAppearanceFinishSpec(finish, preset, true, true),
                )
            }
        }
    }

    @Test fun balancedKeepsCanonicalRecipesAndMicrotextureToggleOnlyChangesGrain() {
        for (finish in AeroFinish.values()) {
            assertEquals(
                aeroFinishSpec(finish, false),
                aeroAppearanceFinishSpec(finish, AeroAppearanceProfile.EQUILIBRADO, false, true),
            )
            val withoutGrain = aeroAppearanceFinishSpec(finish, AeroAppearanceProfile.EQUILIBRADO, false, false)
            assertEquals(0f, withoutGrain.grainAlpha)
            assertEquals(aeroFinishSpec(finish, false).edgeAlpha, withoutGrain.edgeAlpha)
        }
    }

    @Test fun discreetIntensityIsBoundedAndPreservesInsetReadability() {
        for (finish in AeroFinish.values()) {
            val spec = aeroAppearanceFinishSpec(finish, AeroAppearanceProfile.DISCRETO, false, true)
            assertTrue(listOf(spec.topLightAlpha, spec.grainAlpha, spec.edgeAlpha, spec.insetShadeAlpha)
                .all { it in 0f..0.12f })
            assertTrue(spec.edgeAlpha <= aeroFinishSpec(finish, false).edgeAlpha)
        }
        assertEquals(
            aeroFinishSpec(AeroFinish.Inset, false).insetShadeAlpha,
            aeroAppearanceFinishSpec(AeroFinish.Inset, AeroAppearanceProfile.DISCRETO, false, true).insetShadeAlpha,
        )
    }

    @Test fun bannerBlurRequiresLoadedImageAndExplicitAuthorization() {
        assertEquals(
            AeroGlassDecision.BANNER_IMAGE_BLUR,
            aeroGlassDecision(AeroAppearanceProfile.CRISTALINO, AeroOpticalGlassSurface.PLAYLIST_BANNER,
                bannerImageAvailable = true, performanceAllowed = true, highContrast = false),
        )
        for (profile in AeroAppearanceProfile.values()) {
            for (surface in AeroOpticalGlassSurface.values()) {
                val actual = aeroGlassDecision(profile, surface, true, true, false)
                if (profile != AeroAppearanceProfile.CRISTALINO || surface == AeroOpticalGlassSurface.OTHER) {
                    assertEquals(AeroGlassDecision.SMOKED_FALLBACK, actual)
                }
            }
        }
        assertEquals(
            AeroGlassDecision.SMOKED_FALLBACK,
            aeroGlassDecision(AeroAppearanceProfile.CRISTALINO, AeroOpticalGlassSurface.AUDIO_ROUTE_SELECTOR,
                bannerImageAvailable = false, performanceAllowed = true, highContrast = false),
        )
        assertEquals(
            AeroGlassDecision.SMOKED_FALLBACK,
            aeroGlassDecision(AeroAppearanceProfile.CRISTALINO, AeroOpticalGlassSurface.DOCK,
                bannerImageAvailable = true, performanceAllowed = false, highContrast = false),
        )
        assertEquals(
            AeroGlassDecision.SMOKED_FALLBACK,
            aeroGlassDecision(AeroAppearanceProfile.CRISTALINO, AeroOpticalGlassSurface.MINIPLAYER,
                bannerImageAvailable = true, performanceAllowed = true, highContrast = true),
        )
    }
    @Test fun unimplementedSurfacesCannotClaimBannerBlur() {
        for (surface in listOf(AeroOpticalGlassSurface.DOCK,
            AeroOpticalGlassSurface.MINIPLAYER, AeroOpticalGlassSurface.AUDIO_ROUTE_SELECTOR)) {
            assertEquals(AeroGlassDecision.SMOKED_FALLBACK,
                aeroGlassDecision(AeroAppearanceProfile.CRISTALINO, surface, true, true, false))
        }
        assertEquals(AeroGlassDecision.BANNER_IMAGE_BLUR,
            aeroGlassDecision(AeroAppearanceProfile.CRISTALINO,
                AeroOpticalGlassSurface.PLAYLIST_BANNER, true, true, false))
    }

}
