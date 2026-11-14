package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class GenreEditorialPosterPolicyTest {
    private fun record(slug: String = "new_wave", width: Int = 1030, height: Int = 254): String =
        listOf(slug, "genre_${slug}_editorial.webp", "a".repeat(64), width.toString(),
            height.toString(), "UI7_EDITORIAL_LAMINA_03_NewWave_ItaloDisco.png",
            "USER_CONFIRMED_COMPLETE", "POSTER_COMPLETE").joinToString("\t")

    @Test fun completedPostersMapByRealGenreNotCatalogOrder() {
        val manifest = editorialPosterManifestHeader + "\n" + record() + "\n"
        val posters = editorialApprovedPosters(manifest, setOf("genre_new_wave_editorial.webp"))
        assertEquals(1, posters.size)
        assertEquals("new_wave", editorialPosterForTitle("Newwave", posters)?.slug)
        assertNull(editorialPosterForTitle("No existe", posters))
        assertNull(editorialPosterForTitle("Sin clasificar", posters))
    }
    @Test fun originalRatioIsDeclaredButNeverWidensACell() {
        // The approved poster keeps its declared aspect; the layout never widens
        // a cell from that ratio, so Editorial stays a cover/pair rhythm instead
        // of becoming a wall of full-width panoramic banners.
        val panoramic = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280)
        val compact = EditorialPosterAsset("jazz", "genre_jazz_editorial.webp", 513, 327)
        assertTrue(panoramic.ratio > compact.ratio)
        assertEquals(192, editorialSlotFor(GenreEditorialTreatment.COVER, 2).heightDp)
        assertEquals(246, editorialSlotFor(GenreEditorialTreatment.PORTRAIT_LEFT, 2).heightDp)
        assertEquals(1, editorialSlotFor(GenreEditorialTreatment.PORTRAIT_RIGHT, 2).span)
        assertEquals(200, editorialSlotFor(GenreEditorialTreatment.COVER, 1).heightDp)
    }

    /**
     * Historical posters are horizontal, so they must keep their own compact
     * proportional height. A paired Pop (514x268) is ~132dp and Rock (1027x280)
     * ~91dp at a 180.5dp half cell, never a forced 246dp column with a large
     * empty band below the sheet. The 246dp treatment remains the *cap* for a
     * genuinely vertical asset, and still sizes Compose-drawn cells.
     */
    @Test fun horizontalPosterCardsTakeCompactProportionalHeightsNot246dp() {
        assertEquals(192, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 2, 1f))
        assertEquals(246, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 1f))
        assertEquals(246, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_RIGHT, 2, 1f))
        assertEquals(200, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 1, 1f))
        assertEquals(
            genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 1f),
            genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_RIGHT, 2, 1f),
        )
        // Text scale may grow a Compose-drawn card, never the poster ratio.
        assertEquals(216, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 2, 2f))
        assertEquals(270, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 2f))
        val inner = 180.5f - 2 * EDITORIAL_POSTER_MOUNT_INSET_DP
        val chip = 26f
        val popBand = editorialPosterBandHeightDp(inner, 10_000f, 514f / 268f)
        val rockBand = editorialPosterBandHeightDp(inner, 10_000f, 1027f / 280f)
        val popCard = editorialPosterCardHeightDp(popBand, chip)
        val rockCard = editorialPosterCardHeightDp(rockBand, chip)
        assertTrue("Pop must be a compact poster card, not 246dp: $popCard", popCard < 246f)
        assertTrue("Rock must be a compact poster card, not 246dp: $rockCard", rockCard < 246f)
        assertEquals(16f + popBand + 4f + chip, popCard, 0.05f)
        assertEquals(16f + rockBand + 4f + chip, rockCard, 0.05f)
        // The compact cards keep the approved poster ratio and their full band.
        assertTrue(popBand > rockBand + 30f)
    }

    @Test fun posterBandKeepsTheWholePosterAndOnlyAVerticalAssetUsesTheSlot() {
        fun assertNear(expected: Float, actual: Float) {
            assertTrue("expected=$expected actual=$actual",
                kotlin.math.abs(expected - actual) <= 0.05f)
        }
        // 180.5dp half cell: the inner mount leaves 164.5dp for the poster.
        val inner = 180.5f - 2 * EDITORIAL_POSTER_MOUNT_INSET_DP
        assertNear(inner * 268f / 514f, editorialPosterBandHeightDp(inner, 10_000f, 514f / 268f))
        assertNear(inner * 280f / 1027f, editorialPosterBandHeightDp(inner, 10_000f, 1027f / 280f))
        // Only a vertical asset is capped by the treatment slot (246 - 16 - 4 - 26).
        val verticalMax = editorialPosterTreatmentBandMaxDp(246f, 26f)
        assertNear(200f, verticalMax)
        assertNear(200f, editorialPosterBandHeightDp(154f, verticalMax, 0.75f))
        // Nothing is cropped: a 1.9:1 sheet in a 154dp inner width keeps 80.3dp.
        assertNear(154f / (514f / 268f), editorialPosterBandHeightDp(154f, verticalMax, 514f / 268f))
        // Invalid geometry falls back to the caller's cap, never a negative or
        // broken band; a zero cap keeps the cell at zero.
        assertNear(200f, editorialPosterBandHeightDp(0f, 200f, 2f))
        assertNear(200f, editorialPosterBandHeightDp(355f, 200f, Float.NaN))
        assertNear(0f, editorialPosterBandHeightDp(355f, 0f, 2f))
    }

    @Test fun mountInsetClearsTheCardClipSoTheBakedFrameIsNeverCut() {
        val cardRadiusDp = 16f
        val required = cardRadiusDp * (1f - 1f / kotlin.math.sqrt(2f))
        assertTrue("mount=$EDITORIAL_POSTER_MOUNT_INSET_DP required=$required",
            EDITORIAL_POSTER_MOUNT_INSET_DP >= required)
    }

    @Test fun approvedPostersAreHorizontalSoPortraitSlotsStayPending() {
        val panoramic = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280)
        val compact = EditorialPosterAsset("pop", "genre_pop_editorial.webp", 514, 268)
        assertTrue(panoramic.ratio > compact.ratio)
        assertFalse(panoramic.isVertical)
        assertFalse(compact.isVertical)
    }
    @Test fun invalidPosterCatalogFailsClosed() {
        val name = "genre_new_wave_editorial.webp"
        val valid = editorialPosterManifestHeader + "\n" + record() + "\n"
        assertTrue(editorialApprovedPosters(valid, emptySet()).isEmpty())
        assertTrue(editorialApprovedPosters(valid + record() + "\n", setOf(name)).isEmpty())
        assertTrue(editorialApprovedPosters(valid.replace("POSTER_COMPLETE", "ILLUSTRATION"), setOf(name)).isEmpty())
        assertTrue(editorialApprovedPosters(valid, setOf(name, "genre_jazz_editorial.webp")).isEmpty())
        assertTrue(editorialApprovedPosters(valid.replace("1030", "4000"), setOf(name)).isEmpty())
        assertTrue(editorialApprovedPosters("# OLD\n" + record(), setOf(name)).isEmpty())
    }
    @Test fun noSlideFictionForMetadataNames() {
        assertEquals("hip_hop", editorialPosterSlug("HipHop"))
        assertEquals("synth_pop", editorialPosterSlug("Synth-Pop"))
        assertEquals("rn_b", editorialPosterSlug("R&B"))
        assertNull(editorialPosterSlug("R&B/Soul"))
        assertEquals("afrobeats", editorialPosterSlug("Afrobeats"))
        assertEquals("afrobeat", editorialPosterSlug("Afrobeat"))
    }
}
