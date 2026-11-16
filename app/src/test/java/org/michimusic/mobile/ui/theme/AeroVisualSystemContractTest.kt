package org.michimusic.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K48-R08 pins the visual system the enrichment work must reuse.
 *
 * The sprint explicitly forbids inventing a new palette, so the only honest way to
 * satisfy it is to prove the canonical tokens already carry the contract values and
 * that every text role speaks Inter. Contrast is measured on the tokens themselves,
 * which is a colour check and not a claim about rendered screens or TalkBack.
 */
class AeroVisualSystemContractTest {

    private fun assertToken(expected: Long, actual: Color, name: String) {
        assertEquals("$name must keep the contract value", Color(expected), actual)
    }

    @Test
    fun surfaceTokensMatchTheContractTable() {
        assertToken(0xFF090C12, SurfaceObsidian, "SurfaceObsidian")
        assertToken(0xFF0E1118, BackgroundDark, "BackgroundDark")
        assertToken(0xFF1B2029, SurfaceContainer, "SurfaceContainer")
        assertToken(0xFF262C35, SurfaceContainerHigh, "SurfaceContainerHigh")
    }

    @Test
    fun inkAndAccentTokensMatchTheContractTable() {
        assertToken(0xFFFFFFFF, PureWhite, "PureWhite")
        assertToken(0xFFBEC7D2, TextSecondary, "TextSecondary")
        assertToken(0xFFFF5167, PrimaryPinkContainer, "PrimaryPinkContainer")
        assertToken(0xFFDDB7FF, SecondaryPurple, "SecondaryPurple")
        assertToken(0xFF7DF4FF, TertiaryCyan, "TertiaryCyan")
    }

    @Test
    fun everyTextRoleSpeaksInter() {
        val inter = InterFontFamily
        assertSame(inter, MichiTypography.screenEyebrow.fontFamily)
        assertSame(inter, MichiTypography.screenTitle.fontFamily)
        assertSame(inter, MichiTypography.sectionTitle.fontFamily)
        assertSame(inter, MichiTypography.cardTitle.fontFamily)
        assertSame(inter, MichiTypography.trackTitle.fontFamily)
        assertSame(inter, MichiTypography.metadata.fontFamily)
        assertSame(inter, MichiTypography.microLabel.fontFamily)
        assertSame(inter, Typography.bodyMedium.fontFamily)
        assertSame(inter, Typography.labelLarge.fontFamily)
    }

    @Test
    fun primaryTextIsReadableOnEveryCanvasThePanelUses() {
        assertTrue(aeroContrastRatio(PureWhite, SurfaceObsidian) >= 7.0)
        assertTrue(aeroContrastRatio(PureWhite, SurfaceContainer) >= 7.0)
        assertTrue(aeroContrastRatio(TextSecondary, SurfaceContainer) >= 4.5)
    }

    @Test
    fun thePrimaryCallToActionCarriesDarkInkNotWhite() {
        // A white label on #FF5167 lands under 3:1; the obsidian ink clears AA for body text.
        assertTrue(aeroContrastRatio(SurfaceObsidian, PrimaryPinkContainer) >= 4.5)
        assertTrue(aeroContrastRatio(PureWhite, PrimaryPinkContainer) < 4.5)
    }

    @Test
    fun verifiedDataCyanIsReadableOnObsidian() {
        assertTrue(aeroContrastRatio(TertiaryCyan, SurfaceObsidian) >= 4.5)
    }
}
