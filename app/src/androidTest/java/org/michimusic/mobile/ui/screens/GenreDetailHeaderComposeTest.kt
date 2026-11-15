package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * UI7 Phase C instrumentation gate for the genre-detail header origin.
 *
 * These tests open a real genre from both production views and assert the
 * visible header asset identity, its mobile geometry, preservation of the
 * origin view/grid scroll on back, and the 480dp/large-font adaptation. The
 * header is never inferred from a filename: it follows the explicit
 * GenreViewMode captured when the card was opened.
 */
@RunWith(AndroidJUnit4::class)
class GenreDetailHeaderComposeTest {
    @get:Rule val rule = createComposeRule()

    private val fixture by lazy { genreLayoutFixture() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidenceEnabled =
        InstrumentationRegistry.getArguments().getString("michiGenreEvidence") == "true"

    private val evidenceDir: File? = if (evidenceEnabled) {
        File(context.getExternalFilesDir(null), "ui7-mega-c-evidence").apply { mkdirs() }
    } else null
    private val evidenceLines = mutableListOf<String>()

    /**
     * This suite composes the panel inside a fixed reference container. On a phone narrower
     * than that reference Compose measures the container at the screen width, so every
     * geometry expectation must come from the width the panel really receives.
     */
    private val deviceWidthDp: Int = context.resources.configuration.screenWidthDp
    private var activeWidthDp: Float = 411f
    private var activeDensity = 0f

    private fun pxOf(dpValue: Float): Float = dpValue * activeDensity

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float) {
        assertTrue("$message (expected=$expected actual=$actual tolerance=$tolerance)",
            abs(expected - actual) <= tolerance)
    }

    private fun bounds(tag: String, unmergedTree: Boolean = false): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = unmergedTree)
            .fetchSemanticsNode().boundsInRoot

    private fun waitForTag(tag: String) {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setPanel(
        widthDp: Int = 411,
        fontScale: Float = 1f,
        density: Float? = null,
    ) {
        val genres = fixture.genres
        val playableIds = fixture.tracks.map { it.id }.toSet()
        val panelDensity = density ?: rule.density.density
        val appliedWidthDp = minOf(widthDp, deviceWidthDp)
        activeWidthDp = appliedWidthDp.toFloat()
        activeDensity = panelDensity
        rule.setContent {
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(baseConfiguration, widthDp, fontScale) {
                Configuration(baseConfiguration).apply {
                    screenWidthDp = appliedWidthDp
                    this.fontScale = fontScale
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(panelDensity, fontScale),
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                    Box(Modifier.width(appliedWidthDp.dp).fillMaxHeight()) {
                        GenreLibraryPanel(
                            genres = genres,
                            playableTrackIds = playableIds,
                            currentTrack = null,
                            isPlaying = false,
                            onPlay = { _, _ -> },
                        )
                    }
                }
            }
        }
    }

    private fun openView(mode: GenreViewMode) {
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("genre_view_${mode.name.lowercase()}").performClick()
        rule.waitForIdle()
    }

    private fun goBackToOverview() {
        rule.onNodeWithContentDescription("Volver a géneros").performClick()
        rule.waitForIdle()
    }

    private fun record(line: String) {
        println(line)
        evidenceLines += line
    }

    private fun captureEvidence(tag: String, name: String) {
        val directory = evidenceDir ?: return
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val target = File(directory, "$name.png")
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        publish(target, "$name.png")
        println("GENRE_EVIDENCE ${target.absolutePath} ${bitmap.width}x${bitmap.height}")
        evidenceLines += "capture $name ${bitmap.width}x${bitmap.height}"
    }

    /** App external files disappear when UTP uninstalls; keep a shell-owned copy. */
    private fun publish(target: File, remoteName: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(
                "mkdir -p /data/local/tmp/michi-evidence; " +
                    "cp '${target.absolutePath}' '/data/local/tmp/michi-evidence/$remoteName'",
            )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test fun editorialOriginUsesEditorialPosterAndMosaicoOriginUsesMosaicArtwork() {
        setPanel()
        rule.waitForIdle()
        openView(GenreViewMode.EDITORIAL)
        waitForTag(editorialPosterTag("Pop"))
        rule.onNodeWithTag(editorialTileTag("Pop")).performClick()

        waitForTag("genre_detail_header_poster_pop")
        rule.onNodeWithTag("genre_detail_header").assertIsDisplayed()
        rule.onNodeWithTag("genre_detail_header_poster_pop").assertIsDisplayed()
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("Pop")
        rule.onNodeWithTag("genre_detail_count").assertTextEquals("5 canciones · 5 álbumes")
        rule.onNodeWithContentDescription("Reproducir género Pop").assertIsDisplayed()
        rule.onNodeWithContentDescription("Aleatorio: Pop").assertIsDisplayed()
        assertTrue("Editorial header must not borrow a Mosaico crop",
            rule.onAllNodesWithTag("genre_detail_header_mosaico_pop", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        val editorial = bounds("genre_detail_header")
        assertNear("Editorial header keeps 20dp side margins",
            pxOf(activeWidthDp - 40f), editorial.width, pxOf(1.5f))
        assertTrue("Editorial header height ${editorial.height}px must stay in 160..192dp",
            editorial.height >= pxOf(160f) - pxOf(1f) &&
                editorial.height <= pxOf(192f) + pxOf(1f))
        captureEvidence("genre_detail_header", "detail-editorial-pop")
        record("editorial header=$editorial")
        goBackToOverview()
        rule.onNodeWithTag("genres_editorial").assertIsDisplayed()

        openView(GenreViewMode.FEATURED)
        waitForTag(mosaicTileTag("Pop"))
        rule.onNodeWithTag(mosaicTileTag("Pop")).performClick()
        waitForTag("genre_detail_header_mosaico_pop")
        rule.onNodeWithTag("genre_detail_header_mosaico_pop").assertIsDisplayed()
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("Pop")
        assertTrue("Mosaico header must not borrow an Editorial poster",
            rule.onAllNodesWithTag("genre_detail_header_poster_pop", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        val mosaico = bounds("genre_detail_header")
        assertNear("Mosaico header keeps 20dp side margins",
            pxOf(activeWidthDp - 40f), mosaico.width, pxOf(1.5f))
        assertTrue("Mosaico header height ${mosaico.height}px must stay in 160..192dp",
            mosaico.height >= pxOf(160f) - pxOf(1f) &&
                mosaico.height <= pxOf(192f) + pxOf(1f))
        captureEvidence("genre_detail_header", "detail-mosaico-pop")
        record("mosaico header=$mosaico")

        if (evidenceDir != null) {
            val boundsFile = File(evidenceDir, "header-bounds.txt")
            boundsFile.writeText(evidenceLines.joinToString("\n"))
            publish(boundsFile, "header-bounds.txt")
        }
    }

    @Test fun missingApprovedArtworkUsesOnlyTheSameViewFallback() {
        val track = org.michimusic.core.models.Track(
            id = "micro1",
            title = "Micro One",
            artist = "Michi QA",
            album = "Micro Album",
            albumId = "micro_album",
            genre = "Microhouse",
            sourceId = "local",
            filepath = "content://michi/micro1.flac",
        )
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                Box(Modifier.width(activeWidthDp.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = groupLibraryGenres(listOf(track)),
                        playableTrackIds = setOf(track.id),
                        currentTrack = null,
                        isPlaying = false,
                        onPlay = { _, _ -> },
                    )
                }
            }
        }
        rule.waitForIdle()
        waitForTag(mosaicTileTag("Microhouse"))
        rule.onNodeWithTag(mosaicTileTag("Microhouse")).performClick()
        waitForTag("genre_detail_header_mosaico_fallback_microhouse")
        rule.onNodeWithTag("genre_detail_header_mosaico_fallback_microhouse")
            .assertIsDisplayed()
        assertTrue("A missing Mosaico asset never borrows an Editorial poster",
            rule.onAllNodesWithTag("genre_detail_header_poster_microhouse", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        goBackToOverview()

        openView(GenreViewMode.EDITORIAL)
        waitForTag(editorialTileTag("Microhouse"))
        rule.onNodeWithTag(editorialTileTag("Microhouse")).performClick()
        waitForTag("genre_detail_header_editorial_fallback_microhouse")
        rule.onNodeWithTag("genre_detail_header_editorial_fallback_microhouse")
            .assertIsDisplayed()
        assertTrue("A missing Editorial asset never borrows a Mosaico crop",
            rule.onAllNodesWithTag("genre_detail_header_mosaico_microhouse", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("Microhouse")
    }

    @Test fun changingGenreNeverLeavesThePreviousHeaderImage() {
        setPanel()
        rule.waitForIdle()
        openView(GenreViewMode.EDITORIAL)
        waitForTag(editorialPosterTag("Pop"))
        rule.onNodeWithTag(editorialTileTag("Pop")).performClick()
        waitForTag("genre_detail_header_poster_pop")
        goBackToOverview()

        // Jazz is further down the Editorial wall: open it from a scrolled grid.
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Jazz")))
        rule.waitForIdle()
        waitForTag(editorialTileTag("Jazz"))
        rule.onNodeWithTag(editorialTileTag("Jazz")).performClick()
        waitForTag("genre_detail_header_poster_jazz")
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("Jazz")
        assertTrue("Previous Pop poster must leave the header",
            rule.onAllNodesWithTag("genre_detail_header_poster_pop", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        assertTrue("Pop-generated tag must never survive a genre change",
            rule.onAllNodesWithTag("genre_detail_header_mosaico_jazz", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        record("editorial jazz header=${bounds("genre_detail_header")}")
    }

    @Test fun backPreservesOriginViewAndGridScroll() {
        setPanel()
        rule.waitForIdle()
        openView(GenreViewMode.FEATURED)
        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("Tango")))
        rule.waitForIdle()
        val mosaicBefore = bounds(mosaicTileTag("Tango"))
        rule.onNodeWithTag(mosaicTileTag("Tango")).performClick()
        waitForTag("genre_detail_header_mosaico_tango")
        goBackToOverview()
        rule.onNodeWithTag("genres_featured").assertIsDisplayed()
        rule.onNodeWithTag(mosaicTileTag("Tango")).assertIsDisplayed()
        val mosaicAfter = bounds(mosaicTileTag("Tango"))
        assertNear("Mosaico scroll must survive detail back", mosaicBefore.top, mosaicAfter.top,
            pxOf(2f))

        openView(GenreViewMode.EDITORIAL)
        rule.onNodeWithTag("genres_editorial")
            .performScrollToNode(hasTestTag(editorialTileTag("Tango")))
        rule.waitForIdle()
        val editorialBefore = bounds(editorialTileTag("Tango"))
        rule.onNodeWithTag(editorialTileTag("Tango")).performClick()
        waitForTag("genre_detail_header_poster_tango")
        goBackToOverview()
        rule.onNodeWithTag("genres_editorial").assertIsDisplayed()
        rule.onNodeWithTag(editorialTileTag("Tango")).assertIsDisplayed()
        val editorialAfter = bounds(editorialTileTag("Tango"))
        assertNear("Editorial scroll must survive detail back",
            editorialBefore.top, editorialAfter.top, pxOf(2f))
        record("scroll mosaic ${mosaicBefore.top}->${mosaicAfter.top} " +
            "editorial ${editorialBefore.top}->${editorialAfter.top}")
    }

    @Test fun detailHeaderAndRowsStayInsideTheBandAt480DpAndTwoXFont() {
        // 2.0x density keeps a real 480dp panel inside the 1080px emulator while
        // still measuring the requested dp geometry at a 2x font scale. A device
        // narrower than 480dp measures its own width instead (see setPanel).
        setPanel(widthDp = 480, fontScale = 2f, density = 2f)
        rule.waitForIdle()
        // K48: at 2x the tiles grow, so the target can sit outside the composed
        // window on a narrower device; scroll the lazy grid to it instead of
        // assuming it is already laid out (this timed out on CI).
        waitForTag("genres_featured")
        rule.onNodeWithTag("genres_featured")
            .performScrollToNode(hasTestTag(mosaicTileTag("Pop")))
        rule.waitForIdle()
        rule.onNodeWithTag(mosaicTileTag("Pop")).performClick()
        waitForTag("genre_detail_header")
        val header = bounds("genre_detail_header")
        assertNear("480dp header keeps 20dp side margins", pxOf(activeWidthDp - 40f), header.width,
            pxOf(1.5f))
        assertTrue("2x header height ${header.height}px must stay in 160..192dp",
            header.height >= pxOf(160f) - pxOf(1f) &&
                header.height <= pxOf(192f) + pxOf(1f))
        rule.onNodeWithTag("genre_detail_title").assertIsDisplayed()
        rule.onNodeWithTag("genre_detail_count").assertIsDisplayed()
        rule.onNodeWithContentDescription("Reproducir género Pop").assertIsDisplayed()
        val title = bounds("genre_detail_title")
        assertTrue("2x title must remain inside the header",
            title.top >= header.top && title.bottom <= header.bottom)

        waitForTag("genre_track_art_pop0")
        rule.onNodeWithTag("genre_track_art_pop0", useUnmergedTree = true)
            .assertIsDisplayed()
        val art = bounds("genre_track_art_pop0", unmergedTree = true)
        assertNear("Detail row artwork width must be 48dp", pxOf(48f), art.width, pxOf(1f))
        assertNear("Detail row artwork must stay square", art.width, art.height, pxOf(1f))
        val trackTitle = bounds("genre_track_title_pop0", unmergedTree = true)
        assertNear("Row artwork-to-title gap stays 12dp", pxOf(12f),
            trackTitle.left - art.right, pxOf(1.5f))
        record("480/2x header=$header art=$art title=$trackTitle")
    }
}
