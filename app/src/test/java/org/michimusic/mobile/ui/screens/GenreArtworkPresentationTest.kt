package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presentation contract for both UI7 views (doc §1.2, §2, §5).
 *
 * The approved WebP owns the visible title and border; Compose owns the real
 * genre name, the count and the single click target. Availability and artistic
 * quality are separate axes: an unverified mask, a residual band or a chip that
 * does not fit may never replace a decodable resource with a generic card.
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

    private val cellWidth = 170f
    private val cellHeight = cellWidth / 1.42f

    @Test fun anApprovedCropOwnsItsBakedFrameAndBottomEndChip() {
        val spec = mosaicVisualSpec(rockCrop)!!
        assertEquals(Ui7ArtworkKind.LEGACY_COMPLETE, spec.kind)
        assertTrue(spec.embeddedTitleVerified)
        assertTrue(spec.frameIncluded)
        assertTrue(spec.hasVerifiedContour)
        assertNotNull("the chip uses a documented bottom-end region", spec.counterSafeRect)
        val safe = spec.counterSafeRect!!
        assertTrue("region starts on the right half", safe.left >= 0.5f)
        assertTrue("region ends bottom-right", safe.right >= 0.95f && safe.bottom >= 0.95f)
        assertEquals("the only protected region is the baked bottom-start title",
            1, spec.protectedRects.size)
        assertTrue("title stays bottom-start", spec.protectedRects.single().right <= 0.6f)
    }

    @Test fun anUnverifiedOpticalRowStillPaintsWithAProvisionalContour() {
        val unverified = rockCrop.copy(opticalMask = mask.copy(verified = false))
        val spec = mosaicVisualSpec(unverified)!!
        assertFalse(spec.hasVerifiedContour)
        // The provisional contour is a clip, not a second visible border.
        assertFalse(spec.clipMask.verified)
        assertEquals(2f, spec.clipMask.leftInsetPx)
        val decision = ui7ArtworkDecision(spec, cellWidth, cellHeight, 26f, 26f)
        assertTrue("an unmeasured contour must never hide the WebP", decision.paintsArtwork)
        assertEquals(Ui7AssetAvailability.ASSET_PACKAGED, decision.availability)
        assertTrue(decision.quality.opticalMaskUnverified)
        assertTrue(decision.quality.hasDebt)
    }

    @Test fun aRatioMismatchRecordsDebtInsteadOfFallingBack() {
        val pop = rockCrop.copy(widthPx = 506, heightPx = 342)
        val spec = mosaicVisualSpec(pop)!!
        val decision = ui7ArtworkDecision(spec, cellWidth, cellHeight, 26f, 26f)
        assertTrue("a 1.4795 crop still paints in the 1.42 cell", decision.paintsArtwork)
        assertTrue("the residual band is measured", decision.quality.aspectDebt)
        assertTrue(decision.quality.aspectDebtPerSideDp >= 2f)
    }

    @Test fun aMissingCatalogRowKeepsTheGenreOnItsHonestFallback() {
        assertNull(mosaicVisualSpec(null))
        assertNull(editorialVisualSpec(null))
        val decision = ui7ArtworkDecision(null, cellWidth, cellHeight, 26f, 26f)
        assertEquals(Ui7AssetAvailability.NO_ASSET_FOR_GENRE, decision.availability)
        assertFalse(decision.paintsArtwork)
    }

    @Test fun aHorizontalPosterIsAPosterNotAMissingPortrait() {
        // Rock is 3.67:1. Editorial derives a banner row from it; there is no
        // portrait treatment to fail and no vertical variant to wait for.
        val spec = editorialVisualSpec(rockPoster)!!
        assertFalse(rockPoster.isVertical)
        assertEquals(EditorialPosterClass.BANNER_PANORAMICO, editorialPosterClass(spec.ratio))
        val bannerHeight = editorialUsableWidthDp(390f) / spec.ratio
        val decision = ui7ArtworkDecision(spec, 350f, bannerHeight, 26f, 26f)
        assertTrue(decision.paintsArtwork)
        assertEquals(0f, decision.quality.aspectDebtPerSideDp, 0.5f)
    }

    @Test fun editorialCompactIsClassifiedByItsOwnRatio() {
        val pop = EditorialPosterAsset("pop", "genre_pop_editorial.webp", 514, 268)
        val softRock = EditorialPosterAsset("soft_rock", "genre_soft_rock_editorial.webp", 518, 305)
        val synth = EditorialPosterAsset("synth_pop", "genre_synth_pop_editorial.webp", 513, 327)
        assertEquals(EditorialPosterClass.COMPACTO_ANCHO, editorialPosterClass(pop.ratio))
        assertEquals(EditorialPosterClass.COMPACTO_MEDIO, editorialPosterClass(softRock.ratio))
        assertEquals(EditorialPosterClass.COMPACTO_ALTO, editorialPosterClass(synth.ratio))
        assertEquals(EditorialPosterClass.BANNER_FINO, editorialPosterClass(1030f / 254f))
        assertEquals(EditorialPosterClass.COMPACTO_ALTO, editorialPosterClass(1.40f))
        assertEquals(EditorialPosterClass.INTERMEDIO_FUTURO, editorialPosterClass(2.5f))
        assertEquals(EditorialPosterClass.RETRATO_FUTURO, editorialPosterClass(0.9f))
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

    @Test fun counterTokensMatchTheMeasuredContract() {
        assertEquals(12, GENRE_COUNTER_INSET_DP)
        assertEquals("nominal disc inside the 24..28dp range", 26, GENRE_COUNTER_BASE_SIZE_DP)
        assertEquals(6, GENRE_COUNTER_HORIZONTAL_PADDING_DP)
        assertEquals(3, GENRE_COUNTER_VERTICAL_PADDING_DP)
        assertEquals(12f, GenreCounterTextStyle.fontSize.value, 0.01f)
        assertTrue("translucent fill inside 0.52..0.72",
            GenreCounterFill.alpha in 0.52f..0.72f)
        // The visible text is the digit only; the Spanish phrase lives in semantics.
        assertEquals("1 pista", genreCounterLabel(1))
        assertEquals("5 pistas", genreCounterLabel(5))
        assertEquals("1234 pistas", genreCounterLabel(1234))
        assertEquals("Abrir género New Wave, 14 pistas",
            genreCardLabel(
                LibraryGenre(
                    id = "new wave",
                    title = "New Wave",
                    tracks = (1..14).map { index ->
                        org.michimusic.core.models.Track(id = "t$index", title = "T$index")
                    },
                    albumCount = 1,
                ),
            ))
    }

    @Test fun aMalformedOpticalRowIsIsolatedInsteadOfKillingTheTable() {
        val header = ui7OpticalManifestHeader
        fun row(filename: String, verified: String = "1") = listOf(
            filename, "506", "352", "2", "1", "2", "2", "28", "27", "26", "30", verified,
        ).joinToString("\t")
        val problems = mutableListOf<String>()
        val masks = parseUi7OpticalMasks(
            buildString {
                appendLine(header)
                appendLine(row("UI7_MOSAICO_001_Rock.webp"))
                appendLine("broken\trow")
                appendLine(row("UI7_MOSAICO_002_Jazz.webp", verified = "x"))
                appendLine(row("UI7_MOSAICO_003_SynthPop.webp", verified = "0"))
            },
            onInvalidRow = { line, reason -> problems += "$line:$reason" },
        )
        assertEquals("the two good rows survive", 2, masks.size)
        assertTrue(masks.getValue("UI7_MOSAICO_001_Rock.webp").verified)
        assertFalse(masks.getValue("UI7_MOSAICO_003_SynthPop.webp").verified)
        assertEquals(listOf("3:columns", "4:verified_flag"), problems)

        // Only a broken table header yields an empty table (every asset then uses
        // the provisional contour), never a missing asset.
        assertTrue(parseUi7OpticalMasks("# WRONG\n${row("UI7_MOSAICO_001_Rock.webp")}\n")
            .isEmpty())
    }

    @Test fun duplicateOpticalRowsKeepTheFirstMeasurement() {
        val header = ui7OpticalManifestHeader
        val row = "UI7_MOSAICO_001_Rock.webp\t506\t352\t2\t1\t2\t2\t28\t27\t26\t30\t1"
        val masks = parseUi7OpticalMasks("$header\n$row\n$row\n")
        assertEquals(1, masks.size)
        assertEquals(28f, masks.getValue("UI7_MOSAICO_001_Rock.webp").topLeftRadiusPx)
    }

    @Test fun aMaskWithMismatchedSourceSizeIsIgnoredByItsManifestRow() {
        val files = setOf("UI7_MOSAICO_001_Rock.webp")
        val manifest = mosaicManifestHeader + "\n" +
            "rock\tUI7_MOSAICO_001_Rock.webp\t${"a".repeat(64)}\t506\t352\tUSER_CONFIRMED_COMPLETE\n"
        val mismatched = mapOf("UI7_MOSAICO_001_Rock.webp" to mask.copy(sourceWidthPx = 999))
        val artwork = mosaicApprovedArtwork(manifest, files, opticalMasks = mismatched)
        assertEquals(1, artwork.size)
        assertNull("a mask for another size must not be attached", artwork["rock"]?.opticalMask)
        val decision = ui7ArtworkDecision(
            mosaicVisualSpec(artwork["rock"]), cellWidth, cellHeight, 26f, 26f,
        )
        assertTrue("the resource still paints with a provisional contour",
            decision.paintsArtwork)
        assertTrue(decision.quality.opticalMaskUnverified)
    }
}
