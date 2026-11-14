package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
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

    @Test fun compactCropKeepsItsWholeFrameWithoutReservingABottomBand() {
        // A 1.4375 crop in the compact card's square-ish inner box is
        // width-limited; the matte is the card's own surface, never a reserved
        // counter band or a cropped bitmap.
        val ratio = 506f / 352f
        val rect = genreArtworkFitRect(164.5f, 114.4f, ratio)!!
        assertNear(164.5f, rect.width)
        assertNear(164.5f / ratio, rect.height)
        assertNear(0f, rect.top)
    }

    @Test fun artworkRectCanBeTranslatedIntoTheCardSpaceAfterTheMount() {
        val rect = genreArtworkFitRect(164.5f, 100f, 1.645f)!!
        val mounted = rect.offsetBy(8f, 8f)
        assertNear(rect.left + 8f, mounted.left)
        assertNear(rect.top + 8f, mounted.top)
        assertNear(rect.right + 8f, mounted.right)
        assertNear(rect.bottom + 8f, mounted.bottom)
    }

    @Test fun invalidGeometryNeverProducesAStretchedRect() {
        assertNull(genreArtworkFitRect(0f, 218f, 1.4f))
        assertNull(genreArtworkFitRect(371f, 0f, 1.4f))
        assertNull(genreArtworkFitRect(371f, 218f, null))
        assertNull(genreArtworkFitRect(371f, 218f, 0f))
        assertNull(genreArtworkFitRect(371f, 218f, Float.NaN))
        assertNull(genreArtworkFitRect(Float.POSITIVE_INFINITY, 218f, 1.4f))
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

    @Test fun compactPosterCardHeightNeverForcesATallEmptyBand() {
        // Pop 514x268 in a 154dp inner half cell: the poster is 80.3dp tall and
        // the card is that band + mounts + gap + one 26dp count row.
        val popBand = editorialPosterBandHeightDp(154f, 10_000f, 514f / 268f)
        assertNear(154f * 268f / 514f, popBand)
        assertNear(16f + popBand + 4f + 26f, editorialPosterCardHeightDp(popBand, 26f))
        // Rock 1027x280 stays even more compact; it is not stretched to 246dp.
        val rockBand = editorialPosterBandHeightDp(154f, 10_000f, 1027f / 280f)
        assertNear(154f * 280f / 1027f, rockBand)
        assertTrue(editorialPosterCardHeightDp(rockBand, 26f) < 246f)
    }

    @Test fun verticalPosterMayUseTheTreatmentSlotButHistoricalSheetsDoNot() {
        // A real vertical asset is capped by the portrait treatment: the band is
        // treatment - mounts - gap - count row, never a forced panorama band.
        val verticalBandMax = editorialPosterTreatmentBandMaxDp(246f, 26f)
        assertNear(200f, verticalBandMax)
        assertNear(200f, editorialPosterBandHeightDp(154f, verticalBandMax, 0.75f))
        // A 2000:1 malformed declaration is not used to invent a band.
        assertTrue(editorialPosterBandHeightDp(154f, verticalBandMax, 0f) == verticalBandMax)
    }
}
