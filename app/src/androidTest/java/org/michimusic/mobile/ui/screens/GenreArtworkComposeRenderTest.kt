package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme

/** Pixel checks: ImageLoader success alone is insufficient if Compose does not paint. */
@RunWith(AndroidJUnit4::class)
class GenreArtworkComposeRenderTest {
    @get:Rule val rule = createComposeRule()

    /** Compares rendered RGB pixels in an identical-size box before/after artwork. */
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

    private fun assertPaintChanged(before: ImageBitmap, after: ImageBitmap) {
        val (changed, sampled) = changedSamples(before, after)
        assertTrue("No visible artwork pixels: $changed of $sampled samples changed",
            changed > sampled / 20)
    }

    @Test fun realMosaicHeroAndSecondaryPaintDifferentFromFallback() {
        val heroState = AtomicReference<Boolean?>(null)
        val secondaryState = AtomicReference<Boolean?>(null)
        var withArtwork by mutableStateOf(false)
        val rock = MosaicArtworkAsset("rock", "UI7_MOSAICO_001_Rock.webp", 506, 352)
        val jazz = MosaicArtworkAsset("jazz", "UI7_MOSAICO_002_Jazz.webp", 506, 352)
        rule.setContent {
            Column {
                Box(Modifier.width(350.dp).height(218.dp).testTag("ui7_hero_pixels")) {
                    GenreMosaicArtwork(if (withArtwork) rock else null,
                        genreId = "rock", accent = Color.Magenta,
                        onImageResult = { heroState.set(it) })
                }
                Box(Modifier.width(170.dp).height(166.dp).testTag("ui7_secondary_pixels")) {
                    GenreMosaicArtwork(if (withArtwork) jazz else null,
                        genreId = "jazz", accent = Color.Magenta,
                        onImageResult = { secondaryState.set(it) })
                }
            }
        }
        val heroBefore = rule.onNodeWithTag("ui7_hero_pixels").captureToImage()
        val secondaryBefore = rule.onNodeWithTag("ui7_secondary_pixels").captureToImage()
        rule.runOnIdle { withArtwork = true }
        rule.waitUntil(20_000) { heroState.get() != null && secondaryState.get() != null }
        rule.waitForIdle()
        assertEquals("Mosaico hero failed to decode", true, heroState.get())
        assertEquals("Mosaico secondary failed to decode", true, secondaryState.get())
        assertPaintChanged(heroBefore, rule.onNodeWithTag("ui7_hero_pixels").captureToImage())
        assertPaintChanged(secondaryBefore,
            rule.onNodeWithTag("ui7_secondary_pixels").captureToImage())
    }

    @Test fun realEditorialCompletePostersAreActuallyPainted() {
        val coverState = AtomicReference<Boolean?>(null)
        val portraitState = AtomicReference<Boolean?>(null)
        var withArtwork by mutableStateOf(false)
        val rock = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280)
        val jazz = EditorialPosterAsset("jazz", "genre_jazz_editorial.webp", 513, 327)
        rule.setContent {
            Column {
                Box(Modifier.width(350.dp).height(192.dp).background(Color.Black)
                    .testTag("ui7_editorial_cover_pixels")) {
                    if (withArtwork) GenreEditorialPosterArtwork(rock,
                        onImageResult = { coverState.set(it) })
                }
                Box(Modifier.width(170.dp).height(246.dp).background(Color.Black)
                    .testTag("ui7_editorial_portrait_pixels")) {
                    if (withArtwork) GenreEditorialPosterArtwork(jazz,
                        onImageResult = { portraitState.set(it) })
                }
            }
        }
        val coverBefore = rule.onNodeWithTag("ui7_editorial_cover_pixels").captureToImage()
        val portraitBefore = rule.onNodeWithTag("ui7_editorial_portrait_pixels").captureToImage()
        rule.runOnIdle { withArtwork = true }
        rule.waitUntil(20_000) { coverState.get() != null && portraitState.get() != null }
        rule.waitForIdle()
        assertEquals("Editorial cover failed to decode", true, coverState.get())
        assertEquals("Editorial compact failed to decode", true, portraitState.get())
        assertPaintChanged(coverBefore,
            rule.onNodeWithTag("ui7_editorial_cover_pixels").captureToImage())
        assertPaintChanged(portraitBefore,
            rule.onNodeWithTag("ui7_editorial_portrait_pixels").captureToImage())
    }

    @Test fun theProductionLibraryScreenLoadsMosaicAndEditorialMappedAssets() {
        // This is the complete production screen, not a standalone image renderer.
        // It would have detected the historical missing mosaicAsset on secondaries.
        val receipts = ConcurrentHashMap<String, Boolean>()
        val tracks = listOf(
            Track(id = "r1", title = "R", genre = "Rock", sourceId = "local"),
            Track(id = "j1", title = "J", genre = "Jazz", sourceId = "local"),
        )
        val genres = groupLibraryGenres(tracks)
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(genres, setOf("r1", "j1"), currentTrack = null,
                    isPlaying = false, onPlay = { _, _ -> },
                    onArtworkLoad = { view, genreId, loaded ->
                        receipts["${view.name}:$genreId"] = loaded
                    })
            }
        }
        rule.waitUntil(20_000) {
            receipts.containsKey("FEATURED:rock") && receipts.containsKey("FEATURED:jazz")
        }
        assertEquals("Mosaico Rock failed inside GenreLibraryPanel", true, receipts["FEATURED:rock"])
        assertEquals("Mosaico Jazz failed inside GenreLibraryPanel", true, receipts["FEATURED:jazz"])
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.onNodeWithTag("genre_view_editorial").performClick()
        rule.waitUntil(20_000) {
            receipts.containsKey("EDITORIAL:rock") && receipts.containsKey("EDITORIAL:jazz")
        }
        assertEquals("Editorial Rock failed inside GenreLibraryPanel", true, receipts["EDITORIAL:rock"])
        assertEquals("Editorial Jazz failed inside GenreLibraryPanel", true, receipts["EDITORIAL:jazz"])
    }

    /**
     * Completed posters are horizontal assets. A fixed canonical height forced the
     * bitmap to letterbox inside tall/wide cells (large empty bands). The cell now
     * derives its height from the approved asset ratio while ContentScale.Fit keeps
     * the cat/lettering undeformed. This check fails on the fixed-height layout.
     */
    @Test fun editorialPosterTilesTakeTheirAssetAspectWithoutEmptyBands() {
        val rock = LibraryGenre("rock", "Rock", emptyList(), albumCount = 0)
        val jazz = LibraryGenre("jazz", "Jazz", emptyList(), albumCount = 0)
        rule.setContent {
            Column {
                Box(Modifier.width(350.dp)) {
                    GenreEditorialPosterTile(rock,
                        EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280),
                        onOpen = {})
                }
                Box(Modifier.width(170.dp)) {
                    GenreEditorialPosterTile(jazz,
                        EditorialPosterAsset("jazz", "genre_jazz_editorial.webp", 513, 327),
                        onOpen = {})
                }
            }
        }
        rule.waitForIdle()
        val oneDp = with(rule.density) { 1.dp.toPx() }
        fun assertCellMatchesAsset(tileTag: String, posterTag: String, ratio: Float) {
            val tile = rule.onNodeWithTag(tileTag).fetchSemanticsNode().boundsInRoot
            val image = rule.onNodeWithTag(posterTag, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val emptyVertical = (image.height - image.width / ratio).coerceAtLeast(0f)
            val emptyHorizontal = (image.width - image.height * ratio).coerceAtLeast(0f)
            assertTrue("$posterTag does not fill its cell width: tile=$tile image=$image",
                abs(image.width - tile.width) <= oneDp)
            assertTrue("$posterTag has empty bands: vertical=" +
                "${emptyVertical / rule.density.density}dp horizontal=" +
                "${emptyHorizontal / rule.density.density}dp in $image",
                emptyVertical <= oneDp && emptyHorizontal <= oneDp)
            assertTrue("$posterTag aspect ${image.width / image.height} != $ratio",
                abs(image.width / image.height - ratio) <= 0.02f)
        }
        assertCellMatchesAsset("genre_editorial_rock", "genre_editorial_poster_rock", 1027f / 280f)
        assertCellMatchesAsset("genre_editorial_jazz", "genre_editorial_poster_jazz", 513f / 327f)
    }
}
