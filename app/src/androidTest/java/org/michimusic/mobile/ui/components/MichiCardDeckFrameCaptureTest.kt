package org.michimusic.mobile.ui.components

import android.os.Build
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI8/C2: records the frame intervals the deck actually produces while a finger
 * drags the front card, and writes them next to the device that produced them.
 *
 * It deliberately asserts no frame budget. The same scenario on this project runs
 * on a software-rendered emulator during CI and on a physical phone on a desk, and
 * a threshold copied from one would be a fabricated promise for the other. The
 * measurement exists so the report can quote real numbers with their environment
 * and the jank definition, and so a future run can be compared like for like.
 */
@RunWith(AndroidJUnit4::class)
class MichiCardDeckFrameCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDeckDragFrameTimingsAreRecordedForThisDevice() {
        var selected by mutableStateOf("artist-0")
        val items = (0 until 6).map { "artist-$it" }

        compose.setContent {
            MichiCardDeck(
                items = items,
                itemKey = { it },
                selectedKey = selected,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxSize().testTag("deck_under_test"),
                circularArtwork = true,
                navigationButtons = false,
            ) { item, isFront ->
                Box(
                    Modifier.fillMaxSize().padding(32.dp).clip(CircleShape)
                        .background(Color.DarkGray)
                        .testTag(if (isFront) "card_$item" else "card_back_$item"),
                )
            }
        }
        compose.waitForIdle()

        val frames = mutableListOf<Long>()
        val running = AtomicBoolean(true)
        val began = CountDownLatch(1)
        var previous = 0L
        val recorder = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running.get()) return
                if (previous != 0L) frames += (frameTimeNanos - previous) / 1_000_000
                previous = frameTimeNanos
                began.countDown()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().postFrameCallback(recorder)
        }
        assertTrue("no frame was produced", began.await(10, TimeUnit.SECONDS))

        compose.onNodeWithTag("michi_card_front").performTouchInput { down(center) }
        compose.waitForIdle()
        repeat(MOVES) {
            compose.onNodeWithTag("michi_card_front").performTouchInput { moveBy(Offset(-6f, 0f)) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("michi_card_front").performTouchInput { up() }
        compose.waitForIdle()

        running.set(false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().removeFrameCallback(recorder)
        }

        assertTrue("the drag produced no measured frame", frames.size >= MOVES / 2)
        val sorted = frames.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        val refresh = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        val janky = frames.count { it > (1000f / refresh).toLong() }

        val report = buildString {
            appendLine("DECK-FRAMES device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT}")
            appendLine("DECK-FRAMES refreshHz=${String.format(Locale.US, "%.2f", refresh)}")
            appendLine("DECK-FRAMES frames=${frames.size} p50=${p50}ms p95=${p95}ms max=${sorted.last()}ms")
            appendLine("DECK-FRAMES janky=$janky (delta greater than one display frame)")
            appendLine("DECK-FRAMES deltas=${frames.joinToString(",")}")
        }
        val file = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "deck-frames.txt",
        )
        file.writeText(report)
        assertTrue("empty frame report: ${file.absolutePath}", file.length() > 0)
    }

    private companion object {
        const val MOVES = 60
    }
}
