package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.motion.AeroTrackArtworkMotion
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * UI7 Phase C action-sheet artwork gate.
 *
 * The sheet header uses the same shared resolution as Now Playing for the exact
 * open track: 52dp, model cover first, then a real local embedded-art fallback,
 * then the neutral placeholder. The test harness renders the actual Now
 * Playing artwork/title components beside the actual action sheet.
 */
@RunWith(AndroidJUnit4::class)
class TrackActionArtworkComposeTest {
    @get:Rule val rule = createComposeRule()

    private val coverAsset =
        "file:///android_asset/ui7/mosaic/UI7_MOSAICO_005_Pop.webp"
    private val context = androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation().targetContext
    private val evidenceEnabled = androidx.test.platform.app.InstrumentationRegistry
        .getArguments().getString("michiGenreEvidence") == "true"
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
        val descriptor = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().uiAutomation.executeShellCommand(
                "mkdir -p /data/local/tmp/michi-evidence; " +
                    "cp '${target.absolutePath}' '/data/local/tmp/michi-evidence/$remoteName'",
            )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun bounds(tag: String, unmergedTree: Boolean = false): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = unmergedTree)
            .fetchSemanticsNode().boundsInRoot

    private fun track(id: String, title: String, coverId: String, filepath: String) = Track(
        id = id,
        title = title,
        artist = "Michi QA",
        album = "Action Album",
        albumId = "action_album_$id",
        genre = "Pop",
        sourceId = "local",
        coverId = coverId,
        filepath = filepath,
    )

    /** Wraps the actual Now Playing artwork and the actual sheet in one host. */
    private fun setHost(
        trackState: androidx.compose.runtime.MutableState<Track>,
        showSheet: androidx.compose.runtime.MutableState<Boolean>,
        onSheetArtwork: (Boolean) -> Unit = {},
        onNowPlayingArtwork: (Boolean) -> Unit = {},
    ) {
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                Box(Modifier.fillMaxSize()) {
                    val track = trackState.value
                    Column {
                        AeroTrackArtworkMotion(
                            track = track,
                            modifier = Modifier.size(200.dp).testTag("now_playing_art"),
                            onImageResult = onNowPlayingArtwork,
                        )
                        Text(track.title, modifier = Modifier.testTag("now_playing_title"))
                    }
                    if (showSheet.value) {
                        TrackActionBottomSheet(
                            track = track,
                            onDismiss = { showSheet.value = false },
                            onArtworkLoad = onSheetArtwork,
                        )
                    }
                }
            }
        }
    }

    private fun waitForSheetArt(tag: String = "track_action_artwork") {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun sheetUsesTheSameTrackArtworkAndTitleAsNowPlaying() {
        val first = track("sheet1", "Sheet One", coverAsset, "content://michi/sheet1.flac")
        val trackState = mutableStateOf(first)
        val showSheet = mutableStateOf(false)
        var sheetSuccesses by mutableIntStateOf(0)
        var npSuccesses by mutableIntStateOf(0)
        setHost(
            trackState, showSheet,
            onSheetArtwork = { if (it) sheetSuccesses += 1 },
            onNowPlayingArtwork = { if (it) npSuccesses += 1 },
        )
        rule.runOnIdle { showSheet.value = true }
        waitForSheetArt()
        rule.waitUntil(20_000) { sheetSuccesses >= 1 && npSuccesses >= 1 }

        rule.onNodeWithTag("track_action_artwork").assertIsDisplayed()
        rule.onNodeWithTag("track_action_title").assertTextEquals(first.title)
        rule.onNodeWithTag("now_playing_title").assertTextEquals(first.title)
        val sheetArt = bounds("track_action_artwork")
        assertTrue("Action artwork width must be 48..56dp",
            sheetArt.width >= pxOf(48f) - pxOf(1f) && sheetArt.width <= pxOf(56f) + pxOf(1f))
        assertTrue("Action artwork must stay square",
            abs(sheetArt.width - sheetArt.height) <= pxOf(1f))
        assertTrue("A loaded cover must not show the neutral placeholder",
            rule.onAllNodesWithTag("album_artwork_unavailable", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        captureTag("track_action_sheet", "now-playing-action-sheet")
        evidenceDir?.let {
            val boundsFile = File(it, "action-sheet-bounds.txt")
            boundsFile.writeText("art=$sheetArt")
            publish(boundsFile, "action-sheet-bounds.txt")
        }
    }

    @Test fun sheetFollowsTrackChangesCloseReopenAndNoArtFallback() {
        val first = track("sheet2", "First Track", coverAsset, "content://michi/first.flac")
        val second = track("sheet3", "Second Track", "", "content://michi/absent.flac")
        val trackState = mutableStateOf(first)
        val showSheet = mutableStateOf(true)
        var sheetSuccesses by mutableIntStateOf(0)
        setHost(
            trackState, showSheet,
            onSheetArtwork = { if (it) sheetSuccesses += 1 },
        )
        waitForSheetArt()
        rule.waitUntil(20_000) { sheetSuccesses >= 1 }
        rule.onNodeWithTag("track_action_title").assertTextEquals("First Track")

        // Exact track change while the sheet is open: title and artwork must
        // follow the new track, and the previous painter must not remain.
        rule.runOnIdle { trackState.value = second }
        rule.waitForIdle()
        rule.onNodeWithTag("track_action_title").assertTextEquals("Second Track")
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("album_artwork_unavailable", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("track_action_artwork").assertIsDisplayed()

        // Close and reopen still renders the exact current track.
        rule.runOnIdle { showSheet.value = false }
        rule.waitForIdle()
        rule.onNodeWithTag("track_action_artwork").assertDoesNotExist()
        rule.runOnIdle { showSheet.value = true }
        waitForSheetArt()
        rule.onNodeWithTag("track_action_title").assertTextEquals("Second Track")
        rule.onNodeWithTag("track_action_artwork").assertIsDisplayed()
        val art = bounds("track_action_artwork")
        assertTrue("Reopened no-art sheet keeps a 48..56dp placeholder slot",
            art.width >= pxOf(48f) - pxOf(1f) && art.width <= pxOf(56f) + pxOf(1f))
    }
}
