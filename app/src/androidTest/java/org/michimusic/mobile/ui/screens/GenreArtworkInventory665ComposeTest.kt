package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
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
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences

/**
 * UI7 665-asset integration gate.
 *
 * The production panel is rendered with the exact 665 genre keys read from the
 * packaged Mosaico manifest, so both views resolve real assets instead of a drawn
 * fixture. Availability is separate from quality and from approval: every packaged
 * WebP paints, the 14 Mosaico / 85 Editorial `verified=0` rows are QA debt, and the
 * 353 crops imported on 2026-11-17 still carry IMPORTED_PENDING_VISUAL_APPROVAL.
 * The same harness captures Mosaico and Editorial evidence at 320/360/390/411/480
 * dp when `-e michiGenreEvidence true` is passed.
 */
@RunWith(AndroidJUnit4::class)
class GenreArtworkInventory665ComposeTest {
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
        File(context.getExternalFilesDir(null), "ui7-665-evidence").apply { mkdirs() }
    } else {
        null
    }

    private fun approvedMosaics(): Map<String, MosaicArtworkAsset> {
        val files = context.assets.list(mosaicFolder).orEmpty().toSet()
        val manifest = context.assets.open("$mosaicFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val optical = parseUi7OpticalMasks(
            context.assets.open("$mosaicFolder/optical.tsv")
                .bufferedReader().use { it.readText() },
        )
        return mosaicApprovedArtwork(manifest, files, opticalMasks = optical)
    }

    private fun approvedPosters(): Map<String, EditorialPosterAsset> {
        val files = context.assets.list(editorialPosterFolder).orEmpty().toSet()
        val manifest = context.assets.open("$editorialPosterFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val optical = parseUi7OpticalMasks(
            context.assets.open("$editorialPosterFolder/optical.tsv")
                .bufferedReader().use { it.readText() },
        )
        return editorialApprovedPosters(manifest, files, opticalMasks = optical)
    }

    private fun setPanel() {
        rule.setContent {
            // LocalConfiguration is a composable read; copy it once outside remember.
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(widthDp.value, baseConfiguration) {
                Configuration(baseConfiguration).apply {
                    screenWidthDp = widthDp.value
                    fontScale = this@GenreArtworkInventory665ComposeTest.fontScale
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
                    Box(Modifier.width(widthDp.value.dp).fillMaxHeight()
                        .background(androidx.compose.ui.graphics.Color(0xFF090B11))
                        .testTag("ui7_665_root")) {
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
        println("UI7_665_EVIDENCE ${target.absolutePath} ${bitmap.width}x${bitmap.height}")
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

    @Test fun inventoryIsTheFull665AndBothViewsScrollToTheLastGenre() {
        assertEquals("packaged mosaic genres", 665, manifestKeys.size)
        assertEquals("library genres", 665, genres.size)
        assertEquals("unique genre ids", 665, genres.map { it.id }.toSet().size)
        // The manifest is ordered by filename, so it starts and ends where the
        // numbering does: position 001 is Rock and 672 is Hapa Haole.
        assertEquals("rock", manifestKeys.first())
        assertEquals("hapa haole", manifestKeys.last())

        // The panel is not the manifest: it renders genresByTrackCount (most tracks
        // first, then title, with the absence bucket always last). With one track
        // each that is title order, and the bucket closes the list.
        val ordered = genresByTrackCount(genres)
        val hero = ordered.first()
        val last = ordered.last()
        assertEquals("panel genre count", 665, ordered.size)
        assertEquals("first genre in panel order", "aak", hero.id)
        assertEquals("last genre in panel order", "__ui7_unknown_genre__", last.id)

        setPanel()
        rule.waitForIdle()
        rule.onNodeWithTag("genres_header_count").assertIsDisplayed().assertTextEquals("665 géneros")
        waitForNode(mosaicTileTag(hero.title))
        rule.onNodeWithTag(mosaicTileTag(hero.title)).assertIsDisplayed()
        rule.onNodeWithTag(mosaicTileTag(hero.title))
            .assertContentDescriptionEquals("Abrir género ${hero.title}, 1 pista")
        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag(last.title)))
        rule.waitForIdle()
        rule.onNodeWithTag(mosaicTileTag(last.title)).assertIsDisplayed()
        rule.onNodeWithTag(mosaicTileTag(last.title))
            .assertContentDescriptionEquals("Abrir género ${last.title}, 1 pista")

        // Editorial: the pack has no authentic vertical variant, so the planner
        // derives banners/singles from the real horizontal ratios. Every poster is
        // painted; no genre is replaced by a fallback card.
        selectMode(GenreViewMode.EDITORIAL)
        waitForNode(editorialTileTag(hero.title))
        rule.onNodeWithTag(editorialTileTag(hero.title)).assertIsDisplayed()
        assertTrue("first Editorial genre must paint its poster",
            rule.onAllNodesWithTag(editorialPosterTag(hero.title), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty())
        assertTrue("first Editorial genre must not fall back",
            rule.onAllNodesWithTag("genre_editorial_fallback_${hero.id}",
                useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag(last.title)))
        rule.waitForIdle()
        rule.onNodeWithTag(editorialTileTag(last.title)).assertIsDisplayed()
        assertTrue("last Editorial genre must paint its poster",
            rule.onAllNodesWithTag(editorialPosterTag(last.title), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty())
        assertTrue("last Editorial genre must not fall back",
            rule.onAllNodesWithTag("genre_editorial_fallback_${last.id}",
                useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun everyPackagedCropPaintsAtEveryContractWidth() {
        val assets = approvedMosaics()
        assertEquals(665, assets.size)
        assertEquals("verified optical rows", 298,
            assets.values.count { it.opticalMask?.verified == true })
        assertEquals("unverified rows are debt, not absence", 14,
            assets.values.count { it.opticalMask?.verified == false })
        // The 353 imported crops carry no optical row yet: no mask is not a failed
        // mask, so the debt count is measured against the assets that were measured.
        assertEquals("imported crops wait for their own optical pass", 353,
            assets.values.count { it.opticalMask == null })

        listOf(320 to 1, 360 to 1, 390 to 2, 411 to 2, 480 to 2).forEach { (panel, columns) ->
            val size = mosaicCardSize(panel.toFloat(), columns)
            assets.values.forEach { asset ->
                val spec = mosaicVisualSpec(asset)!!
                val decision = ui7ArtworkDecision(
                    spec, size.widthDp, size.heightDp, 26f, 26f,
                )
                assertTrue("[${panel}dp] ${asset.filename} must paint",
                    decision.paintsArtwork)
                assertEquals(Ui7AssetAvailability.ASSET_PACKAGED, decision.availability)
                assertFalse("[${panel}dp] ${asset.filename} must never be reported missing",
                    decision.availability == Ui7AssetAvailability.NO_ASSET_FOR_GENRE)
                // An unverified optical row is recorded as debt and still paints, and
                // a crop with no row at all is unmeasured: both report the same flag,
                // because to the renderer neither contour has been verified.
                assertEquals("[${panel}dp] ${asset.filename} mask debt mirrors its row",
                    asset.opticalMask?.verified != true,
                    decision.quality.opticalMaskUnverified)
                // Every Mosaico source is 1.32..1.56: the residual band is a QA
                // record, never a reason to hide the crop.
                assertTrue("[${panel}dp] ${asset.filename} debt must be measured",
                    decision.quality.aspectDebtPerSideDp >= 0f)
                assertFalse("[${panel}dp] ${asset.filename} never needs a portrait",
                    decision.quality.needsPortraitVariant)
            }
        }
        val unverifiedAcrossWidths = assets.values.count { decision ->
            ui7ArtworkDecision(
                mosaicVisualSpec(decision)!!, 180.5f, 127.11f, 26f, 26f,
            ).quality.opticalMaskUnverified
        }
        assertEquals(
            "14 measured rows with verified=0 plus the 353 crops that have no optical row",
            367,
            unverifiedAcrossWidths,
        )
        println("UI7_MOSAIC_COMPAT 665 packaged, 298 verified, 14 unverified, 0 fallbacks")
    }

    @Test fun editorialPackHasNoAuthenticVerticalVariant() {
        val posters = approvedPosters()
        assertEquals(665, posters.size)
        assertEquals("no portrait source exists in the approved pack", 0,
            posters.values.count { it.isVertical })
        assertEquals("unverified Editorial rows are QA debt", 85,
            posters.values.count { it.opticalMask?.verified == false })

        // The real ratios classify once: the 312 approved posters keep their
        // historical 43/169/22/36/42, and the 353 imported ones add 32/160/71/30/60.
        val counts = posters.values
            .map { editorialPosterClass(it.ratio) }
            .groupingBy { it }
            .eachCount()
        assertEquals(75, counts[EditorialPosterClass.COMPACTO_ALTO] ?: 0)
        assertEquals(329, counts[EditorialPosterClass.COMPACTO_MEDIO] ?: 0)
        assertEquals(93, counts[EditorialPosterClass.COMPACTO_ANCHO] ?: 0)
        assertEquals(66, counts[EditorialPosterClass.BANNER_PANORAMICO] ?: 0)
        assertEquals(102, counts[EditorialPosterClass.BANNER_FINO] ?: 0)
        assertEquals("no horizontal poster is a fake portrait", 0,
            counts[EditorialPosterClass.RETRATO_FUTURO] ?: 0)
        assertEquals(0, counts[EditorialPosterClass.INTERMEDIO_FUTURO] ?: 0)

        // The planner consumes the whole pack at every contract width without a
        // fallback, preserving each real ratio.
        val ordered = posters.values.map {
            LibraryGenre(id = it.slug, title = it.slug, tracks = emptyList(), albumCount = 0)
        }
        listOf(320 to 1, 360 to 1, 390 to 2, 411 to 2, 480 to 2).forEach { (panel, columns) ->
            val usable = editorialUsableWidthDp(panel.toFloat())
            val plan = editorialPlan(ordered, posters, panel.toFloat(), columns, 1f)
            val ids = plan.flatMap { row ->
                when (row) {
                    is EditorialRow.Full -> listOf(row.piece.genre.id)
                    is EditorialRow.Pair -> listOf(row.leading.genre.id, row.trailing.genre.id)
                }
            }
            assertEquals("[$panel] every poster appears exactly once", 665, ids.size)
            assertEquals("[$panel] no duplicate", 665, ids.toSet().size)
            assertFalse("[$panel] no asset-less fallback",
                plan.any { it.kind == EditorialRowKind.FALLBACK })
            plan.forEach { row ->
                when (row) {
                    is EditorialRow.Full -> {
                        assertEquals("[$panel] full rows use U", usable,
                            row.piece.widthDp, 0.01f)
                        assertEquals("[$panel] full row height follows the real ratio",
                            usable / row.piece.poster!!.ratio, row.heightDp, 0.05f)
                        val spec = editorialVisualSpec(row.piece.poster!!)!!
                        val decision = ui7ArtworkDecision(
                            spec, usable, row.heightDp, 26f, 26f,
                        )
                        assertTrue("[$panel] ${spec.fileName} must paint",
                            decision.paintsArtwork)
                        assertFalse("[$panel] ${spec.fileName} never needs a portrait",
                            decision.quality.needsPortraitVariant)
                    }
                    is EditorialRow.Pair -> {
                        assertEquals("[$panel] pair shares one height",
                            row.leading.heightDp, row.trailing.heightDp, 0f)
                        assertEquals("[$panel] pair fills U", usable,
                            row.leading.widthDp + GENRE_GRID_GAP_DP + row.trailing.widthDp, 0.05f)
                        listOf(row.leading, row.trailing).forEach { piece ->
                            val spec = editorialVisualSpec(piece.poster!!)!!
                            assertTrue("[$panel] ${spec.fileName} must paint",
                                ui7ArtworkDecision(
                                    spec, piece.widthDp, piece.heightDp, 26f, 26f,
                                ).paintsArtwork)
                        }
                    }
                }
            }
        }
        println("UI7_EDITORIAL_CLASSES 75/329/93/66/102 verified=227 unverified=85")
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
            capture("ui7_665_root", "mosaico-${width}dp$scaleSuffix")
            record("mosaico-${width}dp${scaleSuffix} fontScale=$fontScale " +
                "receipts=" + artworkReceipts.count { it.value })

            selectMode(GenreViewMode.EDITORIAL)
            scrollToTile("genres_editorial", editorialTileTag(hero.title))
            rule.onNodeWithTag(editorialTileTag(hero.title)).assertIsDisplayed()
            assertTrue("Editorial must paint the first poster",
                rule.onAllNodesWithTag(editorialPosterTag(hero.title),
                    useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            capture("ui7_665_root", "editorial-${width}dp$scaleSuffix")
            record("editorial-${width}dp${scaleSuffix} fontScale=$fontScale " +
                "posters=" + rule.onAllNodesWithTag(editorialPosterTag(hero.title),
                    useUnmergedTree = true).fetchSemanticsNodes().size)

            // Prove the last genre in panel order is reachable at this width too.
            scrollToTile("genres_editorial", editorialTileTag(last.title))
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
