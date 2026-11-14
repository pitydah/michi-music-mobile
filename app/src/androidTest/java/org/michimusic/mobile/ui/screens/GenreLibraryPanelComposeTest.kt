package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * UI7 Phase B instrumentation gate for Library > Genres.
 *
 * The production panel has exactly two views (Mosaico / Editorial), no search
 * row, a compact local header and a measured adaptive grid. These tests prove
 * the visible geometry — not mere node existence: full-width focal card,
 * compact secondaries, cover + portrait-pair Editorial rhythm, one-column 320dp
 * adaptation, touch targets, real scrolling, view persistence and clearance
 * from the persistent player/navigation chrome.
 */
@RunWith(AndroidJUnit4::class)
class GenreLibraryPanelComposeTest {
    @get:Rule val rule = createComposeRule()

    private val fixture by lazy { genreLayoutFixture() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidenceEnabled =
        InstrumentationRegistry.getArguments().getString("michiGenreEvidence") == "true"

    /** Opt-in visual evidence, written to the app's external files directory. */
    private val evidenceDir: File? = if (evidenceEnabled) {
        File(context.getExternalFilesDir(null), "ui7-mega-b-evidence").apply { mkdirs() }
    } else {
        null
    }

    private val evidenceLines = mutableListOf<String>()
    private val artworkReceipts = ConcurrentHashMap<String, Boolean>()

    private data class PanelScenario(val widthDp: Int, val fontScale: Float = 1f)

    // region harness

    /**
     * Real screen pixels used by the requested phone width.
     *
     * The harness renders a 320..480dp panel on a 411dp device by simulating the
     * width through density (`screenWidthPx / widthDp`) instead of a `width(dp)`
     * box, which the root view would coerce back to the physical width at 480dp.
     */
    private val screenWidthPx = context.resources.displayMetrics.widthPixels

    /** Scenario currently rendered by [setPanel]; drives the dp/px conversions. */
    private var scenarioState: MutableState<PanelScenario>? = null

    private val activeDensity: Float
        get() = scenarioState?.let { screenWidthPx.toFloat() / it.value.widthDp }
            ?: rule.density.density

    private fun pxOf(dpValue: Float): Float = dpValue * activeDensity

    private fun dpOf(pxValue: Float): Float = pxValue / activeDensity

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float) {
        assertTrue("$message (expected=$expected actual=$actual tolerance=$tolerance)",
            abs(expected - actual) <= tolerance)
    }

    /** Compact Mosaico card height: 8dp mounts + the crop's own inner aspect. */
    private fun mosaicExpectedCardHeightDp(cellWidthDp: Float, ratio: Float): Float =
        2f * GENRE_ARTWORK_MOUNT_INSET_DP +
            (cellWidthDp - 2f * GENRE_ARTWORK_MOUNT_INSET_DP) / ratio

    /** Compact Editorial card height: mounts + uncropped band + 4dp + count row. */
    private fun editorialExpectedCardHeightDp(
        cellWidthDp: Float,
        ratio: Float,
        fontScale: Float,
    ): Float {
        val band = editorialPosterBandHeightDp(
            innerWidthDp = cellWidthDp - 2f * EDITORIAL_POSTER_MOUNT_INSET_DP,
            maxBandHeightDp = 10_000f,
            posterRatio = ratio,
        )
        return editorialPosterCardHeightDp(band, genreCounterHeightDp(fontScale))
    }

    private fun mosaicArtworkBounds(card: Rect, ratio: Float): Rect {
        val mount = GENRE_ARTWORK_MOUNT_INSET_DP.toFloat()
        val innerWidth = dpOf(card.width) - 2f * mount
        val innerHeight = dpOf(card.height) - 2f * mount
        val fit = genreArtworkFitRect(innerWidth, innerHeight, ratio)!!
        return Rect(
            card.left + pxOf(fit.left + mount),
            card.top + pxOf(fit.top + mount),
            card.left + pxOf(fit.right + mount),
            card.top + pxOf(fit.bottom + mount),
        )
    }

    private fun bounds(tag: String, unmergedTree: Boolean = false): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = unmergedTree)
            .fetchSemanticsNode().boundsInRoot

    private fun record(line: String) {
        println(line)
        evidenceLines += line
    }

    private fun setPanel(
        scenario: MutableState<PanelScenario>,
        chrome: Boolean = false,
    ) {
        scenarioState = scenario
        val genres = fixture.genres
        val playableIds = fixture.tracks.map { it.id }.toSet()
        rule.setContent {
            val current = scenario.value
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(baseConfiguration, current) {
                Configuration(baseConfiguration).apply {
                    screenWidthDp = current.widthDp
                    fontScale = current.fontScale
                }
            }
            // The requested width owns the real screen pixels, exactly like the
            // 312-genre inventory harness: 411dp stays the reference phone and a
            // 480dp panel is measured instead of silently coerced to 411dp.
            val density = Density(
                density = screenWidthPx.toFloat() / current.widthDp,
                fontScale = current.fontScale,
            )
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides density,
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                    val panel: @Composable () -> Unit = {
                        GenreLibraryPanel(
                            genres = genres,
                            playableTrackIds = playableIds,
                            currentTrack = null,
                            isPlaying = false,
                            onPlay = { _, _ -> },
                            onArtworkLoad = { view, genreId, loaded ->
                                artworkReceipts["${view.name}:$genreId"] = loaded
                            },
                        )
                    }
                    if (chrome) {
                        // Mirrors MichiNavHost: content above the persistent mini
                        // player and the bottom dock, never underneath them.
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).testTag("host_content")) { panel() }
                            Box(
                                Modifier.fillMaxWidth().height(MichiSpacing.miniPlayerSpace)
                                    .background(Color(0xFF101318)).testTag("host_mini_player"),
                            )
                            Box(
                                Modifier.fillMaxWidth().height(96.dp)
                                    .background(Color(0xFF0B0D12)).testTag("host_nav_bar"),
                            )
                        }
                    } else {
                        Box(Modifier.width(current.widthDp.dp).fillMaxHeight()) { panel() }
                    }
                }
            }
        }
    }

    private fun openEditorial() {
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("genre_view_editorial").performClick()
        rule.waitForIdle()
    }

    private fun openMosaico() {
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("genre_view_featured").performClick()
        rule.waitForIdle()
    }

    private fun goBackToOverview() {
        rule.onNodeWithContentDescription("Volver a géneros").performClick()
        rule.waitForIdle()
    }

    private fun waitForPoster(title: String) {
        val tag = editorialPosterTag(title)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun approvedPosters(): Map<String, EditorialPosterAsset> {
        val assets = context.assets
        val installed = assets.list(editorialPosterFolder).orEmpty().toSet()
        val manifest = assets.open("$editorialPosterFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        return editorialApprovedPosters(manifest, installed)
    }

    private fun approvedMosaics(): Map<String, MosaicArtworkAsset> {
        val assets = context.assets
        val installed = assets.list(mosaicFolder).orEmpty().toSet()
        val manifest = assets.open("$mosaicFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        return mosaicApprovedArtwork(manifest, installed)
    }

    private fun mosaicAsset(title: String): MosaicArtworkAsset =
        mosaicArtworkForGenre(genreLayoutId(title), approvedMosaics())
            ?: error("no approved Mosaico crop for $title")

    private fun captureEvidence(tag: String, name: String) {
        val directory = evidenceDir ?: return
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val target = File(directory, "$name.png")
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("GENRE_EVIDENCE ${target.absolutePath} ${bitmap.width}x${bitmap.height}")
        evidenceLines += "capture $name ${bitmap.width}x${bitmap.height}"
    }

    private fun writeEvidence(name: String) {
        val directory = evidenceDir ?: return
        File(directory, name).writeText(evidenceLines.joinToString("\n"))
        evidenceLines.clear()
    }

    // endregion

    // region fixture

    @Test fun elevenGenreFixtureMirrorsThePhoneLibraryShape() {
        assertEquals(33, fixture.tracks.size)
        assertEquals(11, fixture.genres.size)
        assertEquals(28, fixture.genres.sumOf { it.albumCount })
        val byTitle = fixture.genres.associateBy { it.title }
        assertEquals(10, byTitle.getValue("New Wave").tracks.size)
        assertEquals(5, byTitle.getValue("New Wave").albumCount)
        assertEquals(5, byTitle.getValue("Pop").tracks.size)
        genreLayoutTwoTrackTitles.forEach { title ->
            assertEquals("$title track count", 2, byTitle.getValue(title).tracks.size)
        }
        assertEquals(
            listOf("New Wave", "Pop", "Ambient", "Blues", "Clásica", "Folk", "Jazz",
                "Metal", "Rock", "Soul", "Tango"),
            fixture.ordered.map { it.title },
        )
    }

    // endregion

    // region header and search removal

    @Test fun noSearchSurfaceExistsAndTheCompactHeaderSitsDirectlyAboveTheGrid() {
        setPanel(mutableStateOf(PanelScenario(411)))
        rule.waitForIdle()

        // The Phase B header has no search row at all: not the field, not the
        // legacy input, not a clear action, and no editable node of any kind.
        rule.onNodeWithTag("genres_search_field").assertDoesNotExist()
        rule.onNodeWithTag("genres_search_input").assertDoesNotExist()
        rule.onNodeWithTag("genres_search_clear").assertDoesNotExist()
        rule.onNodeWithTag("genres_search_empty").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("Buscar géneros").assertDoesNotExist()
        rule.onNodeWithContentDescription("Buscar géneros en esta biblioteca").assertDoesNotExist()

        val headerTitle = rule.onNodeWithTag("genres_header_title")
        headerTitle.assertIsDisplayed().assertTextEquals("Géneros")
        rule.onNodeWithTag("genres_header_count").assertIsDisplayed().assertTextEquals("11 géneros")

        val titleBounds = bounds("genres_header_title")
        val countBounds = bounds("genres_header_count")
        val gridBounds = bounds("genres_featured")
        val selectorBounds = bounds("genre_view_selector")
        assertTrue("Header title must sit above the real genre count",
            countBounds.top >= titleBounds.bottom - pxOf(1f))
        assertTrue("The count must be directly above the grid: count=$countBounds grid=$gridBounds",
            gridBounds.top >= countBounds.bottom - pxOf(1f))
        assertTrue("No search row may sit between header and grid: gap=" +
            "${dpOf(gridBounds.top - countBounds.bottom)}dp",
            gridBounds.top - countBounds.bottom <= pxOf(24f))
        assertTrue("Compact header must not overlap the view selector",
            !titleBounds.overlaps(selectorBounds))
        assertTrue("The grid must start below the header",
            gridBounds.top >= titleBounds.bottom - pxOf(1f))

        // The focal card is genuinely on screen, not just present in semantics.
        rule.onNodeWithTag(mosaicTileTag("New Wave")).assertIsDisplayed()
        record("header_title=$titleBounds")
        record("header_count=$countBounds grid=$gridBounds selector=$selectorBounds")
    }

    // endregion

    // region Mosaico geometry

    @Test fun mosaicHeroIsFullWidthAndSecondariesPairOnPhoneWidths() {
        val scenario = mutableStateOf(PanelScenario(360))
        setPanel(scenario)
        rule.waitForIdle()

        // Latest approved contract: 320 and 360 stay single-column; 390/411/480 pair.
        val cases = listOf(320 to 1, 360 to 1, 390 to 2, 411 to 2, 480 to 2)
        cases.forEach { (width, expectedColumns) ->
            rule.runOnIdle { scenario.value = PanelScenario(width, 1f) }
            rule.waitForIdle()
            flowCheck_Mosaico(width, expectedColumns, 1f)
        }
        writeEvidence("mosaico-bounds.txt")
    }

    private fun flowCheck_Mosaico(width: Int, expectedColumns: Int, fontScale: Float) {
        // The featured grid keeps its scroll position across widths, so anchor the
        // focal card at the viewport top before measuring the rhythm.
        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("New Wave")))
        rule.waitForIdle()
        val grid = bounds("genres_featured")
        val contentWidth = grid.width - pxOf(2f * GENRE_GRID_SIDE_PADDING_DP)
        assertNear("[$width/${fontScale}] grid must span the panel", width.toFloat(), dpOf(grid.width), 1f)
        val hero = bounds(mosaicTileTag("New Wave"))
        val expectedHeroHeight = pxOf(genreMosaicHeroHeightDp(fontScale).toFloat())
        rule.onNodeWithTag(mosaicTileTag("New Wave")).assertIsDisplayed()
        assertNear("[$width/${fontScale}] hero must be full width", contentWidth, hero.width, pxOf(1.5f))
        assertNear("[$width/${fontScale}] hero height stays the focal treatment",
            expectedHeroHeight, hero.height, pxOf(1.5f))
        assertNear("[$width/${fontScale}] hero must keep the 20dp gutters",
            pxOf(GENRE_GRID_SIDE_PADDING_DP.toFloat()), hero.left, pxOf(1f))
        assertTrue("[$width/${fontScale}] hero must keep a 48dp target",
            hero.height >= pxOf(48f))

        // Exactly one title: the approved crop owns its baked title, so Compose
        // must not paint a second one, and the card exposes one scoped name.
        rule.onNodeWithTag(mosaicTileTag("New Wave"))
            .assertContentDescriptionEquals("Abrir género New Wave, 10 pistas")
        rule.onAllNodesWithText("New Wave", useUnmergedTree = true).assertCountEquals(0)

        // The hero counter is 12dp inside the *artwork* border, not the card's.
        val heroAsset = mosaicAsset("New Wave")
        val heroArt = mosaicArtworkBounds(hero, heroAsset.ratio)
        val heroCounter = bounds("genre_counter_new wave", unmergedTree = true)
        assertNear("[$width/${fontScale}] hero counter must be 12dp inside the artwork right edge",
            heroArt.right - pxOf(GENRE_COUNTER_INSET_DP.toFloat()), heroCounter.right, pxOf(1f))
        assertNear("[$width/${fontScale}] hero counter must be 12dp from the artwork top",
            heroArt.top + pxOf(GENRE_COUNTER_INSET_DP.toFloat()), heroCounter.top, pxOf(1f))
        assertTrue("[$width/${fontScale}] hero counter must stay inside the card",
            heroCounter.left >= hero.left && heroCounter.bottom <= hero.bottom)
        // The whole crop is mounted inside the card: no rounded-clip cut at the
        // baked frame and no second border.
        assertTrue("[$width/${fontScale}] hero crop must start at the 8dp mount",
            heroArt.top >= hero.top + pxOf(GENRE_ARTWORK_MOUNT_INSET_DP.toFloat()) - pxOf(1f))

        // First secondary must be displayed with positive bounds and no overlap.
        val firstSecondary = rule.onNodeWithTag(mosaicTileTag("Pop"))
        firstSecondary.assertIsDisplayed()
        val pop = firstSecondary.fetchSemanticsNode().boundsInRoot
        assertTrue("[$width/${fontScale}] first secondary must have visible area: $pop",
            pop.width > 0f && pop.height > 0f)
        assertTrue("[$width/${fontScale}] secondary must start after the 10dp gap: " +
            "hero=${hero.bottom} pop=${pop.top}",
            pop.top >= hero.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        assertTrue("[$width/${fontScale}] secondary must not overlap the hero",
            !pop.overlaps(hero))

        val ambient = bounds(mosaicTileTag("Ambient"))
        val blues = bounds(mosaicTileTag("Blues"))
        val expectedCell = if (expectedColumns == 2) {
            (contentWidth - pxOf(GENRE_GRID_GAP_DP.toFloat())) / 2f
        } else {
            contentWidth
        }
        listOf(pop to "Pop", ambient to "Ambient").forEach { (cell, title) ->
            assertNear("[$width/${fontScale}] compact cell width", expectedCell, cell.width, pxOf(1.5f))
            assertTrue("[$width/${fontScale}] compact cell must stay >= 156dp: ${dpOf(cell.width)}dp",
                cell.width >= pxOf(GENRE_COMPACT_TILE_MIN_WIDTH_DP.toFloat()) - pxOf(1f))
            val asset = mosaicAsset(title)
            assertNear("[$width/${fontScale}] $title compact card is artwork-driven, not a 166dp footer card",
                pxOf(mosaicExpectedCardHeightDp(dpOf(cell.width), asset.ratio)),
                cell.height, pxOf(2f))
        }
        // A tile that only pokes into the viewport is measured clamped to the
        // remaining space: its width is still the cell width, but its exterior
        // height is proven below after scrolling it fully into view.
        assertNear("[$width/${fontScale}] last cell width", expectedCell, blues.width, pxOf(1.5f))
        assertTrue("[$width/${fontScale}] last cell must stay >= 156dp: ${dpOf(blues.width)}dp",
            blues.width >= pxOf(GENRE_COMPACT_TILE_MIN_WIDTH_DP.toFloat()) - pxOf(1f))
        if (expectedColumns == 2) {
            assertNear("[$width/${fontScale}] second row shares one top", pop.top, ambient.top, pxOf(1f))
            assertTrue("[$width/${fontScale}] adjacent cells keep the 10dp gap",
                ambient.left >= pop.right + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
            assertTrue("[$width/${fontScale}] second row must start below the first",
                blues.top >= pop.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        } else {
            assertTrue("[$width/${fontScale}] single column must stack secondaries",
                ambient.top >= pop.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        }
        record("[$width/$fontScale] mosaic columns=$expectedColumns cell=${dpOf(pop.width)}dp " +
            "hero=${dpOf(hero.width)}x${dpOf(hero.height)}dp card=${dpOf(pop.height)}dp")

        // No footer and no reserved band: the 8dp mount is the only matte, the
        // crop fills the rest of the card, and the counter is an overlay in the
        // upper part (never a separate surface below the artwork).
        val popAsset = mosaicAsset("Pop")
        val popArt = mosaicArtworkBounds(pop, popAsset.ratio)
        assertTrue("[$width/${fontScale}] crop must fill the card inner width: " +
            "card=${dpOf(pop.width)}dp art=${dpOf(popArt.width)}dp",
            abs(dpOf(popArt.width) - (dpOf(pop.width) - 2f * GENRE_ARTWORK_MOUNT_INSET_DP)) <= 1.5f)
        assertTrue("[$width/${fontScale}] crop must fill the card inner height: " +
            "card=${dpOf(pop.height)}dp art=${dpOf(popArt.height)}dp",
            abs(dpOf(popArt.height) - (dpOf(pop.height) - 2f * GENRE_ARTWORK_MOUNT_INSET_DP)) <= 1.5f)
        val counter = bounds("genre_counter_pop", unmergedTree = true)
        // The chip lives in the artwork's verified top-right safe band, so its
        // inset is measured from the artwork border, not from the card edge:
        // the 8dp matte sits between them. Measuring from the card would make
        // the same chip drift by the mount as the width changes.
        assertNear("[$width/${fontScale}] compact counter is 12dp inside the artwork right",
            popArt.right - pxOf(GENRE_COUNTER_INSET_DP.toFloat()), counter.right, pxOf(1.5f))
        assertNear("[$width/${fontScale}] compact counter is 12dp below the artwork top",
            popArt.top + pxOf(GENRE_COUNTER_INSET_DP.toFloat()), counter.top, pxOf(1.5f))
        assertTrue("[$width/${fontScale}] compact counter is an overlay, not a footer: " +
            "counter=$counter art=$popArt",
            counter.top < popArt.bottom && counter.bottom > popArt.top &&
                counter.top <= pop.top + pop.height * 0.55f)
        assertTrue("[$width/${fontScale}] counter must stay inside the card",
            counter.left >= pop.left && counter.right <= pop.right &&
                counter.top >= pop.top && counter.bottom <= pop.bottom)
        // No bottom footer surface: nothing may be reserved below the crop.
        assertNear("[$width/${fontScale}] crop reaches the bottom mount",
            pop.bottom - pxOf(GENRE_ARTWORK_MOUNT_INSET_DP.toFloat()), popArt.bottom, pxOf(1.5f))
        // The approved crop already carries "Pop"; Compose never duplicates it.
        rule.onAllNodesWithText("Pop", useUnmergedTree = true).assertCountEquals(0)

        // Prove the last compact card fully after scrolling it into view.
        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("Blues")))
        rule.waitForIdle()
        val bluesFull = bounds(mosaicTileTag("Blues"))
        assertNear("[$width/${fontScale}] scrolled last cell width",
            expectedCell, bluesFull.width, pxOf(1.5f))
        val bluesAsset = mosaicAsset("Blues")
        assertNear("[$width/${fontScale}] scrolled last card is artwork-driven",
            pxOf(mosaicExpectedCardHeightDp(dpOf(bluesFull.width), bluesAsset.ratio)),
            bluesFull.height, pxOf(2f))
    }

    // endregion

    // region Editorial geometry

    @Test fun editorialKeepsCoverAndPortraitPairRhythmInsteadOfBanners() {
        setPanel(mutableStateOf(PanelScenario(411)))
        rule.waitForIdle()
        openEditorial()
        waitForPoster("New Wave")
        rule.onNodeWithTag("genres_editorial").assertIsDisplayed()

        val posters = approvedPosters()
        fixture.ordered.forEach { genre ->
            assertNotNull("Missing approved poster for ${genre.title}",
                editorialPosterForTitle(genre.title, posters))
        }
        assertEquals(11, fixture.ordered.mapNotNull {
            editorialPosterForTitle(it.title, posters)
        }.size)

        val rows = editorialRows(fixture.ordered, columns = 2)
        val coverTitles = rows.filterIsInstance<EditorialRow.Cover>().map { it.genre.title }
        val pairTitles = rows.filterIsInstance<EditorialRow.Pair>()
            .flatMap { listOf(it.leading.title, it.trailing.title) }
        assertEquals("COVER rhythm changed", 3, coverTitles.size)
        assertEquals("pair rhythm changed", 8, pairTitles.size)
        assertEquals(11, coverTitles.size + pairTitles.size)

        val grid = bounds("genres_editorial")
        val contentWidth = grid.width - pxOf(2f * GENRE_GRID_SIDE_PADDING_DP)
        val halfCell = (contentWidth - pxOf(GENRE_GRID_GAP_DP.toFloat())) / 2f

        data class TileRecord(
            val title: String,
            val width: Float,
            val height: Float,
            val ratio: Float,
        )
        val treatmentByTitle = rows.flatMap { row ->
            when (row) {
                is EditorialRow.Cover -> listOf(row.genre.title to GenreEditorialTreatment.COVER)
                is EditorialRow.Pair -> listOf(
                    row.leading.title to GenreEditorialTreatment.PORTRAIT_LEFT,
                    row.trailing.title to GenreEditorialTreatment.PORTRAIT_RIGHT,
                )
            }
        }.toMap()
        // 1.0x compact rhythm: each historical poster keeps its own proportional
        // height plus one integrated count row. The 246dp treatment is only a
        // cap for authentic vertical assets, which this pack does not contain.
        val records = fixture.ordered.map { genre ->
            val tag = editorialTileTag(genre.title)
            rule.onNodeWithTag("genres_editorial").performScrollToNode(hasTestTag(tag))
            rule.waitForIdle()
            val tileNode = rule.onNodeWithTag(tag)
            tileNode.assertIsDisplayed().assertHasClickAction()
            val count = genre.tracks.size
            tileNode.assertContentDescriptionEquals(
                "Abrir género ${genre.title}, $count ${if (count == 1) "pista" else "pistas"}",
            )
            assertEquals("Exactly one card per genre",
                1, rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size)
            val tile = tileNode.fetchSemanticsNode().boundsInRoot
            val poster = bounds(editorialPosterTag(genre.title), unmergedTree = true)
            val ratio = editorialPosterForTitle(genre.title, posters)!!.ratio
            val treatment = treatmentByTitle.getValue(genre.title)

            assertTrue("${genre.title} card must keep a 48dp target: $tile",
                tile.height >= pxOf(48f))
            val expectedHeight = editorialExpectedCardHeightDp(dpOf(tile.width), ratio, 1f)
            assertNear("${genre.title} card must keep its compact proportional height",
                pxOf(expectedHeight), tile.height, pxOf(2f))
            // The guard is against the fixed treatment, not against being tall:
            // a full-width COVER whose approved sheet is near 1.6:1 is honestly
            // taller than 246dp. The exact compact height above is the contract.
            assertTrue("${genre.title} horizontal poster must not use the fixed 246dp " +
                "treatment: ${dpOf(tile.height)}dp", abs(dpOf(tile.height) - 246f) > 1f)
            // The whole poster is preserved (no false vertical crop) and mounted
            // inside the card's one continuous surface.
            val posterWidthDp = dpOf(poster.width)
            assertNear("${genre.title} poster must keep its approved aspect",
                ratio, posterWidthDp / dpOf(poster.height), 0.02f)
            assertTrue("${genre.title} poster must not be cropped by the card width: " +
                "poster=${dpOf(poster.width)}dp tile=${dpOf(tile.width)}dp",
                dpOf(poster.width) <=
                    dpOf(tile.width) - 2 * EDITORIAL_POSTER_MOUNT_INSET_DP + 1f)
            assertNear("${genre.title} poster starts at the 8dp top mount",
                tile.top + pxOf(EDITORIAL_POSTER_MOUNT_INSET_DP.toFloat()), poster.top, pxOf(1f))
            // The compact card is exactly the poster + 4dp + count row + 8dp:
            // there is no large empty vertical band between them.
            assertNear("${genre.title} poster uses the full inner width",
                tile.width - pxOf(2f * EDITORIAL_POSTER_MOUNT_INSET_DP), poster.width, pxOf(1.5f))
            assertNear("${genre.title} poster band height",
                pxOf(posterWidthDp / ratio), poster.height, pxOf(1.5f))
            // Inventory gap is technical only: no pending warning on the card.
            assertTrue("${genre.title} must not render pending-resource technical text",
                rule.onAllNodesWithTag(
                    "genre_editorial_pending_vertical_${genre.id}", useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty())
            // No second Compose title: the approved poster already contains it.
            rule.onAllNodesWithText(genre.title, useUnmergedTree = true).assertCountEquals(0)
            val counter = rule.onNodeWithTag("genre_counter_${genre.id}", useUnmergedTree = true)
            counter.assertIsDisplayed()
            val counterBounds = counter.fetchSemanticsNode().boundsInRoot
            assertNear("${genre.title} counter follows its poster after 4dp",
                poster.bottom + pxOf(EDITORIAL_POSTER_COUNTER_GAP_DP.toFloat()),
                counterBounds.top, pxOf(1f))
            assertNear("${genre.title} counter keeps the 8dp bottom mount",
                tile.bottom - pxOf(EDITORIAL_POSTER_MOUNT_INSET_DP.toFloat()),
                counterBounds.bottom, pxOf(1f))
            assertEquals("${genre.title} card has exactly one continuous surface",
                0, rule.onAllNodesWithTag("genre_editorial_footer_${genre.id}",
                    useUnmergedTree = true).fetchSemanticsNodes().size)
            assertTrue("${genre.title} counter must sit inside the poster card: " +
                "counter=$counterBounds tile=$tile",
                counterBounds.left >= tile.left && counterBounds.right <= tile.right &&
                    counterBounds.top >= tile.top && counterBounds.bottom <= tile.bottom)
            if (treatment == GenreEditorialTreatment.PORTRAIT_RIGHT) {
                assertNear("${genre.title} trailing pair counter is mirrored",
                    tile.right - pxOf(EDITORIAL_POSTER_MOUNT_INSET_DP.toFloat()),
                    counterBounds.right, pxOf(1f))
            } else {
                assertNear("${genre.title} counter keeps the leading anchor",
                    tile.left + pxOf(EDITORIAL_POSTER_MOUNT_INSET_DP.toFloat()),
                    counterBounds.left, pxOf(1f))
            }
            record("editorial ${genre.title} treatment=$treatment " +
                "tile=${dpOf(tile.width)}x${dpOf(tile.height)}dp ratio=$ratio")
            TileRecord(genre.title, tile.width, tile.height, ratio)
        }
        assertEquals(11, records.size)

        // Pair rhythm: equal cell width and top alignment, but each historical
        // poster keeps its own compact height. No shared 246dp padding and no
        // bottom-aligned black block.
        val pairHeights = fixture.ordered.chunked(3).mapNotNull { chunk ->
            if (chunk.size < 3) null else {
                // The lazy list keeps only the visible rows composed: bring the
                // pair back before reading its geometry.
                rule.onNodeWithTag("genres_editorial")
                    .performScrollToNode(hasTestTag(editorialTileTag(chunk[1].title)))
                rule.waitForIdle()
                val left = bounds(editorialTileTag(chunk[1].title))
                val right = bounds(editorialTileTag(chunk[2].title))
                left to right
            }
        }
        pairHeights.forEach { (left, right) ->
            assertNear("paired cards must share one width", left.width, right.width, pxOf(1f))
            assertNear("paired cards must be top-aligned", left.top, right.top, pxOf(1f))
            assertTrue("paired historical posters keep different compact heights: " +
                "left=${dpOf(left.height)}dp right=${dpOf(right.height)}dp",
                abs(dpOf(left.height) - dpOf(right.height)) > 0.5f)
            assertTrue("paired cards must not be padded to one 246dp exterior",
                dpOf(left.height) < 246f && dpOf(right.height) < 246f)
        }

        // Only the three COVER rows are full width; the eight portrait cells are
        // compact. A wall of eleven banners fails here.
        val fullWidth = records.filter { abs(it.width - contentWidth) <= pxOf(1.5f) }.map { it.title }
        val compact = records.filter { abs(it.width - halfCell) <= pxOf(1.5f) }.map { it.title }
        assertEquals("COVER rows must be exactly the three positional covers: $fullWidth",
            coverTitles.toSet(), fullWidth.toSet())
        assertEquals("Portrait pairs must be the eight compact cells: $compact",
            pairTitles.toSet(), compact.toSet())
        assertTrue("Editorial must not render 11 full-width banners", fullWidth.size <= 3)

        // Back to the top: the first cards are displayed and the rhythm is real.
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("New Wave")))
        rule.waitForIdle()
        rule.onNodeWithTag(editorialTileTag("New Wave")).assertIsDisplayed()
        rule.onNodeWithTag(editorialTileTag("Pop")).assertIsDisplayed()
        rule.onNodeWithTag(editorialTileTag("Ambient")).assertIsDisplayed()
        val cover = bounds(editorialTileTag("New Wave"))
        val pairLeft = bounds(editorialTileTag("Pop"))
        val pairRight = bounds(editorialTileTag("Ambient"))
        assertTrue("Cover must be followed by the portrait pair after a 10dp gap",
            pairLeft.top >= cover.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        assertTrue("Portrait pair must not overlap the cover", !cover.overlaps(pairLeft))
        assertNear("Pair must share one row top", pairLeft.top, pairRight.top, pxOf(1f))
        assertTrue("Pair must keep the 10dp horizontal gap",
            pairRight.left >= pairLeft.right + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        record("editorial cover=${dpOf(cover.width)}x${dpOf(cover.height)}dp " +
            "pair=${dpOf(pairLeft.width)}x${dpOf(pairLeft.height)}dp")
        record("editorial covers=$coverTitles pairs=$pairTitles")

        captureEvidence("genres_overview", "editorial-411-11genres")
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Tango")))
        rule.waitForIdle()
        captureEvidence("genres_overview", "editorial-411-scrolled")
        writeEvidence("editorial-bounds.txt")
    }

    // endregion

    // region interaction

    @Test fun viewSelectionPersistsAcrossDetailNavigation() {
        setPanel(mutableStateOf(PanelScenario(411)))
        rule.waitForIdle()
        rule.onNodeWithTag("genres_featured").assertIsDisplayed()

        openEditorial()
        rule.onNodeWithTag("genres_editorial").assertIsDisplayed()
        rule.onNodeWithTag("genres_featured").assertDoesNotExist()
        waitForPoster("New Wave")

        rule.onNodeWithTag(editorialTileTag("New Wave")).performClick()
        rule.onNodeWithTag("genre_detail").assertIsDisplayed()
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("New Wave")
        goBackToOverview()
        rule.onNodeWithTag("genres_editorial").assertIsDisplayed()
        rule.onNodeWithTag("genres_featured").assertDoesNotExist()

        openMosaico()
        rule.onNodeWithTag("genres_featured").assertIsDisplayed()
        rule.onNodeWithTag("genres_editorial").assertDoesNotExist()

        rule.onNodeWithTag(mosaicTileTag("Pop")).performClick()
        rule.onNodeWithTag("genre_detail").assertIsDisplayed()
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("Pop")
        goBackToOverview()
        rule.onNodeWithTag("genres_featured").assertIsDisplayed()
        rule.onNodeWithTag("genres_editorial").assertDoesNotExist()
    }

    @Test fun cardsAreClickableAndBothViewsScrollToTheLastGenre() {
        setPanel(mutableStateOf(PanelScenario(411)))
        rule.waitForIdle()

        rule.onNodeWithTag(mosaicTileTag("New Wave")).assertHasClickAction().performClick()
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("New Wave")
        goBackToOverview()

        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("Tango")))
        rule.waitForIdle()
        rule.onNodeWithTag(mosaicTileTag("Tango")).assertIsDisplayed().assertHasClickAction()
        val tango = bounds(mosaicTileTag("Tango"))
        assertTrue("Scrolled last Mosaico card must have visible area: $tango",
            tango.width > 0f && tango.height > 0f)
        rule.onNodeWithTag(mosaicTileTag("Tango")).performClick()
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("Tango")
        goBackToOverview()

        openEditorial()
        waitForPoster("New Wave")
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Tango")))
        rule.waitForIdle()
        rule.onNodeWithTag(editorialTileTag("Tango")).assertIsDisplayed().assertHasClickAction()
        val editorialTango = bounds(editorialTileTag("Tango"))
        assertTrue("Scrolled last Editorial card must have visible area: $editorialTango",
            editorialTango.width > 0f && editorialTango.height > 0f)
        rule.onNodeWithTag(editorialTileTag("Tango")).performClick()
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("Tango")
        goBackToOverview()
        rule.onNodeWithTag("genres_editorial").assertIsDisplayed()
    }

    // endregion

    // region adaptation

    @Test fun narrowThreeHundredTwentyPanelUsesOneColumnInBothViews() {
        val scenario = mutableStateOf(PanelScenario(320))
        setPanel(scenario)
        rule.waitForIdle()

        val grid = bounds("genres_featured")
        val contentWidth = grid.width - pxOf(2f * GENRE_GRID_SIDE_PADDING_DP)
        val pop = bounds(mosaicTileTag("Pop"))
        val ambient = bounds(mosaicTileTag("Ambient"))
        assertNear("320dp Mosaico must use the full content width", contentWidth, pop.width,
            pxOf(1.5f))
        assertTrue("320dp Mosaico secondaries must stack",
            ambient.top >= pop.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        rule.onNodeWithTag(mosaicTileTag("Ambient")).assertIsDisplayed()

        openEditorial()
        waitForPoster("New Wave")
        val editorialGrid = bounds("genres_editorial")
        val editorialContent = editorialGrid.width - pxOf(2f * GENRE_GRID_SIDE_PADDING_DP)
        listOf("New Wave", "Pop", "Ambient").forEach { title ->
            val tile = bounds(editorialTileTag(title))
            assertNear("320dp Editorial $title must be a one-column cover",
                editorialContent, tile.width, pxOf(1.5f))
        }
        val cover = bounds(editorialTileTag("New Wave"))
        val second = bounds(editorialTileTag("Pop"))
        assertTrue("320dp Editorial rows must stack",
            second.top >= cover.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Tango")))
        rule.waitForIdle()
        val last = bounds(editorialTileTag("Tango"))
        assertNear("320dp Editorial last card stays one column", editorialContent, last.width,
            pxOf(1.5f))
        rule.onNodeWithTag(editorialTileTag("Tango")).assertIsDisplayed()
        record("320dp mosaic_cell=${dpOf(pop.width)}dp editorial_cover=${dpOf(cover.width)}dp")
    }

    @Test fun widthAndFontScaleMatrixKeepsBothViewsLegibleWithoutOverlap() {
        val scenario = mutableStateOf(PanelScenario(360, 1f))
        setPanel(scenario)
        rule.waitForIdle()

        val widths = listOf(320, 360, 390, 411, 480)
        val scales = listOf(1f, 1.3f, 1.5f, 2f)
        widths.forEach { width ->
            scales.forEach { fontScale ->
                rule.runOnIdle { scenario.value = PanelScenario(width, fontScale) }
                rule.waitForIdle()
                flowCheck_MatrixBeforeSwitch(width, fontScale)

                openEditorial()
                waitForPoster("New Wave")
                flowCheck_MatrixEditorial(width, fontScale)
                openMosaico()
                rule.runOnIdle { scenario.value = PanelScenario(360, 1f) }
                rule.waitForIdle()
            }
        }
        writeEvidence("font-scale-bounds.txt")
    }

    private fun flowCheck_MatrixBeforeSwitch(width: Int, fontScale: Float) {
        val grid = bounds("genres_featured")
        val contentWidth = grid.width - pxOf(2f * GENRE_GRID_SIDE_PADDING_DP)
        // Header stays inside the panel and clear of the selector.
        val title = bounds("genres_header_title")
        val count = bounds("genres_header_count")
        val selector = bounds("genre_view_selector")
        assertTrue("[$width/$fontScale] header title must be above the count",
            count.top >= title.bottom - pxOf(1f))
        assertTrue("[$width/$fontScale] header must not overlap the selector",
            !title.overlaps(selector) && !count.overlaps(selector))
        assertTrue("[$width/$fontScale] header title must start at the 20dp gutter",
            title.left >= pxOf(GENRE_GRID_SIDE_PADDING_DP.toFloat()) - pxOf(1f))
        assertTrue("[$width/$fontScale] header must sit above the grid",
            grid.top >= count.bottom - pxOf(1f))

        val hero = bounds(mosaicTileTag("New Wave"))
        assertNear("[$width/$fontScale] hero height", pxOf(genreMosaicHeroHeightDp(fontScale).toFloat()),
            hero.height, pxOf(1.5f))
        rule.onNodeWithTag(mosaicTileTag("New Wave")).assertIsDisplayed()
        val pop = bounds(mosaicTileTag("Pop"))
        val ambient = bounds(mosaicTileTag("Ambient"))
        val popAsset = mosaicAsset("Pop")
        assertNear("[$width/$fontScale] compact height is artwork-driven",
            pxOf(mosaicExpectedCardHeightDp(dpOf(pop.width), popAsset.ratio)),
            pop.height, pxOf(2f))
        assertTrue("[$width/$fontScale] compact card must stay >=156dp: ${dpOf(pop.width)}dp",
            pop.width >= pxOf(GENRE_COMPACT_TILE_MIN_WIDTH_DP.toFloat()) - pxOf(1f))
        assertTrue("[$width/$fontScale] every card must stay inside the panel: " +
            "grid=$grid pop=$pop",
            pop.left >= grid.left && pop.right <= grid.right)
        val columns = if (abs(pop.top - ambient.top) <= pxOf(1f)) 2 else 1
        if (columns == 2) {
            assertTrue("[$width/$fontScale] paired cells keep the 10dp gap",
                ambient.left >= pop.right + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        } else {
            assertTrue("[$width/$fontScale] stacked cells keep the 10dp gap",
                ambient.top >= pop.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        }
        val popArt = mosaicArtworkBounds(pop, popAsset.ratio)
        val counter = bounds("genre_counter_pop", unmergedTree = true)
        assertTrue("[$width/$fontScale] counter must stay inside its card: " +
            "counter=$counter card=$pop",
            counter.left >= pop.left && counter.right <= pop.right &&
                counter.top >= pop.top && counter.bottom <= pop.bottom)
        assertTrue("[$width/$fontScale] counter is an overlay over the crop, not a footer",
            counter.top < popArt.bottom && counter.bottom > popArt.top &&
                counter.top <= pop.top + pop.height * 0.55f)
        // The crop owns the card's proportion; only the column count adapts, so
        // no font is reduced and no title is truncated.
        record("[$width/$fontScale] mosaic columns=$columns cell=${dpOf(pop.width)}dp " +
            "hero=${dpOf(hero.width)}x${dpOf(hero.height)}dp card=${dpOf(pop.height)}dp")
    }

    private fun flowCheck_MatrixEditorial(width: Int, fontScale: Float) {
        rule.onNodeWithTag("genres_editorial").assertIsDisplayed()
        val grid = bounds("genres_editorial")
        val contentWidth = grid.width - pxOf(2f * GENRE_GRID_SIDE_PADDING_DP)
        val posters = approvedPosters()
        // Anchor the first row at the viewport top so the rhythm reads below all
        // describe the same scroll position.
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("New Wave")))
        rule.waitForIdle()
        val cover = bounds(editorialTileTag("New Wave"))
        val pairLeft = bounds(editorialTileTag("Pop"))
        val pairRight = bounds(editorialTileTag("Ambient"))
        // The Editorial rhythm shares the measured column policy with Mosaico:
        // one column at 320/360dp renders every genre as its own compact cover.
        val columns = if (abs(pairLeft.top - pairRight.top) <= pxOf(1f)) 2 else 1
        fun expectedHeight(title: String, widthDp: Float): Float {
            val ratio = editorialPosterForTitle(title, posters)!!.ratio
            return editorialExpectedCardHeightDp(widthDp, ratio, fontScale)
        }
        assertNear("[$width/$fontScale] editorial cover height is compact/proportional",
            pxOf(expectedHeight("New Wave", dpOf(cover.width))), cover.height, pxOf(2f))
        assertNear("[$width/$fontScale] cover must be full width", contentWidth, cover.width,
            pxOf(1.5f))
        if (columns == 2) {
            val halfCellDp = dpOf(pairLeft.width)
            assertNear("[$width/$fontScale] Pop pair height is compact/proportional",
                pxOf(expectedHeight("Pop", halfCellDp)), pairLeft.height, pxOf(2f))
            assertNear("[$width/$fontScale] Ambient pair height is compact/proportional",
                pxOf(expectedHeight("Ambient", dpOf(pairRight.width))), pairRight.height, pxOf(2f))
            assertNear("[$width/$fontScale] paired cards share one width",
                pairLeft.width, pairRight.width, pxOf(1f))
            assertNear("[$width/$fontScale] paired cards are top-aligned",
                pairLeft.top, pairRight.top, pxOf(1f))
            // Different historical ratios keep different compact heights. They
            // must not be padded to a shared 246dp or aligned by a black block.
            assertTrue("[$width/$fontScale] pair must not force a shared tall height",
                abs(dpOf(pairLeft.height) - dpOf(pairRight.height)) > 0.5f)
            assertTrue("[$width/$fontScale] cover and pair must not intersect",
                !cover.overlaps(pairLeft))
            assertTrue("[$width/$fontScale] pair must keep the 10dp gap",
                pairRight.left >= pairLeft.right + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
            assertTrue("[$width/$fontScale] editorial rows must not overlap",
                pairLeft.top >= cover.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
        } else {
            // One column: Pop/Ambient are full-width compact covers, stacked.
            assertTrue("[$width/$fontScale] one-column Pop must not intersect the cover",
                !cover.overlaps(pairLeft))
            assertTrue("[$width/$fontScale] one-column Ambient must not intersect the cover",
                !cover.overlaps(pairRight))
            assertTrue("[$width/$fontScale] one-column rows must stack",
                pairRight.top >= pairLeft.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
            listOf("Pop", "Ambient").forEach { title ->
                rule.onNodeWithTag("genres_editorial")
                    .performScrollToNode(hasTestTag(editorialTileTag(title)))
                rule.waitForIdle()
                val tile = bounds(editorialTileTag(title))
                assertNear("[$width/$fontScale] one-column $title compact height",
                    pxOf(expectedHeight(title, dpOf(tile.width))), tile.height, pxOf(2f))
                assertNear("[$width/$fontScale] one-column $title must be full width",
                    contentWidth, tile.width, pxOf(1.5f))
            }
        }
        // Measure a pair card, its poster and its chip in one frame.
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Pop")))
        rule.waitForIdle()
        val popCard = bounds(editorialTileTag("Pop"))
        val poster = bounds(editorialPosterTag("Pop"), unmergedTree = true)
        val ratio = posters.let { editorialPosterForTitle("Pop", it)!!.ratio }
        assertNear("[$width/$fontScale] poster keeps its approved aspect", ratio,
            dpOf(poster.width) / dpOf(poster.height), 0.02f)
        val counter = bounds("genre_counter_pop", unmergedTree = true)
        assertTrue("[$width/$fontScale] editorial counter must stay inside its card: " +
            "counter=$counter card=$popCard",
            counter.left >= popCard.left && counter.right <= popCard.right &&
                counter.top >= popCard.top && counter.bottom <= popCard.bottom)
        assertNear("[$width/$fontScale] editorial counter follows the poster after 4dp",
            dpOf(poster.bottom) + EDITORIAL_POSTER_COUNTER_GAP_DP, dpOf(counter.top), 1f)
        assertTrue("[$width/$fontScale] no pending-resource technical text on cards",
            rule.onAllNodesWithTag("genre_editorial_pending_vertical_pop",
                useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // No second title and no detached footer: the chip is the only extra ink.
        rule.onAllNodesWithText("Pop", useUnmergedTree = true).assertCountEquals(0)
        record("[$width/$fontScale] editorial columns=$columns " +
            "cover=${dpOf(cover.width)}x${dpOf(cover.height)}dp " +
            "pair=${dpOf(popCard.width)}x${dpOf(popCard.height)}dp")
    }

    // endregion

    // region host chrome

    @Test fun contentClearsThePersistentPlayerAndNavigationChrome() {
        setPanel(mutableStateOf(PanelScenario(411)), chrome = true)
        rule.waitForIdle()

        val content = bounds("host_content")
        val miniPlayer = bounds("host_mini_player")
        val navigation = bounds("host_nav_bar")
        rule.onNodeWithTag("host_mini_player").assertIsDisplayed()
        rule.onNodeWithTag("host_nav_bar").assertIsDisplayed()
        assertTrue("Genre content must end above the mini player: content=$content mini=$miniPlayer",
            content.bottom <= miniPlayer.top + pxOf(1f))
        assertTrue("Mini player must sit above the navigation dock",
            miniPlayer.bottom <= navigation.top + pxOf(1f))

        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("Tango")))
        rule.waitForIdle()
        val tango = bounds(mosaicTileTag("Tango"))
        rule.onNodeWithTag(mosaicTileTag("Tango")).assertIsDisplayed()
        assertTrue("Last Mosaico card must not overlap the mini player",
            !tango.overlaps(miniPlayer))
        assertTrue("Last Mosaico card must not overlap the navigation dock",
            !tango.overlaps(navigation))
        assertTrue("Last Mosaico card must stay inside the content area",
            tango.bottom <= content.bottom + pxOf(1f))

        openEditorial()
        waitForPoster("New Wave")
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Tango")))
        rule.waitForIdle()
        val editorialTango = bounds(editorialTileTag("Tango"))
        rule.onNodeWithTag(editorialTileTag("Tango")).assertIsDisplayed()
        assertTrue("Last Editorial card must not overlap the mini player",
            !editorialTango.overlaps(miniPlayer))
        assertTrue("Last Editorial card must not overlap the navigation dock",
            !editorialTango.overlaps(navigation))
        assertTrue("Last Editorial card must stay inside the content area",
            editorialTango.bottom <= content.bottom + pxOf(1f))
        record("chrome content=$content mini=$miniPlayer nav=$navigation")
    }

    // endregion

    // region visual evidence

    @Test fun captureMosaicoAndEditorialEvidence() {
        assumeTrue("Visual evidence is opt-in: -e michiGenreEvidence true", evidenceEnabled)
        setPanel(mutableStateOf(PanelScenario(411)))
        rule.waitForIdle()
        rule.waitUntil(20_000) {
            artworkReceipts.containsKey("FEATURED:new wave") &&
                artworkReceipts.containsKey("FEATURED:pop")
        }
        assertEquals(true, artworkReceipts["FEATURED:new wave"])
        assertEquals(true, artworkReceipts["FEATURED:pop"])
        captureEvidence("genres_overview", "mosaico-411-top")
        record("mosaico hero=${bounds(mosaicTileTag("New Wave"))} " +
            "pop=${bounds(mosaicTileTag("Pop"))} ambient=${bounds(mosaicTileTag("Ambient"))}")

        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("Tango")))
        rule.waitForIdle()
        captureEvidence("genres_overview", "mosaico-411-secondaries")
        record("mosaico tango=${bounds(mosaicTileTag("Tango"))}")

        openEditorial()
        waitForPoster("New Wave")
        rule.waitUntil(20_000) {
            artworkReceipts.containsKey("EDITORIAL:new wave") &&
                artworkReceipts.containsKey("EDITORIAL:pop")
        }
        assertEquals(true, artworkReceipts["EDITORIAL:new wave"])
        assertEquals(true, artworkReceipts["EDITORIAL:pop"])
        captureEvidence("genres_overview", "editorial-411-top")
        record("editorial new wave=${bounds(editorialTileTag("New Wave"))} " +
            "pop=${bounds(editorialTileTag("Pop"))} ambient=${bounds(editorialTileTag("Ambient"))}")

        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Tango")))
        rule.waitForIdle()
        captureEvidence("genres_overview", "editorial-411-scrolled")
        record("editorial tango=${bounds(editorialTileTag("Tango"))}")
        writeEvidence("visual-evidence-bounds.txt")
    }

    // endregion

    // region playback authority regressions (kept from the pre-Phase B gate)

    @Test fun detailPlaysOnlyAuthorizedTracksWithRealIndex() {
        val songs = listOf(
            Track(id = "a", title = "A", genre = "Jazz", sourceId = "one", filepath = "a.flac"),
            Track(id = "b", title = "B", genre = "Jazz", sourceId = "one", filepath = "b.flac"),
        )
        var lastIds: List<String> = emptyList()
        var lastIndex = -1
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(groupLibraryGenres(songs), setOf("b"), currentTrack = null,
                    isPlaying = false, onPlay = { queue, index ->
                        lastIds = queue.map { it.id }
                        lastIndex = index
                    })
            }
        }
        rule.onNodeWithTag("genre_tile_jazz").performClick()
        rule.onNodeWithTag("genre_track_a").assertIsNotEnabled()
        rule.onNodeWithTag("genre_track_b").performClick()
        rule.runOnIdle {
            assertEquals(listOf("b"), lastIds)
            assertEquals(0, lastIndex)
        }
    }

    @Test fun duplicateOwnerIdsAreNotAccidentallyAuthorized() {
        val songs = listOf(
            Track(id = "dup", title = "One", genre = "Jazz", sourceId = "one", filepath = "one.flac"),
            Track(id = "dup", title = "Two", genre = "Jazz", sourceId = "two", filepath = "two.flac"),
            Track(id = "unique", title = "Unique", genre = "Jazz", sourceId = "one",
                filepath = "ok.flac"),
        )
        var played: List<String> = emptyList()
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(groupLibraryGenres(songs), setOf("dup", "unique"), null,
                    isPlaying = false, onPlay = { queue, _ -> played = queue.map { it.id } })
            }
        }
        rule.onNodeWithTag("genre_tile_jazz").performClick()
        rule.onNodeWithTag("genre_track_unique").performClick()
        rule.runOnIdle { assertEquals(listOf("unique"), played) }
    }

    @Test fun blockedOwnerIdNeverReachesPlaybackEvenWhenGenreHasSingleTrack() {
        val only = Track(id = "shared", title = "Jazz", genre = "Jazz",
            sourceId = "peer-a", filepath = "one.flac")
        var invoked = false
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(groupLibraryGenres(listOf(only)), setOf("shared"), null,
                    isPlaying = false, onPlay = { _, _ -> invoked = true },
                    globallyBlockedTrackIds = setOf("shared"))
            }
        }
        rule.onNodeWithTag("genre_tile_jazz").performClick()
        rule.onNodeWithTag("genre_track_shared").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(false, invoked) }
    }

    // endregion
}
