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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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

    private fun dpOf(pxValue: Float): Float = pxValue / rule.density.density

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float) {
        assertTrue("$message (expected=$expected actual=$actual tolerance=$tolerance)",
            abs(expected - actual) <= tolerance)
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
        // The panel publishes its approved catalog from produceState after the
        // first composition; drain that continuation before polling so the
        // recomposition is not starved in a warm process.
        rule.waitForIdle()
        rule.waitUntil(20_000) {
            receipts.containsKey("FEATURED:rock") && receipts.containsKey("FEATURED:jazz")
        }
        assertEquals("Mosaico Rock failed inside GenreLibraryPanel", true, receipts["FEATURED:rock"])
        assertEquals("Mosaico Jazz failed inside GenreLibraryPanel", true, receipts["FEATURED:jazz"])
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.onNodeWithTag("genre_view_editorial").performClick()
        rule.waitForIdle()
        rule.waitUntil(20_000) {
            receipts.containsKey("EDITORIAL:rock") && receipts.containsKey("EDITORIAL:jazz")
        }
        assertEquals("Editorial Rock failed inside GenreLibraryPanel", true, receipts["EDITORIAL:rock"])
        assertEquals("Editorial Jazz failed inside GenreLibraryPanel", true, receipts["EDITORIAL:jazz"])
    }

    /**
     * P0 regression: the old cell height came from the approved asset ratio, so a
     * paired Pop (514x268) rendered 88.6dp next to a Rock (1027x280) at 46.3dp in
     * the same 170dp slot. Heights must come from the treatment (cover 192dp,
     * portrait pair 246dp) while the whole poster stays uncropped inside the card.
     */
    @Test fun editorialPosterCardsUseTreatmentHeightsAndKeepTheWholePoster() {
        val rock = LibraryGenre("rock", "Rock", emptyList(), albumCount = 0)
        val pop = LibraryGenre("pop", "Pop", emptyList(), albumCount = 0)
        val rockPoster = EditorialPosterAsset("rock", "genre_rock_editorial.webp", 1027, 280)
        val popPoster = EditorialPosterAsset("pop", "genre_pop_editorial.webp", 514, 268)
        rule.setContent {
            Column {
                // Rock appears twice in this fixture (cover + portrait pair), so
                // each slot gets its own wrapper tag: the production tile tag
                // alone would resolve to two nodes.
                Box(Modifier.width(350.dp).testTag("rock_cover")) {
                    GenreEditorialPosterTile(rock, rockPoster,
                        treatment = GenreEditorialTreatment.COVER, columns = 2, onOpen = {})
                }
                Box(Modifier.width(170.dp).testTag("rock_pair")) {
                    GenreEditorialPosterTile(rock, rockPoster,
                        treatment = GenreEditorialTreatment.PORTRAIT_LEFT, columns = 2, onOpen = {})
                }
                Box(Modifier.width(170.dp).testTag("pop_pair")) {
                    GenreEditorialPosterTile(pop, popPoster,
                        treatment = GenreEditorialTreatment.PORTRAIT_RIGHT, columns = 2, onOpen = {})
                }
            }
        }
        rule.waitForIdle()
        val oneDp = with(rule.density) { 1.dp.toPx() }
        fun slotNode(wrapper: String, tag: String) = rule.onNode(
            hasTestTag(tag) and hasAnyAncestor(hasTestTag(wrapper)),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        fun posterOf(wrapper: String, genreId: String) =
            slotNode(wrapper, "genre_editorial_poster_$genreId")
        fun tileOf(wrapper: String, genreId: String) =
            slotNode(wrapper, "genre_editorial_$genreId")

        val cover = tileOf("rock_cover", "rock")
        assertEquals("cover must be the 192dp treatment", 192f, dpOf(cover.height), 0.5f)
        val left = tileOf("rock_pair", "rock")
        assertEquals("portrait must be the 246dp treatment", 246f, dpOf(left.height), 0.5f)
        assertNear("cover must stay wider than a paired cell", 0f, dpOf(cover.width) - 350f, 0.5f)
        assertNear("paired cells must keep the 170dp slot", 0f, dpOf(left.width) - 170f, 0.5f)
        // The same genre in a 170dp pair cell must not shrink to its asset ratio.
        val pairRock = tileOf("rock_pair", "rock")
        val pairPop = tileOf("pop_pair", "pop")
        assertNear("paired cards must share one exterior height", pairRock.height,
            pairPop.height, oneDp)

        // The whole poster is preserved: its displayed box keeps the asset ratio
        // and never exceeds the card's mount.
        data class Slot(val wrapper: String, val genreId: String, val ratio: Float)
        listOf(
            Slot("rock_cover", "rock", rockPoster.ratio),
            Slot("rock_pair", "rock", rockPoster.ratio),
            Slot("pop_pair", "pop", popPoster.ratio),
        ).forEach { slot ->
            val poster = posterOf(slot.wrapper, slot.genreId)
            assertEquals("${slot.wrapper} poster must keep its approved aspect",
                slot.ratio, poster.width / poster.height, 0.02f)
            val tile = tileOf(slot.wrapper, slot.genreId)
            assertTrue(
                "${slot.wrapper} poster must fit inside the mounted card: poster=$poster tile=$tile",
                poster.width <= tile.width - 2 * oneDp * EDITORIAL_POSTER_MOUNT_INSET_DP + oneDp,
            )
            assertTrue("${slot.wrapper} poster must stay inside the card mount: poster=$poster tile=$tile",
                poster.left >= tile.left - oneDp && poster.right <= tile.right + oneDp)
        }
        // The Pop poster must be about 85dp tall, not the old 88.6dp exterior.
        assertTrue("Pop poster band must keep its truthful height",
            dpOf(posterOf("pop_pair", "pop").height) < 120f)
        assertEquals("Pop card must not become an 88.6dp exterior",
            246f, dpOf(pairPop.height), 0.5f)
    }
}
