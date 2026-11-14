package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editorial catalog + treatment policy.
 *
 * The approved pack is made of horizontal finished posters (1.4..4.5:1). A
 * horizontal sheet is never promoted to a portrait slot and never cropped into
 * one: the portrait treatment stays `NEEDS_APPROVED_PORTRAIT` until an authentic
 * vertical variant exists. Cover sheets are used only when they fill the 192dp
 * box intact; otherwise they stay `NEEDS_APPROVED_COVER` and the Editorial
 * Compose fallback covers the rhythm.
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

    @Test fun aVerifiedOpticalRowGetsTheBottomEndSafeRegion() {
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
        assertNull("but no fake safe region", unverified.getValue("new_wave").counterSafeRect)
        assertEquals(
            Ui7RenderOutcome.UNVERIFIED_CONTOUR,
            ui7RenderOutcome(
                editorialVisualSpec(unverified["new_wave"], GenreEditorialTreatment.COVER),
                Ui7Treatment.EDITORIAL_COVER, 350f, 192f, 1030f / 254f, 28f, 28f,
            ),
        )
    }

    @Test fun editorialHeightsAreTreatmentsNeverAssetRatios() {
        assertEquals(192, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 2, 1f))
        assertEquals(246, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 1f))
        assertEquals(246, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_RIGHT, 2, 1f))
        assertEquals(200, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 1, 1f))
        assertEquals(
            genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 1f),
            genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_RIGHT, 2, 1f),
        )
        // Text scale may grow the Compose content; both halves grow identically.
        assertEquals(216, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 2, 2f))
        assertEquals(270, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 2f))
        assertEquals(
            genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 2f),
            genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_RIGHT, 2, 2f),
        )
    }

    @Test fun editorialCardSizeMatrixMatchesTheContractTable() {
        listOf(
            Triple(390, 350f, 170f),
            Triple(411, 371f, 180.5f),
            Triple(480, 440f, 215f),
        ).forEach { (width, cover, portrait) ->
            val size = editorialCardSize(width.toFloat(), columns = 2, fontScale = 1f)
            assertEquals("cover width @$width", cover, size.coverWidthDp, 0.01f)
            assertEquals("portrait width @$width", portrait, size.portraitWidthDp, 0.01f)
            assertEquals("cover height @$width", 192f, size.coverHeightDp, 0.01f)
            assertEquals("portrait height @$width", 246f, size.portraitHeightDp, 0.01f)
        }
        listOf(320, 360).forEach { width ->
            val size = editorialCardSize(width.toFloat(), columns = 1, fontScale = 1f)
            assertEquals("one-column width @$width", (width - 40).toFloat(), size.coverWidthDp, 0.01f)
            assertEquals("one-column height @$width", 200f, size.singleHeightDp, 0.01f)
        }
    }

    @Test fun horizontalPostersNeverBecomePortraits() {
        val panoramic = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280,
            opticalMask = mask(1027, 280))
        val compact = EditorialPosterAsset("pop", "genre_pop_editorial.webp", 514, 268,
            opticalMask = mask(514, 268))
        assertTrue(panoramic.ratio > compact.ratio)
        assertFalse(panoramic.isVertical)
        assertFalse(compact.isVertical)
        listOf(panoramic, compact).forEach { poster ->
            assertEquals(
                emptySet<Ui7Treatment>(),
                editorialVisualSpec(poster, GenreEditorialTreatment.PORTRAIT_LEFT)!!.approvedTreatments,
            )
            assertEquals(
                emptySet<Ui7Treatment>(),
                editorialVisualSpec(poster, GenreEditorialTreatment.PORTRAIT_RIGHT)!!.approvedTreatments,
            )
        }
    }

    @Test fun aCoverIsAcceptedOnlyWhenItFillsTheBoxIntact() {
        // 1030x566 = 1.82:1 fills the 350x192 cover without a visible band.
        val prepared = EditorialPosterAsset("new_wave", "genre_new_wave_editorial.webp",
            1030, 566, opticalMask = mask(1030, 566))
        assertEquals(
            Ui7RenderOutcome.ASSET,
            ui7RenderOutcome(
                editorialVisualSpec(prepared, GenreEditorialTreatment.COVER),
                Ui7Treatment.EDITORIAL_COVER, 350f, 192f, prepared.ratio, 28f, 28f,
            ),
        )
        // The real Rock sheet is 3.67:1 and must not be used as a 192dp cover.
        val rock = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280,
            opticalMask = mask(1027, 280))
        assertEquals(
            Ui7RenderOutcome.NEEDS_APPROVED_COVER,
            ui7RenderOutcome(
                editorialVisualSpec(rock, GenreEditorialTreatment.COVER),
                Ui7Treatment.EDITORIAL_COVER, 350f, 192f, rock.ratio, 28f, 28f,
            ),
        )
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
