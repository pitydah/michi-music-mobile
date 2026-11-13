package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreEditorialArtworkPolicyTest {
    private val header = "# UI7_EDITORIAL_PRODUCTION_MANIFEST_V1\n"

    private fun manifestRow(slug: String = "new_wave", variant: String = "cover",
        filename: String = "genre_${slug}_${variant}.webp", approval: String = "APPROVED",
        safe: String = "YES", sha: String = "a".repeat(64)): String =
        header + listOf(slug, variant, filename, sha, "1024", "576",
            "1zTH98iNz4rsDIpJ2LUINsPgQO6TAqP5i", approval, "YES", "YES", safe).joinToString("\t")

    @Test fun emptyManifestAndLooseFilesNeverShowArtwork() {
        assertTrue(editorialApprovedAssetNames("# empty\n", setOf("genre_new_wave_cover.webp")).isEmpty())
    }

    @Test fun approvedRowMustBePackagedAndNamedExactly() {
        val name = "genre_new_wave_cover.webp"
        assertEquals(setOf(name), editorialApprovedAssetNames(manifestRow(), setOf(name)))
        assertTrue(editorialApprovedAssetNames(manifestRow(), emptySet()).isEmpty())
        assertTrue(editorialApprovedAssetNames(manifestRow(filename = "genre_rock_cover.webp"), setOf(name)).isEmpty())
    }

    @Test fun unapprovedOrUnsafeManifestFailsClosed() {
        val name = "genre_new_wave_cover.webp"
        assertTrue(editorialApprovedAssetNames(manifestRow(approval = "DRAFT"), setOf(name)).isEmpty())
        assertTrue(editorialApprovedAssetNames(manifestRow(safe = "NO"), setOf(name)).isEmpty())
        assertTrue(editorialApprovedAssetNames(manifestRow(sha = "bad"), setOf(name)).isEmpty())
        assertTrue(editorialApprovedAssetNames(manifestRow() + "\n" + manifestRow().removePrefix(header), setOf(name)).isEmpty())
        assertTrue(editorialApprovedAssetNames(manifestRow() + "\nbroken row", setOf(name)).isEmpty())
    }

    @Test fun manifestCannotDisplayNonexistentOrUnknownGenres() {
        val invalid = manifestRow(slug = "not_a_genre", filename = "genre_not_a_genre_cover.webp")
        assertTrue(editorialApprovedAssetNames(invalid, setOf("genre_not_a_genre_cover.webp")).isEmpty())
    }

    @Test fun publicationRejectsMissingOrUnrecognizedManifestVersion() {
        val name = "genre_new_wave_cover.webp"
        assertTrue(editorialApprovedAssetNames(manifestRow().removePrefix(header), setOf(name)).isEmpty())
        assertTrue(editorialApprovedAssetNames(manifestRow().replace("V1", "V2"), setOf(name)).isEmpty())
        assertTrue(editorialApprovedAssetNames("", emptySet()).isEmpty())
    }

    @Test fun strayWebpAndWrongVariantGeometryFailClosed() {
        val name = "genre_new_wave_cover.webp"
        assertTrue(editorialApprovedAssetNames(manifestRow(), setOf(name, "genre_rock_cover.webp")).isEmpty())
        val portrait = manifestRow(variant = "portrait_left")
        assertTrue(editorialApprovedAssetNames(portrait, setOf("genre_new_wave_portrait_left.webp")).isEmpty())
        assertTrue(editorialProductionGeometryValid("cover", 1024, 576))
        assertTrue(editorialProductionGeometryValid("portrait_left", 768, 1024))
        assertTrue(editorialProductionGeometryValid("single", 800, 500))
        assertTrue(!editorialProductionGeometryValid("cover", 4096, 4096))
    }

    @Test fun inventoryContainsOnlyThe216VerifiedEditorialSources() {
        // The reconciled UI7 editorial pack ships one verified source per Mosaico genre.
        assertEquals(216, editorialSourceKeys.size)
        assertTrue("new_wave" in editorialSourceKeys)
        assertTrue("afrobeat" in editorialSourceKeys)
        assertTrue("afrobeats" in editorialSourceKeys)
    }

    @Test fun imagePathUsesSlotAndNeverCreatesNewGenre() {
        assertEquals(
            "ui7/editorial/production/genre_new_wave_cover.webp",
            editorialProductionAssetPath("New Wave", GenreEditorialTreatment.COVER, 2),
        )
        assertEquals(
            "ui7/editorial/production/genre_new_wave_portrait_left.webp",
            editorialProductionAssetPath("New Wave", GenreEditorialTreatment.PORTRAIT_LEFT, 2),
        )
        assertEquals(
            "ui7/editorial/production/genre_new_wave_portrait_right.webp",
            editorialProductionAssetPath("New Wave", GenreEditorialTreatment.PORTRAIT_RIGHT, 2),
        )
        assertEquals(
            "ui7/editorial/production/genre_new_wave_single.webp",
            editorialProductionAssetPath("New Wave", GenreEditorialTreatment.PORTRAIT_RIGHT, 1),
        )
    }

    @Test fun unknownLabelsNeverSelectIncorrectArt() {
        assertNull(editorialProductionAssetPath("Sin clasificar", GenreEditorialTreatment.COVER, 2))
        assertNull(editorialProductionAssetPath("Some Unknown New Style", GenreEditorialTreatment.COVER, 2))
        assertNull(editorialProductionAssetPath("Metal!!!", GenreEditorialTreatment.COVER, 2))
        assertNull(editorialProductionAssetPath("R&B/Soul", GenreEditorialTreatment.COVER, 2))
        assertEquals("ui7/editorial/production/genre_rn_b_cover.webp",
            editorialProductionAssetPath("R&B", GenreEditorialTreatment.COVER, 2))
    }

    @Test fun closelyRelatedLabelsRetainDifferentArtwork() {
        assertNotEquals(
            editorialProductionAssetPath("Afrobeat", GenreEditorialTreatment.COVER, 2),
            editorialProductionAssetPath("Afrobeats", GenreEditorialTreatment.COVER, 2))
        assertNotEquals(
            editorialProductionAssetPath("Metal", GenreEditorialTreatment.COVER, 2),
            editorialProductionAssetPath("Heavy Metal", GenreEditorialTreatment.COVER, 2))
    }
}
