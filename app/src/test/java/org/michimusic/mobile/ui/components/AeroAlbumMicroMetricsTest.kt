package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroAlbumMicroMetricsTest {
    @Test fun layoutIsExactlyOneCompactAccessibleRow() {
        assertEquals(48, AeroAlbumMicroMetrics.TOUCH_TARGET_DP)
        assertEquals(44, AeroAlbumMicroMetrics.PLAY_SURFACE_DP)
        assertEquals(40, AeroAlbumMicroMetrics.SECONDARY_SURFACE_DP)
        assertEquals(22, AeroAlbumMicroMetrics.PLAY_GLYPH_DP)
        assertEquals(20, AeroAlbumMicroMetrics.SECONDARY_GLYPH_DP)
        assertEquals(6, AeroAlbumMicroMetrics.GAP_DP)
        assertEquals(156, AeroAlbumMicroMetrics.GROUP_WIDTH_DP)
        assertTrue(AeroAlbumMicroMetrics.hasValidGeometry())
    }

    @Test fun sizesFitCommonPhoneContentWidthsWithoutShrinkingTouchAreas() {
        for (screenWidth in listOf(320, 340, 360, 390, 411)) {
            assertTrue(AeroAlbumMicroMetrics.fits((screenWidth - 40).toFloat()))
        }
        assertTrue(AeroAlbumMicroMetrics.fits(156f))
        assertFalse(AeroAlbumMicroMetrics.fits(155.9f))
        assertFalse(AeroAlbumMicroMetrics.fits(Float.NaN))
        assertFalse(AeroAlbumMicroMetrics.fits(Float.POSITIVE_INFINITY))
    }
    @Test fun threeTapAreasNeverOverlapOrShrink() {
        val b = (0..2).mapNotNull(::aeroAlbumActionTargetBounds)
        assertEquals(3, b.size)
        assertEquals(48, b[0].count())
        assertEquals(0, b.first().first)
        assertEquals(155, b.last().last) // last pixel of 156 dp strip
        assertTrue(b.zipWithNext().all { (a, next) -> a.last < next.first })
        assertEquals(null, aeroAlbumActionTargetBounds(3))
    }

}
