package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Fit geometry and count-chip placement. These are the numbers the layout
 * and the pixel tests rely on, so they are asserted without Compose.
 */
class GenreArtworkGeometryTest {
    private fun assertNear(expected: Float, actual: Float) {
        assertTrue("expected=$expected actual=$actual", kotlin.math.abs(expected - actual) < 0.05f)
    }

    @Test fun heroCropKeepsItsWholeFrameBetweenTwoMatteBands() {
        // 411dp panel: 371dp card, 218dp hero, 506x352 approved crop (1.4375).
        val ratio = 506f / 352f
        val rect = genreArtworkFitRect(371f, 218f, ratio)!!
        assertNear(218f * ratio, rect.width)
        assertNear(218f, rect.height)
        assertNear((371f - 218f * ratio) / 2f, rect.left)
        assertNear(0f, rect.top)
        assertNear(371f - (371f - 218f * ratio) / 2f, rect.right)
    }

    @Test fun compactCropKeepsItsWholeFrameAboveTheCountSurface() {
        // 411dp panel: 180.5dp compact card, 166dp tall: the crop is
        // height-limited, so the matte is above and below, never a crop.
        val ratio = 506f / 352f
        val rect = genreArtworkFitRect(180.5f, 166f, ratio)!!
        val height = 180.5f / ratio
        assertNear(180.5f, rect.width)
        assertNear(height, rect.height)
        assertNear((166f - height) / 2f, rect.top)
    }

    @Test fun invalidGeometryNeverProducesAStretchedRect() {
        assertNull(genreArtworkFitRect(0f, 218f, 1.4f))
        assertNull(genreArtworkFitRect(371f, 0f, 1.4f))
        assertNull(genreArtworkFitRect(371f, 218f, null))
        assertNull(genreArtworkFitRect(371f, 218f, 0f))
        assertNull(genreArtworkFitRect(371f, 218f, Float.NaN))
        assertNull(genreArtworkFitRect(Float.POSITIVE_INFINITY, 218f, 1.4f))
    }

    @Test fun artworkBandKeepsTheWholeFrameAndReservesTheCountSurface() {
        // Compact card 180.5dp wide, 122dp kept above the chip reserve: the
        // approved 1.4375 crop would need 125.6dp, so it is clamped (scaled down
        // inside the band, never cropped).
        assertNear(122f, genreArtworkBandHeightDp(180.5f, 506f / 352f, 122f))
        assertNear(180.5f / (506f / 352f),
            genreArtworkBandHeightDp(180.5f, 506f / 352f, 200f))
        // A 1.918:1 poster in a 355dp band needs 185.1dp and must not be clamped.
        assertNear(355f / (514f / 268f), genreArtworkBandHeightDp(355f, 514f / 268f, 138f * 2f))
        // Invalid declaration keeps the full reserved band instead of a broken card.
        assertNear(138f, genreArtworkBandHeightDp(355f, null, 138f))
        assertNear(138f, genreArtworkBandHeightDp(355f, Float.NaN, 138f))
        assertNear(0f, genreArtworkBandHeightDp(355f, 1.9f, 0f))
    }

    @Test fun focalCountChipSitsTwelveDpInsideTheArtworkBorder() {
        val rect = genreArtworkFitRect(371f, 218f, 506f / 352f)!!
        val chip = GenreCounterSize(widthDp = 90f, heightDp = 26f)
        val placement = genreCounterPlacement(
            anchor = GenreOverlayAnchor.TOP_END,
            containerWidthDp = 371f,
            containerHeightDp = 218f,
            artworkRect = rect,
            chip = chip,
        )
        assertNear(GENRE_COUNTER_INSET_DP.toFloat(), placement.topDp)
        assertNear(371f - rect.right + GENRE_COUNTER_INSET_DP, placement.endDp)
        assertNear(0f, placement.startDp)
        assertNear(0f, placement.bottomDp)
    }

    @Test fun counterPlacementClampsInsideTheCard() {
        val oversized = GenreCounterSize(widthDp = 400f, heightDp = 300f)
        val placement = genreCounterPlacement(
            anchor = GenreOverlayAnchor.BOTTOM_END,
            containerWidthDp = 180f,
            containerHeightDp = 166f,
            artworkRect = null,
            chip = oversized,
        )
        assertEquals(0f, placement.startDp)
        assertEquals(0f, placement.topDp)
        assertTrue(placement.endDp >= 0f)
        assertTrue(placement.bottomDp >= 0f)
        // Degenerate containers never throw.
        val degenerate = genreCounterPlacement(
            anchor = GenreOverlayAnchor.TOP_START,
            containerWidthDp = 0f,
            containerHeightDp = 0f,
            artworkRect = null,
            chip = GenreCounterSize(10f, 10f),
        )
        assertEquals(0f, degenerate.startDp)
        assertEquals(0f, degenerate.topDp)
    }

    @Test fun editorialPosterBandNeverExceedsTheTreatmentSurface() {
        // Rock 1027x280 in a 180.5dp half cell: the band is width-limited.
        val rock = genreArtworkBandHeightDp(164f, 1027f / 280f, 192f)
        assertNear(164f * 280f / 1027f, rock)
        // Pop 514x268 would be 164/1.918 = 85.5dp, not the old 88.6dp exterior cell.
        assertNear(164f * 268f / 514f, genreArtworkBandHeightDp(164f, 514f / 268f, 192f))
        assertNotNull(rock)
    }
}
