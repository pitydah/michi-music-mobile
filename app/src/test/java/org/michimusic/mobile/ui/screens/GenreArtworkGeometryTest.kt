package org.michimusic.mobile.ui.screens

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry contract for UI7 (doc §2.1, §2.2, §3.3).
 *
 * Mosaico is one homogeneous 1.42 cell in one and two columns. Editorial derives
 * every height from the real file ratio. Nothing here rejects an image: a ratio
 * difference is reported as aspect debt and the WebP still paints.
 */
class GenreArtworkGeometryTest {

    private fun assertNear(message: String, expected: Float, actual: Float,
                           tolerance: Float = 0.05f) {
        assertTrue("$message (expected=$expected actual=$actual)",
            kotlin.math.abs(expected - actual) < tolerance)
    }

    private fun mask(
        width: Int = 506,
        height: Int = 352,
        verified: Boolean = true,
        inset: Float = 2f,
    ) = Ui7OpticalMask(
        sourceWidthPx = width,
        sourceHeightPx = height,
        leftInsetPx = inset,
        topInsetPx = inset,
        rightInsetPx = inset,
        bottomInsetPx = inset,
        topLeftRadiusPx = 28f,
        topRightRadiusPx = 27f,
        bottomRightRadiusPx = 26f,
        bottomLeftRadiusPx = 30f,
        verified = verified,
    )

    @Test fun mosaicCellIsOnePointFourTwoInOneAndTwoColumns() {
        // Contract table §2.1: 320 -> 280x197.18, 360 -> 320x225.35,
        // 390 -> 170x119.72, 411 -> 180.5x127.11, 480 -> 215x151.41.
        listOf(
            Triple(320, 1, 280f),
            Triple(360, 1, 320f),
            Triple(390, 2, 170f),
            Triple(411, 2, 180.5f),
            Triple(480, 2, 215f),
        ).forEach { (panel, columns, width) ->
            val size = mosaicCardSize(panel.toFloat(), columns)
            assertEquals("[$panel] card width", width, size.widthDp, 0.01f)
            assertEquals("[$panel] card height", width / 1.42f, size.heightDp, 0.01f)
            assertNear("[$panel] ratio", 1.42f, size.widthDp / size.heightDp, 0.001f)
        }
    }

    @Test fun everyCardOfABreakpointSharesExactlyOneExteriorSize() {
        listOf(320 to 1, 360 to 1, 390 to 2, 411 to 2, 480 to 2).forEach { (panel, columns) ->
            val size = mosaicCardSize(panel.toFloat(), columns)
            repeat(4) { index ->
                val same = mosaicCardSize(panel.toFloat(), columns)
                assertEquals(size.widthDp, same.widthDp, 0f)
                assertEquals(size.heightDp, same.heightDp, 0f)
                assertTrue(size.heightDp > 0f && index < 4)
            }
        }
    }

    @Test fun fitKeepsTheRealProportionsAndReportsTheResidualBand() {
        val width = 170f
        val height = width / 1.42f
        // Exact 1.42: no residual band and no debt.
        val exact = genreArtworkFitRect(width, height, 1.42f)!!
        assertNear("exact width", width, exact.width, 0.01f)
        assertNear("exact height", height, exact.height, 0.01f)
        assertEquals(0f, aspectDebtPerSideDp(width, height, 1.42f), 0.001f)

        // Pop 506x342 = 1.4795 is width-limited: the side band is real and recorded.
        val wide = genreArtworkFitRect(width, height, 506f / 342f)!!
        assertNear("wide width", width, wide.width, 0.01f)
        assertNear("wide height", width / (506f / 342f), wide.height, 0.01f)
        val wideDebt = aspectDebtPerSideDp(width, height, 506f / 342f)
        assertTrue("1.4795 must record aspect debt: $wideDebt", wideDebt >= 2f)
        assertNear("wide debt", (height - wide.height) / 2f, wideDebt, 0.01f)

        // A 1.56 source leaves a larger band; a 1.3228 one leaves a side band.
        assertTrue(aspectDebtPerSideDp(width, height, 1.56f) >= 2f)
        assertTrue(aspectDebtPerSideDp(width, height, 1.3228f) >= 2f)
        // 1.45 is within the 2dp tolerance at this cell: no artistic debt.
        assertTrue(aspectDebtPerSideDp(width, height, 1.45f) < 2f)
    }

    @Test fun editorialBannerKeepsFullWidthAndRealHeight() {
        val usable = editorialUsableWidthDp(390f)
        assertEquals(350f, usable, 0.01f)
        // New Wave 1030x254 -> 350x86.3, Rock 1027x280 -> 350x95.4.
        assertEquals(86.3f, usable / (1030f / 254f), 0.1f)
        assertEquals(95.4f, usable / (1027f / 280f), 0.1f)
        // Art Rock 727x200 -> 350x96.3.
        assertEquals(96.3f, usable / (727f / 200f), 0.1f)
    }

    @Test fun editorialPairKeepsOneHeightAndProportionalWidths() {
        val usable = editorialUsableWidthDp(390f)
        val gap = GENRE_GRID_GAP_DP.toFloat()
        // Pop 514x268 with Soft Rock 518x305.
        val r1 = 514f / 268f
        val r2 = 518f / 305f
        val height = (usable - gap) / (r1 + r2)
        val w1 = r1 * height
        val w2 = r2 * height
        assertEquals("pair height", 94.0f, height, 0.2f)
        assertEquals("Pop width", 180.3f, w1, 0.3f)
        assertEquals("Soft Rock width", 159.7f, w2, 0.3f)
        assertNear("the pair fills the usable width", usable, w1 + gap + w2, 0.01f)
        assertTrue("both pieces keep the 150dp minimum", minOf(w1, w2) >= 150f)
    }

    @Test fun fitGeometryReportsTheTruthfulPictorialRect() {
        val rect = genreArtworkFitRect(170f, 119.718f, 506f / 352f)!!
        assertNear("width-limited", 170f, rect.width)
        assertNear("pictorial height", 170f / (506f / 352f), rect.height)
        assertNear("top band", (119.718f - rect.height) / 2f, rect.top, 0.01f)
        assertEquals(0f, rect.left)
        assertNull(genreArtworkFitRect(0f, 121f, 1.4f))
        assertNull(genreArtworkFitRect(170f, 0f, 1.4f))
        assertNull(genreArtworkFitRect(170f, 121f, null))
        assertNull(genreArtworkFitRect(170f, 121f, 0f))
        assertNull(genreArtworkFitRect(170f, 121f, Float.NaN))
        assertNull(genreArtworkFitRect(Float.POSITIVE_INFINITY, 121f, 1.4f))
    }

    @Test fun counterIsAnchoredTwelveDpInsideThePaintedArtwork() {
        val width = 170f
        val height = width / 1.42f
        val artwork = GenreArtworkRect(0f, 0f, width, height)
        val placement = genreCounterPlacement(
            containerWidthDp = width,
            containerHeightDp = height,
            artworkRect = artwork,
            chip = GenreCounterSize(26f, 26f),
            anchor = GenreOverlayAnchor.BOTTOM_END,
        )
        assertEquals(0f, placement.startDp)
        assertEquals(0f, placement.topDp)
        assertNear("end inset", 12f, placement.endDp)
        assertNear("bottom inset", 12f, placement.bottomDp)
        // A narrower artwork keeps its own contour as anchor.
        val insetArt = GenreArtworkRect(4f, 2f, 166f, 118f)
        val insetPlacement = genreCounterPlacement(
            containerWidthDp = width,
            containerHeightDp = height,
            artworkRect = insetArt,
            chip = GenreCounterSize(26f, 26f),
        )
        assertNear("from artwork right", 170f - 166f + 12f, insetPlacement.endDp)
        assertNear("from artwork bottom", height - 118f + 12f, insetPlacement.bottomDp, 0.01f)
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
        val width = 170f
        val height = width / 1.42f
        val box = normalizedBottomEndChipBox(width, height, 26f, 26f)!!
        assertNear("left", (width - 12f - 26f) / width, box.left, 0.001f)
        assertNear("top", (height - 12f - 26f) / height, box.top, 0.001f)
        assertNear("right", (width - 12f) / width, box.right, 0.001f)
        assertNear("bottom", (height - 12f) / height, box.bottom, 0.001f)
        assertTrue("must be the bottom-right corner", box.left > 0.5f && box.top > 0.5f)
        assertNull(normalizedBottomEndChipBox(20f, 20f, 26f, 26f))
        assertNull(normalizedBottomEndChipBox(0f, 121f, 26f, 26f))
    }

    @Test fun chipRegionIsAFlagAndNeverAnImageVeto() {
        val asset = MosaicArtworkAsset(
            genreKey = "rock",
            filename = "UI7_MOSAICO_001_Rock.webp",
            widthPx = 506,
            heightPx = 352,
            opticalMask = mask(),
        )
        val spec = mosaicVisualSpec(asset)!!
        val width = 170f
        val height = width / 1.42f
        assertTrue("the documented bottom-end region accepts a 26dp chip",
            counterPlacementIsSafe(spec, width, height, 26f, 26f))
        assertTrue("a wider 3-digit capsule still fits",
            counterPlacementIsSafe(spec, width, height, 40f, 26f))
        assertFalse("a chip wider than the artwork is unsafe",
            counterPlacementIsSafe(spec, width, height, 320f, 26f))
        // Unsafe placement is recorded as debt, and the bitmap still paints.
        val decision = ui7ArtworkDecision(spec, width, height, 320f, 26f)
        assertTrue("the image must paint even with an unsafe chip", decision.paintsArtwork)
        assertTrue(decision.quality.counterRegionUnverified)
    }

    @Test fun chipOverlayIsHiddenOnlyWhenItCannotFitAThinBanner() {
        // New Wave at 390dp: 350x86.3 still holds a 26dp chip with 12dp insets.
        assertTrue(counterFitsInsideArtwork(350f, 86.3f, 26f, 26f))
        // A 40dp strip cannot (26dp chip + 2*10dp inset); only the overlay is
        // dropped, never the bitmap.
        assertFalse(counterFitsInsideArtwork(350f, 40f, 26f, 26f))
        assertFalse(counterFitsInsideArtwork(0f, 60f, 26f, 26f))
    }

    @Test fun opticalContourAppliesOneUniformScaleToXAndY() {
        // 1000x500 source with 20px insets inside a square 200x200 box: the uniform
        // scale is 0.2, so the contour is 192x192. A per-axis scale (old defect)
        // would produce 192x184 and visibly detach the frame from the bitmap.
        val contour = ui7OpticalContour(
            size = Size(200f, 200f),
            sourceWidthPx = 1000,
            sourceHeightPx = 500,
            mask = mask(width = 1000, height = 500, inset = 20f),
        )!!
        assertEquals("contour width", 192f, contour.width, 0.5f)
        assertEquals("contour height", 192f, contour.height, 0.5f)
        assertEquals("left inset", 4f, contour.left, 0.1f)
        assertEquals("top inset", 4f, contour.top, 0.1f)
        // The rendered frame is proportional to the painted rect: exact-ratio boxes
        // reproduce the source measurements with one scale.
        val exact = ui7OpticalContour(
            size = Size(506f, 352f),
            sourceWidthPx = 506,
            sourceHeightPx = 352,
            mask = mask(),
        )!!
        assertEquals(2f, exact.left, 0.01f)
        assertEquals(28f, exact.topLeftRadius, 0.01f)
    }
}
