package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * UI7 Phase C detail-row artwork gate.
 *
 * Uses the project's approved packaged image fixture as a real model cover. A
 * generated file with an embedded picture is not produced in this environment,
 * so the MediaStore/SAF embedded-picture provider path stays NOT_RUN; the
 * shared fallback seam is still exercised with a real (missing) content URI and
 * must degrade to the neutral placeholder instead of borrowing another cover.
 */
@RunWith(AndroidJUnit4::class)
class GenreDetailRowArtworkComposeTest {
    @get:Rule val rule = createComposeRule()

    private val coverAsset =
        "file:///android_asset/ui7/mosaic/UI7_MOSAICO_005_Pop.webp"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidenceEnabled =
        InstrumentationRegistry.getArguments().getString("michiGenreEvidence") == "true"
    private val evidenceDir: File? = if (evidenceEnabled) {
        File(context.getExternalFilesDir(null), "ui7-mega-c-evidence").apply { mkdirs() }
    } else null

    private fun pxOf(dpValue: Float): Float = with(rule.density) { dpValue.dp.toPx() }

    private fun captureTag(tag: String, name: String) {
        val directory = evidenceDir ?: return
        rule.waitForIdle()
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val target = File(directory, "$name.png")
        FileOutputStream(target).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        publish(target, "$name.png")
        println("GENRE_EVIDENCE ${target.absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    private fun publish(target: File, remoteName: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(
                "mkdir -p /data/local/tmp/michi-evidence; " +
                    "cp '${target.absolutePath}' '/data/local/tmp/michi-evidence/$remoteName'",
            )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

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
        tracksState: androidx.compose.runtime.MutableState<List<Track>>,
        onTrackArtworkLoad: (String, Boolean) -> Unit = { _, _ -> },
        widthDp: Int = 411,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(baseConfiguration, widthDp, fontScale) {
                Configuration(baseConfiguration).apply {
                    screenWidthDp = widthDp
                    this.fontScale = fontScale
                }
            }
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(baseDensity.density, fontScale),
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                    Box(Modifier.width(widthDp.dp).fillMaxHeight()) {
                        val tracks = tracksState.value
                        GenreLibraryPanel(
                            genres = groupLibraryGenres(tracks),
                            playableTrackIds = tracks.map { it.id }.toSet(),
                            currentTrack = null,
                            isPlaying = false,
                            onPlay = { _, _ -> },
                            onTrackArtworkLoad = onTrackArtworkLoad,
                        )
                    }
                }
            }
        }
    }

    private fun openPop() {
        rule.onNodeWithTag("genre_tile_pop").performClick()
        rule.waitForIdle()
    }

    /**
     * Phase C's detail header can push the first row outside the initial viewport, so the
     * lazy row and its artwork slot are not composed until the list scrolls to them.
     * Waiting for the detail container first keeps the scroll itself from racing the
     * navigation frame.
     */
    private fun openPopAndReveal(rowTag: String) {
        openPop()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("genre_detail").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("genre_detail").performScrollToNode(hasTestTag(rowTag))
        rule.waitForIdle()
    }

    private fun rowTrack(id: String, title: String, coverId: String, filepath: String) = Track(
        id = id,
        title = title,
        artist = "Michi QA",
        album = "Row Album",
        albumId = "row_album_$id",
        genre = "Pop",
        sourceId = "local",
        coverId = coverId,
        filepath = filepath,
    )

    @Test fun realModelCoverRendersInThe48dpRowSeam() {
        val track = rowTrack("real1", "Real One", coverAsset, "content://michi/real1.flac")
        var receipt: Boolean? = null
        setPanel(mutableStateOf(listOf(track)), onTrackArtworkLoad = { id, ok ->
            if (id == track.id) receipt = ok
        })
        openPopAndReveal("genre_track_real1")
        rule.waitUntil(20_000) { receipt == true }
        rule.onNodeWithTag("genre_track_art_real1", useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithTag("genre_track_title_real1", useUnmergedTree = true)
            .assertTextEquals("Real One")
        val art = bounds("genre_track_art_real1", unmergedTree = true)
        assertNear("Row artwork width is 48dp", pxOf(48f), art.width, pxOf(1f))
        assertNear("Row artwork is square", art.width, art.height, pxOf(1f))
        val title = bounds("genre_track_title_real1", unmergedTree = true)
        assertNear("Artwork-to-title gap is 12dp", pxOf(12f), title.left - art.right, pxOf(1.5f))
        assertTrue("A loaded model cover must not show the neutral placeholder",
            rule.onAllNodesWithTag("album_artwork_unavailable", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        captureTag("genre_detail", "detail-rows-covers")
        evidenceDir?.let {
            val boundsFile = File(it, "row-bounds.txt")
            boundsFile.writeText(
                "art=$art title=$title gapDp=${(title.left - art.right) / rule.density.density}",
            )
            publish(boundsFile, "row-bounds.txt")
        }
    }

    @Test fun missingArtworkUsesTheNeutralPlaceholder() {
        val track = rowTrack("none1", "No Cover", "", "content://michi/missing.flac")
        setPanel(mutableStateOf(listOf(track)))
        // A detail header built from missing artwork is tall enough to push the
        // first row out of the initial viewport, so the lazy row and its
        // placeholder are not composed yet. Reveal the row before waiting.
        openPopAndReveal("genre_track_none1")
        waitForTag("album_artwork_unavailable")
        rule.onNodeWithTag("genre_track_art_none1", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("album_artwork_unavailable", useUnmergedTree = true)
            .assertIsDisplayed()
        assertTrue("Missing art must never borrow a genre or title image",
            rule.onAllNodesWithTag("genre_artwork_pop", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
    }

    @Test fun rapidCoverChangeNeverKeepsThePreviousImage() {
        val first = rowTrack("switch1", "Switching", coverAsset, "content://michi/switch.flac")
        val tracks = mutableStateOf(listOf(first))
        val receipts = mutableListOf<Boolean>()
        setPanel(tracks, onTrackArtworkLoad = { _, ok -> receipts += ok })
        openPopAndReveal("genre_track_switch1")
        rule.waitUntil(20_000) { receipts.contains(true) }

        // Same row identity, new model with no artwork: the previous painter
        // must be released and the neutral placeholder must become visible.
        rule.runOnIdle {
            tracks.value = listOf(first.copy(coverId = "", filepath = "content://michi/absent.flac"))
        }
        waitForTag("album_artwork_unavailable")
        rule.onNodeWithTag("album_artwork_unavailable", useUnmergedTree = true)
            .assertIsDisplayed()

        // And back to a real model cover: the placeholder disappears again.
        rule.runOnIdle { tracks.value = listOf(first) }
        rule.waitUntil(20_000) { receipts.count { it } >= 2 }
        rule.onNodeWithTag("genre_track_art_switch1", useUnmergedTree = true)
            .assertIsDisplayed()
        assertTrue("Reloaded cover must replace the placeholder",
            rule.onAllNodesWithTag("album_artwork_unavailable", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
    }

    @Test fun oneHundredTwentyRowGenreScrollsLazilyToTheLastTrack() {
        // No local path: the neutral placeholder proves lazy composition without
        // touching a provider per row.
        val tracks = (0 until 120).map { index ->
            rowTrack("long-$index", "Long Track ${index + 1}", "", "")
        }
        setPanel(mutableStateOf(tracks))
        // Phase C's detail header can push the first row outside the initial
        // viewport; reveal it before waiting for its lazy artwork slot.
        openPopAndReveal("genre_track_long-0")
        waitForTag("genre_track_art_long-0")
        rule.onNodeWithTag("genre_track_art_long-0", useUnmergedTree = true)
            .assertIsDisplayed()
        val composedBefore = rule.onAllNodesWithTag(
            "album_artwork_unavailable", useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertTrue("Only visible rows decode artwork, composed=$composedBefore",
            composedBefore in 1..40)

        rule.onNodeWithTag("genre_detail")
            .performScrollToNode(hasTestTag("genre_track_long-119"))
        rule.waitForIdle()
        rule.onNodeWithTag("genre_track_art_long-119", useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithTag("genre_track_art_long-0", useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
