package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Pixel evidence for the card refinement, on the calibration cards the user
 * named: New Wave / Pop / Rock (Mosaico) and New Wave / Pop / Rock / Soft Rock
 * (Editorial).
 *
 * The assertions are about *rendered pixels plus measured bounds*, not node
 * existence: 400% corner crops of a mounted Editorial card must stay near-black
 * (no black wedge outside the baked frame, no bright cut, no halo, no second
 * border), the count chip must sit inside the artwork/card without touching the
 * baked title band, and the artwork region itself must be painted instead of a
 * black background.
 */
@RunWith(AndroidJUnit4::class)
class GenreArtworkPixelCalibrationTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val receipts = ConcurrentHashMap<String, Boolean>()
    private var clicked = false

    private val mosaicAssets: Map<String, MosaicArtworkAsset> by lazy {
        val files = context.assets.list(mosaicFolder).orEmpty().toSet()
        val text = context.assets.open("$mosaicFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        mosaicApprovedArtwork(text, files)
    }

    private val posterAssets: Map<String, EditorialPosterAsset> by lazy {
        val files = context.assets.list(editorialPosterFolder).orEmpty().toSet()
        val text = context.assets.open("$editorialPosterFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        editorialApprovedPosters(text, files)
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

    private val calibrationTitles = listOf("New Wave", "Pop", "Rock")
    private val editorialTitles = listOf("New Wave", "Pop", "Rock", "Soft Rock")
    private val editorialTreatments = mapOf(
        "New Wave" to GenreEditorialTreatment.COVER,
        "Pop" to GenreEditorialTreatment.PORTRAIT_LEFT,
        "Rock" to GenreEditorialTreatment.PORTRAIT_RIGHT,
        "Soft Rock" to GenreEditorialTreatment.COVER,
    )

    private fun pxOf(dpValue: Float): Float = with(rule.density) { dpValue.dp.toPx() }
    private fun dpOf(pxValue: Float): Float = pxValue / rule.density.density

    private fun bounds(tag: String, unmerged: Boolean = false): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    /** Mean and max luminance of a fractional block of a captured card. */
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

    /** Luminance variance: ~0 for a flat/black card, large for painted artwork. */
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

    private fun waitForReceipt(view: GenreViewMode, id: String) {
        rule.waitUntil(20_000) { receipts["${view.name}:$id"] != null }
        assertEquals("${view.name}:$id failed to decode", true, receipts["${view.name}:$id"])
    }

    private fun setCalibrationPanel() {
        val genres = calibrationTitles.map { genre(it) }
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()
                    .background(SurfaceObsidian).testTag("calibration_root")) {
                    GenreMosaicGrid(
                        genres = genres,
                        columns = 2,
                        state = rememberLazyGridState(),
                        artwork = mosaicAssets,
                        onArtworkLoad = { view, id, ok -> receipts["${view.name}:$id"] = ok },
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        calibrationTitles.forEach { waitForReceipt(GenreViewMode.FEATURED, genreGroupingKey(it)) }
    }

    @Test fun mosaicCalibrationCardsPaintArtworkWithNoBlackSurfaceBands() {
        setCalibrationPanel()
        // The focal card is a full-bleed treated card; the compact cards below it
        // keep a reserved count surface. Check the compact cards' reserved band.
        calibrationTitles.drop(1).forEach { title ->
            val id = genreGroupingKey(title)
            val card = rule.onNodeWithTag("genre_tile_$id")
            card.assertIsDisplayed()
            val image = card.captureToImage()
            // Painted artwork, not a black/empty background.
            assertTrue("$title artwork must be painted, variance=${image.lumaVariance()}",
                image.lumaVariance() > 150.0)
            // The card's own continuous surface under the crop band is near-black
            // and flat: no bright separator, no second border, no halo.
            val (panelMean, panelMax) = image.blockStats(0f, 0.80f, 0.15f, 1f)
            assertTrue("$title count surface mean=$panelMean must be near-black",
                panelMean < 45.0)
            assertTrue("$title count surface max=$panelMax must have no bright ink",
                panelMax < 110)
        }
    }

    @Test fun mosaicCounterSitsInsideTheArtworkAndClearOfTheBakedTitle() {
        setCalibrationPanel()
        calibrationTitles.forEach { title ->
            val id = genreGroupingKey(title)
            val card = bounds("genre_tile_$id")
            val counter = bounds("genre_counter_$id", unmerged = true)
            val asset = mosaicArtworkForGenre(id, mosaicAssets)!!
            val art = genreArtworkFitRect(dpOf(card.width), dpOf(card.height), asset.ratio)!!
            if (title == calibrationTitles.first()) {
                // Focal card: 12dp inside the *artwork* border, top-right anchor.
                assertTrue("$title counter must be 12dp inside the artwork right edge: " +
                    "counter=$counter cardRight=${card.right} artRight=${art.right}",
                    abs(card.left + pxOf(art.right - GENRE_COUNTER_INSET_DP) - counter.right) <=
                        pxOf(1.5f))
                assertTrue("$title counter must be 12dp from the artwork top",
                    abs(card.top + pxOf(art.top + GENRE_COUNTER_INSET_DP) - counter.top) <=
                        pxOf(1.5f))
                // The baked title lives bottom-left in the approved crops.
                val titleTop = card.top + pxOf(art.top + art.height * 0.72f)
                assertTrue("$title counter must not cover the baked title: " +
                    "counter=$counter titleRegionTop=$titleTop",
                    counter.bottom < titleTop)
            } else {
                // Compact card: the chip sits on the continuous surface, 12dp
                // from the card's optical border, below the crop band.
                assertTrue("$title counter must be 12dp from the card right",
                    abs(card.right - pxOf(GENRE_COUNTER_INSET_DP.toFloat()) - counter.right) <= pxOf(1.5f))
                assertTrue("$title counter must be 12dp from the card bottom",
                    abs(card.bottom - pxOf(GENRE_COUNTER_INSET_DP.toFloat()) - counter.bottom) <=
                        pxOf(1.5f))
                val artBand = bounds("genre_artwork_$id", unmerged = true)
                assertTrue("$title counter must be 8dp clear of the crop band",
                    counter.top >= artBand.bottom + pxOf(GENRE_COUNTER_BREATHING_DP.toFloat()) - pxOf(1f))
                // The baked title is inside that band, so the chip is clear.
                assertTrue("$title counter must stay below the artwork",
                    counter.top >= artBand.bottom)
            }
            // No duplicate Compose title: the crop already carries it.
            rule.onNode(
                hasText(title) and hasAnyAncestor(hasTestTag("genre_tile_$id")),
                useUnmergedTree = true,
            ).assertDoesNotExist()
        }
    }

    @Test fun editorialCalibrationCardsMountThePosterWithoutCornerWedges() {
        val genres = editorialTitles.map { genre(it) }
        rule.setContent {
            MichiTheme {
                Column(Modifier.width(411.dp).background(SurfaceObsidian)) {
                    genres.forEach { item ->
                        Box(Modifier.width(411.dp)) {
                            GenreEditorialPosterTile(
                                genre = item,
                                poster = editorialPosterForTitle(item.title, posterAssets)!!,
                                treatment = editorialTreatments.getValue(item.title),
                                columns = 2,
                                onArtworkLoad = { view, id, ok ->
                                    receipts["${view.name}:$id"] = ok
                                },
                                onOpen = {},
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        editorialTitles.forEach { waitForReceipt(GenreViewMode.EDITORIAL, genreGroupingKey(it)) }

        genres.forEach { item ->
            val id = genreGroupingKey(item.title)
            val treatment = editorialTreatments.getValue(item.title)
            val card = bounds("genre_editorial_$id")
            assertEquals("${item.title} treatment height",
                genreEditorialHeightDp(treatment, 2, 1f).toFloat(), dpOf(card.height), 1f)

            val image = rule.onNodeWithTag("genre_editorial_$id").captureToImage()
            // The poster is mounted 8dp inside the card, so the 400% outer corner
            // crops are the card's own surface: no black wedge outside the frame,
            // no bright cut of the frame, no halo and no second border.
            listOf(
                Triple(0f, 0f, "top-left"),
                Triple(0.985f, 0f, "top-right"),
                Triple(0f, 0.97f, "bottom-left"),
                Triple(0.985f, 0.97f, "bottom-right"),
            ).forEach { (x, y, corner) ->
                val (mean, max) = image.blockStats(x, y, x + 0.015f, y + 0.03f)
                assertTrue("${item.title} $corner corner mean=$mean must be near-black",
                    mean < 35.0)
                assertTrue("${item.title} $corner corner max=$max must not be a bright " +
                    "wedge/halo/cut", max < 80)
            }
            // The poster band is painted, not a black/empty background.
            assertTrue("${item.title} poster must be painted",
                image.lumaVariance() > 150.0)
            // One title only: the approved poster owns it.
            rule.onNode(
                hasText(item.title) and hasAnyAncestor(hasTestTag("genre_editorial_$id")),
                useUnmergedTree = true,
            ).assertDoesNotExist()
        }
    }

    @Test fun editorialPairCardsShareOneExteriorHeightAndStayAligned() {
        val genres = editorialTitles.map { genre(it) }
        rule.setContent {
            MichiTheme {
                Column(Modifier.width(411.dp).background(SurfaceObsidian)) {
                    genres.forEach { item ->
                        Box(Modifier.width(411.dp)) {
                            GenreEditorialPosterTile(
                                genre = item,
                                poster = editorialPosterForTitle(item.title, posterAssets)!!,
                                treatment = editorialTreatments.getValue(item.title),
                                columns = 2,
                                onOpen = {},
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val pop = bounds("genre_editorial_pop")
        val rock = bounds("genre_editorial_rock")
        // Pop 514x268 and Rock 1027x280: the old ratio-driven cells were 88.6dp
        // and 46.3dp; both must now be the 246dp portrait treatment.
        assertEquals(246f, dpOf(pop.height), 1f)
        assertEquals(246f, dpOf(rock.height), 1f)
        assertEquals(dpOf(pop.height), dpOf(rock.height), 0.5f)
    }

    @Test fun fallbackCardsKeepOneAccessibleTargetAndOneComposeTitle() {
        val only = genre("Sin clasificar", count = 1)
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(350.dp).fillMaxHeight()) {
                    GenreMosaicGrid(
                        genres = listOf(only),
                        columns = 2,
                        state = rememberLazyGridState(),
                        artwork = emptyMap(),
                        onArtworkLoad = null,
                        onOpen = { clicked = true },
                    )
                }
            }
        }
        rule.waitForIdle()
        val card = rule.onNodeWithTag("genre_tile_${only.id}")
        card.assertIsDisplayed()
        // Singular form, real name and one merged target.
        card.assertContentDescriptionEquals("Abrir género Sin clasificar, 1 pista")
        // The fallback draws one live title because the artwork provably has none.
        rule.onNodeWithText("Sin clasificar").assertIsDisplayed()
        rule.onNodeWithTag("genre_counter_${only.id}", useUnmergedTree = true)
            .assertIsDisplayed()
        card.performClick()
        assertTrue("fallback card must stay clickable", clicked)
    }

    @Test fun editorialFallbackKeepsADrawnCardAndOneTitle() {
        val only = genre("Ambient", count = 2)
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(350.dp).fillMaxHeight()) {
                    GenreEditorialGrid(
                        genres = listOf(only),
                        columns = 2,
                        state = rememberLazyListState(),
                        posterAssets = emptyMap(),
                        productionAssets = emptySet(),
                        onArtworkLoad = null,
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("genre_editorial_${only.id}").assertIsDisplayed()
        rule.onNodeWithText("Ambient").assertIsDisplayed()
    }

    // region card-refinement evidence (7 calibration cards, widths x font scales)

    private val arguments = InstrumentationRegistry.getArguments()
    private val evidenceEnabled = arguments.getString("michiGenreEvidence") == "true"
    private val evidenceFontScale = arguments.getString("michiGenreFontScale")
        ?.toFloatOrNull()?.takeIf { it in 0.8f..2.5f } ?: 1f
    private val evidenceWidths = arguments.getString("michiGenreWidths")
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.filter { it in 280..600 }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(320, 360, 390, 411, 480)
    private val evidenceScaleTag =
        "fs" + evidenceFontScale.toString().replace(".", "")

    private fun evidenceName(title: String): String = genreGroupingKey(title).replace(' ', '_')

    private fun writeBitmap(bitmap: Bitmap, directory: File, name: String) {
        FileOutputStream(File(directory, name)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun captureInto(tag: String, directory: File, name: String) {
        val image = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        writeBitmap(image, directory, "$name.png")
        println("CARDS_EVIDENCE $name ${image.width}x${image.height}")
    }

    /**
     * 400% nearest-neighbour crop of one 1.5% x 3% card corner.
     *
     * At 4x the approved frame edge, any black wedge outside the mount, bright
     * cut, offset border or halo becomes visible instead of sub-pixel noise.
     */
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

    @Test fun captureCalibrationCardsAtPhoneWidths() {
        assumeTrue("Visual evidence is opt-in: -e michiGenreEvidence true", evidenceEnabled)
        val directory = File(context.getExternalFilesDir(null), "ui7-cards-evidence")
            .apply { mkdirs() }
        val screenWidthPx = context.resources.displayMetrics.widthPixels
        val widthDp = mutableStateOf(411)
        val editorialMode = mutableStateOf(false)
        val mosaicGenres = calibrationTitles.map { genre(it) }
        val posterGenres = editorialTitles.map { genre(it) }
        val boundsLines = mutableListOf<String>()

        rule.setContent {
            val width = widthDp.value
            val configuration = remember(width) {
                Configuration(context.resources.configuration).apply {
                    screenWidthDp = width
                    fontScale = evidenceFontScale
                }
            }
            // Same simulated-density harness as the production panel gate: the
            // requested width owns the real screen pixels, so 480dp is measurable.
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(
                    density = screenWidthPx.toFloat() / width,
                    fontScale = evidenceFontScale,
                ),
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                    Box(
                        Modifier.fillMaxSize().background(SurfaceObsidian)
                            .testTag("cards_evidence_root"),
                    ) {
                        // The panel is exactly `width` dp wide, so the production
                        // column policy is evaluated from that width directly instead
                        // of a BoxWithConstraints subcomposition that can still hold
                        // the previous width's constraints on the first frame after a
                        // width change.
                        val columns = rememberGenreGridColumns(width.toFloat())
                        if (editorialMode.value) {
                            GenreEditorialGrid(
                                genres = posterGenres,
                                columns = columns,
                                state = rememberLazyListState(),
                                posterAssets = posterAssets,
                                productionAssets = emptySet(),
                                onArtworkLoad = { view, id, ok ->
                                    receipts["${view.name}:$id"] = ok
                                },
                                onOpen = {},
                            )
                        } else {
                            GenreMosaicGrid(
                                genres = mosaicGenres,
                                columns = columns,
                                state = rememberLazyGridState(),
                                artwork = mosaicAssets,
                                onArtworkLoad = { view, id, ok ->
                                    receipts["${view.name}:$id"] = ok
                                },
                                onOpen = {},
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        evidenceWidths.forEach { width ->
            val density = screenWidthPx.toFloat() / width
            fun dp(px: Float): Float = px / density
            rule.runOnIdle { widthDp.value = width }
            rule.waitForIdle()

            // Mosaico: the focal New Wave card plus the Pop / Rock secondaries.
            rule.runOnIdle { editorialMode.value = false }
            rule.waitForIdle()
            mosaicGenres.forEach { waitForReceipt(GenreViewMode.FEATURED, it.id) }
            rule.onNodeWithTag("genres_featured")
                .performScrollToNode(hasTestTag("genre_tile_${mosaicGenres.first().id}"))
            rule.waitForIdle()
            captureInto("cards_evidence_root", directory,
                "panel-mosaico-${width}dp-$evidenceScaleTag")
            calibrationTitles.forEach { title ->
                val id = genreGroupingKey(title)
                val tag = "genre_tile_$id"
                // Each card is anchored at the viewport top so no capture is a
                // partially clipped lazy item.
                rule.onNodeWithTag("genres_featured")
                    .performScrollToNode(hasTestTag(tag))
                rule.waitForIdle()
                rule.onNodeWithTag(tag).assertIsDisplayed()
                captureInto(tag, directory,
                    "mosaico-${evidenceName(title)}-${width}dp-$evidenceScaleTag")
                val rect = bounds(tag)
                val asset = mosaicArtworkForGenre(id, mosaicAssets)!!
                // The focal card is full-bleed: its artwork box is the computed Fit
                // rect, while the compact cards expose a real `genre_artwork` node.
                val artInfo = if (title == calibrationTitles.first()) {
                    "artFit=${genreArtworkFitRect(dp(rect.width), dp(rect.height), asset.ratio)}"
                } else {
                    "art=${bounds("genre_artwork_$id", unmerged = true)}"
                }
                boundsLines += "mosaico width=${width}dp fontScale=$evidenceFontScale " +
                    "title=$title id=$id rect=${rect.left},${rect.top},${rect.right},${rect.bottom} " +
                    "size=${dp(rect.width)}x${dp(rect.height)}dp assetRatio=${asset.ratio} " +
                    "$artInfo " +
                    "counter=${bounds("genre_counter_$id", unmerged = true)}"
            }

            // Editorial: COVER (New Wave), the Pop/Rock pair and the trailing
            // COVER (Soft Rock). No production vertical asset exists yet.
            rule.runOnIdle { editorialMode.value = true }
            rule.waitForIdle()
            posterGenres.forEach { waitForReceipt(GenreViewMode.EDITORIAL, it.id) }
            rule.onNodeWithTag("genres_editorial")
                .performScrollToNode(
                    hasTestTag("genre_editorial_${posterGenres.first().id}"),
                )
            rule.waitForIdle()
            captureInto("cards_evidence_root", directory,
                "panel-editorial-${width}dp-$evidenceScaleTag")
            editorialTitles.forEach { title ->
                val id = genreGroupingKey(title)
                val tag = "genre_editorial_$id"
                rule.onNodeWithTag("genres_editorial")
                    .performScrollToNode(hasTestTag(tag))
                rule.waitForIdle()
                rule.onNodeWithTag(tag).assertIsDisplayed()
                val image = rule.onNodeWithTag(tag).captureToImage()
                writeBitmap(image.asAndroidBitmap(), directory,
                    "editorial-${evidenceName(title)}-${width}dp-$evidenceScaleTag.png")
                listOf("top-left", "top-right", "bottom-left", "bottom-right").forEach { corner ->
                    writeCornerCrop(
                        image, corner, directory,
                        "editorial-${evidenceName(title)}-$corner-400pct-" +
                            "${width}dp-$evidenceScaleTag",
                    )
                }
                val rect = bounds(tag)
                boundsLines += "editorial width=${width}dp fontScale=$evidenceFontScale " +
                    "title=$title id=$id treatment=${editorialTreatments.getValue(title)} " +
                    "rect=${rect.left},${rect.top},${rect.right},${rect.bottom} " +
                    "size=${dp(rect.width)}x${dp(rect.height)}dp " +
                    "poster=${bounds(editorialPosterTag(title), unmerged = true)} " +
                    "counter=${bounds("genre_counter_$id", unmerged = true)} " +
                    "pendingVertical=${rule.onAllNodesWithTag(
                        "genre_editorial_pending_vertical_$id", useUnmergedTree = true,
                    ).fetchSemanticsNodes().size}"
            }
        }

        File(directory, "bounds-$evidenceScaleTag.txt")
            .writeText(boundsLines.joinToString("\n") + "\n")
        assertEquals("every calibration card must produce a bounds line",
            evidenceWidths.size * (calibrationTitles.size + editorialTitles.size),
            boundsLines.size)
    }

    // endregion
}
