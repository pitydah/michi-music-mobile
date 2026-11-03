package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Aero keeps a single canonical theme with two accents. The canonization pass pushed
 * every eyebrow to cyan, so the home screen read like telemetry. The owner's decision
 * restored pink for content and kept cyan for technical evidence; this suite pins that
 * contract so a future theme pass cannot silently flip it again.
 */
class AeroAccentIdentityTest {

    @Test
    fun contentEyebrowsSpeakPink() {
        assertEquals(PrimaryPinkContainer, MichiTypography.screenEyebrow.color)
    }

    @Test
    fun technicalEyebrowsSpeakCyan() {
        assertEquals(TertiaryCyan, MichiTypography.screenEyebrowTechnical.color)
    }

    @Test
    fun bothEyebrowsShareOneTypography() {
        assertEquals(MichiTypography.screenEyebrow.fontSize, MichiTypography.screenEyebrowTechnical.fontSize)
        assertEquals(MichiTypography.screenEyebrow.fontWeight, MichiTypography.screenEyebrowTechnical.fontWeight)
        assertEquals(MichiTypography.screenEyebrow.letterSpacing, MichiTypography.screenEyebrowTechnical.letterSpacing)
        assertEquals(MichiTypography.screenEyebrow.lineHeight, MichiTypography.screenEyebrowTechnical.lineHeight)
    }

    @Test
    fun theTwoAccentsAreNeverTheSameColour() {
        assertNotEquals(MichiTypography.screenEyebrow.color, MichiTypography.screenEyebrowTechnical.color)
    }
}
