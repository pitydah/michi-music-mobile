package org.michimusic.mobile.ui.screens

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inventory contract for the UI7 pack: 665 artwork pairs per view.
 *
 * Positions 1-312 were reviewed and approved; positions 313-672 were imported from
 * the pending batch on 2026-11-17 with bytes, dimensions and SHA-256 verified but
 * art review still open, so their manifest rows say so. Seven positions (557, 576,
 * 589, 590, 591, 592, 607) were withdrawn at the source and must stay absent: the
 * numbering is original, never renumbered to hide the gaps.
 *
 * This test runs on the JVM against the checked-in assets, so a manifest/asset drift
 * fails before any device build. It never asserts a music taxonomy: artwork can only
 * attach to the genre its own filename spells.
 */
class GenreArtworkInventory665Test {
    private val withdrawn = setOf(557, 576, 589, 590, 591, 592, 607)
    private val expectedPositions = (1..672).filterNot { it in withdrawn }

    private val moduleDir: File = File(System.getProperty("user.dir") ?: ".").let { cwd ->
        if (File(cwd, "src/main/java").isDirectory) cwd
        else File(cwd, "app").takeIf { File(it, "src/main/java").isDirectory } ?: cwd
    }
    private val ui7 = File(moduleDir, "src/main/assets/ui7")
    private val mosaicDir = File(ui7, "mosaic")
    private val posterDir = File(ui7, "editorial/posters")

    private fun rows(file: File): List<List<String>> = file.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }

    private fun text(file: File): String = file.readText()

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun mosaicCatalog(optical: Boolean = false) = mosaicApprovedArtwork(
        text(File(mosaicDir, "manifest.tsv")),
        mosaicDir.listFiles().orEmpty().map { it.name }.toSet(),
        opticalMasks = if (optical) parseUi7OpticalMasks(text(File(mosaicDir, "optical.tsv"))) else emptyMap(),
    )

    private fun posterCatalog(optical: Boolean = false) = editorialApprovedPosters(
        text(File(posterDir, "manifest.tsv")),
        posterDir.listFiles().orEmpty().map { it.name }.toSet(),
        opticalMasks = if (optical) parseUi7OpticalMasks(text(File(posterDir, "optical.tsv"))) else emptyMap(),
    )

    @Test fun bothManifestsDeclareTheFullInventoryWithTheirRealApprovalStates() {
        val mosaic = rows(File(mosaicDir, "manifest.tsv"))
        val posters = rows(File(posterDir, "manifest.tsv"))
        assertEquals("Mosaico manifest rows", 665, mosaic.size)
        assertEquals("Editorial poster manifest rows", 665, posters.size)
        assertEquals("Mosaico manifest keys", 665, mosaic.map { it[0] }.toSet().size)
        assertEquals("Editorial poster slugs", 665, posters.map { it[0] }.toSet().size)
        assertTrue(mosaic.all { it.size == 6 })
        assertTrue(posters.all { it.size == 8 })
        // Packaging is not approval: exactly the reviewed rows claim it.
        assertEquals(312, mosaic.count { it[5] == "USER_CONFIRMED_COMPLETE" })
        assertEquals(312, posters.count {
            it[6] == "USER_CONFIRMED_COMPLETE" && it[7] == "POSTER_COMPLETE"
        })
        assertEquals(353, mosaic.count { it[5] == "IMPORTED_PENDING_VISUAL_APPROVAL" })
        assertEquals(353, posters.count {
            it[6] == "IMPORTED_PENDING_VISUAL_APPROVAL" && it[7] == "POSTER_IMPORTED_PENDING"
        })
        assertEquals(0, mosaic.count { it[5] == "REJECTED" })
        assertEquals(0, posters.count { it[6] == "REJECTED" })
    }

    @Test fun bothOpticalTablesStillCoverTheSame312MeasuredAssets() {
        // The optical tables were measured for the reviewed pack only. The imported
        // 353 carry no recorded mask yet, so they render with the provisional contour
        // instead of an invented one.
        val mosaicOptical = rows(File(mosaicDir, "optical.tsv"))
        val posterOptical = rows(File(posterDir, "optical.tsv"))
        assertEquals("Mosaico optical rows", 312, mosaicOptical.size)
        assertEquals("Editorial optical rows", 312, posterOptical.size)
        val mosaicManifest = rows(File(mosaicDir, "manifest.tsv")).associateBy { it[1] }
        val posterManifest = rows(File(posterDir, "manifest.tsv")).associateBy { it[1] }
        fun checkRows(optical: List<List<String>>, manifest: Map<String, List<String>>) {
            optical.forEach { row ->
                assertEquals("optical columns for ${row[0]}", 12, row.size)
                val declared = manifest.getValue(row[0])
                assertEquals("width for ${row[0]}", declared[3], row[1])
                assertEquals("height for ${row[0]}", declared[4], row[2])
                val width = row[1].toInt()
                val height = row[2].toInt()
                // Four insets and four corner radii are always recorded.
                (3..6).forEach { column ->
                    val inset = row[column].toFloat()
                    assertTrue("inset $column for ${row[0]}", inset >= 0f && inset < minOf(width, height) / 2f)
                }
                (7..10).forEach { column ->
                    val radius = row[column].toFloat()
                    assertTrue("radius $column for ${row[0]}", radius >= 0f && radius < minOf(width, height))
                }
                assertTrue("verified flag for ${row[0]}", row[11] == "0" || row[11] == "1")
            }
        }
        checkRows(mosaicOptical, mosaicManifest)
        checkRows(posterOptical, posterManifest)
        assertEquals("verified Mosaico rows", 298, mosaicOptical.count { it[11] == "1" })
        assertEquals("verified Editorial rows", 227, posterOptical.count { it[11] == "1" })
    }

    @Test fun everyOpticalRowAttachesOnlyToTheAssetItMeasured() {
        val mosaicOptical = parseUi7OpticalMasks(text(File(mosaicDir, "optical.tsv")))
        val posterOptical = parseUi7OpticalMasks(text(File(posterDir, "optical.tsv")))
        assertEquals(312, mosaicOptical.size)
        assertEquals(312, posterOptical.size)
        val mosaic = mosaicCatalog(optical = true)
        val posters = posterCatalog(optical = true)
        assertEquals(665, mosaic.size)
        assertEquals(665, posters.size)
        assertEquals("every measured Mosaico row keeps its mask", 312,
            mosaic.values.count { it.opticalMask != null })
        assertEquals("every measured Editorial row keeps its mask", 312,
            posters.values.count { it.opticalMask != null })
        // An unverified row is a QA flag, not an absence: both counts are the
        // documented debt of the published pack (298/312 and 227/312).
        assertEquals(298, mosaic.values.count { it.opticalMask?.verified == true })
        assertEquals(227, posters.values.count { it.opticalMask?.verified == true })
        // The imported crops have no measured mask, and none was invented.
        assertEquals(353, mosaic.values.count { it.opticalMask == null })
        assertEquals(353, posters.values.count { it.opticalMask == null })
    }

    @Test fun theInventoryKeepsTheOriginalNumberingIncludingItsSevenGaps() {
        val mosaic = rows(File(mosaicDir, "manifest.tsv"))
        val numbers = mosaic.map { it[1].substring(12, 15).toInt() }
        assertEquals(expectedPositions, numbers)
        assertEquals(665, numbers.size)
        assertTrue("withdrawn positions must stay absent", numbers.none { it in withdrawn })
        // Spot-pin the boundaries so nothing was renumbered to make the gaps vanish.
        assertEquals("UI7_MOSAICO_001_Rock.webp", mosaic.first()[1])
        assertEquals("UI7_MOSAICO_216_JRock.webp", mosaic[215][1])
        assertEquals("UI7_MOSAICO_217_ArtRock.webp", mosaic[216][1])
        assertEquals("UI7_MOSAICO_312_Timba.webp", mosaic[311][1])
        assertTrue("first imported crop", mosaic[312][1].startsWith("UI7_MOSAICO_313_"))
        assertTrue("last imported crop", mosaic.last()[1].startsWith("UI7_MOSAICO_672_"))
        val posters = rows(File(posterDir, "manifest.tsv"))
        // Poster rows are written in slug order: that is the deterministic contract,
        // not a historical creation order that a rewrite would silently reshuffle.
        assertEquals(posters.map { it[0] }.sorted(), posters.map { it[0] })
        assertTrue("acid_house" in posters.map { it[0] })
        assertTrue("ye_ye" in posters.map { it[0] })
        assertEquals(665, posters.map { it[1] }.toSet().size)
    }

    @Test fun everyDeclaredHashMatchesThePackagedAsset() {
        rows(File(mosaicDir, "manifest.tsv")).forEach { row ->
            assertEquals("Mosaico hash for ${row[1]}", row[2], sha256(File(mosaicDir, row[1])))
        }
        rows(File(posterDir, "manifest.tsv")).forEach { row ->
            assertEquals("Poster hash for ${row[1]}", row[2], sha256(File(posterDir, row[1])))
        }
    }

    @Test fun theWholeInventoryLoadsAtomicallyFromTheRealAssets() {
        var mosaicRejection: String? = null
        val mosaic = mosaicApprovedArtwork(
            text(File(mosaicDir, "manifest.tsv")),
            mosaicDir.listFiles().orEmpty().map { it.name }.toSet(),
            onRejected = { mosaicRejection = it },
        )
        assertNull("Mosaico rejected: $mosaicRejection", mosaicRejection)
        assertEquals(665, mosaic.size)

        var posterRejection: String? = null
        val posters = editorialApprovedPosters(
            text(File(posterDir, "manifest.tsv")),
            posterDir.listFiles().orEmpty().map { it.name }.toSet(),
            onRejected = { posterRejection = it },
        )
        assertNull("Editorial rejected: $posterRejection", posterRejection)
        assertEquals(665, posters.size)
    }

    @Test fun everyMosaicCropPaintsAtEveryContractWidth() {
        val mosaic = mosaicCatalog(optical = true)
        listOf(320 to 1, 360 to 1, 390 to 2, 411 to 2, 480 to 2).forEach { (panel, columns) ->
            val size = mosaicCardSize(panel.toFloat(), columns)
            mosaic.values.forEach { asset ->
                val decision = ui7ArtworkDecision(
                    mosaicVisualSpec(asset), size.widthDp, size.heightDp, 26f, 26f,
                )
                assertTrue("[${panel}dp] ${asset.filename} must paint",
                    decision.paintsArtwork)
                assertEquals(Ui7AssetAvailability.ASSET_PACKAGED, decision.availability)
                // Every Mosaico source is 1.32..1.56: the residual band is a QA
                // record, never a reason to hide the crop.
                assertTrue("[${panel}dp] ${asset.filename} debt must be measured",
                    decision.quality.aspectDebtPerSideDp >= 0f)
            }
        }
    }

    @Test fun everyPosterPaintsAsBannerOrCompact() {
        val posters = posterCatalog(optical = true)
        listOf(320 to 1, 360 to 1, 390 to 2, 411 to 2, 480 to 2).forEach { (panel, _) ->
            val usable = editorialUsableWidthDp(panel.toFloat())
            posters.values.forEach { poster ->
                val spec = editorialVisualSpec(poster)!!
                val height = usable / spec.ratio
                val decision = ui7ArtworkDecision(spec, usable, height, 26f, 26f)
                assertTrue("[${panel}dp] ${poster.filename} must paint", decision.paintsArtwork)
                val expectedClass = editorialPosterClass(spec.ratio)
                assertTrue("${poster.filename} must be a real banner/compact",
                    expectedClass != EditorialPosterClass.RETRATO_FUTURO)
                assertEquals("[${panel}dp] ${poster.filename} must not need a portrait",
                    false, decision.quality.needsPortraitVariant)
            }
        }
    }

    @Test fun importedGenresResolveInBothViewsWithoutBorrowingArt() {
        val mosaic = mosaicCatalog()
        val posters = posterCatalog()
        val expected = mapOf(
            // Reviewed batch, still resolvable.
            "Art Rock" to "UI7_MOSAICO_217_ArtRock.webp",
            "Timba" to "UI7_MOSAICO_312_Timba.webp",
            // Imported batch, including the hyphenated suffixes MI-26 preserves.
            "Jangle Pop" to "UI7_MOSAICO_313_JanglePop.webp",
            "Proto-Punk" to "UI7_MOSAICO_317_ProtoPunk.webp",
            "Abstract Hip-Hop" to "UI7_MOSAICO_345_AbstractHipHop.webp",
            "Post-Bop" to "UI7_MOSAICO_353_PostBop.webp",
            "Go-Go" to "UI7_MOSAICO_609_GoGo.webp",
            "Pinoy Hip-Hop" to "UI7_MOSAICO_670_PinoyHipHop.webp",
        )
        expected.forEach { (title, filename) ->
            // Runtime passes the scoped library genre id (diacritic-free grouping key),
            // never the raw metadata label; mirror that contract here.
            val genreId = genreGroupingKey(title)
            assertEquals("Mosaico $title", filename, mosaicArtworkForGenre(genreId, mosaic)?.filename)
            assertNotNull("Editorial $title must resolve",
                editorialPosterForTitle(title, posters)?.filename)
        }
    }

    @Test fun theAbsenceBucketOwnsArtworkTheTaxonomyNeverDoes() {
        val mosaic = mosaicCatalog()
        val posters = posterCatalog()
        // The genre bucket id is the internal marker, so that is what runtime passes.
        val absenceId = org.michimusic.core.intelligence.MichiGenreAbsencePolicy.UNCLASSIFIED_KEY
        assertEquals(
            "UI7_MOSAICO_616_SinClasificar.webp",
            mosaicArtworkForGenre(absenceId, mosaic)?.filename,
        )
        assertEquals(
            "genre_sin_clasificar_editorial.webp",
            editorialPosterForTitle("Sin clasificar", posters)?.filename,
        )
        // It stays an absence marker: no music genre resolves to it, and the label
        // itself is not a genre key.
        assertEquals("", genreGroupingKey("Sin clasificar"))
        assertNotEquals(
            mosaicArtworkForGenre("Rock", mosaic)?.filename,
            mosaicArtworkForGenre(absenceId, mosaic)?.filename,
        )
        assertNull(mosaicArtworkForGenre("__ui7_unknown_genre__x", mosaic))
    }

    @Test fun unknownGenresKeepNoArtworkInEitherView() {
        val mosaic = mosaicCatalog()
        val posters = posterCatalog()
        listOf("", "Some Unknown New Style", "R&B/Soul").forEach {
            assertNull("Mosaico invented art for '$it'", mosaicArtworkForGenre(it, mosaic))
        }
        // Editorial is stricter about punctuation: an unapproved slug never borrows art.
        listOf("", "Some Unknown New Style", "Metal!!!", "R&B/Soul").forEach {
            assertNull("Editorial invented art for '$it'", editorialPosterForTitle(it, posters))
        }
    }

    @Test fun multiTagTrackKeepsOneApprovedIdentityPerTag() {
        val mosaic = mosaicCatalog()
        // A single track can carry several genre tags; each tag keeps its own crop.
        val tags = listOf("Art Rock", "Gqom", "Timba")
        val filenames = tags.map {
            mosaicArtworkForGenre(genreGroupingKey(it), mosaic)?.filename
        }
        assertEquals(3, filenames.toSet().size)
        filenames.forEach { assertTrue("missing crop for $tags", it != null) }
        // Closely related approved genres are never merged by tolerant lookup.
        assertNotEquals(
            mosaicArtworkForGenre("Afrobeat", mosaic)?.filename,
            mosaicArtworkForGenre("Afrobeats", mosaic)?.filename,
        )
    }

    @Test fun switchingViewsNeverMutatesTheOtherCatalog() {
        val mosaicText = text(File(mosaicDir, "manifest.tsv"))
        val posterText = text(File(posterDir, "manifest.tsv"))
        val mosaicFiles = mosaicDir.listFiles().orEmpty().map { it.name }.toSet()
        val posterFiles = posterDir.listFiles().orEmpty().map { it.name }.toSet()
        val mosaic = mosaicApprovedArtwork(mosaicText, mosaicFiles)
        val posters = editorialApprovedPosters(posterText, posterFiles)
        val mosaicRock = mosaicArtworkForGenre("Rock", mosaic)?.filename
        val posterRock = editorialPosterForTitle("Rock", posters)?.filename
        repeat(3) {
            // "Switch" back and forth: both catalogs stay complete and unchanged.
            assertEquals(665, mosaic.size)
            assertEquals(665, posters.size)
            assertEquals(mosaicRock, mosaicArtworkForGenre("Rock", mosaic)?.filename)
            assertEquals(posterRock, editorialPosterForTitle("Rock", posters)?.filename)
            assertEquals(665, editorialApprovedPosters(posterText, posterFiles).size)
            assertEquals(665, mosaicApprovedArtwork(mosaicText, mosaicFiles).size)
        }
    }
}
