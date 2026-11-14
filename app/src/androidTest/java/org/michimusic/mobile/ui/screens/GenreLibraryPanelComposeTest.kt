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

    private fun pxOf(dpValue: Float): Float = with(rule.density) { dpValue.dp.toPx() }

    private fun dpOf(pxValue: Float): Float = pxValue / rule.density.density

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float) {
        assertTrue("$message (expected=$expected actual=$actual tolerance=$tolerance)",
            abs(expected - actual) <= tolerance)
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
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(baseDensity.density, current.fontScale),
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

        // 360/390/411 pair compact cards; 320 keeps a single readable column.
        val cases = listOf(360 to 2, 390 to 2, 411 to 2, 320 to 1)
        cases.forEach { (width, expectedColumns) ->
            rule.runOnIdle { scenario.value = PanelScenario(width, 1f) }
            rule.waitForIdle()
            flowCheck_Mosaico(width, expectedColumns, 1f)
        }
        writeEvidence("mosaico-bounds.txt")
    }

    private fun flowCheck_Mosaico(width: Int, expectedColumns: Int, fontScale: Float) {
        val grid = bounds("genres_featured")
        val contentWidth = grid.width - pxOf(2f * GENRE_GRID_SIDE_PADDING_DP)
        assertNear("[$width/${fontScale}] grid must span the panel", width.toFloat(), dpOf(grid.width), 1f)
        val hero = bounds(mosaicTileTag("New Wave"))
        val expectedHeroHeight = pxOf(
            genreMosaicHeroHeightDp(dpOf(contentWidth), fontScale).toFloat(),
        )
        rule.onNodeWithTag(mosaicTileTag("New Wave")).assertIsDisplayed()
        assertNear("[$width/${fontScale}] hero must be full width", contentWidth, hero.width, pxOf(1.5f))
        assertNear("[$width/${fontScale}] hero height follows the measured policy",
            expectedHeroHeight, hero.height, pxOf(1.5f))
        assertNear("[$width/${fontScale}] hero must keep the 20dp gutters",
            pxOf(GENRE_GRID_SIDE_PADDING_DP.toFloat()), hero.left, pxOf(1f))
        assertTrue("[$width/${fontScale}] hero must keep a 48dp target",
            hero.height >= pxOf(48f))

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
        listOf(pop, ambient, blues).forEach { cell ->
            assertNear("[$width/${fontScale}] compact cell width", expectedCell, cell.width, pxOf(1.5f))
            assertTrue("[$width/${fontScale}] compact cell must stay >= 148dp: ${dpOf(cell.width)}dp",
                cell.width >= pxOf(GENRE_COMPACT_TILE_MIN_WIDTH_DP.toFloat()) - pxOf(1f))
        }
        if (expectedColumns == 2) {
            assertNear("[$width/${fontScale}] second row shares one top", pop.top, ambient.top, pxOf(1f))
            assertTrue("[$width/${fontScale}] adjacent cells keep the 10dp gap",
                ambient.left >= pop.right + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
            assertTrue("[$width/${fontScale}] second row must start below the first",
                blues.top >= pop.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
            record("[$width/$fontScale] mosaic columns=2 cell=${dpOf(pop.width)}dp " +
                "hero=${dpOf(hero.width)}x${dpOf(hero.height)}dp")
        } else {
            assertTrue("[$width/${fontScale}] single column must stack secondaries",
                ambient.top >= pop.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
            record("[$width/$fontScale] mosaic columns=1 cell=${dpOf(pop.width)}dp " +
                "hero=${dpOf(hero.width)}x${dpOf(hero.height)}dp")
        }

        // The caption carries the real track count, with no duplicate Compose title.
        val artwork = bounds(mosaicArtworkTag("Pop"), unmergedTree = true)
        val expectedFooter = pxOf(genreMosaicFooterHeightDp(fontScale).toFloat())
        assertNear("[$width/${fontScale}] artwork aspect must stay 1.41:1",
            pop.width / GENRE_ARTWORK_FRAME_RATIO, artwork.height, pxOf(1.5f))
        assertNear("[$width/${fontScale}] caption band height", expectedFooter,
            pop.bottom - artwork.bottom, pxOf(1.5f))
        val counter = rule.onNode(
            hasText("5 pistas") and hasAnyAncestor(hasTestTag(mosaicTileTag("Pop"))),
            useUnmergedTree = true,
        )
        counter.assertIsDisplayed()
        val counterBounds = counter.fetchSemanticsNode().boundsInRoot
        assertTrue("[$width/${fontScale}] counter must live in the caption band: " +
            "counter=$counterBounds artworkBottom=${artwork.bottom}",
            counterBounds.top >= artwork.bottom - pxOf(1f))
        assertTrue("[$width/${fontScale}] counter must stay inside the card",
            counterBounds.left >= pop.left && counterBounds.right <= pop.right &&
                counterBounds.bottom <= pop.bottom)
        rule.onAllNodesWithText("Pop", useUnmergedTree = true).assertCountEquals(0)
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

            assertTrue("${genre.title} card must keep a 48dp target: $tile",
                tile.height >= pxOf(48f))
            assertNear("${genre.title} poster must fill the tile width",
                tile.width, poster.width, pxOf(1f))
            assertNear("${genre.title} poster must fill the tile height",
                tile.height, poster.height, pxOf(1f))
            // Panoramic approved sheets would be only ~38-45dp tall in a
            // half-width portrait slot; the cell keeps the 48dp touch floor
            // without cropping or stretching the poster.
            val truthfulHeight = tile.width / ratio
            val expectedHeight = maxOf(truthfulHeight, pxOf(48f))
            assertNear("${genre.title} must keep its approved poster aspect or the " +
                "48dp target: tile=$tile assetHeight=${dpOf(truthfulHeight)}dp",
                expectedHeight, tile.height, pxOf(1.5f))
            if (truthfulHeight < pxOf(48f)) {
                assertTrue("${genre.title} panoramic poster must still reach 48dp: $tile",
                    tile.height >= pxOf(48f) - pxOf(1f))
                record("editorial ${genre.title} panoramic assetHeight=" +
                    "${dpOf(truthfulHeight)}dp raisedTo=${dpOf(tile.height)}dp")
            }
            // No second Compose title and no external counter: the approved
            // poster already contains title and frame, the chip sits inside it.
            rule.onAllNodesWithText(genre.title, useUnmergedTree = true).assertCountEquals(0)
            val counter = rule.onNode(
                hasText("$count ${if (count == 1) "pista" else "pistas"}") and
                    hasAnyAncestor(hasTestTag(tag)),
                useUnmergedTree = true,
            )
            counter.assertIsDisplayed()
            val counterBounds = counter.fetchSemanticsNode().boundsInRoot
            assertTrue("${genre.title} counter must sit inside the poster: " +
                "counter=$counterBounds tile=$tile",
                counterBounds.left >= tile.left && counterBounds.right <= tile.right &&
                    counterBounds.top >= tile.top && counterBounds.bottom <= tile.bottom)
            record("editorial ${genre.title} tile=${dpOf(tile.width)}x${dpOf(tile.height)}dp " +
                "ratio=$ratio")
            TileRecord(genre.title, tile.width, tile.height, ratio)
        }
        assertEquals(11, records.size)

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

    @Test fun fontScalesKeepBothViewsLegibleWithoutOverlap() {
        val scenario = mutableStateOf(PanelScenario(360, 1f))
        setPanel(scenario)
        rule.waitForIdle()

        listOf(1f, 1.3f, 1.5f, 2f).forEach { fontScale ->
            rule.runOnIdle { scenario.value = PanelScenario(360, fontScale) }
            rule.waitForIdle()

            // Compact header stays inside the panel and clear of the selector.
            val title = bounds("genres_header_title")
            val count = bounds("genres_header_count")
            val selector = bounds("genre_view_selector")
            val grid = bounds("genres_featured")
            assertTrue("[$fontScale] header title must be above the count",
                count.top >= title.bottom - pxOf(1f))
            assertTrue("[$fontScale] header must not overlap the selector",
                !title.overlaps(selector) && !count.overlaps(selector))
            assertTrue("[$fontScale] header title must start at the 20dp gutter",
                title.left >= pxOf(GENRE_GRID_SIDE_PADDING_DP.toFloat()) - pxOf(1f))
            assertTrue("[$fontScale] header must sit above the grid",
                grid.top >= count.bottom - pxOf(1f))

            // Mosaico: adaptive columns, but every card stays legible.
            val hero = bounds(mosaicTileTag("New Wave"))
            val expectedHero = pxOf(
                genreMosaicHeroHeightDp(dpOf(grid.width - pxOf(2f * GENRE_GRID_SIDE_PADDING_DP)),
                    fontScale).toFloat(),
            )
            assertNear("[$fontScale] hero height", expectedHero, hero.height, pxOf(1.5f))
            rule.onNodeWithTag(mosaicTileTag("New Wave")).assertIsDisplayed()
            val pop = bounds(mosaicTileTag("Pop"))
            val ambient = bounds(mosaicTileTag("Ambient"))
            val columns = if (abs(pop.top - ambient.top) <= pxOf(1f)) 2 else 1
            assertTrue("[$fontScale] compact card must stay >=148dp: ${dpOf(pop.width)}dp",
                pop.width >= pxOf(GENRE_COMPACT_TILE_MIN_WIDTH_DP.toFloat()) - pxOf(1f))
            val artwork = bounds(mosaicArtworkTag("Pop"), unmergedTree = true)
            assertNear("[$fontScale] caption band", pxOf(genreMosaicFooterHeightDp(fontScale).toFloat()),
                pop.bottom - artwork.bottom, pxOf(1.5f))
            val counter = rule.onNode(
                hasText("5 pistas") and hasAnyAncestor(hasTestTag(mosaicTileTag("Pop"))),
                useUnmergedTree = true,
            )
            counter.assertIsDisplayed()
            val counterBounds = counter.fetchSemanticsNode().boundsInRoot
            assertTrue("[$fontScale] counter must stay inside its caption: $counterBounds card=$pop",
                counterBounds.left >= pop.left && counterBounds.right <= pop.right &&
                    counterBounds.top >= artwork.bottom - pxOf(1f) &&
                    counterBounds.bottom <= pop.bottom + pxOf(1f))
            if (columns == 2) {
                assertTrue("[$fontScale] paired cards must not overlap",
                    ambient.left >= pop.right + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
            } else {
                assertTrue("[$fontScale] stacked cards must not overlap",
                    ambient.top >= pop.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
            }
            record("[$fontScale] mosaic columns=$columns cell=${dpOf(pop.width)}dp " +
                "footer=${dpOf(pop.bottom - artwork.bottom)}dp")

            openEditorial()
            waitForPoster("New Wave")
            rule.onNodeWithTag("genres_editorial").assertIsDisplayed()
            val cover = bounds(editorialTileTag("New Wave"))
            val pairLeft = bounds(editorialTileTag("Pop"))
            val pairRight = bounds(editorialTileTag("Ambient"))
            val posterRatio = approvedPosters().let { posters ->
                editorialPosterForTitle("New Wave", posters)!!.ratio
            }
            assertNear("[$fontScale] editorial cover keeps its poster aspect or the " +
                "48dp target",
                maxOf(cover.width / posterRatio, pxOf(48f)), cover.height, pxOf(1.5f))
            assertTrue("[$fontScale] editorial cover must keep a 48dp target",
                cover.height >= pxOf(48f))
            assertTrue("[$fontScale] editorial rows must not overlap",
                pairLeft.top >= cover.bottom + pxOf(GENRE_GRID_GAP_DP.toFloat()) - pxOf(1f))
            assertTrue("[$fontScale] editorial cover and pair must not intersect",
                !cover.overlaps(pairLeft))
            val counterEditorial = rule.onNode(
                hasText("5 pistas") and hasAnyAncestor(hasTestTag(editorialTileTag("Pop"))),
                useUnmergedTree = true,
            )
            counterEditorial.assertIsDisplayed()
            val editorialCounterBounds = counterEditorial.fetchSemanticsNode().boundsInRoot
            assertTrue("[$fontScale] editorial counter must stay inside its poster",
                editorialCounterBounds.left >= pairLeft.left &&
                    editorialCounterBounds.right <= pairLeft.right &&
                    editorialCounterBounds.top >= pairLeft.top &&
                    editorialCounterBounds.bottom <= pairLeft.bottom)
            record("[$fontScale] editorial cover=${dpOf(cover.width)}x${dpOf(cover.height)}dp " +
                "pair=${dpOf(pairLeft.width)}x${dpOf(pairRight.height)}dp")
            openMosaico()
        }
        writeEvidence("font-scale-bounds.txt")
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
