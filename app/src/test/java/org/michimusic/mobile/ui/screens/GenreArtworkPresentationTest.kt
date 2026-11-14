package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

/**
 * Presentation contract for both UI7 views: which artwork is complete, who owns
 * the title, where the count may sit and when the portrait slot is still waiting
 * for an approved vertical asset. No OCR, no runtime pixel analysis: every
 * decision comes from the approved catalog metadata.
 */
class GenreArtworkPresentationTest {
    private val rockCrop = MosaicArtworkAsset("rock", "UI7_MOSAICO_001_Rock.webp", 506, 352)
    private val rockPoster = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280)
    private val popPoster = EditorialPosterAsset("pop", "genre_pop_editorial.webp", 514, 268)

    @Test fun approvedMosaicoCropOwnsItsBakedTitleAndCountPlacement() {
        val spec = mosaicPresentationSpec(rockCrop)
        assertEquals(GenreArtworkPresentation.LEGACY_COMPLETE, spec.presentation)
        assertEquals(GenreTitlePresentation.EMBEDDED_TITLE, spec.title)
        assertEquals(GenreOverlayAnchor.TOP_END, spec.counterAnchor)
        assertFalse("a landscape crop is not a pending vertical slot", spec.pendingVerticalAsset)
    }

    @Test fun missingMosaicoCropFallsBackToALiveComposeTitle() {
        val spec = mosaicPresentationSpec(null)
        assertEquals(GenreArtworkPresentation.FALLBACK, spec.presentation)
        assertEquals(GenreTitlePresentation.COMPOSE_TITLE, spec.title)
        // Nothing may be overlaid on unseen artwork: the conservative placement
        // stays outside the artwork area.
        assertEquals(GenreOverlayAnchor.BOTTOM_START, spec.counterAnchor)
    }

    @Test fun approvedPosterNeverGetsASecondTitleOrBorder() {
        val spec = editorialPresentationSpec(rockPoster, GenreEditorialTreatment.COVER)
        assertEquals(GenreArtworkPresentation.LEGACY_COMPLETE, spec.presentation)
        assertEquals(GenreTitlePresentation.EMBEDDED_TITLE, spec.title)
        assertEquals(GenreOverlayAnchor.BOTTOM_START, spec.counterAnchor)
        assertFalse(spec.pendingVerticalAsset)
        assertTrue(spec.opticalFrame != NormalizedRect.FULL)
    }

    @Test fun portraitSlotsAreMarkedPendingWithoutAnApprovedVerticalAsset() {
        val left = editorialPresentationSpec(popPoster, GenreEditorialTreatment.PORTRAIT_LEFT)
        val right = editorialPresentationSpec(rockPoster, GenreEditorialTreatment.PORTRAIT_RIGHT)
        assertTrue(left.pendingVerticalAsset)
        assertTrue(right.pendingVerticalAsset)
        // The pair mirrors its count chip, never the artwork itself.
        assertEquals(GenreOverlayAnchor.BOTTOM_START, left.counterAnchor)
        assertEquals(GenreOverlayAnchor.BOTTOM_END, right.counterAnchor)
    }

    @Test fun aRealVerticalPosterClearsThePendingState() {
        val vertical = EditorialPosterAsset("jazz", "genre_jazz_editorial.webp", 600, 800)
        val spec = editorialPresentationSpec(vertical, GenreEditorialTreatment.PORTRAIT_LEFT)
        assertTrue(vertical.isVertical)
        assertFalse(spec.pendingVerticalAsset)
        assertFalse(rockPoster.isVertical)
    }

    @Test fun noPosterFallsBackInsteadOfInventingMetadata() {
        val spec = editorialPresentationSpec(null, GenreEditorialTreatment.COVER)
        assertEquals(GenreArtworkPresentation.FALLBACK, spec.presentation)
        assertEquals(GenreTitlePresentation.COMPOSE_TITLE, spec.title)
    }

    @Test fun countChipMustFitTheVerifiedSafeRegion() {
        val spec = mosaicPresentationSpec(rockCrop)
        // 316x218 artwork, 90x26 chip: 12dp inside the artwork border, top-right.
        assertTrue(counterFitsSafeRegion(spec, 316f, 218f, 90f, 26f))
        // The compact Mosaico card (180x119 artwork) with the measured 60x22.4
        // chip must also fit. The probe may not add a margin of its own, or the
        // same chip lands 12dp from the card at some widths and 20dp at others.
        assertTrue(counterFitsSafeRegion(spec, 180.38f, 119.1f, 60f, 22.4f))
        // A chip that would reach past the artwork edge, the title band or the
        // safe band height is rejected so the caller uses the card placement.
        assertFalse(counterFitsSafeRegion(spec, 316f, 218f, 320f, 26f))
        assertFalse(counterFitsSafeRegion(spec, 316f, 218f, 90f, 218f))
        assertFalse(counterFitsSafeRegion(spec, 180.38f, 119.1f, 60f, 40f))
        assertFalse(counterFitsSafeRegion(spec, 0f, 218f, 90f, 26f))
        assertFalse(counterFitsSafeRegion(spec, Float.NaN, 218f, 90f, 26f))
    }

    @Test fun normalizedRectRejectsOutOfRangeAndInvertedBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedRect(-0.1f, 0f, 1f, 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedRect(0.9f, 0f, 0.1f, 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedRect(0f, 0f, Float.NaN, 1f)
        }
        val region = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)
        assertTrue(region.contains(NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)))
        assertFalse(region.contains(NormalizedRect(0.05f, 0.2f, 0.8f, 0.8f)))
        assertTrue(region.intersects(NormalizedRect(0.8f, 0.8f, 0.95f, 0.95f)))
        assertFalse(region.intersects(NormalizedRect(0.91f, 0.1f, 0.99f, 0.9f)))
    }

    @Test fun counterTokensFollowTheContract() {
        assertEquals(12, GENRE_COUNTER_INSET_DP)
        assertEquals(7, GENRE_COUNTER_RADIUS_DP)
        assertEquals(8, GENRE_COUNTER_HORIZONTAL_PADDING_DP)
        assertEquals(4, GENRE_COUNTER_VERTICAL_PADDING_DP)
        assertEquals("1 pista", genreCounterLabel(1))
        assertEquals("5 pistas", genreCounterLabel(5))
    }
}
