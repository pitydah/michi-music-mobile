package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry and counter-placement contract for UI7.
 *
 * The Mosaico cell is exactly 7:5 (two columns) or 8:5 (one column); the
 * Editorial cell is 192dp (cover) / 246dp (portrait). No test derives a card
 * height from an asset's native ratio: a source that cannot fill its treatment
 * intact is rejected by [ui7RenderOutcome], never cropped or letterboxed.
 */
class GenreArtworkGeometryTest {

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float = 0.05f) {
        assertTrue("$message (expected=$expected actual=$actual)", kotlin.math.abs(expected - actual) < tolerance)
    }

    private fun mask(
        width: Int = 506,
        height: Int = 352,
        verified: Boolean = true,
    ) = Ui7OpticalMask(
        sourceWidthPx = width,
        sourceHeightPx = height,
        leftInsetPx = 2f,
        topInsetPx = 1f,
        rightInsetPx = 2f,
        bottomInsetPx = 2f,
        topLeftRadiusPx = 28f,
        topRightRadiusPx = 27f,
        bottomRightRadiusPx = 26f,
        bottomLeftRadiusPx = 30f,
        verified = verified,
    )

    private fun mosaicAsset(
        width: Int = 506,
        height: Int = 352,
        verified: Boolean = true,
    ) = MosaicArtworkAsset(
        genreKey = "rock",
        filename = "UI7_MOSAICO_001_Rock.webp",
        widthPx = width,
        heightPx = height,
        opticalMask = mask(width, height, verified),
    )

    private fun posterAsset(
        width: Int = 1027,
        height: Int = 280,
        verified: Boolean = true,
    ) = EditorialPosterAsset(
        slug = "rock",
        filename = "genre_rock_editorial.webp",
        widthPx = width,
        heightPx = height,
        opticalMask = mask(width, height, verified),
    )

    private val twoColumnWidth = 170f
    private val twoColumnHeight = twoColumnWidth * 5f / 7f

    @Test fun twoColumnCellKeepsTheSevenToFiveContract() {
        val size = mosaicCardSize(390f, columns = 2)
        assertNear("card width", 170f, size.widthDp)
        assertNear("card height", 121.428f, size.heightDp, 0.01f)
        // Same arithmetic for the full contract matrix.
        assertEquals(180.5f, mosaicCardSize(411f, 2).widthDp, 0.01f)
        assertEquals(129f, mosaicCardSize(411f, 2).heightDp, 0.6f)
        assertEquals(215f, mosaicCardSize(480f, 2).widthDp, 0.01f)
        assertEquals(154f, mosaicCardSize(480f, 2).heightDp, 0.6f)
    }

    @Test fun singleColumnCellKeepsTheEightToFiveContract() {
        assertEquals(280f, mosaicCardSize(320f, 1).widthDp, 0.01f)
        assertEquals(175f, mosaicCardSize(320f, 1).heightDp, 0.01f)
        assertEquals(320f, mosaicCardSize(360f, 1).widthDp, 0.01f)
        assertEquals(200f, mosaicCardSize(360f, 1).heightDp, 0.01f)
    }

    @Test fun editorialCellsKeep192And246DpAtScaleOne() {
        val size = editorialCardSize(390f, columns = 2, fontScale = 1f)
        assertEquals(350f, size.coverWidthDp, 0.01f)
        assertEquals(170f, size.portraitWidthDp, 0.01f)
        assertEquals(192f, size.coverHeightDp, 0.01f)
        assertEquals(246f, size.portraitHeightDp, 0.01f)
        val single = editorialCardSize(320f, columns = 1, fontScale = 1f)
        assertEquals(280f, single.coverWidthDp, 0.01f)
        assertEquals(200f, single.singleHeightDp, 0.01f)
        // Both halves of a pair always share one height.
        assertEquals(size.portraitHeightDp, editorialCardSize(480f, 2, 1f).portraitHeightDp, 0.01f)
    }

    @Test fun anExactRatioFillsItsTreatmentWithNoBand() {
        // Timba 504x359 = 1.4039: height-limited by the 7:5 cell.
        val ratio = 504f / 359f
        assertTrue(genreArtworkFitsWithoutDamage(twoColumnWidth, twoColumnHeight, ratio))
        assertTrue(genreArtworkFitsWithoutDamage(280f, 175f, 1.6f))
    }

    @Test fun aDifferentSourceRatioIsRejectedInsteadOfLettered() {
        // Rock 506x352 = 1.4375 in a 7:5 cell leaves a ~3.2dp band.
        assertFalse(genreArtworkFitsWithoutDamage(twoColumnWidth, twoColumnHeight, 506f / 352f))
        // Pop 506x342 = 1.4795 leaves a ~6.5dp band.
        assertFalse(genreArtworkFitsWithoutDamage(twoColumnWidth, twoColumnHeight, 506f / 342f))
        // No Mosaico crop is 8:5, so a one-column cell never shows a legacy crop.
        assertFalse(genreArtworkFitsWithoutDamage(280f, 175f, 504f / 359f))
        // A 3.67:1 Editorial sheet cannot fill the 350x192 cover.
        assertFalse(genreArtworkFitsWithoutDamage(350f, 192f, 1027f / 280f))
    }

    @Test fun fitGeometryStillReportsTheTruthfulPictorialRect() {
        val rect = genreArtworkFitRect(twoColumnWidth, twoColumnHeight, 506f / 352f)!!
        assertNear("width-limited", twoColumnWidth, rect.width)
        assertNear("pictorial height", twoColumnWidth / (506f / 352f), rect.height)
        assertNear("top band", (twoColumnHeight - rect.height) / 2f, rect.top)
        assertEquals(0f, rect.left)
        assertNull(genreArtworkFitRect(0f, 121f, 1.4f))
        assertNull(genreArtworkFitRect(170f, 0f, 1.4f))
        assertNull(genreArtworkFitRect(170f, 121f, null))
        assertNull(genreArtworkFitRect(170f, 121f, 0f))
        assertNull(genreArtworkFitRect(170f, 121f, Float.NaN))
        assertNull(genreArtworkFitRect(Float.POSITIVE_INFINITY, 121f, 1.4f))
    }

    @Test fun counterPlacementIsTwelveDpInsideTheArtworkBottomEnd() {
        val artwork = GenreArtworkRect(0f, 0f, twoColumnWidth, twoColumnHeight)
        val placement = genreCounterPlacement(
            containerWidthDp = twoColumnWidth,
            containerHeightDp = twoColumnHeight,
            artworkRect = artwork,
            chip = GenreCounterSize(28f, 28f),
            anchor = GenreOverlayAnchor.BOTTOM_END,
        )
        assertEquals(0f, placement.startDp)
        assertEquals(0f, placement.topDp)
        assertNear("end inset", 12f, placement.endDp)
        assertNear("bottom inset", 12f, placement.bottomDp)
        // An artwork narrower than the container keeps its own contour as anchor.
        val insetArt = GenreArtworkRect(4f, 2f, 166f, 118f)
        val insetPlacement = genreCounterPlacement(
            containerWidthDp = twoColumnWidth,
            containerHeightDp = twoColumnHeight,
            artworkRect = insetArt,
            chip = GenreCounterSize(28f, 28f),
        )
        assertNear("from artwork right", 170f - 166f + 12f, insetPlacement.endDp)
        assertNear("from artwork bottom", 121.43f - 118f + 12f, insetPlacement.bottomDp, 0.01f)
    }

    @Test fun counterPlacementClampsInsideTheCard() {
        val oversized = GenreCounterSize(widthDp = 400f, heightDp = 300f)
        val placement = genreCounterPlacement(
            containerWidthDp = 180f,
            containerHeightDp = 121f,
            artworkRect = null,
            chip = oversized,
        )
        assertEquals(0f, placement.endDp)
        assertEquals(0f, placement.bottomDp)
        val degenerate = genreCounterPlacement(
            containerWidthDp = 0f,
            containerHeightDp = 0f,
            artworkRect = null,
            chip = GenreCounterSize(10f, 10f),
        )
        assertEquals(GenreCounterPlacement(), degenerate)
    }

    @Test fun normalizedChipBoxStaysInTheBottomEndCorner() {
        val box = normalizedBottomEndChipBox(
            artworkWidthDp = twoColumnWidth,
            artworkHeightDp = twoColumnHeight,
            chipWidthDp = 28f,
            chipHeightDp = 28f,
        )!!
        assertNear("left", (170f - 12f - 28f) / 170f, box.left, 0.001f)
        assertNear("top", (121.428f - 12f - 28f) / 121.428f, box.top, 0.001f)
        assertNear("right", (170f - 12f) / 170f, box.right, 0.001f)
        assertNear("bottom", (121.428f - 12f) / 121.428f, box.bottom, 0.001f)
        assertTrue("must be the bottom-right corner", box.left > 0.5f && box.top > 0.5f)
        assertNull(normalizedBottomEndChipBox(20f, 20f, 28f, 28f))
        assertNull(normalizedBottomEndChipBox(0f, 121f, 28f, 28f))
    }

    @Test fun counterSafetyRequiresVerifiedRegionAndNoProtectedOverlap() {
        val verified = mosaicVisualSpec(mosaicAsset(), Ui7Treatment.MOSAIC_GRID)!!
        assertTrue(
            counterPlacementIsSafe(verified, twoColumnWidth, twoColumnHeight, 28f, 28f),
        )
        // A wider 46dp chip still fits the 12dp inset.
        assertTrue(
            counterPlacementIsSafe(verified, twoColumnWidth, twoColumnHeight, 46f, 28f),
        )
        assertFalse(
            "a chip larger than the artwork must fail",
            counterPlacementIsSafe(verified, twoColumnWidth, twoColumnHeight, 320f, 28f),
        )
        assertFalse(
            "an unverified row has no safe region",
            counterPlacementIsSafe(
                mosaicVisualSpec(mosaicAsset(verified = false), Ui7Treatment.MOSAIC_GRID)!!,
                twoColumnWidth,
                twoColumnHeight,
                28f,
                28f,
            ),
        )
        val protected = verified.copy(
            protectedRects = listOf(NormalizedRect(0.6f, 0.6f, 1f, 1f)),
        )
        assertFalse(
            "protected bottom-right art must reject the chip",
            counterPlacementIsSafe(protected, twoColumnWidth, twoColumnHeight, 28f, 28f),
        )
    }

    @Test fun renderOutcomeMatrixFollowsTheContractDecisionTree() {
        val timba = mosaicVisualSpec(mosaicAsset(504, 359), Ui7Treatment.MOSAIC_GRID)
        assertEquals(
            Ui7RenderOutcome.ASSET,
            ui7RenderOutcome(timba, Ui7Treatment.MOSAIC_GRID, twoColumnWidth, twoColumnHeight,
                504f / 359f, 28f, 28f),
        )
        assertEquals(
            "no catalog row keeps the genre visible",
            Ui7RenderOutcome.NO_ASSET,
            ui7RenderOutcome(null, Ui7Treatment.MOSAIC_GRID, 170f, 121f, 1f, 28f, 28f),
        )
        assertEquals(
            "unmeasured frame must never be guessed",
            Ui7RenderOutcome.UNVERIFIED_CONTOUR,
            ui7RenderOutcome(
                mosaicVisualSpec(mosaicAsset(verified = false), Ui7Treatment.MOSAIC_GRID),
                Ui7Treatment.MOSAIC_GRID, twoColumnWidth, twoColumnHeight, 1.4f, 28f, 28f,
            ),
        )
        assertEquals(
            "Rock is not a 7:5 crop",
            Ui7RenderOutcome.UNSUPPORTED_ASPECT,
            ui7RenderOutcome(
                mosaicVisualSpec(mosaicAsset(506, 352), Ui7Treatment.MOSAIC_GRID),
                Ui7Treatment.MOSAIC_GRID, twoColumnWidth, twoColumnHeight, 506f / 352f, 28f, 28f,
            ),
        )
        assertEquals(
            "a legacy crop cannot fill an 8:5 cell",
            Ui7RenderOutcome.UNSUPPORTED_ASPECT,
            ui7RenderOutcome(
                mosaicVisualSpec(mosaicAsset(504, 359), Ui7Treatment.SINGLE_COLUMN),
                Ui7Treatment.SINGLE_COLUMN, 280f, 175f, 504f / 359f, 28f, 28f,
            ),
        )
        val cover = editorialVisualSpec(posterAsset(), GenreEditorialTreatment.COVER)
        assertEquals(
            "editorial cover needs a prepared horizontal variant",
            Ui7RenderOutcome.NEEDS_APPROVED_COVER,
            ui7RenderOutcome(
                cover, Ui7Treatment.EDITORIAL_COVER, 350f, 192f, 1027f / 280f, 28f, 28f,
            ),
        )
        val portrait = editorialVisualSpec(posterAsset(), GenreEditorialTreatment.PORTRAIT_LEFT)
        assertEquals(
            "horizontal sheet is never a portrait",
            Ui7RenderOutcome.NEEDS_APPROVED_PORTRAIT,
            ui7RenderOutcome(
                portrait, Ui7Treatment.EDITORIAL_PORTRAIT_LEFT, 170f, 246f,
                1027f / 280f, 28f, 28f,
            ),
        )
        assertEquals(
            "an oversized chip is a collision",
            Ui7RenderOutcome.COUNTER_COLLISION,
            ui7RenderOutcome(timba, Ui7Treatment.MOSAIC_GRID, twoColumnWidth, twoColumnHeight,
                504f / 359f, 400f, 28f),
        )
    }
}
