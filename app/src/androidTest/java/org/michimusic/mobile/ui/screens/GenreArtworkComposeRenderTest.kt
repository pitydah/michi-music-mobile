package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * Render proof for the rev05 contract: an approved WebP is painted whenever it is
 * packaged and decodable, and quality debt (unverified contour, residual aspect
 * band, unsafe chip) is reported without replacing the image.
 */
@RunWith(AndroidJUnit4::class)
class GenreArtworkComposeRenderTest {
    @get:Rule val rule = createComposeRule()

    /**
     * The render contracts were written against a 411 dp reference container. A narrower
     * device measures that container at the screen width, so both the composed width and
     * every derived expectation must use the width the grid really receives.
     */
    private val referenceWidthDp: Int = minOf(
        411,
        InstrumentationRegistry.getInstrumentation().targetContext
            .resources.configuration.screenWidthDp,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun changedSamples(before: ImageBitmap, after: ImageBitmap): Pair<Int, Int> {
        assertEquals("width changed", before.width, after.width)
        assertEquals("height changed", before.height, after.height)
        val a = before.toPixelMap()
        val b = after.toPixelMap()
        var changed = 0
        var sampled = 0
        for (y in 1 until before.height step 6) {
            for (x in 1 until before.width step 6) {
                val left = a[x, y].toArgb()
                val right = b[x, y].toArgb()
                val difference = abs((left shr 16 and 255) - (right shr 16 and 255)) +
                    abs((left shr 8 and 255) - (right shr 8 and 255)) +
                    abs((left and 255) - (right and 255))
                if (difference >= 30) changed++
                sampled++
            }
        }
        return changed to sampled
    }

    private fun assertPaintChanged(before: ImageBitmap, after: ImageBitmap, what: String) {
        val (changed, sampled) = changedSamples(before, after)
        assertTrue("$what: no visible artwork pixels ($changed of $sampled samples changed)",
            changed > sampled / 20)
    }

    private fun approvedMosaics(): Map<String, MosaicArtworkAsset> {
        val assets = context.assets
        val installed = assets.list(mosaicFolder).orEmpty().toSet()
        val manifest = assets.open("$mosaicFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val optical = runCatching {
            assets.open("$mosaicFolder/optical.tsv")
                .bufferedReader().use { parseUi7OpticalMasks(it.readText()) }
        }.getOrDefault(emptyMap())
        return mosaicApprovedArtwork(manifest, installed, opticalMasks = optical)
    }

    private fun approvedPosters(): Map<String, EditorialPosterAsset> {
        val assets = context.assets
        val installed = assets.list(editorialPosterFolder).orEmpty().toSet()
        val manifest = assets.open("$editorialPosterFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val optical = runCatching {
            assets.open("$editorialPosterFolder/optical.tsv")
                .bufferedReader().use { parseUi7OpticalMasks(it.readText()) }
        }.getOrDefault(emptyMap())
        return editorialApprovedPosters(manifest, installed, opticalMasks = optical)
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

    private fun bounds(tag: String, unmerged: Boolean = false) =
        rule.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun hasTag(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun composeHasTitle(tileTag: String, title: String): Boolean =
        rule.onAllNodes(
            hasText(title) and hasAnyAncestor(hasTestTag(tileTag)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

    @Test fun realTimbaCropPaintChangesInsideTheOpticalContour() {
        val timba = approvedMosaics()["timba"] ?: error("missing Timba crop")
        var withArtwork by mutableStateOf(false)
        val decoded = AtomicReference<Boolean?>(null)
        rule.setContent {
            Box(
                Modifier.width(180.5.dp).height(129.dp)
                    .background(SurfaceObsidian)
                    .testTag("ui7_timba_pixels"),
            ) {
                if (withArtwork) {
                    GenreMosaicArtwork(
                        asset = timba,
                        genreId = "timba",
                        accent = Color.Magenta,
                        onImageResult = { decoded.set(it) },
                    )
                }
            }
        }
        val before = rule.onNodeWithTag("ui7_timba_pixels").captureToImage()
        rule.runOnIdle { withArtwork = true }
        rule.waitUntil(20_000) { decoded.get() != null }
        rule.waitForIdle()
        assertEquals("Timba failed to decode", true, decoded.get())
        assertPaintChanged(before, rule.onNodeWithTag("ui7_timba_pixels").captureToImage(), "Timba")
    }

    @Test fun realEditorialPosterArtworkIsActuallyPainted() {
        val rock = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280)
        val decoded = AtomicReference<Boolean?>(null)
        var withArtwork by mutableStateOf(false)
        rule.setContent {
            Box(
                Modifier.width(350.dp).height(192.dp).background(Color.Black)
                    .testTag("ui7_editorial_pixels"),
            ) {
                if (withArtwork) {
                    GenreEditorialPosterArtwork(rock, onImageResult = { decoded.set(it) })
                }
            }
        }
        val before = rule.onNodeWithTag("ui7_editorial_pixels").captureToImage()
        rule.runOnIdle { withArtwork = true }
        rule.waitUntil(20_000) { decoded.get() != null }
        rule.waitForIdle()
        assertEquals("Editorial Rock failed to decode", true, decoded.get())
        assertPaintChanged(before, rule.onNodeWithTag("ui7_editorial_pixels").captureToImage(),
            "Editorial Rock")
    }

    /**
     * The production panel must paint both packaged crops. Availability is the
     * only gate: ratio, contour and chip are debt, never a fallback.
     */
    @Test fun everyPackagedCropPaintsInTheProductionPanel() {
        val receipts = ConcurrentHashMap<String, Boolean>()
        val titles = listOf("Timba", "Salsa")
        val genres = titles.map { genre(it) }
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(referenceWidthDp.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = genres,
                        playableTrackIds = genres.flatMap { it.tracks }.map { it.id }.toSet(),
                        currentTrack = null,
                        isPlaying = false,
                        onPlay = { _, _ -> },
                        onArtworkLoad = { view, genreId, loaded ->
                            receipts["${view.name}:$genreId"] = loaded
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        val grid = rule.onNodeWithTag("genres_featured").fetchSemanticsNode().boundsInRoot
        val widthDp = grid.width / rule.density.density
        val columns = genreGridColumns(widthDp.roundToInt(), GENRE_COMPACT_TILE_MIN_WIDTH_DP)
        val size = mosaicCardSize(widthDp, columns)
        val assets = approvedMosaics()

        titles.forEach { title ->
            val id = genreGroupingKey(title)
            // K48-R12: the grid is lazy. A card that is not composed does not request or
            // decode anything, so waiting for its receipt before scrolling to it waits for
            // an event that composition itself has to produce. Compose the card first, then
            // wait for the decode it starts.
            rule.waitUntil(20_000) {
                rule.onAllNodesWithTag("genres_featured").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("genres_featured")
                .performScrollToNode(hasTestTag("genre_tile_$id"))
            rule.waitForIdle()
            rule.waitUntil(20_000) { receipts["FEATURED:$id"] != null }
            assertEquals("$title must decode", true, receipts["FEATURED:$id"])
            rule.onNodeWithTag("genre_tile_$id").assertIsDisplayed()
            assertTrue("$title must compose its approved WebP", hasTag("genre_artwork_$id"))
            assertTrue("$title must own a painted frame", hasTag("genre_art_frame_$id"))
            assertFalse("$title must not fall back", hasTag("genre_mosaic_fallback_$id"))
            assertFalse("$title must not duplicate its baked title",
                composeHasTitle("genre_tile_$id", title))
            val tile = bounds("genre_tile_$id")
            assertTrue("$title card width ${tile.width}px must match the homogeneous cell",
                abs(tile.width / rule.density.density - size.widthDp) <= 1f)
            assertTrue("$title card height ${tile.height}px must match the homogeneous cell",
                abs(tile.height / rule.density.density - size.heightDp) <= 1f)
            rule.onNodeWithTag("genre_tile_$id")
                .assertContentDescriptionEquals("Abrir género $title, 3 pistas")

            val decision = ui7ArtworkDecision(
                mosaicVisualSpec(assets.getValue(id)), size.widthDp, size.heightDp, 26f, 26f,
            )
            assertTrue("$title decision must paint", decision.paintsArtwork)
            assertEquals(Ui7AssetAvailability.ASSET_PACKAGED, decision.availability)
        }
    }

    /**
     * A `verified=0` optical row is QA debt: the crop keeps painting with the
     * conservative provisional contour and records `mask_pending`.
     */
    @Test fun unverifiedOpticalRowStillPaintsWithTheProvisionalContour() {
        val timba = approvedMosaics().getValue("timba")
        val unverified = timba.copy(
            opticalMask = timba.opticalMask?.copy(verified = false),
        )
        assertEquals("fixture must have an optical row", true, timba.opticalMask != null)
        val receipts = ConcurrentHashMap<String, Boolean>()
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(referenceWidthDp.dp).fillMaxHeight().background(SurfaceObsidian)) {
                    GenreMosaicGrid(
                        genres = listOf(genre("Timba")),
                        columns = 2,
                        availableWidthDp = referenceWidthDp.toFloat(),
                        state = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
                        artwork = mapOf("timba" to unverified),
                        onArtworkLoad = { view, id, ok -> receipts["${view.name}:$id"] = ok },
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(20_000) { receipts["FEATURED:timba"] != null }
        assertEquals("an unmeasured contour must not hide the WebP", true,
            receipts["FEATURED:timba"])
        assertTrue("the crop must be composed", hasTag("genre_artwork_timba"))
        assertTrue("the painted frame must exist", hasTag("genre_art_frame_timba"))
        assertFalse("no generic fallback may replace the crop",
            hasTag("genre_mosaic_fallback_timba"))

        val spec = mosaicVisualSpec(unverified)!!
        assertFalse("the verified contour is missing", spec.hasVerifiedContour)
        assertFalse("the provisional clip is not certified", spec.clipMask.verified)
        assertEquals("provisional inset", 2f, spec.clipMask.leftInsetPx)
        val size = mosaicCardSize(referenceWidthDp.toFloat(), 2)
        val decision = ui7ArtworkDecision(spec, size.widthDp, size.heightDp, 26f, 26f)
        assertTrue(decision.paintsArtwork)
        assertTrue(decision.quality.opticalMaskUnverified)
        rule.onNodeWithTag("genre_tile_timba")
            .assertContentDescriptionEquals("Abrir género Timba, 3 pistas")
    }

    /**
     * A residual aspect band and an unsafe chip are recorded for artistic QA only.
     * Pop is 1.92:1 inside the 1.42 cell, so it must paint with measured debt.
     */
    @Test fun aspectAndCounterDebtNeverReplaceTheBitmap() {
        val pop = approvedMosaics().getValue("pop")
        val receipts = ConcurrentHashMap<String, Boolean>()
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(390.dp).fillMaxHeight().background(SurfaceObsidian)) {
                    GenreMosaicGrid(
                        genres = listOf(genre("Pop")),
                        columns = 2,
                        availableWidthDp = 390f,
                        state = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
                        artwork = mapOf("pop" to pop),
                        onArtworkLoad = { view, id, ok -> receipts["${view.name}:$id"] = ok },
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(20_000) { receipts["FEATURED:pop"] != null }
        assertEquals(true, receipts["FEATURED:pop"])
        assertTrue(hasTag("genre_artwork_pop"))
        assertFalse(hasTag("genre_mosaic_fallback_pop"))

        val size = mosaicCardSize(390f, 2)
        val spec = mosaicVisualSpec(pop)!!
        val decision = ui7ArtworkDecision(spec, size.widthDp, size.heightDp, 26f, 26f)
        assertTrue("the crop paints despite its ratio", decision.paintsArtwork)
        assertTrue("the residual band is recorded", decision.quality.aspectDebt)
        // The chip keeps its own quality axis: an oversized capsule is debt and
        // never a reason to remove the bitmap.
        val chipDebt = ui7ArtworkDecision(spec, size.widthDp, size.heightDp, 320f, 26f)
        assertTrue(chipDebt.paintsArtwork)
        assertTrue(chipDebt.quality.counterRegionUnverified)
        // Only the numeric chip is painted: the Spanish phrase stays in semantics.
        assertTrue("the numeric chip must be visible",
            rule.onAllNodesWithTag("genre_counter_pop", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty())
        assertFalse("only the numeric chip is painted",
            rule.onAllNodes(
                hasText("pistas") and hasAnyAncestor(hasTestTag("genre_tile_pop")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * Editorial is driven by the real poster ratios: one full-width row per
     * banner/compact, the baked poster as the only title and the numeric chip
     * inside the painted frame.
     */
    @Test fun editorialPaintsRealPostersWithRatioDrivenRowsAndOneTitle() {
        val titles = listOf("New Wave", "Pop", "Rock", "Soft Rock")
        val genres = listOf(genre("New Wave", 14), genre("Pop", 5), genre("Rock", 2),
            genre("Soft Rock", 2))
        val posters = approvedPosters()
        val receipts = ConcurrentHashMap<String, Boolean>()
        rule.setContent {
            MichiTheme {
                Row(Modifier.width(referenceWidthDp.dp).background(SurfaceObsidian)) {
                    GenreEditorialGrid(
                        genres = genres,
                        columns = 2,
                        availableWidthDp = referenceWidthDp.toFloat(),
                        state = rememberLazyListState(),
                        posterAssets = posters,
                        onArtworkLoad = { view, id, ok -> receipts["${view.name}:$id"] = ok },
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()

        val plan = editorialPlan(genres, posters, referenceWidthDp.toFloat(), 2, 1f)
        assertEquals("banner, compact single, banner, compact single",
            listOf(EditorialRowKind.BANNER, EditorialRowKind.SINGLE_COMPACT,
                EditorialRowKind.BANNER, EditorialRowKind.SINGLE_COMPACT),
            plan.map { it.kind })
        val usable = editorialUsableWidthDp(referenceWidthDp.toFloat())
        val density = rule.density.density

        titles.forEachIndexed { index, title ->
            val id = genreGroupingKey(title)
            rule.onNodeWithTag("genres_editorial")
                .performScrollToNode(hasTestTag("genre_editorial_$id"))
            rule.waitForIdle()
            rule.waitUntil(20_000) { receipts["EDITORIAL:$id"] != null }
            assertEquals("$title poster must decode", true, receipts["EDITORIAL:$id"])
            val row = plan[index] as EditorialRow.Full
            val tile = bounds("genre_editorial_$id")
            assertEquals("$title full width", usable, tile.width / density, 1f)
            assertEquals("$title height follows its real ratio",
                usable / row.piece.poster!!.ratio, tile.height / density, 1f)
            assertTrue("$title must paint its poster", hasTag("genre_editorial_poster_$id"))
            assertTrue("$title must own the painted frame", hasTag("genre_art_frame_$id"))
            assertFalse("$title must not fall back",
                hasTag("genre_editorial_fallback_$id"))
            assertFalse("$title must not duplicate its baked title",
                composeHasTitle("genre_editorial_$id", title))
            assertTrue("$title chip must stay visible", hasTag("genre_counter_$id"))
            rule.onNodeWithTag("genre_editorial_$id")
                .assertContentDescriptionEquals(
                    "Abrir género $title, ${genres[index].tracks.size} pistas",
                )
        }
        listOf("PENDIENTE", "NO-GO", "vertical").forEach { word ->
            assertTrue("technical text '$word' must not be visible",
                rule.onAllNodesWithText(word, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty())
        }
    }

    /**
     * Two contiguous compacts share exactly one height and split the usable width
     * proportionally; no banner is ever squeezed into a half column.
     */
    @Test fun editorialPairsTwoContiguousCompactsWithEqualHeight() {
        val genres = listOf(genre("Pop", 5), genre("Soft Rock", 2), genre("NDW", 3))
        val posters = approvedPosters()
        val receipts = ConcurrentHashMap<String, Boolean>()
        rule.setContent {
            MichiTheme {
                Row(Modifier.width(referenceWidthDp.dp).background(SurfaceObsidian)) {
                    GenreEditorialGrid(
                        genres = genres,
                        columns = 2,
                        availableWidthDp = referenceWidthDp.toFloat(),
                        state = rememberLazyListState(),
                        posterAssets = posters,
                        onArtworkLoad = { view, id, ok -> receipts["${view.name}:$id"] = ok },
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(20_000) { receipts["EDITORIAL:pop"] == true }

        val plan = editorialPlan(genres, posters, referenceWidthDp.toFloat(), 2, 1f)
        assertEquals(2, plan.size)
        val pair = plan[0] as EditorialRow.Pair
        assertEquals("Pop", pair.leading.genre.title)
        assertEquals("Soft Rock", pair.trailing.genre.title)
        assertEquals("pair height is shared", pair.leading.heightDp, pair.trailing.heightDp, 0f)
        val usable = editorialUsableWidthDp(referenceWidthDp.toFloat())
        assertEquals("pair fills the usable width", usable,
            pair.leading.widthDp + GENRE_GRID_GAP_DP + pair.trailing.widthDp, 0.05f)
        assertTrue("proportional widths differ", pair.leading.widthDp != pair.trailing.widthDp)

        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Soft Rock")))
        rule.waitForIdle()
        val pop = bounds(editorialTileTag("Pop"))
        val softRock = bounds(editorialTileTag("Soft Rock"))
        assertEquals("tiles share one top", pop.top, softRock.top, 1f)
        assertEquals("tiles share one height", pop.height, softRock.height, 1f)
        assertEquals("10dp gap", rule.density.density * GENRE_GRID_GAP_DP,
            softRock.left - pop.right, 1f)
        assertTrue(hasTag(editorialPosterTag("Pop")))
        assertTrue(hasTag(editorialPosterTag("Soft Rock")))
        assertFalse(hasTag("genre_editorial_fallback_${genreGroupingKey("Pop")}"))
        assertFalse(hasTag("genre_editorial_fallback_${genreGroupingKey("Soft Rock")}"))
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("NDW")))
        rule.waitForIdle()
        val ndw = bounds(editorialTileTag("NDW"))
        assertTrue("the single compact starts a new row below the pair",
            ndw.top >= pop.bottom + rule.density.density * GENRE_GRID_GAP_DP - 1f)
        assertEquals("the pair keeps two pieces", 2,
            rule.onAllNodesWithTag(editorialTileTag("Pop")).fetchSemanticsNodes().size +
                rule.onAllNodesWithTag(editorialTileTag("Soft Rock"))
                    .fetchSemanticsNodes().size)
    }
}
