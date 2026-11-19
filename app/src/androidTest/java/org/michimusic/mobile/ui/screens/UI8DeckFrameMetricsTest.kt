package org.michimusic.mobile.ui.screens

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.screens.PlaylistItem
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * UI8-G6 motion budget on the available device.
 *
 * The project has no macrobenchmark module, so this test reads the platform's own
 * per-frame metrics while the deck performs real horizontal swipes and moves the
 * front card. It asserts that the measurement is real (frames are produced and
 * every swipe advances the deck); it deliberately does not assert a frame budget.
 * This emulator renders with SwiftShader (software GL) and cannot certify phone
 * jank. The printed p50/p95/janky numbers are reported as emulator evidence.
 */
@RunWith(AndroidJUnit4::class)
class UI8DeckFrameMetricsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deckSwipesProduceFrameMetricsAndAdvanceTheFrontCard() {
        val cards = (0 until 20).map {
            PlaylistItem(id = "frame-$it", name = "Colección $it", trackCount = it)
        }
        rule.setContent {
            MichiTheme {
                Box(Modifier.fillMaxSize().background(SurfaceObsidian).testTag("ui8_frames_harness")) {
                    PlaylistSwipingDeck(
                        cards = cards,
                        key = { it.id },
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    ) { item ->
                        PlaylistCatalogSpotlight(
                            playlist = item,
                            visual = PlaylistVisualState(),
                            onOpen = {},
                            actions = {},
                            modifier = Modifier.fillMaxSize(),
                            deck = true,
                        )
                    }
                }
            }
        }

        val frames = CopyOnWriteArrayList<Long>()
        val thread = HandlerThread("ui8-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        rule.runOnUiThread {
            rule.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }
        rule.waitForIdle()
        var front = 0
        repeat(16) {
            rule.onNodeWithTag("playlist_swiping_front", useUnmergedTree = true)
                .performTouchInput { swipeLeft() }
            rule.waitForIdle()
            front = (front + 1) % cards.size
            rule.onNodeWithTag("playlist_spotlight_title_frame-$front", useUnmergedTree = true)
                .assertExists()
        }
        rule.runOnUiThread { rule.activity.window.removeOnFrameMetricsAvailableListener(listener) }
        thread.quitSafely()

        val sorted = frames.sorted()
        assertTrue("the deck must produce frames (got ${sorted.size})", sorted.size >= 16)
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]
        val janky = sorted.count { it > 16_666_667L }
        println(
            "UI8-FRAMES frames=${sorted.size} p50=${p50 / 1_000_000.0}ms " +
                "p95=${p95 / 1_000_000.0}ms janky=$janky",
        )
        // No frame budget is asserted here: SwiftShader cannot certify phone jank.
        // The p50/p95/janky values above are the measurable evidence of this run.
        assertTrue("p95 must be a real measurement", p95 > 0L)
    }
}
