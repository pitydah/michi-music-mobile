package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroBackdropGlassPolicyTest {
    @Test fun onlyOptedInReadyBannerWithSupportedRendererUsesBlur() {
        assertTrue(aeroPlaylistBannerBlurEnabled(AeroAppearanceProfile.CRISTALINO, 35, true, false, false))
        assertFalse(aeroPlaylistBannerBlurEnabled(AeroAppearanceProfile.EQUILIBRADO, 35, true, false, false))
        assertFalse(aeroPlaylistBannerBlurEnabled(AeroAppearanceProfile.CRISTALINO, 30, true, false, false))
        assertFalse(aeroPlaylistBannerBlurEnabled(AeroAppearanceProfile.CRISTALINO, 35, false, false, false))
        assertFalse(aeroPlaylistBannerBlurEnabled(AeroAppearanceProfile.CRISTALINO, 35, true, true, false))
        assertFalse(aeroPlaylistBannerBlurEnabled(AeroAppearanceProfile.CRISTALINO, 35, true, false, true))
    }
    @Test fun textPanelIsOpaqueUnderHighContrast() {
        assertEquals(1f, aeroPlaylistPanelOpacity(true, true))
        assertEquals(.84f, aeroPlaylistPanelOpacity(true, false))
        assertEquals(.92f, aeroPlaylistPanelOpacity(false, false))
    }
    @Test fun glassRegionIsExactlyTheInformationTrayAndFailsClosed() {
        assertEquals(300, aeroPlaylistGlassRegionTopPx(500, 200, glassEnabled = true))
        assertNull(aeroPlaylistGlassRegionTopPx(500, 200, glassEnabled = false))
        assertNull(aeroPlaylistGlassRegionTopPx(0, 200, glassEnabled = true))
        assertNull(aeroPlaylistGlassRegionTopPx(500, 0, glassEnabled = true))
        assertNull(aeroPlaylistGlassRegionTopPx(500, 500, glassEnabled = true))
        assertNull(aeroPlaylistGlassRegionTopPx(500, 600, glassEnabled = true))
    }

    @Test fun systemResourceConstraintsDisableRealBlur() {
        assertFalse(aeroPlaylistBannerBlurEnabled(AeroAppearanceProfile.CRISTALINO,
            35, true, false, false, powerSaveMode = true))
        assertFalse(aeroPlaylistBannerBlurEnabled(AeroAppearanceProfile.CRISTALINO,
            35, true, false, false, lowRamDevice = true))
        assertTrue(aeroPlaylistBannerBlurEnabled(AeroAppearanceProfile.CRISTALINO,
            35, true, false, false, powerSaveMode = false, lowRamDevice = false))
    }

    @Test fun appearanceReasonNeverClaimsGlassWithoutImage() {
        assertEquals(null, glassSuspensionReason(AeroAppearanceProfile.EQUILIBRADO, 34, false, false, false, false))
        assertEquals("Android anterior a 12: acabado Aero compatible",
            glassSuspensionReason(AeroAppearanceProfile.CRISTALINO, 30, false, false, false, false))
        assertEquals("Ahorro de batería: desenfoque suspendido",
            glassSuspensionReason(AeroAppearanceProfile.CRISTALINO, 34, false, false, true, false))
        assertEquals("Contraste alto: desenfoque suspendido",
            glassSuspensionReason(AeroAppearanceProfile.CRISTALINO, 34, true, false, true, true))
    }

}
