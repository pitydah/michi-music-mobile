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
     * The old defect: card height came from the poster's own panorama ratio, so a
     * paired Pop (514x268) rendered 88.6dp next to a Rock (1027x280) at 46.3dp in
     * the same 170dp slot. Treatment heights fix the exterior rhythm.
     */
    @Test fun pairedPosterCardsShareOneTreatmentHeightNotTheirAssetRatio() {
        assertEquals(192, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 2, 1f))
        assertEquals(246, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 1f))
        assertEquals(246, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_RIGHT, 2, 1f))
        assertEquals(200, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 1, 1f))
        assertEquals(
            genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 1f),
            genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_RIGHT, 2, 1f),
        )
        // Text scale may grow the card, never the artwork ratio.
        assertEquals(216, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 2, 2f))
        assertEquals(270, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 2f))
        val inner = 180.5f - 2 * EDITORIAL_POSTER_MOUNT_INSET_DP
        val popRatio = 514f / 268f
        val rockRatio = 1027f / 280f
        assertTrue("ratio-driven heights must differ", inner / popRatio - inner / rockRatio > 30f)
        assertTrue(genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 1f) >= 48)
    }

    @Test fun posterBandKeepsTheWholePosterAndReservesTheCountSurface() {
        fun assertNear(expected: Float, actual: Float) {
            assertTrue("expected=$expected actual=$actual",
                kotlin.math.abs(expected - actual) <= 0.05f)
        }
        // 180.5dp half cell: the inner mount leaves 164.5dp for the poster.
        val inner = 180.5f - 2 * EDITORIAL_POSTER_MOUNT_INSET_DP
        assertNear(inner * 268f / 514f, editorialPosterBandHeightDp(inner, 192f, 514f / 268f))
        assertNear(inner * 280f / 1027f, editorialPosterBandHeightDp(inner, 192f, 1027f / 280f))
        // A poster that would need more than the treatment surface is clamped, not
        // cropped: 355dp inner width, 1.918:1 needs 185.1dp and fits under 192dp.
        assertNear(355f / (514f / 268f), editorialPosterBandHeightDp(355f, 192f, 514f / 268f))
        assertNear(138f, editorialPosterBandHeightDp(355f, 138f, 514f / 268f))
        // Invalid geometry keeps the reserved band instead of a broken card.
        assertNear(192f, editorialPosterBandHeightDp(0f, 192f, 2f))
        assertNear(192f, editorialPosterBandHeightDp(355f, 192f, Float.NaN))
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
