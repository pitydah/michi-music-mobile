package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editorial catalog + availability policy.
 *
 * The approved pack is made of horizontal finished posters (1.4..4.5:1). Each one
 * is classified by its own ratio and painted: a banner takes a full-width row, a
 * compact poster pairs or becomes a single. There is no portrait slot to fill and
 * no aspect/mask condition that may hide a packaged poster.
 */
class GenreEditorialPosterPolicyTest {
    private fun record(slug: String = "new_wave", width: Int = 1030, height: Int = 254): String =
        listOf(slug, "genre_${slug}_editorial.webp", "a".repeat(64), width.toString(),
            height.toString(), "UI7_EDITORIAL_LAMINA_03_NewWave_ItaloDisco.png",
            "USER_CONFIRMED_COMPLETE", "POSTER_COMPLETE").joinToString("\t")

    private fun mask(width: Int, height: Int, verified: Boolean = true) = Ui7OpticalMask(
        sourceWidthPx = width,
        sourceHeightPx = height,
        leftInsetPx = 3f,
        topInsetPx = 2f,
        rightInsetPx = 3f,
        bottomInsetPx = 2f,
        topLeftRadiusPx = 22f,
        topRightRadiusPx = 19f,
        bottomRightRadiusPx = 19f,
        bottomLeftRadiusPx = 18f,
        verified = verified,
    )

    @Test fun completedPostersMapByRealGenreNotCatalogOrder() {
        val manifest = editorialPosterManifestHeader + "\n" + record() + "\n"
        val posters = editorialApprovedPosters(manifest, setOf("genre_new_wave_editorial.webp"))
        assertEquals(1, posters.size)
        assertEquals("new_wave", editorialPosterForTitle("Newwave", posters)?.slug)
        assertNull(editorialPosterForTitle("No existe", posters))
        assertNull(editorialPosterForTitle("Sin clasificar", posters))
    }

    @Test fun anUnverifiedOpticalRowStillPaintsThePoster() {
        val name = "genre_new_wave_editorial.webp"
        val manifest = editorialPosterManifestHeader + "\n" + record() + "\n"
        val verified = editorialApprovedPosters(
            manifest, setOf(name), opticalMasks = mapOf(name to mask(1030, 254)),
        )
        assertNotNull(verified.getValue("new_wave").opticalMask)
        assertNotNull(verified.getValue("new_wave").counterSafeRect)
        val unverified = editorialApprovedPosters(
            manifest, setOf(name), opticalMasks = mapOf(name to mask(1030, 254, verified = false)),
        )
        assertNotNull("an unmeasured frame still records the row", unverified["new_wave"])
        assertNotNull("the documented provisional chip region is used",
            unverified.getValue("new_wave").counterSafeRect)
        val spec = editorialVisualSpec(unverified["new_wave"])!!
        val decision = ui7ArtworkDecision(spec, 350f, 350f / spec.ratio, 26f, 26f)
        assertTrue("the poster is painted with a provisional contour", decision.paintsArtwork)
        assertTrue(decision.quality.opticalMaskUnverified)
    }

    @Test fun horizontalPostersAreNeverAskedToBecomePortraits() {
        val panoramic = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280,
            opticalMask = mask(1027, 280))
        val compact = EditorialPosterAsset("pop", "genre_pop_editorial.webp", 514, 268,
            opticalMask = mask(514, 268))
        assertTrue(panoramic.ratio > compact.ratio)
        assertFalse(panoramic.isVertical)
        assertFalse(compact.isVertical)
        // No vertical variant exists in the approved pack; the wall never needs one.
        assertEquals(0, listOf(panoramic, compact).count { it.isVertical })
        val decision = ui7ArtworkDecision(editorialVisualSpec(panoramic)!!,
            350f, 350f / panoramic.ratio, 26f, 26f)
        assertTrue(decision.paintsArtwork)
        assertFalse(decision.quality.needsPortraitVariant)
    }

    @Test fun everyBannerAndCompactKeepsItsRealProportions() {
        // 1030x254 banner: 350 x 86.3 at 390dp panel width.
        val newWave = EditorialPosterAsset("new_wave", "genre_new_wave_editorial.webp", 1030, 254,
            opticalMask = mask(1030, 254))
        val spec = editorialVisualSpec(newWave)!!
        val height = editorialUsableWidthDp(390f) / spec.ratio
        assertEquals(86.3f, height, 0.1f)
        val decision = ui7ArtworkDecision(spec, 350f, height, 26f, 26f)
        assertTrue(decision.paintsArtwork)
        assertEquals(0f, decision.quality.aspectDebtPerSideDp, 0.2f)
        // 514x268 compact in its proportional pair cell: no band either.
        val pop = EditorialPosterAsset("pop", "genre_pop_editorial.webp", 514, 268)
        val popSpec = editorialVisualSpec(pop)!!
        val pairHeight = 94.0f
        val decisionPair = ui7ArtworkDecision(popSpec, pop.ratio * pairHeight, pairHeight, 26f, 26f)
        assertTrue(decisionPair.paintsArtwork)
        assertEquals(0f, decisionPair.quality.aspectDebtPerSideDp, 0.2f)
    }

    @Test fun invalidPosterCatalogFailsClosed() {
        val name = "genre_new_wave_editorial.webp"
        val valid = editorialPosterManifestHeader + "\n" + record() + "\n"
        assertTrue(editorialApprovedPosters(valid, emptySet()).isEmpty())
        assertTrue(editorialApprovedPosters(valid + record() + "\n", setOf(name)).isEmpty())
        assertTrue(editorialApprovedPosters(valid.replace("POSTER_COMPLETE", "ILLUSTRATION"),
            setOf(name)).isEmpty())
        assertTrue(editorialApprovedPosters(valid, setOf(name, "genre_jazz_editorial.webp"))
            .isEmpty())
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
