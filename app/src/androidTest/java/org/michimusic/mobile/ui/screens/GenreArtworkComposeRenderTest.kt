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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/** Pixel checks: an ImageLoader success alone is insufficient if Compose does not paint. */
@RunWith(AndroidJUnit4::class)
class GenreArtworkComposeRenderTest {
    @get:Rule val rule = createComposeRule()

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
     * The production panel must paint an approved crop only when it can fill the
     * cell intact. The expectation is derived from the same contract arithmetic as
     * production, not from a device-specific width guess.
     */
    @Test fun theProductionPanelPaintsArtworkOrUsesTheHonestFallback() {
        val receipts = ConcurrentHashMap<String, Boolean>()
        val genres = listOf("Timba", "Salsa").map { title ->
            LibraryGenre(
                id = genreGroupingKey(title),
                title = title,
                tracks = listOf(
                    Track(id = title, title = title, genre = title, sourceId = "local"),
                ),
                albumCount = 1,
            )
        }
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
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
        val asset = approvedMosaics().getValue("timba")
        val treatment = if (columns >= 2) Ui7Treatment.MOSAIC_GRID else Ui7Treatment.SINGLE_COLUMN
        val expected = ui7RenderOutcome(
            mosaicVisualSpec(asset, treatment), treatment, size.widthDp, size.heightDp,
            asset.ratio, 46f, 28f,
        ) == Ui7RenderOutcome.ASSET
        if (expected) {
            rule.waitUntil(20_000) { receipts["FEATURED:timba"] != null }
            assertEquals(true, receipts["FEATURED:timba"])
            assertTrue("Timba artwork must be composed",
                rule.onAllNodesWithTag("genre_artwork_timba", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty())
        } else {
            assertTrue("Timba must not letterbox an incompatible cell",
                rule.onAllNodesWithTag("genre_artwork_timba", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun editorialFallbackTilesUseTheTreatmentHeightAndOneTitle() {
        val pop = LibraryGenre(
            id = "pop", title = "Pop",
            tracks = (1..5).map { Track(id = "p$it", title = "P$it", genre = "Pop") },
            albumCount = 5,
        )
        val rock = LibraryGenre(
            id = "rock", title = "Rock",
            tracks = (1..2).map { Track(id = "r$it", title = "R$it", genre = "Rock") },
            albumCount = 2,
        )
        rule.setContent {
            MichiTheme {
                Row(Modifier.width(411.dp).background(SurfaceObsidian)) {
                    GenreEditorialGrid(
                        genres = listOf(pop, rock),
                        columns = 2,
                        availableWidthDp = 411f,
                        state = rememberLazyListState(),
                        posterAssets = approvedPosters(),
                        onArtworkLoad = null,
                        onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val density = rule.density.density
        val popTile = rule.onNodeWithTag("genre_editorial_pop")
            .fetchSemanticsNode().boundsInRoot
        val rockTile = rule.onNodeWithTag("genre_editorial_rock")
            .fetchSemanticsNode().boundsInRoot
        // Two genres -> COVER then a single-genre... the second row is a PAIR with
        // one item, promoted to COVER; both are 192dp covers at scale 1.
        assertEquals(192f, popTile.height / density, 1f)
        assertEquals(192f, rockTile.height / density, 1f)
        assertTrue("Pop fallback must be composed",
            rule.onAllNodesWithTag("genre_editorial_fallback_pop", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty())
        assertTrue("Rock fallback must be composed",
            rule.onAllNodesWithTag("genre_editorial_fallback_rock", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty())
        // No horizontal poster is painted in an Editorial slot.
        assertTrue(rule.onAllNodesWithTag("genre_editorial_poster_pop", useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("genre_editorial_poster_rock", useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty())
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
}
