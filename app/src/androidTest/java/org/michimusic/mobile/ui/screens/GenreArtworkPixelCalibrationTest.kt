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
import org.junit.Assert.assertFalse
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
 * Pixel contract (rev05) on the cards named by the user: Timba, Rumba Cubana,
 * New Wave, Pop, Rock and Soft Rock.
 *
 * Availability and quality are independent: a packaged, decodable crop paints in
 * its homogeneous 1.42 cell even when its ratio leaves a residual band, its
 * optical row is `verified=0` (provisional contour) or its chip region is not
 * certified. Editorial derives a banner, a proportional pair or a compact single
 * from each real poster ratio; no horizontal sheet becomes a fake portrait.
 * One frame, one baked title and a numeric chip inside the painted ArtRect.
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
    private val editorialTitles =
        listOf("New Wave", "Pop", "Rock", "Soft Rock", "NDW", "Timba")

    /**
     * The pixel contracts were written against a 411 dp reference container. A narrower
     * device measures that container at the screen width, so both the composed width and
     * every derived expectation must use the width the grid really receives.
     */
    private val referenceWidthDp: Int = minOf(
        411,
        InstrumentationRegistry.getInstrumentation().targetContext
            .resources.configuration.screenWidthDp,
    )

    private fun pxOf(dpValue: Float): Float = with(rule.density) { dpValue.dp.toPx() }
    private fun dpOf(pxValue: Float): Float = pxValue / rule.density.density

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float) {
        assertTrue("$message (expected=$expected actual=$actual tolerance=$tolerance)",
            abs(expected - actual) <= tolerance)
    }

    private fun bounds(tag: String, unmerged: Boolean = false): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun hasTag(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun composeHasTitle(tileTag: String, title: String): Boolean =
        rule.onAllNodes(
            hasText(title) and hasAnyAncestor(hasTestTag(tileTag)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

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

    /** A verified optical contour leaves the four corner blocks on the dark screen. */
    private fun assertNoCornerWedges(image: ImageBitmap, label: String) {
        cornerBlocks.forEach { (x, y, corner) ->
            val (mean, max) = image.blockStats(x, y, x + 0.014f, y + 0.028f)
            assertTrue("$label $corner corner mean=$mean must be near-black", mean < 40.0)
            assertTrue("$label $corner corner max=$max must not be an opaque wedge/halo",
                max < 90)
        }
    }

    /**
     * A fallback tile and a provisional (unverified) clip legitimately draw their
     * one visible border along the rounded contour, so the corner must be dark
     * (no rectangular block, no bright matte) but may contain that line.
     */
    private fun assertCornersAreDark(image: ImageBitmap, label: String) {
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

    /** The chip is anchored 12dp inside the *painted* ArtRect, never a footer. */
    private fun assertChipInsideArtwork(tileTag: String, frameTag: String, chipTag: String,
                                        label: String) {
        val tile = bounds(tileTag)
        val frame = bounds(frameTag, unmerged = true)
        val chip = bounds(chipTag, unmerged = true)
        assertNear("$label chip right edge", frame.right - pxOf(12f), chip.right, pxOf(1.5f))
        assertNear("$label chip bottom edge", frame.bottom - pxOf(12f), chip.bottom, pxOf(1.5f))
        assertTrue("$label chip must stay inside the ArtRect: chip=$chip frame=$frame",
            chip.left >= frame.left && chip.top >= frame.top)
        assertTrue("$label chip must stay inside the cell: chip=$chip tile=$tile",
            chip.right <= tile.right + pxOf(0.5f) && chip.bottom <= tile.bottom + pxOf(0.5f))
    }

    @Test fun verifiedTimbaCropHasOneContourAndNoCornerWedges() {
        setMosaicPanel(referenceWidthDp, mosaicTitles)
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

        // The frame is the painted ArtRect, centred inside the homogeneous cell.
        val tileBounds = bounds("genre_tile_timba")
        val frame = bounds("genre_art_frame_timba", unmerged = true)
        val asset = mosaicArtworkForGenre("timba", mosaicAssets)!!
        assertNear("frame ratio follows the source", asset.ratio, frame.width / frame.height, 0.02f)
        assertNear("frame is horizontally centred",
            (tileBounds.width - frame.width) / 2f, frame.left - tileBounds.left, pxOf(1f))
        assertNear("frame is vertically centred",
            (tileBounds.height - frame.height) / 2f, frame.top - tileBounds.top, pxOf(1f))

        // Numeric chip: bottom-end inside the optical contour, no footer.
        assertChipInsideArtwork("genre_tile_timba", "genre_art_frame_timba",
            "genre_counter_timba", "Timba")
        assertTrue("chip must not sit in a footer",
            bounds("genre_counter_timba", unmerged = true).bottom <
                tileBounds.bottom - pxOf(4f))
        rule.onAllNodes(
            hasText("pistas") and hasAnyAncestor(hasTestTag("genre_tile_timba")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().also {
            assertTrue("the word 'pistas' must never be painted", it.isEmpty())
        }
    }

    @Test fun everyPackagedCropPaintsInTheHomogeneousCell() {
        setMosaicPanel(referenceWidthDp, mosaicTitles)
        val size = mosaicCardSize(referenceWidthDp.toFloat(), 2)
        mosaicTitles.forEach { title ->
            val id = genreGroupingKey(title)
            rule.onNodeWithTag("genres_featured")
                .performScrollToNode(hasTestTag("genre_tile_$id"))
            rule.waitForIdle()
            rule.waitUntil(20_000) { receipts["FEATURED:$id"] != null }
            assertEquals("$title must decode", true, receipts["FEATURED:$id"])

            val tile = bounds("genre_tile_$id")
            assertNear("$title card width", size.widthDp, dpOf(tile.width), 1f)
            assertNear("$title card height", size.heightDp, dpOf(tile.height), 1f)
            assertTrue("$title must paint its approved crop", hasTag("genre_artwork_$id"))
            assertFalse("$title must not fall back", hasTag("genre_mosaic_fallback_$id"))
            assertEquals("$title must not duplicate its baked title", false,
                composeHasTitle("genre_tile_$id", title))
            assertChipInsideArtwork("genre_tile_$id", "genre_art_frame_$id",
                "genre_counter_$id", title)

            val asset = mosaicArtworkForGenre(id, mosaicAssets)!!
            val decision = ui7ArtworkDecision(
                mosaicVisualSpec(asset)!!, size.widthDp, size.heightDp, 26f, 26f,
            )
            assertEquals("$title must be available", true, decision.paintsArtwork)
            assertNear("$title frame keeps the source ratio", asset.ratio,
                bounds("genre_art_frame_$id", unmerged = true).let { it.width / it.height },
                0.02f)
        }
        // The two named corners stay clean on a verified crop; unverified rows are
        // exercised by the dedicated provisional-contour test.
        listOf("Timba", "Rumba Cubana").forEach { title ->
            val id = genreGroupingKey(title)
            rule.onNodeWithTag("genres_featured")
                .performScrollToNode(hasTestTag("genre_tile_$id"))
            rule.waitForIdle()
            assertNoCornerWedges(rule.onNodeWithTag("genre_tile_$id").captureToImage(), title)
        }
    }

    /**
     * `verified=0` is QA debt, never an image veto: the crop paints with the
     * conservative 2px/5% provisional contour and the card records mask_pending.
     */
    @Test fun unverifiedOpticalRowPaintsWithTheProvisionalContour() {
        val timba = mosaicAssets.getValue("timba")
        val unverified = timba.copy(opticalMask = timba.opticalMask?.copy(verified = false))
        assertEquals("fixture must have an optical row", true, timba.opticalMask != null)
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(referenceWidthDp.dp).fillMaxHeight().background(SurfaceObsidian)) {
                    GenreMosaicGrid(
                        genres = listOf(genre("Timba")),
                        columns = 2,
                        availableWidthDp = referenceWidthDp.toFloat(),
                        state = rememberLazyGridState(),
                        artwork = mapOf("timba" to unverified),
                        onArtworkLoad = { view, id, ok -> receipts["${view.name}:$id"] = ok },
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(20_000) { receipts["FEATURED:timba"] != null }
        assertEquals("an unmeasured frame must not hide the WebP", true,
            receipts["FEATURED:timba"])
        assertTrue("the crop must be composed", hasTag("genre_artwork_timba"))
        assertFalse("no generic card may replace the crop",
            hasTag("genre_mosaic_fallback_timba"))
        assertCornersAreDark(rule.onNodeWithTag("genre_tile_timba").captureToImage(),
            "Timba provisional")

        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag("genre_tile_timba"))
        rule.waitForIdle()
        val spec = mosaicVisualSpec(unverified)!!
        assertFalse(spec.hasVerifiedContour)
        assertFalse("the provisional clip is not certified", spec.clipMask.verified)
        assertEquals(2f, spec.clipMask.leftInsetPx)
        val size = mosaicCardSize(referenceWidthDp.toFloat(), 2)
        val decision = ui7ArtworkDecision(spec, size.widthDp, size.heightDp, 26f, 26f)
        assertTrue(decision.paintsArtwork)
        assertTrue(decision.quality.opticalMaskUnverified)
        assertChipInsideArtwork("genre_tile_timba", "genre_art_frame_timba",
            "genre_counter_timba", "Timba provisional")
        rule.onNodeWithTag("genre_tile_timba")
            .assertContentDescriptionEquals("Abrir género Timba, 3 pistas")
    }

    @Test fun editorialIsRatioDrivenAndNeverFakesAPortrait() {
        val genres = editorialTitles.map { genre(it) }
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                Box(Modifier.width(referenceWidthDp.dp).fillMaxHeight().background(SurfaceObsidian)) {
                    GenreEditorialGrid(
                        genres = genres,
                        columns = 2,
                        availableWidthDp = referenceWidthDp.toFloat(),
                        state = rememberLazyListState(),
                        posterAssets = posterAssets,
                        onArtworkLoad = { view, id, ok -> receipts["${view.name}:$id"] = ok },
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()

        val plan = editorialPlan(genres, posterAssets, referenceWidthDp.toFloat(), 2, 1f)
        // New Wave banner, Pop compact single (Rock is a banner), Rock banner,
        // Soft Rock+NDW pair, Timba compact single.
        assertEquals(
            listOf(EditorialRowKind.BANNER, EditorialRowKind.SINGLE_COMPACT,
                EditorialRowKind.BANNER, EditorialRowKind.PAIR,
                EditorialRowKind.SINGLE_COMPACT),
            plan.map { it.kind },
        )
        val usable = editorialUsableWidthDp(referenceWidthDp.toFloat())
        plan.forEach { row ->
            when (row) {
                is EditorialRow.Full -> {
                    val title = row.piece.genre.title
                    rule.onNodeWithTag("genres_editorial")
                        .performScrollToNode(hasTestTag(editorialTileTag(title)))
                    rule.waitForIdle()
                    rule.waitUntil(20_000) {
                        receipts["EDITORIAL:${genreGroupingKey(title)}"] != null
                    }
                    val tile = bounds(editorialTileTag(title))
                    assertNear("$title full width", usable, dpOf(tile.width), 1f)
                    assertNear("$title height follows its real ratio",
                        row.piece.heightDp, dpOf(tile.height), 1f)
                    assertTrue("$title must paint its poster",
                        hasTag(editorialPosterTag(title)))
                    assertFalse("$title must not fall back",
                        hasTag("genre_editorial_fallback_${genreGroupingKey(title)}"))
                    assertFalse("$title must not add a Compose title",
                        composeHasTitle(editorialTileTag(title), title))
                    assertChipInsideArtwork(editorialTileTag(title),
                        "genre_art_frame_${genreGroupingKey(title)}",
                        "genre_counter_${genreGroupingKey(title)}", title)
                }
                is EditorialRow.Pair -> {
                    val leading = row.leading.genre.title
                    val trailing = row.trailing.genre.title
                    rule.onNodeWithTag("genres_editorial")
                        .performScrollToNode(hasTestTag(editorialTileTag(leading)))
                    rule.waitForIdle()
                    rule.waitUntil(20_000) {
                        receipts["EDITORIAL:${genreGroupingKey(trailing)}"] != null
                    }
                    val left = bounds(editorialTileTag(leading))
                    val right = bounds(editorialTileTag(trailing))
                    assertNear("pair shares one top", left.top, right.top, pxOf(1f))
                    assertNear("pair shares one height", left.height, right.height, pxOf(1f))
                    assertNear("pair keeps the 10dp gap", pxOf(10f), right.left - left.right,
                        pxOf(1f))
                    assertNear("pair fills the usable width", usable,
                        dpOf(left.width + right.width + pxOf(10f)), 1.5f)
                    assertNear("$leading proportional width", row.leading.widthDp,
                        dpOf(left.width), 1f)
                    assertNear("$trailing proportional width", row.trailing.widthDp,
                        dpOf(right.width), 1f)
                    assertTrue("$leading must paint its poster",
                        hasTag(editorialPosterTag(leading)))
                    assertTrue("$trailing must paint its poster",
                        hasTag(editorialPosterTag(trailing)))
                    assertFalse("$leading must not fall back",
                        hasTag("genre_editorial_fallback_${genreGroupingKey(leading)}"))
                    assertFalse("$trailing must not fall back",
                        hasTag("genre_editorial_fallback_${genreGroupingKey(trailing)}"))
                }
            }
        }
        // No horizontal sheet is forced into a portrait slot, no technical text.
        assertFalse("no fake portrait appears", hasTag("genre_editorial_portrait"))
        listOf("PENDIENTE", "NO-GO", "vertical").forEach { word ->
            assertTrue("technical text '$word' must not be visible",
                rule.onAllNodesWithText(word, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty())
        }
        // Rock has an unverified Editorial optical row: it still paints, with the
        // provisional contour, and the poster is not withheld.
        val rock = editorialPosterForTitle("Rock", posterAssets)!!
        assertFalse("Rock keeps mask debt", editorialVisualSpec(rock)!!.hasVerifiedContour)
        assertTrue("Rock still paints",
            rule.onAllNodesWithTag(editorialPosterTag("Rock"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun counterChipGrowsWithRealDigitsWithoutTruncation() {
        val counts = listOf(1, 5, 99, 100, 1234)
        val genres = counts.mapIndexed { index, count -> genre("Conteo $index", count) }
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(referenceWidthDp.dp).fillMaxHeight().background(SurfaceObsidian)) {
                    GenreMosaicGrid(
                        genres = genres,
                        columns = 2,
                        availableWidthDp = referenceWidthDp.toFloat(),
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
            assertTrue("count $count chip never collapses below the 24dp contract floor",
                chip.width >= pxOf(24f) - pxOf(1f) && chip.height >= pxOf(24f) - pxOf(1f))
            if (count >= 100) {
                assertTrue("wide count ${count} grows the capsule", chip.width > previousWidth)
            }
            assertFalse("the chip never paints the word 'pistas'",
                rule.onAllNodes(
                    hasText("pistas") and hasAnyAncestor(hasTestTag("genre_tile_$id")),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty())
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
                val poster = editorialPosterForTitle(title, posterAssets)
                boundsLines += "editorial width=${width}dp fontScale=$evidenceFontScale " +
                    "title=$title tile=${dp(rect.width)}x${dp(rect.height)}dp " +
                    "poster=${poster?.filename} ratio=${poster?.ratio} " +
                    "art=${rule.onAllNodesWithTag("genre_editorial_poster_$id",
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

    /**
     * Doc §7.3/§7.4 evidence matrix: the real 19-genre library shape rendered by
     * the production panel at every contract width and text scale. Each capture
     * proves painting, one frame, no duplicate title, a numeric chip and an
     * explicit per-genre receipt; both views are captured per scenario.
     */
    @Test fun captureNineteenGenreFixtureAcrossWidthsAndFontScales() {
        assumeTrue("Visual evidence is opt-in: -e michiGenreEvidence true", evidenceEnabled)
        val directory = File(context.getExternalFilesDir(null), "ui7-rev05-evidence")
            .apply { mkdirs() }
        val screenWidthPx = context.resources.displayMetrics.widthPixels
        val scenarios = evidenceWidths.flatMap { width ->
            listOf(1f, 1.3f, 1.5f, 2f).map { scale -> width to scale }
        }
        val scenario = mutableStateOf(scenarios.first())
        val genres = genreNineteenFixture()
        val playableIds = genres.flatMap { it.tracks }.map { it.id }.toSet()
        val panelReceipts = ConcurrentHashMap<String, Boolean>()
        val lines = mutableListOf<String>()

        rule.setContent {
            val (width, scale) = scenario.value
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(width, scale, baseConfiguration) {
                Configuration(baseConfiguration).apply {
                    screenWidthDp = width
                    fontScale = scale
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(
                    density = screenWidthPx.toFloat() / width,
                    fontScale = scale,
                ),
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                    Box(
                        Modifier.width(width.dp).fillMaxHeight().background(SurfaceObsidian)
                            .testTag("ui7_19_root"),
                    ) {
                        GenreLibraryPanel(
                            genres = genres,
                            playableTrackIds = playableIds,
                            currentTrack = null,
                            isPlaying = false,
                            onPlay = { _, _ -> },
                            onArtworkLoad = { view, genreId, loaded ->
                                panelReceipts["${view.name}:$genreId"] = loaded
                            },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        var renderedMode = GenreViewMode.FEATURED
        fun selectMode(mode: GenreViewMode) {
            if (renderedMode == mode) return
            rule.onNodeWithTag("genre_view_selector").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("genre_view_${mode.name.lowercase()}").performClick()
            rule.onNodeWithTag(if (mode == GenreViewMode.EDITORIAL) "genres_editorial"
                else "genres_featured").assertIsDisplayed()
            rule.waitForIdle()
            renderedMode = mode
        }
        fun captureRoot(name: String) {
            val bitmap = rule.onNodeWithTag("ui7_19_root").captureToImage().asAndroidBitmap()
            writeBitmap(bitmap, directory, "$name.png")
            println("UI7_19_EVIDENCE ${File(directory, "$name.png").absolutePath} " +
                "${bitmap.width}x${bitmap.height}")
        }

        scenarios.forEach { (width, scale) ->
            val tag = "fs" + scale.toString().replace(".", "")
            rule.runOnIdle { scenario.value = width to scale }
            rule.waitForIdle()
            val density = screenWidthPx.toFloat() / width
            fun dp(px: Float): Float = px / density

            selectMode(GenreViewMode.FEATURED)
            rule.onNodeWithTag("genres_featured")
                .performScrollToNode(hasTestTag(mosaicTileTag("New Wave")))
            rule.waitForIdle()
            captureRoot("mosaico-19-${width}dp-$tag")
            genreNineteenTitles.forEach { title ->
                val id = genreGroupingKey(title)
                rule.onNodeWithTag("genres_featured")
                    .performScrollToNode(hasTestTag(mosaicTileTag(title)))
                rule.waitForIdle()
                assertTrue("[$width/$scale] $title Mosaico must paint",
                    hasTag("genre_artwork_$id"))
                assertFalse("[$width/$scale] $title Mosaico must not fall back",
                    hasTag("genre_mosaic_fallback_$id"))
                assertFalse("[$width/$scale] $title must not duplicate its title",
                    composeHasTitle(mosaicTileTag(title), title))
                val tile = bounds(mosaicTileTag(title))
                lines += "mosaico\t${width}dp\t$scale\t$title\t${dp(tile.width)}" +
                    "x${dp(tile.height)}\tart=1\t" +
                    "receipt=${panelReceipts["FEATURED:$id"]}"
            }

            selectMode(GenreViewMode.EDITORIAL)
            rule.onNodeWithTag("genres_editorial")
                .performScrollToNode(hasTestTag(editorialTileTag("New Wave")))
            rule.waitForIdle()
            captureRoot("editorial-19-${width}dp-$tag")
            genreNineteenTitles.forEach { title ->
                val id = genreGroupingKey(title)
                rule.onNodeWithTag("genres_editorial")
                    .performScrollToNode(hasTestTag(editorialTileTag(title)))
                rule.waitForIdle()
                assertTrue("[$width/$scale] $title Editorial must paint",
                    hasTag(editorialPosterTag(title)))
                assertFalse("[$width/$scale] $title Editorial must not fall back",
                    hasTag("genre_editorial_fallback_$id"))
                assertFalse("[$width/$scale] $title must not duplicate its title",
                    composeHasTitle(editorialTileTag(title), title))
                val tile = bounds(editorialTileTag(title))
                lines += "editorial\t${width}dp\t$scale\t$title\t${dp(tile.width)}" +
                    "x${dp(tile.height)}\tart=1\t" +
                    "receipt=${panelReceipts["EDITORIAL:$id"]}"
            }
        }
        File(directory, "bounds-19-genres.tsv").writeText(
            lines.joinToString("\n") + "\n",
        )
        assertEquals("19 genres x 5 widths x 4 scales x 2 views",
            scenarios.size.toLong() * genreNineteenTitles.size * 2, lines.size.toLong())
        assertTrue("the Mosaico matrix must decode real crops",
            panelReceipts.any { it.key.startsWith("FEATURED:") && it.value })
        assertTrue("the Editorial matrix must decode real posters",
            panelReceipts.any { it.key.startsWith("EDITORIAL:") && it.value })
    }

    // endregion
}
