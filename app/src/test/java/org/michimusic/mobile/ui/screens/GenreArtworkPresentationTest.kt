package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presentation contract for both UI7 views: one artwork, one silhouette, one
 * frame. The approved WebP owns the visible title and border; Compose owns the
 * real genre name, the count and the single click target. No OCR and no runtime
 * pixel analysis: every decision comes from the static optical metadata.
 */
class GenreArtworkPresentationTest {
    private val mask = Ui7OpticalMask(
        sourceWidthPx = 506,
        sourceHeightPx = 352,
        leftInsetPx = 2f,
        topInsetPx = 1f,
        rightInsetPx = 2f,
        bottomInsetPx = 2f,
        topLeftRadiusPx = 28f,
        topRightRadiusPx = 27f,
        bottomRightRadiusPx = 26f,
        bottomLeftRadiusPx = 30f,
        verified = true,
    )
    private val rockCrop = MosaicArtworkAsset(
        genreKey = "rock", filename = "UI7_MOSAICO_001_Rock.webp",
        widthPx = 506, heightPx = 352, opticalMask = mask,
    )
    private val rockPoster = EditorialPosterAsset(
        slug = "rock", filename = "genre_rock_editorial.webp",
        widthPx = 1027, heightPx = 280, opticalMask = mask.copy(
            sourceWidthPx = 1027, sourceHeightPx = 280,
        ),
    )

    @Test fun approvedMosaicoCropOwnsItsBakedTitleFrameAndBottomEndChip() {
        val spec = mosaicVisualSpec(rockCrop, Ui7Treatment.MOSAIC_GRID)!!
        assertEquals(Ui7ArtworkKind.LEGACY_COMPLETE, spec.kind)
        assertTrue(spec.embeddedTitleVerified)
        assertTrue(spec.frameIncluded)
        assertTrue(spec.hasVerifiedContour)
        assertEquals(Ui7AssetApproval.APPROVED, spec.approval)
        assertEquals(
            setOf(Ui7Treatment.MOSAIC_GRID, Ui7Treatment.SINGLE_COLUMN),
            spec.approvedTreatments,
        )
        assertNotNull("the chip uses a verified bottom-end region", spec.counterSafeRect)
        val safe = spec.counterSafeRect!!
        assertTrue("region starts on the right half", safe.left >= 0.5f)
        assertTrue("region ends at the bottom-right corner", safe.right >= 0.95f && safe.bottom >= 0.95f)
        // The only protected region is the baked bottom-start title.
        assertEquals(1, spec.protectedRects.size)
        assertTrue("title stays bottom-start", spec.protectedRects.single().right <= 0.6f)
    }

    @Test fun anUnverifiedOpticalRowNeverGetsAnApprovedTreatment() {
        val unverified = rockCrop.copy(opticalMask = mask.copy(verified = false))
        val spec = mosaicVisualSpec(unverified, Ui7Treatment.MOSAIC_GRID)!!
        assertEquals(Ui7AssetApproval.PROVISIONAL, spec.approval)
        assertFalse(spec.hasVerifiedContour)
        assertTrue("no treatment can be approved without a contour", spec.approvedTreatments.isEmpty())
        assertNull("no safe region without optical calibration", spec.counterSafeRect)
        assertEquals(
            Ui7RenderOutcome.UNVERIFIED_CONTOUR,
            ui7RenderOutcome(spec, Ui7Treatment.MOSAIC_GRID, 170f, 121f, 506f / 352f, 28f, 28f),
        )
    }

    @Test fun aMissingMosaicoRowFallsBackInsteadOfInventingMetadata() {
        assertNull(mosaicVisualSpec(null, Ui7Treatment.MOSAIC_GRID))
        assertEquals(
            Ui7RenderOutcome.NO_ASSET,
            ui7RenderOutcome(null, Ui7Treatment.MOSAIC_GRID, 170f, 121f, 1f, 28f, 28f),
        )
    }

    @Test fun editorialCoverIsApprovedButAPortraitNeedsAnAuthenticVariant() {
        val cover = editorialVisualSpec(rockPoster, GenreEditorialTreatment.COVER)!!
        assertEquals(setOf(Ui7Treatment.EDITORIAL_COVER), cover.approvedTreatments)
        val left = editorialVisualSpec(rockPoster, GenreEditorialTreatment.PORTRAIT_LEFT)!!
        assertTrue("a 3.67:1 sheet is not a portrait", left.approvedTreatments.isEmpty())
        assertFalse(rockPoster.isVertical)
        // A real vertical variant (0.691 ratio) is approved for both sides.
        val vertical = EditorialPosterAsset(
            slug = "jazz", filename = "genre_jazz_editorial.webp", widthPx = 691, heightPx = 1000,
            opticalMask = mask.copy(sourceWidthPx = 691, sourceHeightPx = 1000),
        )
        assertTrue(vertical.isVertical)
        assertEquals(
            setOf(Ui7Treatment.EDITORIAL_PORTRAIT_LEFT),
            editorialVisualSpec(vertical, GenreEditorialTreatment.PORTRAIT_LEFT)!!.approvedTreatments,
        )
        assertEquals(
            setOf(Ui7Treatment.EDITORIAL_PORTRAIT_RIGHT),
            editorialVisualSpec(vertical, GenreEditorialTreatment.PORTRAIT_RIGHT)!!.approvedTreatments,
        )
        assertEquals(
            Ui7RenderOutcome.ASSET,
            ui7RenderOutcome(
                editorialVisualSpec(vertical, GenreEditorialTreatment.PORTRAIT_LEFT),
                Ui7Treatment.EDITORIAL_PORTRAIT_LEFT, 170f, 246f, 0.691f, 28f, 28f,
            ),
        )
    }

    @Test fun noPosterFallsBackToTheEditorialComposeTile() {
        assertNull(editorialVisualSpec(null, GenreEditorialTreatment.COVER))
        assertEquals(
            Ui7RenderOutcome.NO_ASSET,
            ui7RenderOutcome(null, Ui7Treatment.EDITORIAL_COVER, 350f, 192f, 1f, 28f, 28f),
        )
    }

    @Test fun normalizedRectRejectsOutOfRangeAndInvertedBounds() {
        assertThrows(IllegalArgumentException::class.java) { NormalizedRect(-0.1f, 0f, 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedRect(0.9f, 0f, 0.1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedRect(0f, 0f, Float.NaN, 1f) }
        val region = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)
        assertTrue(region.contains(NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)))
        assertFalse(region.contains(NormalizedRect(0.05f, 0.2f, 0.8f, 0.8f)))
        assertTrue(region.intersects(NormalizedRect(0.8f, 0.8f, 0.95f, 0.95f)))
        assertFalse(region.intersects(NormalizedRect(0.91f, 0.1f, 0.99f, 0.9f)))
    }

    @Test fun counterTokensMatchTheThirtyTwoSpContract() {
        assertEquals(12, GENRE_COUNTER_INSET_DP)
        assertEquals(28, GENRE_COUNTER_BASE_SIZE_DP)
        assertEquals(6, GENRE_COUNTER_HORIZONTAL_PADDING_DP)
        assertEquals(4, GENRE_COUNTER_VERTICAL_PADDING_DP)
        assertEquals(14f, GenreCounterTextStyle.fontSize.value, 0.01f)
        assertEquals(0.45f, GenreCounterFill.alpha, 0.001f)
        // The visible text is the digit only; the Spanish phrase lives in semantics.
        assertEquals("1 pista", genreCounterLabel(1))
        assertEquals("5 pistas", genreCounterLabel(5))
        assertEquals("1234 pistas", genreCounterLabel(1234))
    }

    @Test fun opticalParserFailsClosedOnMalformedTables() {
        val header = ui7OpticalManifestHeader
        val row = listOf(
            "UI7_MOSAICO_001_Rock.webp", "506", "352", "2", "1", "2", "2",
            "28", "27", "26", "30", "1",
        ).joinToString("\t")
        val masks = parseUi7OpticalMasks("$header\n$row\n")
        assertEquals(1, masks.size)
        val parsed = masks.getValue("UI7_MOSAICO_001_Rock.webp")
        assertTrue(parsed.verified)
        assertEquals(506, parsed.sourceWidthPx)
        assertEquals(28f, parsed.topLeftRadiusPx)
        assertEquals(26f, parsed.bottomRightRadiusPx)

        assertTrue(parseUi7OpticalMasks("# WRONG\n$row\n").isEmpty())
        assertTrue(parseUi7OpticalMasks("$header\n${row.replace("\t30", "\tbad")}\n").isEmpty())
        assertTrue(parseUi7OpticalMasks("$header\n${row.replace("\t1", "\t2")}\n").isEmpty())
        assertTrue(parseUi7OpticalMasks("$header\n${row.replace("506", "10")}\n").isEmpty())
        assertTrue(parseUi7OpticalMasks("$header\n$row\n$row\n").isEmpty())
        assertTrue(parseUi7OpticalMasks("$header\nnot-a-webp.png\t506\t352\t2\t1\t2\t2\t28\t27\t26\t30\t1\n")
            .isEmpty())
        assertTrue(parseUi7OpticalMasks("$header\nUI7_MOSAICO_002_Jazz.webp\t506\t352\t2\t1\t2\t2\t28\t27\t26\t30\t1\n".let {
            it + "UI7_MOSAICO_002_Jazz.webp\t506\t352\t2\t1\t2\t2\t28\t27\t26\t30\t1\n"
        }).isEmpty())
    }

    @Test fun aMaskWithMismatchedSourceSizeIsIgnoredByItsManifestRow() {
        val files = setOf("UI7_MOSAICO_001_Rock.webp")
        val manifest = mosaicManifestHeader + "\n" +
            "rock\tUI7_MOSAICO_001_Rock.webp\t${"a".repeat(64)}\t506\t352\tUSER_CONFIRMED_COMPLETE\n"
        val mismatched = mapOf("UI7_MOSAICO_001_Rock.webp" to mask.copy(sourceWidthPx = 999))
        val artwork = mosaicApprovedArtwork(manifest, files, opticalMasks = mismatched)
        assertEquals(1, artwork.size)
        assertNull("a mask for another size must not be attached", artwork["rock"]?.opticalMask)
        assertEquals(
            Ui7RenderOutcome.UNVERIFIED_CONTOUR,
            ui7RenderOutcome(
                mosaicVisualSpec(artwork["rock"], Ui7Treatment.MOSAIC_GRID),
                Ui7Treatment.MOSAIC_GRID, 170f, 121f, 506f / 352f, 28f, 28f,
            ),
        )
    }
}
