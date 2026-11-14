package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Distinguishes an unapproved pack from missing artwork on a genuine genre. */
class GenreArtworkDiagnosticPolicyTest {
    @Test fun mosaicRejectsFirstBadRowWithItsLine() {
        val bad = mosaicManifestHeader + "\nrock\tbroken_line\n"
        var reason: String? = null
        assertTrue(mosaicApprovedArtwork(
            bad, emptySet(), onRejected = { reason = it },
        ).isEmpty())
        assertEquals("line_2_columns", reason)
    }

    @Test fun mosaicRejectsEmptyApprovedCatalog() {
        var reason: String? = null
        assertTrue(mosaicApprovedArtwork(
            mosaicManifestHeader + "\n", emptySet(), onRejected = { reason = it },
        ).isEmpty())
        assertEquals("empty_catalog", reason)
    }

    @Test fun editorialRejectsInvalidHeaderWithCause() {
        var reason: String? = null
        assertTrue(editorialApprovedPosters(
            "# WRONG\n", emptySet(), onRejected = { reason = it },
        ).isEmpty())
        assertEquals("invalid_header", reason)
    }

    private val sha = "a".repeat(64)
    private val rockMosaic = "rock\tUI7_MOSAICO_001_Rock.webp\t$sha\t506\t352\tUSER_CONFIRMED_COMPLETE"
    private val jazzMosaic = "jazz\tUI7_MOSAICO_002_Jazz.webp\t$sha\t506\t352\tUSER_CONFIRMED_COMPLETE"
    private val rockPoster = "rock\tgenre_rock_editorial.webp\t$sha\t1027\t280\tUI7_EDITORIAL_LAMINA_01_Rock.png\tUSER_CONFIRMED_COMPLETE\tPOSTER_COMPLETE"

    @Test fun validMosaicCatalogResolvesOnlyApprovedIdentity() {
        val files = setOf("UI7_MOSAICO_001_Rock.webp", "UI7_MOSAICO_002_Jazz.webp")
        var reject: String? = null
        val result = mosaicApprovedArtwork(
            mosaicManifestHeader + "\n" + rockMosaic + "\n" + jazzMosaic + "\n", files,
            onRejected = { reject = it },
        )
        assertEquals(2, result.size)
        assertEquals("UI7_MOSAICO_001_Rock.webp", result["rock"]?.filename)
        assertEquals(null, reject)
    }

    @Test fun oneStrayWebpRejectsEntireCatalogInsteadOfInventingGenres() {
        var reason: String? = null
        val files = setOf("UI7_MOSAICO_001_Rock.webp", "UI7_MOSAICO_999_Fake.webp")
        val result = mosaicApprovedArtwork(
            mosaicManifestHeader + "\n" + rockMosaic + "\n", files,
            onRejected = { reason = it },
        )
        assertTrue(result.isEmpty())
        assertEquals("unexpected_webp", reason)
    }

    @Test fun anUnapprovedOrWrongNamedMosaicIsNeverPublished() {
        var reason: String? = null
        val files = setOf("UI7_MOSAICO_001_Rock.webp")
        val row = rockMosaic.replace("USER_CONFIRMED_COMPLETE", "PLANNED")
        assertTrue(mosaicApprovedArtwork(
            mosaicManifestHeader + "\n" + row + "\n", files, onRejected = { reason = it },
        ).isEmpty())
        assertEquals("line_2_integrity_or_approval", reason)
        val wrong = rockMosaic.replace("UI7_MOSAICO_001_Rock.webp", "UI7_MOSAICO_001_Jazz.webp")
        assertTrue(mosaicApprovedArtwork(
            mosaicManifestHeader + "\n" + wrong + "\n", files, onRejected = { reason = it },
        ).isEmpty())
        assertEquals("line_2_genre_identity", reason)
    }

    @Test fun editorialApprovedIdentityRefusesMissingAssetAndStrayFile() {
        var reason: String? = null
        val manifest = editorialPosterManifestHeader + "\n" + rockPoster + "\n"
        assertTrue(editorialApprovedPosters(
            manifest, emptySet(), onRejected = { reason = it },
        ).isEmpty())
        assertEquals("line_2_asset_missing", reason)
        assertTrue(editorialApprovedPosters(
            manifest, setOf("genre_rock_editorial.webp", "genre_fake_editorial.webp"),
            onRejected = { reason = it },
        ).isEmpty())
        assertEquals("unexpected_webp", reason)
        val approved = editorialApprovedPosters(manifest, setOf("genre_rock_editorial.webp"))
        assertEquals("genre_rock_editorial.webp", approved["rock"]?.filename)
    }

    @Test fun editorialRejectsEmptyApprovedCatalog() {
        var reason: String? = null
        assertTrue(editorialApprovedPosters(
            editorialPosterManifestHeader + "\n", emptySet(), onRejected = { reason = it },
        ).isEmpty())
        assertEquals("empty_catalog", reason)
    }

    @Test fun aMalformedOpticalTableLeavesRowsUnverifiedButKeepsTheCatalog() {
        val files = setOf("UI7_MOSAICO_001_Rock.webp")
        val manifest = mosaicManifestHeader + "\n" + rockMosaic + "\n"
        val masks = runCatching {
            parseUi7OpticalMasks("# WRONG\nUI7_MOSAICO_001_Rock.webp\t506\t352\t2\t1\t2\t2\t28\t27\t26\t30\t1\n")
        }.getOrDefault(emptyMap())
        val art = mosaicApprovedArtwork(manifest, files, opticalMasks = masks)
        assertEquals("the approved catalog still loads", 1, art.size)
        assertEquals("no invented frame", null, art["rock"]?.opticalMask)
        // The same malformed row cannot be parsed as an approved optical table.
        assertTrue(masks.isEmpty())
    }
}
