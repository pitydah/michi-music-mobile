package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
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
     * Clásica/Metal are approved ~1032x255 (4.05:1) panoramic sheets. Inside a
     * half-width portrait slot (180.5dp at a 411dp panel, 155dp at 360dp) their
     * truthful aspect height is 38-45dp, below the 48dp touch floor. The cell
     * must keep the aspect for compact posters and only raise the smallest ones.
     */
    @Test fun panoramicPosterCellsNeverFallBelowTheTouchFloor() {
        fun assertNear(expected: Float, actual: Float) {
            assertTrue("expected=$expected actual=$actual",
                kotlin.math.abs(expected - actual) <= 0.05f)
        }
        val clasicaRatio = 1032f / 255f
        val metalRatio = 1033f / 255f
        assertNear(48f, editorialPosterCellHeightDp(180.5f, clasicaRatio))
        assertNear(48f, editorialPosterCellHeightDp(155f, metalRatio))
        // Compact posters keep their real aspect: 514x268 at a 180.5dp cell.
        assertNear(180.5f / (514f / 268f), editorialPosterCellHeightDp(180.5f, 514f / 268f))
        assertNear(280f / 1.4f, editorialPosterCellHeightDp(280f, 1.4f))
        // Invalid geometry fails closed to the touch floor, never to a broken cell.
        assertNear(48f, editorialPosterCellHeightDp(0f, 2f))
        assertNear(48f, editorialPosterCellHeightDp(180f, Float.NaN))
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
