package org.michimusic.mobile.ui.screens

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inventory contract for the approved 312-genre UI7 pack.
 *
 * The first 216 genres were already published and must stay byte-identical; the
 * 96 genres on laminas 28-39 were user-approved on 2026-11-14. This test runs on
 * the JVM against the checked-in assets, so a manifest/asset drift fails before
 * any device build. It never asserts a music taxonomy: artwork can only attach
 * to the genre its own approved filename spells.
 */
class GenreArtworkInventory312Test {
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

    @Test fun bothManifestsDeclareExactly312ApprovedEntries() {
        val mosaic = rows(File(mosaicDir, "manifest.tsv"))
        val posters = rows(File(posterDir, "manifest.tsv"))
        assertEquals("Mosaico manifest rows", 312, mosaic.size)
        assertEquals("Editorial poster manifest rows", 312, posters.size)
        assertEquals("Mosaico manifest keys", 312, mosaic.map { it[0] }.toSet().size)
        assertEquals("Editorial poster slugs", 312, posters.map { it[0] }.toSet().size)
        assertTrue(mosaic.all { it.size == 6 })
        assertTrue(posters.all { it.size == 8 })
        assertTrue(mosaic.all { it[5] == "USER_CONFIRMED_COMPLETE" })
        assertTrue(posters.all {
            it[6] == "USER_CONFIRMED_COMPLETE" && it[7] == "POSTER_COMPLETE"
        })
    }

    @Test fun bothOpticalTablesCoverTheSame312AssetsWithMeasuredCorners() {
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
        val mosaicFiles = mosaicDir.listFiles().orEmpty().map { it.name }.toSet()
        val posterFiles = posterDir.listFiles().orEmpty().map { it.name }.toSet()
        val mosaic = mosaicApprovedArtwork(
            text(File(mosaicDir, "manifest.tsv")), mosaicFiles, opticalMasks = mosaicOptical,
        )
        val posters = editorialApprovedPosters(
            text(File(posterDir, "manifest.tsv")), posterFiles, opticalMasks = posterOptical,
        )
        assertEquals(312, mosaic.size)
        assertEquals(312, posters.size)
        assertEquals("every Mosaico row keeps its measured mask", 312,
            mosaic.values.count { it.opticalMask != null })
        assertEquals("every Editorial row keeps its measured mask", 312,
            posters.values.count { it.opticalMask != null })
        // An unverified row stays on the fallback path instead of guessing a frame.
        assertEquals(298, mosaic.values.count { it.opticalMask?.verified == true })
        assertEquals(227, posters.values.count { it.opticalMask?.verified == true })
    }

    @Test fun existing216KeepTheirIdentitiesAndTheNew96AppendAfterThem() {
        val mosaic = rows(File(mosaicDir, "manifest.tsv"))
        val numbers = mosaic.map { it[1].substring(12, 15).toInt() }
        assertEquals((1..312).toList(), numbers)
        // Spot-pin the first/last pre-existing entries so nothing was renumbered.
        assertEquals("UI7_MOSAICO_001_Rock.webp", mosaic.first()[1])
        assertEquals("UI7_MOSAICO_216_JRock.webp", mosaic[215][1])
        assertEquals("UI7_MOSAICO_217_ArtRock.webp", mosaic[216][1])
        assertEquals("UI7_MOSAICO_312_Timba.webp", mosaic.last()[1])
        val posters = rows(File(posterDir, "manifest.tsv"))
        assertEquals("acid_house", posters.first()[0])
        assertEquals("ye_ye", posters[215][0])
        assertEquals(312, posters.map { it[1] }.toSet().size)
    }

    @Test fun everyDeclaredHashMatchesThePackagedAsset() {
        rows(File(mosaicDir, "manifest.tsv")).forEach { row ->
            assertEquals("Mosaico hash for ${row[1]}", row[2], sha256(File(mosaicDir, row[1])))
        }
        rows(File(posterDir, "manifest.tsv")).forEach { row ->
            assertEquals("Poster hash for ${row[1]}", row[2], sha256(File(posterDir, row[1])))
        }
    }

    @Test fun approvedCatalogsLoadAtomicallyFromTheRealAssets() {
        var mosaicRejection: String? = null
        val mosaic = mosaicApprovedArtwork(
            text(File(mosaicDir, "manifest.tsv")),
            mosaicDir.listFiles().orEmpty().map { it.name }.toSet(),
            onRejected = { mosaicRejection = it },
        )
        assertNull("Mosaico rejected: $mosaicRejection", mosaicRejection)
        assertEquals(312, mosaic.size)

        var posterRejection: String? = null
        val posters = editorialApprovedPosters(
            text(File(posterDir, "manifest.tsv")),
            posterDir.listFiles().orEmpty().map { it.name }.toSet(),
            onRejected = { posterRejection = it },
        )
        assertNull("Editorial rejected: $posterRejection", posterRejection)
        assertEquals(312, posters.size)
    }

    @Test fun representativeNewGenresResolveInBothViews() {
        val mosaic = mosaicApprovedArtwork(
            text(File(mosaicDir, "manifest.tsv")),
            mosaicDir.listFiles().orEmpty().map { it.name }.toSet(),
        )
        val posters = editorialApprovedPosters(
            text(File(posterDir, "manifest.tsv")),
            posterDir.listFiles().orEmpty().map { it.name }.toSet(),
        )
        val expected = mapOf(
            "Art Rock" to "UI7_MOSAICO_217_ArtRock.webp",
            "NDW" to "UI7_MOSAICO_218_NDW.webp",
            "Raï" to "UI7_MOSAICO_225_Rai.webp",
            "Qawwali" to "UI7_MOSAICO_241_Qawwali.webp",
            "Russian Rock" to "UI7_MOSAICO_233_RussianRock.webp",
            "Klezmer" to "UI7_MOSAICO_249_Klezmer.webp",
            "Gqom" to "UI7_MOSAICO_262_Gqom.webp",
            "Visual Kei" to "UI7_MOSAICO_275_VisualKei.webp",
            "Chiptune" to "UI7_MOSAICO_281_Chiptune.webp",
            "Chacarera" to "UI7_MOSAICO_290_Chacarera.webp",
            "Modal Jazz" to "UI7_MOSAICO_297_ModalJazz.webp",
            "Guajira Cubana" to "UI7_MOSAICO_305_GuajiraCubana.webp",
            "Timba" to "UI7_MOSAICO_312_Timba.webp",
        )
        expected.forEach { (title, filename) ->
            // Runtime passes the scoped library genre id (diacritic-free grouping key),
            // never the raw metadata label; mirror that contract here.
            val genreId = genreGroupingKey(title)
            assertEquals("Mosaico $title", filename, mosaicArtworkForGenre(genreId, mosaic)?.filename)
            assertEquals(
                "Editorial $title",
                "genre_${editorialArtworkSlug(title)}_editorial.webp",
                editorialPosterForTitle(title, posters)?.filename,
            )
        }
    }

    @Test fun unknownGenresKeepNoArtworkInEitherView() {
        val mosaic = mosaicApprovedArtwork(
            text(File(mosaicDir, "manifest.tsv")),
            mosaicDir.listFiles().orEmpty().map { it.name }.toSet(),
        )
        val posters = editorialApprovedPosters(
            text(File(posterDir, "manifest.tsv")),
            posterDir.listFiles().orEmpty().map { it.name }.toSet(),
        )
        listOf("", "Sin clasificar", "Some Unknown New Style", "R&B/Soul").forEach {
            assertNull("Mosaico invented art for '$it'", mosaicArtworkForGenre(it, mosaic))
        }
        // Editorial is stricter about punctuation: an unapproved slug never borrows art.
        listOf("", "Sin clasificar", "Some Unknown New Style", "Metal!!!", "R&B/Soul").forEach {
            assertNull("Editorial invented art for '$it'", editorialPosterForTitle(it, posters))
        }
    }

    @Test fun multiTagTrackKeepsOneApprovedIdentityPerTag() {
        val mosaic = mosaicApprovedArtwork(
            text(File(mosaicDir, "manifest.tsv")),
            mosaicDir.listFiles().orEmpty().map { it.name }.toSet(),
        )
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

    @Test fun switchingViewsNeverMutatesTheOtherApprovedCatalog() {
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
            assertEquals(312, mosaic.size)
            assertEquals(312, posters.size)
            assertEquals(mosaicRock, mosaicArtworkForGenre("Rock", mosaic)?.filename)
            assertEquals(posterRock, editorialPosterForTitle("Rock", posters)?.filename)
            assertEquals(312, editorialApprovedPosters(posterText, posterFiles).size)
            assertEquals(312, mosaicApprovedArtwork(mosaicText, mosaicFiles).size)
        }
    }
}
