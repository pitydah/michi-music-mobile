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
import androidx.compose.ui.test.performScrollToIndex
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
 * UI7 instrumentation gate for Library > Genres after the visual contract:
 *
 * - Mosaico is a homogeneous 1/2-column grid: no hero, no footer, no asset-derived
 *   card height, one numeric chip bottom-end, one optical frame for approved art.
 * - Editorial follows `COVER -> PAIR -> PAIR -> COVER`; every pair shares its
 *   246dp height. Historical horizontal posters are never promoted to portraits:
 *   the Editorial Compose fallback occupies the slot honestly.
 * - Widths 320/360/390/411/480 and fontScale 1.0/1.3/1.5/2.0 are measured on the
 *   real production panel, with the panel's real container width.
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
        File(context.getExternalFilesDir(null), "ui7-contract-evidence").apply { mkdirs() }
    } else {
        null
    }

    private val evidenceLines = mutableListOf<String>()
    private val artworkReceipts = ConcurrentHashMap<String, Boolean>()

    private data class PanelScenario(val widthDp: Int, val fontScale: Float = 1f)

    // region harness

    private val screenWidthPx = context.resources.displayMetrics.widthPixels
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

    /** True when the production card paints the approved bitmap (no fallback). */
    private fun hasArtwork(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun composeHasTitle(tag: String, title: String): Boolean =
        rule.onAllNodes(
            hasText(title) and hasAnyAncestor(hasTestTag(tag)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

    private fun assertChipBottomEnd(tileTag: String, chipTag: String, label: String) {
        val tile = bounds(tileTag)
        val chip = bounds(chipTag, unmergedTree = true)
        assertNear("$label chip must be 12dp inside the card right edge",
            tile.right - pxOf(GENRE_COUNTER_INSET_DP.toFloat()), chip.right, pxOf(1f))
        assertNear("$label chip must be 12dp above the card bottom edge",
            tile.bottom - pxOf(GENRE_COUNTER_INSET_DP.toFloat()), chip.bottom, pxOf(1f))
        assertTrue("$label chip must stay inside the card: chip=$chip tile=$tile",
            chip.left >= tile.left && chip.top >= tile.top)
        // The chip is an overlay: it sits well above the bottom edge, never in a
        // dedicated footer row.
        assertTrue("$label chip is not a footer: chip=$chip tile=$tile",
            chip.bottom < tile.bottom - pxOf(4f))
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

    // region header

    @Test fun noSearchSurfaceExistsAndTheCompactHeaderSitsDirectlyAboveTheGrid() {
        setPanel(mutableStateOf(PanelScenario(411)))
        rule.waitForIdle()

        rule.onNodeWithTag("genres_search_field").assertDoesNotExist()
        rule.onNodeWithTag("genres_search_input").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("Buscar géneros").assertDoesNotExist()

        rule.onNodeWithTag("genres_header_title").assertIsDisplayed().assertTextEquals("Géneros")
        rule.onNodeWithTag("genres_header_count").assertIsDisplayed().assertTextEquals("11 géneros")

        val titleBounds = bounds("genres_header_title")
        val countBounds = bounds("genres_header_count")
        val gridBounds = bounds("genres_featured")
        val selectorBounds = bounds("genre_view_selector")
        assertTrue("Header title must sit above the real genre count",
            countBounds.top >= titleBounds.bottom - pxOf(1f))
        assertTrue("The count must be directly above the grid",
            gridBounds.top >= countBounds.bottom - pxOf(1f))
        assertTrue("No search row may sit between header and grid",
            gridBounds.top - countBounds.bottom <= pxOf(24f))
        assertTrue("Compact header must not overlap the view selector",
            !titleBounds.overlaps(selectorBounds))
        rule.onNodeWithTag(mosaicTileTag("New Wave")).assertIsDisplayed()
        record("header_title=$titleBounds")
        record("header_count=$countBounds grid=$gridBounds selector=$selectorBounds")
    }

    // endregion

    // region Mosaico geometry

    @Test fun mosaicIsUniformTwoColumnGridWithoutHeroFooterOrAssetHeight() {
        val scenario = mutableStateOf(PanelScenario(390))
        setPanel(scenario)
        rule.waitForIdle()

        val cases = listOf(320 to 1, 360 to 1, 390 to 2, 411 to 2, 480 to 2)
        cases.forEach { (width, expectedColumns) ->
            rule.runOnIdle { scenario.value = PanelScenario(width, 1f) }
            rule.waitForIdle()
            checkMosaicoContract(width, expectedColumns)
        }
        writeEvidence("mosaico-contract-bounds.txt")
    }

    private fun mosaicIndex(title: String): Int =
        fixture.ordered.indexOfFirst { it.title == title }

    /** Scrolls the grid so the item starts at the top and is fully measurable. */
    private fun scrollMosaicTo(title: String) {
        val index = mosaicIndex(title)
        if (index >= 0) {
            rule.onNodeWithTag("genres_featured").performScrollToIndex(index)
            rule.waitForIdle()
        }
    }

    private fun checkMosaicoContract(width: Int, expectedColumns: Int) {
        scrollMosaicTo("New Wave")
        val grid = bounds("genres_featured")
        assertNear("[$width] grid spans the panel", width.toFloat(), dpOf(grid.width), 1f)

        val cardSize = mosaicCardSize(width.toFloat(), expectedColumns)
        val newWave = bounds(mosaicTileTag("New Wave"))
        val pop = bounds(mosaicTileTag("Pop"))

        // The first cell is a normal cell: never a full-width hero.
        assertNear("[$width] first card width", cardSize.widthDp, dpOf(newWave.width), 1f)
        assertNear("[$width] first card height", cardSize.heightDp, dpOf(newWave.height), 1f)
        assertTrue("[$width] first card must keep the 48dp target", newWave.height >= pxOf(48f))
        assertNear("[$width] left gutter", pxOf(20f), newWave.left - grid.left, pxOf(1f))
        assertNear("[$width] top padding", pxOf(4f), newWave.top - grid.top, pxOf(1.5f))

        fun assertCell(title: String, tile: Rect) {
            assertNear("[$width] $title card height", cardSize.heightDp, dpOf(tile.height), 1f)
            assertNear("[$width] $title card width", cardSize.widthDp, dpOf(tile.width), 1f)
            assertEquals("[$width] exactly one $title card",
                1, rule.onAllNodesWithTag(mosaicTileTag(title)).fetchSemanticsNodes().size)
        }
        assertCell("New Wave", newWave)
        assertCell("Pop", pop)

        if (expectedColumns == 2) {
            // Row 0 is at the top; row 1 is fully visible on the reference phone.
            val ambient = bounds(mosaicTileTag("Ambient"))
            val blues = bounds(mosaicTileTag("Blues"))
            assertCell("Ambient", ambient)
            assertCell("Blues", blues)
            assertNear("[$width] row cells share a top", newWave.top, pop.top, pxOf(1f))
            assertNear("[$width] horizontal gap", pxOf(10f), pop.left - newWave.right, pxOf(1f))
            assertNear("[$width] second row shares a top", ambient.top, blues.top, pxOf(1f))
            assertNear("[$width] vertical gap", pxOf(10f), ambient.top - newWave.bottom, pxOf(1f))
            assertNear("[$width] cards share one width", newWave.width, pop.width, pxOf(1f))
        } else {
            assertNear("[$width] single column must be full content width",
                pxOf(width - 40f), newWave.width, pxOf(1f))
            assertTrue("[$width] single column must stack New Wave/Pop",
                pop.top >= newWave.bottom + pxOf(10f) - pxOf(1f))
            // Measure Pop/Ambient inside one scroll window, then Blues.
            scrollMosaicTo("Pop")
            val popSecond = bounds(mosaicTileTag("Pop"))
            val ambient = bounds(mosaicTileTag("Ambient"))
            assertCell("Ambient", ambient)
            assertTrue("[$width] single column must stack Pop/Ambient",
                ambient.top >= popSecond.bottom + pxOf(10f) - pxOf(1f))
            scrollMosaicTo("Blues")
            assertCell("Blues", bounds(mosaicTileTag("Blues")))
        }

        // Approved-asset ownership: the compatible crop shows no second title,
        // and any fallback shows exactly one Compose title.
        listOf("New Wave", "Pop", "Ambient", "Blues", "Clásica", "Folk", "Jazz",
            "Metal", "Rock", "Soul", "Tango").forEach { title ->
            scrollMosaicTo(title)
            val tag = mosaicTileTag(title)
            val hasArt = hasArtwork(mosaicArtworkTag(title))
            assertEquals("[$width] $title single-title rule (art=$hasArt)",
                !hasArt, composeHasTitle(tag, title))
            rule.onNodeWithTag(tag).assertContentDescriptionEquals(
                "Abrir género $title, ${genreTracks(title)} " +
                    (if (genreTracks(title) == 1) "pista" else "pistas"),
            )
        }

        // Counter: only the number, bottom-end, never a separate footer row.
        listOf("New Wave", "Pop", "Soul", "Folk").forEach { title ->
            scrollMosaicTo(title)
            val id = genreLayoutId(title)
            assertChipBottomEnd(mosaicTileTag(title), "genre_counter_$id", "[$width] $title")
            val chip = rule.onNodeWithTag("genre_counter_$id", useUnmergedTree = true)
            chip.assertIsDisplayed()
            val words = rule.onAllNodes(
                hasText("pistas") and hasAnyAncestor(hasTestTag(mosaicTileTag(title))),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertTrue("[$width] $title must not paint the word 'pistas'", words.isEmpty())
        }

        // No footer surface and no reserved band exists in the new composition.
        assertTrue("[$width] no Mosaico footer node",
            rule.onAllNodesWithTag("genre_footer", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        record("[$width] mosaic columns=$expectedColumns card=${cardSize.widthDp}x${cardSize.heightDp}")
    }

    private fun genreTracks(title: String): Int =
        fixture.genres.first { it.title == title }.tracks.size

    @Test fun aCompatibleCropIsPaintedWithOneFrameWhileAnIncompatibleOneFallsBack() {
        setPanel(mutableStateOf(PanelScenario(390)))
        rule.waitForIdle()
        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("Soul")))
        rule.waitForIdle()

        // Soul 506x361 = 1.4017 fills the 7:5 cell: the approved bitmap is painted
        // with a single calibrated contour and no Compose title.
        rule.waitForIdle()
        rule.waitUntil(20_000) { artworkReceipts["FEATURED:soul"] != null }
        assertEquals(true, artworkReceipts["FEATURED:soul"])
        assertTrue("Soul must render its approved crop", hasArtwork(mosaicArtworkTag("Soul")))
        assertTrue("Soul must not duplicate its baked title",
            !composeHasTitle(mosaicTileTag("Soul"), "Soul"))

        // Pop 506x342 = 1.4795 cannot fill the cell intact: honest fallback, never
        // a letterboxed crop.
        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("Pop")))
        rule.waitForIdle()
        assertTrue("Pop must not letterbox an incompatible sheet",
            !hasArtwork(mosaicArtworkTag("Pop")))
        assertTrue("Pop fallback must show exactly one Compose title",
            composeHasTitle(mosaicTileTag("Pop"), "Pop"))
        // The fallback keeps the same contract height and the same count.
        val pop = bounds(mosaicTileTag("Pop"))
        assertNear("fallback cell height", mosaicCardSize(390f, 2).heightDp, dpOf(pop.height), 1f)
        rule.onNodeWithTag(mosaicTileTag("Pop"))
            .assertContentDescriptionEquals("Abrir género Pop, 5 pistas")
    }

    // endregion

    // region Editorial geometry

    @Test fun editorialKeepsCoverPairPairCoverWithEqualPairHeights() {
        setPanel(mutableStateOf(PanelScenario(411)))
        rule.waitForIdle()
        openEditorial()
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
        assertEquals(
            listOf("cover", "pair", "pair", "cover", "cover", "pair", "pair"),
            rows.map { if (it is EditorialRow.Pair) "pair" else "cover" },
        )

        val grid = bounds("genres_editorial")
        val contentWidth = grid.width - pxOf(40f)
        val halfCell = (contentWidth - pxOf(10f)) / 2f
        val size = editorialCardSize(411f, columns = 2, fontScale = 1f)

        data class TileRecord(val title: String, val width: Float, val height: Float)
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
            val treatment = editorialTreatmentByTitle().getValue(genre.title)
            val expectedHeight = if (treatment == GenreEditorialTreatment.COVER) {
                size.coverHeightDp
            } else {
                size.portraitHeightDp
            }
            assertNear("${genre.title} must use the treatment height, not its sheet ratio",
                expectedHeight, dpOf(tile.height), 1f)
            if (treatment == GenreEditorialTreatment.COVER) {
                assertNear("${genre.title} cover must be full width", contentWidth, tile.width,
                    pxOf(1f))
            } else {
                assertNear("${genre.title} portrait must use the half cell", halfCell,
                    tile.width, pxOf(1f))
            }
            // Historical horizontal posters never fill a portrait slot.
            assertTrue("${genre.title} must not paint a horizontal poster in this treatment",
                !hasArtwork(editorialPosterTag(genre.title)))
            assertTrue("${genre.title} fallback must show exactly one Compose title",
                composeHasTitle(tag, genre.title))
            assertChipBottomEnd(tag, "genre_counter_${genre.id}", genre.title)
            // No detached footer row and no technical label anywhere on the card.
            assertTrue("${genre.title} must not show a pending-resource label",
                rule.onAllNodesWithTag("genre_editorial_pending_vertical_${genre.id}",
                    useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            assertTrue("${genre.title} must not show technical text",
                rule.onAllNodes(
                    hasText("PENDIENTE", substring = true) and
                        hasAnyAncestor(hasTestTag(tag)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty())
            assertTrue("${genre.title} has no footer node",
                rule.onAllNodesWithTag("genre_editorial_footer_${genre.id}",
                    useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            record("editorial ${genre.title} treatment=$treatment " +
                "tile=${dpOf(tile.width)}x${dpOf(tile.height)}dp")
            TileRecord(genre.title, tile.width, tile.height)
        }
        assertEquals(11, records.size)

        // Every pair shares one height and one top; no half-empty row exists.
        val pairs = rows.filterIsInstance<EditorialRow.Pair>()
        assertEquals(4, pairs.size)
        pairs.forEach { pair ->
            rule.onNodeWithTag("genres_editorial")
                .performScrollToNode(hasTestTag(editorialTileTag(pair.leading.title)))
            rule.waitForIdle()
            val left = bounds(editorialTileTag(pair.leading.title))
            val right = bounds(editorialTileTag(pair.trailing.title))
            assertNear("pair width ${pair.leading.title}/${pair.trailing.title}",
                left.width, right.width, pxOf(1f))
            assertNear("pair top ${pair.leading.title}/${pair.trailing.title}",
                left.top, right.top, pxOf(1f))
            assertNear("pair height ${pair.leading.title}/${pair.trailing.title}",
                left.height, right.height, pxOf(1f))
            assertNear("pair height equals the 246dp treatment",
                size.portraitHeightDp, dpOf(left.height), 1f)
            assertNear("pair gap", pxOf(10f), right.left - left.right, pxOf(1f))
        }

        // Only the three COVER rows are full width; the eight portrait cells are not.
        val fullWidth = records.filter { abs(it.width - contentWidth) <= pxOf(1.5f) }.map { it.title }
        val compact = records.filter { abs(it.width - halfCell) <= pxOf(1.5f) }.map { it.title }
        val coverTitles = rows.filterIsInstance<EditorialRow.Cover>().map { it.genre.title }.toSet()
        val pairTitles = pairs.flatMap { listOf(it.leading.title, it.trailing.title) }.toSet()
        assertEquals("COVER rows must be exactly the positional covers", coverTitles, fullWidth.toSet())
        assertEquals("Portrait pairs must be the compact cells", pairTitles, compact.toSet())
        assertTrue("Editorial must not render 11 full-width banners", fullWidth.size <= 3)

        // Rhythm at the top: cover, then the pair after a 10dp gap.
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("New Wave")))
        rule.waitForIdle()
        val cover = bounds(editorialTileTag("New Wave"))
        val pairLeft = bounds(editorialTileTag("Pop"))
        val pairRight = bounds(editorialTileTag("Ambient"))
        assertTrue("Cover must be followed by the portrait pair after a 10dp gap",
            pairLeft.top >= cover.bottom + pxOf(10f) - pxOf(1f))
        assertNear("Pair must share one row top", pairLeft.top, pairRight.top, pxOf(1f))
        assertTrue("Pair must keep the 10dp horizontal gap",
            pairRight.left >= pairLeft.right + pxOf(10f) - pxOf(1f))

        captureEvidence("genres_overview", "editorial-411-11genres")
        writeEvidence("editorial-contract-bounds.txt")
    }

    private fun editorialTreatmentByTitle(): Map<String, GenreEditorialTreatment> =
        editorialRows(fixture.ordered, columns = 2).flatMap { row ->
            when (row) {
                is EditorialRow.Cover -> listOf(row.genre.title to GenreEditorialTreatment.COVER)
                is EditorialRow.Pair -> listOf(
                    row.leading.title to GenreEditorialTreatment.PORTRAIT_LEFT,
                    row.trailing.title to GenreEditorialTreatment.PORTRAIT_RIGHT,
                )
            }
        }.toMap()

    // endregion

    // region interaction

    @Test fun viewSelectionPersistsAcrossDetailNavigation() {
        setPanel(mutableStateOf(PanelScenario(411)))
        rule.waitForIdle()
        rule.onNodeWithTag("genres_featured").assertIsDisplayed()

        openEditorial()
        rule.onNodeWithTag("genres_editorial").assertIsDisplayed()
        rule.onNodeWithTag("genres_featured").assertDoesNotExist()

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

        val mosaicSize = mosaicCardSize(320f, 1)
        val pop = bounds(mosaicTileTag("Pop"))
        val ambient = bounds(mosaicTileTag("Ambient"))
        assertNear("320dp Mosaico must use the full content width", mosaicSize.widthDp,
            dpOf(pop.width), 1f)
        assertNear("320dp Mosaico card height", mosaicSize.heightDp, dpOf(pop.height), 1f)
        assertTrue("320dp Mosaico secondaries must stack",
            ambient.top >= pop.bottom + pxOf(10f) - pxOf(1f))
        rule.onNodeWithTag(mosaicTileTag("Ambient")).assertIsDisplayed()

        // No Mosaico crop is 8:5, so one-column cards are honest fallbacks.
        assertTrue("320dp must not letterbox a 1.4:1 crop",
            !hasArtwork(mosaicArtworkTag("Pop")))
        assertTrue("320dp fallback must keep one Compose title",
            composeHasTitle(mosaicTileTag("Pop"), "Pop"))

        openEditorial()
        val editorialSize = editorialCardSize(320f, columns = 1, fontScale = 1f)
        listOf("New Wave", "Pop", "Ambient").forEach { title ->
            val tile = bounds(editorialTileTag(title))
            assertNear("320dp Editorial $title must be a one-column cover",
                editorialSize.coverWidthDp, dpOf(tile.width), 1f)
            assertNear("320dp Editorial $title height", editorialSize.singleHeightDp,
                dpOf(tile.height), 1f)
        }
        val cover = bounds(editorialTileTag("New Wave"))
        val second = bounds(editorialTileTag("Pop"))
        assertTrue("320dp Editorial rows must stack",
            second.top >= cover.bottom + pxOf(10f) - pxOf(1f))
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Tango")))
        rule.waitForIdle()
        val last = bounds(editorialTileTag("Tango"))
        assertNear("320dp Editorial last card stays one column",
            editorialSize.coverWidthDp, dpOf(last.width), 1f)
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
                flowCheckMatrixMosaico(width, fontScale)

                openEditorial()
                flowCheckMatrixEditorial(width, fontScale)
                openMosaico()
            }
        }
        writeEvidence("font-scale-contract-bounds.txt")
    }

    private fun expectedColumns(width: Int): Int =
        genreGridColumns(width, GENRE_COMPACT_TILE_MIN_WIDTH_DP)

    private fun flowCheckMatrixMosaico(width: Int, fontScale: Float) {
        val grid = bounds("genres_featured")
        val title = bounds("genres_header_title")
        val count = bounds("genres_header_count")
        val selector = bounds("genre_view_selector")
        assertTrue("[$width/$fontScale] header title must be above the count",
            count.top >= title.bottom - pxOf(1f))
        assertTrue("[$width/$fontScale] header must not overlap the selector",
            !title.overlaps(selector) && !count.overlaps(selector))
        assertTrue("[$width/$fontScale] header must start at the 20dp gutter",
            title.left >= grid.left + pxOf(20f) - pxOf(1f))
        assertTrue("[$width/$fontScale] header must sit above the grid",
            grid.top >= count.bottom - pxOf(1f))

        val columns = expectedColumns(width)
        val size = mosaicCardSize(width.toFloat(), columns)
        // Scroll Pop to the top so the measured card is fully visible, not clipped.
        rule.onNodeWithTag("genres_featured").performScrollToIndex(mosaicIndex("Pop"))
        rule.waitForIdle()
        val pop = bounds(mosaicTileTag("Pop"))
        record("[$width/$fontScale] diag grid=${dpOf(grid.width)}x${dpOf(grid.height)}dp " +
            "size=${size.widthDp}x${size.heightDp} pop=${dpOf(pop.width)}x${dpOf(pop.height)}dp")
        assertNear("[$width/$fontScale] card width", size.widthDp, dpOf(pop.width), 1f)
        assertNear("[$width/$fontScale] card height", size.heightDp, dpOf(pop.height), 1f)
        assertTrue("[$width/$fontScale] card must stay >=156dp: ${dpOf(pop.width)}dp",
            pop.width >= pxOf(GENRE_COMPACT_TILE_MIN_WIDTH_DP.toFloat()) - pxOf(1f))
        assertTrue("[$width/$fontScale] every card must stay inside the panel",
            pop.left >= grid.left && pop.right <= grid.right)
        if (columns == 2) {
            val newWave = bounds(mosaicTileTag("New Wave"))
            val ambient = bounds(mosaicTileTag("Ambient"))
            val blues = bounds(mosaicTileTag("Blues"))
            assertNear("[$width/$fontScale] row top", pop.top, newWave.top, pxOf(1f))
            assertTrue("[$width/$fontScale] 10dp horizontal gap",
                pop.left >= newWave.right + pxOf(10f) - pxOf(1f))
            assertNear("[$width/$fontScale] second row shares a top", ambient.top, blues.top,
                pxOf(1f))
            assertNear("[$width/$fontScale] equal row height", pop.height, ambient.height,
                pxOf(1f))
            assertNear("[$width/$fontScale] 10dp vertical gap",
                pxOf(10f), ambient.top - newWave.bottom, pxOf(1f))
        } else {
            // Pop is at the top and Ambient is the next single-column card.
            val ambient = bounds(mosaicTileTag("Ambient"))
            assertTrue("[$width/$fontScale] stacked cells keep the 10dp gap",
                ambient.top >= pop.bottom + pxOf(10f) - pxOf(1f))
            assertNear("[$width/$fontScale] stacked card height", size.heightDp,
                dpOf(ambient.height), 1f)
        }
        assertChipBottomEnd(mosaicTileTag("Pop"), "genre_counter_pop", "[$width/$fontScale]")
        record("[$width/$fontScale] mosaic columns=$columns cell=${dpOf(pop.width)}dp " +
            "height=${dpOf(pop.height)}dp")
    }

    private fun flowCheckMatrixEditorial(width: Int, fontScale: Float) {
        rule.onNodeWithTag("genres_editorial").assertIsDisplayed()
        val grid = bounds("genres_editorial")
        val contentWidth = grid.width - pxOf(40f)
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("New Wave")))
        rule.waitForIdle()
        val cover = bounds(editorialTileTag("New Wave"))
        val pairLeft = bounds(editorialTileTag("Pop"))
        val pairRight = bounds(editorialTileTag("Ambient"))
        val columns = expectedColumns(width)
        val size = editorialCardSize(width.toFloat(), columns, fontScale)

        assertNear("[$width/$fontScale] cover must be full width", contentWidth, cover.width,
            pxOf(1f))
        assertNear("[$width/$fontScale] cover height", size.coverHeightDp, dpOf(cover.height), 1f)
        if (columns == 2) {
            assertNear("[$width/$fontScale] pair shares one width", pairLeft.width,
                pairRight.width, pxOf(1f))
            assertNear("[$width/$fontScale] pair shares one top", pairLeft.top, pairRight.top,
                pxOf(1f))
            assertNear("[$width/$fontScale] pair shares one height", pairLeft.height,
                pairRight.height, pxOf(1f))
            assertNear("[$width/$fontScale] pair height is the portrait treatment",
                size.portraitHeightDp, dpOf(pairLeft.height), 1f)
            assertTrue("[$width/$fontScale] cover and pair must not intersect",
                !cover.overlaps(pairLeft))
            assertTrue("[$width/$fontScale] pair must keep the 10dp gap",
                pairRight.left >= pairLeft.right + pxOf(10f) - pxOf(1f))
            assertTrue("[$width/$fontScale] editorial rows must not overlap",
                pairLeft.top >= cover.bottom + pxOf(10f) - pxOf(1f))
        } else {
            assertNear("[$width/$fontScale] one-column cover height",
                size.singleHeightDp, dpOf(cover.height), 1f)
            assertTrue("[$width/$fontScale] one-column rows must stack",
                pairLeft.top >= cover.bottom + pxOf(10f) - pxOf(1f))
            listOf("Pop", "Ambient").forEach { title ->
                rule.onNodeWithTag("genres_editorial")
                    .performScrollToNode(hasTestTag(editorialTileTag(title)))
                rule.waitForIdle()
                val tile = bounds(editorialTileTag(title))
                assertNear("[$width/$fontScale] one-column $title height",
                    size.singleHeightDp, dpOf(tile.height), 1f)
                assertNear("[$width/$fontScale] one-column $title full width",
                    contentWidth, tile.width, pxOf(1f))
            }
        }
        assertChipBottomEnd(editorialTileTag("Pop"), "genre_counter_pop",
            "[$width/$fontScale] editorial")
        record("[$width/$fontScale] editorial columns=$columns " +
            "cover=${dpOf(cover.width)}x${dpOf(cover.height)}dp")
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
        assertTrue("Genre content must end above the mini player",
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
        rule.waitUntil(20_000) { artworkReceipts["FEATURED:soul"] == true }
        captureEvidence("genres_overview", "mosaico-411-top")
        record("mosaico newWave=${bounds(mosaicTileTag("New Wave"))} " +
            "pop=${bounds(mosaicTileTag("Pop"))} soul=${bounds(mosaicTileTag("Soul"))}")

        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("Tango")))
        rule.waitForIdle()
        captureEvidence("genres_overview", "mosaico-411-tail")

        openEditorial()
        captureEvidence("genres_overview", "editorial-411-top")
        record("editorial newWave=${bounds(editorialTileTag("New Wave"))} " +
            "pop=${bounds(editorialTileTag("Pop"))} ambient=${bounds(editorialTileTag("Ambient"))}")

        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Tango")))
        rule.waitForIdle()
        captureEvidence("genres_overview", "editorial-411-tail")
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
