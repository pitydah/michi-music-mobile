package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences

/**
 * UI7 312-genre integration gate.
 *
 * The production panel is rendered with the exact 312 approved genre keys read
 * from the packaged Mosaico manifest, so both views resolve real assets instead
 * of a drawn fixture. The same harness captures Mosaico and Editorial evidence
 * at 320/360/390/411/480 dp when `-e michiGenreEvidence true` is passed.
 * `-e michiGenreWidths 320,360,...` narrows the width matrix and
 * `-e michiGenreFontScale 1.3` renders the same panel at a scalable-text scale.
 */
@RunWith(AndroidJUnit4::class)
class GenreArtworkInventory312ComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val evidenceEnabled = arguments.getString("michiGenreEvidence") == "true"
    private val screenWidthPx = context.resources.displayMetrics.widthPixels
    private val widthDp = mutableStateOf(411)
    // Font scale is a run parameter, not a device setting: the harness renders the
    // real panel at a real screen width with the requested scalable text config, so
    // 1.3/2.0 evidence does not mutate the emulator between runs.
    private val fontScale = arguments.getString("michiGenreFontScale")
        ?.toFloatOrNull()?.takeIf { it in 0.8f..2.5f } ?: 1f
    // 1.0 keeps the historical file names; other scales are suffixed so the
    // 320/360/390/411/480 matrix and the large-text matrix never overwrite.
    private val scaleSuffix = if (fontScale == 1f) "" else
        "-fs" + fontScale.toString().replace(".", "")
    private val captureWidths = arguments.getString("michiGenreWidths")
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.filter { it in 280..600 }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(320, 360, 390, 411, 480)
    private val artworkReceipts = ConcurrentHashMap<String, Boolean>()
    private val evidenceLines = mutableListOf<String>()

    private val manifestKeys: List<String> by lazy {
        context.assets.open("$mosaicFolder/manifest.tsv").bufferedReader().use { it.readText() }
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t')[0] }
            .toList()
    }

    private val genres: List<LibraryGenre> by lazy {
        manifestKeys.mapIndexed { index, key ->
            LibraryGenre(
                id = key,
                title = key,
                tracks = listOf(
                    Track(
                        id = "g$index",
                        title = key,
                        genre = key,
                        sourceId = "local",
                        filepath = "content://michi/genre/$index.flac",
                    ),
                ),
                albumCount = 1,
            )
        }
    }

    private val evidenceDir: File? = if (evidenceEnabled) {
        File(context.getExternalFilesDir(null), "ui7-312-evidence").apply { mkdirs() }
    } else {
        null
    }

    private fun setPanel() {
        rule.setContent {
            // LocalConfiguration is a composable read; copy it once outside remember.
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(widthDp.value, baseConfiguration) {
                Configuration(baseConfiguration).apply {
                    screenWidthDp = widthDp.value
                    fontScale = this@GenreArtworkInventory312ComposeTest.fontScale
                }
            }
            // Simulate the requested phone width with the real screen pixels, so
            // 480dp is measurable on a 411dp device without resizing the emulator.
            val density = Density(
                density = screenWidthPx.toFloat() / widthDp.value,
                fontScale = fontScale,
            )
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides density,
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                    Box(Modifier.width(widthDp.value.dp).fillMaxHeight().testTag("ui7_312_root")) {
                        GenreLibraryPanel(
                            genres = genres,
                            playableTrackIds = genres.flatMap { it.tracks }.map { it.id }.toSet(),
                            currentTrack = null,
                            isPlaying = false,
                            onPlay = { _, _ -> },
                            onArtworkLoad = { view, genreId, loaded ->
                                artworkReceipts["${view.name}:$genreId"] = loaded
                            },
                        )
                    }
                }
            }
        }
    }

    /** The panel keeps one composition across width changes in this harness. */
    private var renderedMode = GenreViewMode.FEATURED

    /**
     * Deterministic view switch: open the selector, wait for the popup item, click
     * it and only then treat the new view as rendered. The panel starts on
     * FEATURED, so re-selecting it is a no-op rather than a needless popup cycle.
     */
    private fun selectMode(mode: GenreViewMode) {
        if (renderedMode == mode) return
        rule.onNodeWithTag("genre_view_selector").performClick()
        val itemTag = "genre_view_${mode.name.lowercase()}"
        waitForNode(itemTag)
        rule.onNodeWithTag(itemTag).performClick()
        waitForNode(if (mode == GenreViewMode.EDITORIAL) "genres_editorial" else "genres_featured")
        rule.waitForIdle()
        renderedMode = mode
    }

    /**
     * Bring a tile into composition before asserting or capturing it. Both grids
     * intentionally retain their scroll position across view switches
     * (`featuredGridState`/`editorialListState` live at panel scope), so the first
     * row is not guaranteed to be composed after a previous width scrolled the
     * list to its tail.
     */
    private fun scrollToTile(listTag: String, tileTag: String) {
        rule.onNodeWithTag(listTag).performScrollToNode(hasTestTag(tileTag))
        rule.waitForIdle()
    }

    private fun waitForNode(tag: String) {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(tag: String, name: String) {
        val directory = evidenceDir ?: return
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val target = File(directory, "$name.png")
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("UI7_312_EVIDENCE ${target.absolutePath} ${bitmap.width}x${bitmap.height}")
        evidenceLines += "capture $name ${bitmap.width}x${bitmap.height}"
    }

    private fun writeIndex() {
        val directory = evidenceDir ?: return
        val name = "capture-index$scaleSuffix.txt"
        File(directory, name).writeText(
            evidenceLines.joinToString("\n") + "\nfontScale=$fontScale widths=$captureWidths\n",
        )
        evidenceLines.clear()
    }

    @Test fun inventoryIsTheApproved312AndBothViewsScrollToTheLastGenre() {
        assertEquals("approved mosaic genres", 312, manifestKeys.size)
        assertEquals("library genres", 312, genres.size)
        assertEquals("unique genre ids", 312, genres.map { it.id }.toSet().size)
        assertEquals("rock", manifestKeys.first())
        assertEquals("timba", manifestKeys.last())

        // The panel is not the manifest: it renders genresByTrackCount (most tracks
        // first, then title). With one track each that is title order, so the hero
        // and the final reachable card must come from the production ordering.
        val ordered = genresByTrackCount(genres)
        val hero = ordered.first()
        val last = ordered.last()
        assertEquals("panel genre count", 312, ordered.size)
        assertEquals("hero is the alphabetical first genre", "acid house", hero.id)
        assertEquals("last genre in panel order", "zouk", last.id)

        setPanel()
        rule.waitForIdle()
        rule.onNodeWithTag("genres_header_count").assertIsDisplayed().assertTextEquals("312 géneros")
        waitForNode(mosaicTileTag(hero.title))
        rule.onNodeWithTag(mosaicTileTag(hero.title)).assertIsDisplayed()
        rule.waitUntil(20_000) { artworkReceipts["FEATURED:${hero.id}"] == true }
        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag(last.title)))
        rule.waitForIdle()
        rule.onNodeWithTag(mosaicTileTag(last.title)).assertIsDisplayed()
        rule.waitUntil(20_000) { artworkReceipts["FEATURED:${last.id}"] == true }

        selectMode(GenreViewMode.EDITORIAL)
        waitForNode(editorialTileTag(hero.title))
        rule.onNodeWithTag(editorialTileTag(hero.title)).assertIsDisplayed()
        rule.waitUntil(20_000) { artworkReceipts["EDITORIAL:${hero.id}"] == true }
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag(last.title)))
        rule.waitForIdle()
        rule.onNodeWithTag(editorialTileTag(last.title)).assertIsDisplayed()
        rule.waitUntil(20_000) { artworkReceipts["EDITORIAL:${last.id}"] == true }
        assertEquals(true, artworkReceipts["FEATURED:${hero.id}"])
        assertEquals(true, artworkReceipts["EDITORIAL:${last.id}"])
    }

    @Test fun captureBothViewsAtFivePhoneWidths() {
        assumeTrue("Visual evidence is opt-in: -e michiGenreEvidence true", evidenceEnabled)
        setPanel()
        rule.waitForIdle()
        // Same production ordering as the panel: genresByTrackCount, then title.
        val ordered = genresByTrackCount(genres)
        val hero = ordered.first()
        val last = ordered.last()
        captureWidths.forEach { width ->
            rule.runOnIdle { widthDp.value = width }
            rule.waitForIdle()

            selectMode(GenreViewMode.FEATURED)
            // The grid keeps its scroll position across widths and view switches,
            // so bring the first row back into composition before proving it.
            scrollToTile("genres_featured", mosaicTileTag(hero.title))
            rule.onNodeWithTag(mosaicTileTag(hero.title)).assertIsDisplayed()
            rule.waitUntil(20_000) { artworkReceipts["FEATURED:${hero.id}"] == true }
            capture("genres_overview", "mosaico-${width}dp$scaleSuffix")
            record("mosaico-${width}dp${scaleSuffix} fontScale=$fontScale " +
                "receipts=" + artworkReceipts.count { it.value })

            selectMode(GenreViewMode.EDITORIAL)
            scrollToTile("genres_editorial", editorialTileTag(hero.title))
            rule.onNodeWithTag(editorialTileTag(hero.title)).assertIsDisplayed()
            rule.waitUntil(20_000) { artworkReceipts["EDITORIAL:${hero.id}"] == true }
            capture("genres_overview", "editorial-${width}dp$scaleSuffix")
            record("editorial-${width}dp${scaleSuffix} fontScale=$fontScale " +
                "receipts=" + artworkReceipts.count { it.value })

            // Prove the 312th approved genre is reachable at this width too.
            rule.onNodeWithTag("genres_editorial")
                .performScrollToNode(hasTestTag(editorialTileTag(last.title)))
            rule.waitForIdle()
            rule.onNodeWithTag(editorialTileTag(last.title)).assertIsDisplayed()
            record("editorial-${width}dp${scaleSuffix} tail=${last.title} displayed")
        }
        writeIndex()
        assertTrue("expected at least one decoded Mosaico receipt",
            artworkReceipts.any { it.key.startsWith("FEATURED:") && it.value })
        assertTrue("expected at least one decoded Editorial receipt",
            artworkReceipts.any { it.key.startsWith("EDITORIAL:") && it.value })
    }

    private fun record(line: String) {
        println(line)
        evidenceLines += line
    }
}
