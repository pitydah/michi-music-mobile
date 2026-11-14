package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.VisualPreferences

/**
 * Pixel contract on the cards named by the user: Timba, Rumba Cubana, New Wave,
 * Pop, Rock and Soft Rock.
 *
 * A compatible crop is clipped by its calibrated optical outline, so the four
 * corners outside the baked frame show the dark screen — never an opaque wedge,
 * a cut stroke, a halo or a second border. An incompatible horizontal sheet is
 * never letterboxed inside a vertical or 7:5 slot: it falls back honestly.
 */
@RunWith(AndroidJUnit4::class)
class GenreArtworkPixelCalibrationTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val evidenceEnabled = arguments.getString("michiGenreEvidence") == "true"
    private val receipts = ConcurrentHashMap<String, Boolean>()

    private val mosaicAssets: Map<String, MosaicArtworkAsset> by lazy {
        val files = context.assets.list(mosaicFolder).orEmpty().toSet()
        val text = context.assets.open("$mosaicFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val optical = runCatching {
            context.assets.open("$mosaicFolder/optical.tsv")
                .bufferedReader().use { parseUi7OpticalMasks(it.readText()) }
        }.getOrDefault(emptyMap())
        mosaicApprovedArtwork(text, files, opticalMasks = optical)
    }

    private val posterAssets: Map<String, EditorialPosterAsset> by lazy {
        val files = context.assets.list(editorialPosterFolder).orEmpty().toSet()
        val text = context.assets.open("$editorialPosterFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val optical = runCatching {
            context.assets.open("$editorialPosterFolder/optical.tsv")
                .bufferedReader().use { parseUi7OpticalMasks(it.readText()) }
        }.getOrDefault(emptyMap())
        editorialApprovedPosters(text, files, opticalMasks = optical)
    }

    private fun genre(title: String, count: Int = 3): LibraryGenre = LibraryGenre(
        id = genreGroupingKey(title),
        title = title,
        tracks = (1..count).map { index ->
            Track(
                id = "${genreGroupingKey(title)}_$index",
                title = "$title $index",
                genre = title,
                sourceId = "local",
                filepath = "content://michi/genre/${genreGroupingKey(title)}_$index.flac",
            )
        },
        albumCount = count,
    )

    private val mosaicTitles = listOf("Timba", "Rumba Cubana", "New Wave", "Pop", "Rock", "Soft Rock")
    private val editorialTitles = listOf("New Wave", "Pop", "Rock", "Soft Rock")

    private fun pxOf(dpValue: Float): Float = with(rule.density) { dpValue.dp.toPx() }
    private fun dpOf(pxValue: Float): Float = pxValue / rule.density.density

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float) {
        assertTrue("$message (expected=$expected actual=$actual tolerance=$tolerance)",
            abs(expected - actual) <= tolerance)
    }

    private fun bounds(tag: String, unmerged: Boolean = false): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun ImageBitmap.blockStats(
        leftFraction: Float,
        topFraction: Float,
        rightFraction: Float,
        bottomFraction: Float,
    ): Pair<Double, Int> {
        val pixels = toPixelMap()
        val x0 = (width * leftFraction).toInt().coerceIn(0, width - 1)
        val x1 = (width * rightFraction).toInt().coerceIn(x0 + 1, width)
        val y0 = (height * topFraction).toInt().coerceIn(0, height - 1)
        val y1 = (height * bottomFraction).toInt().coerceIn(y0 + 1, height)
        var sum = 0.0
        var max = 0
        var samples = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val argb = pixels[x, y].toArgb()
                val luma = (0.2126 * (argb shr 16 and 255) + 0.7152 * (argb shr 8 and 255) +
                    0.0722 * (argb and 255)).toInt()
                sum += luma
                if (luma > max) max = luma
                samples++
                x += 2
            }
            y += 2
        }
        return (sum / samples.coerceAtLeast(1)) to max
    }

    private fun ImageBitmap.lumaVariance(): Double {
        val pixels = toPixelMap()
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val argb = pixels[x, y].toArgb()
                val value = 0.2126 * (argb shr 16 and 255) + 0.7152 * (argb shr 8 and 255) +
                    0.0722 * (argb and 255)
                sum += value
                sumSquares += value * value
                count++
                x += 3
            }
            y += 3
        }
        val mean = sum / count
        return sumSquares / count - mean * mean
    }

    private val cornerBlocks = listOf(
        Triple(0f, 0f, "top-left"),
        Triple(0.985f, 0f, "top-right"),
        Triple(0f, 0.97f, "bottom-left"),
        Triple(0.985f, 0.97f, "bottom-right"),
    )

    /** A clipped optical contour leaves the four corner blocks on the dark screen. */
    private fun assertNoCornerWedges(image: ImageBitmap, label: String) {
        cornerBlocks.forEach { (x, y, corner) ->
            val (mean, max) = image.blockStats(x, y, x + 0.014f, y + 0.028f)
            assertTrue("$label $corner corner mean=$mean must be near-black", mean < 40.0)
            assertTrue("$label $corner corner max=$max must not be an opaque wedge/halo",
                max < 90)
        }
    }

    /**
     * Compose fallbacks legitimately draw their one visible border along the
     * rounded contour, so the strict wedge test does not apply: the corner must be
     * dark (no black rectangular block, no bright matte) but may contain that line.
     */
    private fun assertFallbackCornersAreDark(image: ImageBitmap, label: String) {
        cornerBlocks.forEach { (x, y, corner) ->
            val (mean, _) = image.blockStats(x, y, x + 0.014f, y + 0.028f)
            assertTrue("$label $corner corner mean=$mean must be near-black", mean < 40.0)
        }
    }

    private fun setMosaicPanel(widthDp: Int, titles: List<String>) {
        val genres = titles.map { genre(it) }
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                Box(
                    Modifier.width(widthDp.dp).fillMaxHeight()
                        .background(SurfaceObsidian).testTag("calibration_root"),
                ) {
                    GenreMosaicGrid(
                        genres = genres,
                        columns = genreGridColumns(widthDp, GENRE_COMPACT_TILE_MIN_WIDTH_DP),
                        availableWidthDp = widthDp.toFloat(),
                        state = rememberLazyGridState(),
                        artwork = mosaicAssets,
                        onArtworkLoad = { view, id, ok -> receipts["${view.name}:$id"] = ok },
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun compatibleTimbaCropHasOneContourAndNoCornerWedges() {
        setMosaicPanel(411, mosaicTitles)
        rule.waitUntil(20_000) { receipts["FEATURED:timba"] != null }
        assertEquals("Timba must decode", true, receipts["FEATURED:timba"])
        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag("genre_tile_timba"))
        rule.waitForIdle()
        val tile = rule.onNodeWithTag("genre_tile_timba")
        tile.assertIsDisplayed()
        val image = tile.captureToImage()
        assertNoCornerWedges(image, "Timba")
        assertTrue("Timba crop must actually be painted", image.lumaVariance() > 120.0)
        // One title only: the baked title stays in the bitmap.
        assertTrue("Timba must not duplicate its baked title",
            rule.onAllNodesWithText("Timba", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        // Numeric chip: bottom-end, 12dp inside the optical contour, no footer.
        val tileBounds = bounds("genre_tile_timba")
        val chip = bounds("genre_counter_timba", unmerged = true)
        assertNear("chip right", tileBounds.right - pxOf(12f), chip.right, pxOf(1f))
        assertNear("chip bottom", tileBounds.bottom - pxOf(12f), chip.bottom, pxOf(1f))
        assertTrue("chip must not sit in a footer", chip.bottom < tileBounds.bottom - pxOf(4f))
        assertTrue("chip must be inside the card",
            chip.left >= tileBounds.left && chip.top >= tileBounds.top)
        rule.onAllNodes(
            hasText("pistas") and hasAnyAncestor(hasTestTag("genre_tile_timba")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().also {
            assertTrue("the word 'pistas' must never be painted", it.isEmpty())
        }
    }

    @Test fun incompatibleSheetsFallBackWithoutLetterboxingOrPerAssetHeight() {
        setMosaicPanel(411, mosaicTitles)
        val size = mosaicCardSize(411f, 2)
        fun expectedArt(title: String): Boolean {
            val asset = mosaicArtworkForGenre(genreGroupingKey(title), mosaicAssets) ?: return false
            return ui7RenderOutcome(
                mosaicVisualSpec(asset, Ui7Treatment.MOSAIC_GRID),
                Ui7Treatment.MOSAIC_GRID, size.widthDp, size.heightDp,
                asset.ratio, 46f, 28f,
            ) == Ui7RenderOutcome.ASSET
        }
        assertTrue("Timba 1.404 must fill the 7:5 cell", expectedArt("Timba"))
        mosaicTitles.forEach { title ->
            val id = genreGroupingKey(title)
            rule.onNodeWithTag("genres_featured")
                .performScrollToNode(hasTestTag("genre_tile_$id"))
            rule.waitForIdle()
            val tile = bounds("genre_tile_$id")
            assertNear("$title card height must be the treatment height",
                size.heightDp, dpOf(tile.height), 1f)
            assertNear("$title card width", size.widthDp, dpOf(tile.width), 1f)
            val hasArt = rule.onAllNodesWithTag("genre_artwork_$id", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            val hasTitle = rule.onAllNodes(
                hasText(title) and hasAnyAncestor(hasTestTag("genre_tile_$id")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
            // Only crops that fill the 7:5 cell intact may render; the rest are
            // honest fallbacks with exactly one Compose title.
            assertEquals("$title art decision", expectedArt(title), hasArt)
            assertEquals("$title single title", !hasArt, hasTitle)
            assertNear("$title chip bottom-end", tile.bottom - pxOf(12f),
                bounds("genre_counter_$id", unmerged = true).bottom, pxOf(1f))
        }
    }

    @Test fun anUnverifiedOpticalRowFallsBackInsteadOfGuessingAFrame() {
        val timba = mosaicAssets.getValue("timba")
        val unverified = timba.copy(opticalMask = timba.opticalMask?.copy(verified = false))
        assertEquals("fixture must have an optical row", true, timba.opticalMask != null)
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight().background(SurfaceObsidian)) {
                    GenreMosaicGrid(
                        genres = listOf(genre("Timba")),
                        columns = 2,
                        availableWidthDp = 411f,
                        state = rememberLazyGridState(),
                        artwork = mapOf("timba" to unverified),
                        onArtworkLoad = null,
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue("an unmeasured frame must not be rendered",
            rule.onAllNodesWithTag("genre_artwork_timba", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        assertTrue("the fallback keeps the genre visible",
            rule.onAllNodesWithTag("genre_mosaic_fallback_timba", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("genre_tile_timba")
            .assertContentDescriptionEquals("Abrir género Timba, 3 pistas")
    }

    @Test fun editorialFallbacksUseTreatmentHeightsAndNeverFakePortraits() {
        val genres = editorialTitles.map { genre(it) }
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                Box(Modifier.width(411.dp).fillMaxHeight().background(SurfaceObsidian)) {
                    GenreEditorialGrid(
                        genres = genres,
                        columns = 2,
                        availableWidthDp = 411f,
                        state = rememberLazyListState(),
                        posterAssets = posterAssets,
                        onArtworkLoad = { view, id, ok -> receipts["${view.name}:$id"] = ok },
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val size = editorialCardSize(411f, columns = 2, fontScale = 1f)
        data class Expected(val title: String, val height: Float)
        val expected = listOf(
            Expected("New Wave", size.coverHeightDp),
            Expected("Pop", size.portraitHeightDp),
            Expected("Rock", size.portraitHeightDp),
            Expected("Soft Rock", size.coverHeightDp),
        )
        expected.forEach { entry ->
            val id = genreGroupingKey(entry.title)
            rule.onNodeWithTag("genres_editorial")
                .performScrollToNode(hasTestTag("genre_editorial_$id"))
            rule.waitForIdle()
            val tile = rule.onNodeWithTag("genre_editorial_$id")
            tile.assertIsDisplayed()
            val tileBounds = bounds("genre_editorial_$id")
            assertNear("${entry.title} height", entry.height, dpOf(tileBounds.height), 1f)
            // No horizontal sheet is forced into the slot.
            assertTrue("${entry.title} must not paint a horizontal poster",
                rule.onAllNodesWithTag("genre_editorial_poster_$id", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty())
            assertTrue("${entry.title} must expose the fallback tile",
                rule.onAllNodesWithTag("genre_editorial_fallback_$id", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty())
            val image = tile.captureToImage()
            assertFallbackCornersAreDark(image, "${entry.title} fallback")
            val chip = bounds("genre_counter_$id", unmerged = true)
            assertNear("${entry.title} chip bottom-end", tileBounds.bottom - pxOf(12f),
                chip.bottom, pxOf(1f))
            assertNear("${entry.title} chip right-end", tileBounds.right - pxOf(12f),
                chip.right, pxOf(1f))
            val count = genres.first { it.id == id }.tracks.size
            tile.assertContentDescriptionEquals(
                "Abrir género ${entry.title}, $count pistas",
            )
        }
        // Pop and Rock form a real pair: equal height, one row.
        val pop = bounds("genre_editorial_pop")
        val rock = bounds("genre_editorial_rock")
        assertNear("pair shares one top", pop.top, rock.top, pxOf(1f))
        assertNear("pair shares one height", pop.height, rock.height, pxOf(1f))
        assertNear("pair shares one width", pop.width, rock.width, pxOf(1f))
        assertNear("pair keeps the 10dp gap", pxOf(10f), rock.left - pop.right, pxOf(1f))
        // No technical label is ever visible.
        listOf("PENDIENTE", "NO-GO", "vertical").forEach { word ->
            assertTrue("technical text '$word' must not be visible",
                rule.onAllNodesWithText(word, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun counterChipGrowsWithRealDigitsWithoutTruncation() {
        val counts = listOf(1, 5, 99, 100, 1234)
        val genres = counts.mapIndexed { index, count -> genre("Conteo $index", count) }
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight().background(SurfaceObsidian)) {
                    GenreMosaicGrid(
                        genres = genres,
                        columns = 2,
                        availableWidthDp = 411f,
                        state = rememberLazyGridState(),
                        artwork = emptyMap(),
                        onArtworkLoad = null,
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        var previousWidth = 0f
        counts.forEachIndexed { index, count ->
            val title = "Conteo $index"
            val id = genreGroupingKey(title)
            rule.onNodeWithTag("genres_featured")
                .performScrollToNode(hasTestTag("genre_tile_$id"))
            rule.waitForIdle()
            val chip = bounds("genre_counter_$id", unmerged = true)
            val tile = bounds("genre_tile_$id")
            assertTrue("count $count chip must fit inside the card",
                chip.left >= tile.left && chip.right <= tile.right &&
                    chip.top >= tile.top && chip.bottom <= tile.bottom)
            assertNear("count $count chip bottom-end", tile.bottom - pxOf(12f), chip.bottom,
                pxOf(1f))
            // The exact digits are rendered, not an ellipsized/abbreviated figure.
            assertTrue("count $count must be displayed verbatim",
                rule.onAllNodesWithText(count.toString(), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty())
            assertTrue("count $count chip never collapses", chip.width >= pxOf(28f) - pxOf(1f))
            if (count >= 100) {
                assertTrue("wide count ${count} grows the capsule", chip.width > previousWidth)
            }
            previousWidth = chip.width
        }
    }

    // region opt-in evidence (400% corners across widths and font scales)

    private val evidenceFontScale = arguments.getString("michiGenreFontScale")
        ?.toFloatOrNull()?.takeIf { it in 0.8f..2.5f } ?: 1f
    private val evidenceWidths = arguments.getString("michiGenreWidths")
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.filter { it in 280..600 }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(320, 360, 390, 411, 480)
    private val evidenceScaleTag = "fs" + evidenceFontScale.toString().replace(".", "")

    private fun evidenceName(title: String): String =
        genreGroupingKey(title).replace(' ', '_')

    private fun writeBitmap(bitmap: Bitmap, directory: File, name: String) {
        FileOutputStream(File(directory, name)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun writeCornerCrop(
        source: ImageBitmap,
        corner: String,
        directory: File,
        name: String,
    ) {
        val bitmap = source.asAndroidBitmap()
        val cropWidth = maxOf(4, bitmap.width * 15 / 1000)
        val cropHeight = maxOf(4, bitmap.height * 3 / 100)
        val left = if (corner.endsWith("right")) bitmap.width - cropWidth else 0
        val top = if (corner.startsWith("bottom")) bitmap.height - cropHeight else 0
        val crop = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        val scaled = Bitmap.createScaledBitmap(crop, cropWidth * 4, cropHeight * 4, false)
        writeBitmap(scaled, directory, "$name.png")
    }

    @Test fun captureContractCardsAtPhoneWidths() {
        assumeTrue("Visual evidence is opt-in: -e michiGenreEvidence true", evidenceEnabled)
        val directory = File(context.getExternalFilesDir(null), "ui7-contract-visual")
            .apply { mkdirs() }
        val screenWidthPx = context.resources.displayMetrics.widthPixels
        val widthDp = mutableStateOf(411)
        val editorialMode = mutableStateOf(false)
        val mosaicGenres = mosaicTitles.map { genre(it) }
        val editorialGenres = editorialTitles.map { genre(it) }
        val boundsLines = mutableListOf<String>()

        rule.setContent {
            val width = widthDp.value
            val configuration = remember(width) {
                Configuration(context.resources.configuration).apply {
                    screenWidthDp = width
                    fontScale = evidenceFontScale
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(
                    density = screenWidthPx.toFloat() / width,
                    fontScale = evidenceFontScale,
                ),
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                    Box(Modifier.fillMaxSize().background(SurfaceObsidian).testTag("visual_root")) {
                        val columns = rememberGenreGridColumns(width.toFloat())
                        if (editorialMode.value) {
                            GenreEditorialGrid(
                                genres = editorialGenres,
                                columns = columns,
                                availableWidthDp = width.toFloat(),
                                state = rememberLazyListState(),
                                posterAssets = posterAssets,
                                onArtworkLoad = null,
                                onOpen = {},
                            )
                        } else {
                            GenreMosaicGrid(
                                genres = mosaicGenres,
                                columns = columns,
                                availableWidthDp = width.toFloat(),
                                state = rememberLazyGridState(),
                                artwork = mosaicAssets,
                                onArtworkLoad = null,
                                onOpen = {},
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        evidenceWidths.forEach { width ->
            fun dp(px: Float): Float = px / (screenWidthPx.toFloat() / width)
            rule.runOnIdle { widthDp.value = width }
            rule.waitForIdle()

            rule.runOnIdle { editorialMode.value = false }
            rule.waitForIdle()
            mosaicTitles.forEach { title ->
                val id = genreGroupingKey(title)
                rule.onNodeWithTag("genres_featured")
                    .performScrollToNode(hasTestTag("genre_tile_$id"))
                rule.waitForIdle()
                val image = rule.onNodeWithTag("genre_tile_$id").captureToImage()
                writeBitmap(image.asAndroidBitmap(), directory,
                    "mosaico-${evidenceName(title)}-${width}dp-$evidenceScaleTag.png")
                listOf("top-left", "top-right", "bottom-left", "bottom-right").forEach { corner ->
                    writeCornerCrop(image, corner, directory,
                        "mosaico-${evidenceName(title)}-$corner-400pct-${width}dp-$evidenceScaleTag")
                }
                val rect = bounds("genre_tile_$id")
                val asset = mosaicArtworkForGenre(id, mosaicAssets)
                boundsLines += "mosaico width=${width}dp fontScale=$evidenceFontScale " +
                    "title=$title size=${dp(rect.width)}x${dp(rect.height)}dp " +
                    "asset=${asset?.filename} ratio=${asset?.ratio} " +
                    "art=${rule.onAllNodesWithTag("genre_artwork_$id", useUnmergedTree = true)
                        .fetchSemanticsNodes().size} " +
                    "counter=${bounds("genre_counter_$id", unmerged = true)}"
            }

            rule.runOnIdle { editorialMode.value = true }
            rule.waitForIdle()
            editorialTitles.forEach { title ->
                val id = genreGroupingKey(title)
                rule.onNodeWithTag("genres_editorial")
                    .performScrollToNode(hasTestTag("genre_editorial_$id"))
                rule.waitForIdle()
                val image = rule.onNodeWithTag("genre_editorial_$id").captureToImage()
                writeBitmap(image.asAndroidBitmap(), directory,
                    "editorial-${evidenceName(title)}-${width}dp-$evidenceScaleTag.png")
                listOf("top-left", "top-right", "bottom-left", "bottom-right").forEach { corner ->
                    writeCornerCrop(image, corner, directory,
                        "editorial-${evidenceName(title)}-$corner-400pct-${width}dp-$evidenceScaleTag")
                }
                val rect = bounds("genre_editorial_$id")
                boundsLines += "editorial width=${width}dp fontScale=$evidenceFontScale " +
                    "title=$title size=${dp(rect.width)}x${dp(rect.height)}dp " +
                    "screenRatio=${screenWidthPx.toFloat() / rect.width} " +
                    "poster=${rule.onAllNodesWithTag("genre_editorial_poster_$id",
                        useUnmergedTree = true).fetchSemanticsNodes().size} " +
                    "counter=${bounds("genre_counter_$id", unmerged = true)}"
            }
        }

        File(directory, "bounds-$evidenceScaleTag.txt").writeText(
            boundsLines.joinToString("\n") + "\nfontScale=$evidenceFontScale widths=$evidenceWidths\n",
        )
        assertEquals("every calibration card must produce a bounds line",
            evidenceWidths.size * (mosaicTitles.size + editorialTitles.size), boundsLines.size)
    }

    // endregion
}
