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
    @Test fun originalRatioDeterminesSpanWithoutReinterpretingPortraits() {
        val panoramic = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280)
        val compact = EditorialPosterAsset("jazz", "genre_jazz_editorial.webp", 513, 327)
        assertEquals(2, editorialPosterSpan(2, panoramic))
        assertEquals(1, editorialPosterSpan(2, compact))
        assertEquals(1, editorialPosterSpan(1, panoramic))
        assertEquals(1, editorialPosterSpan(2, null))
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
