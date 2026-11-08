package org.michimusic.mobile.ui.motion

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.AeroTheme
import org.michimusic.mobile.ui.theme.VisualPreferences
import androidx.compose.material3.Text

/** Run on emulator by OpenCode, never as a Drive-side Android test. */
class AeroMotionComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val generatedArtwork = mutableListOf<File>()

    @After
    fun removeGeneratedArtwork() {
        generatedArtwork.forEach(File::delete)
        generatedArtwork.clear()
    }

    @Test fun sourceTextChangesWithoutInventingConnectionState() {
        val observed = mutableStateOf("Sin conexión")
        rule.setContent {
            AeroTheme(VisualPreferences(reducedMotion = true)) {
                AeroObservedText(value = observed.value, color = Color.White, style = TextStyle.Default)
            }
        }
        rule.onNodeWithText("Sin conexión").assertExists()
        rule.runOnIdle { observed.value = "Controlando Michi Desktop" }
        rule.onNodeWithText("Controlando Michi Desktop").assertExists()
    }

    @Test fun pendingBannerDisappearsWhenAuthorityClearsIt() {
        val pending = mutableStateOf(true)
        rule.setContent {
            AeroTheme(VisualPreferences(reducedMotion = true)) {
                AeroPendingReveal(visible = pending.value) { Text("Orden pendiente") }
            }
        }
        rule.onNodeWithText("Orden pendiente").assertExists()
        rule.runOnIdle { pending.value = false }
        rule.onNodeWithText("Orden pendiente").assertDoesNotExist()
    }

    @Test fun dynamicMeshFallsBackWithoutAnArtworkOrPlaybackSession() {
        // The canvas must mount without a local track or bitmap decoder; drawing
        // the canonical Aero fallback must never require playback services.
        val preferences = mutableStateOf(VisualPreferences(reducedMotion = true))
        rule.setContent {
            AeroTheme(preferences.value) {
                AeroDynamicMeshBackground(
                    track = null,
                    playing = false,
                    modifier = Modifier.width(180.dp).height(280.dp),
                )
            }
        }
        rule.onNodeWithTag("aero_dynamic_mesh_background").assertExists()
        rule.runOnIdle {
            preferences.value = VisualPreferences(reducedMotion = true, highContrast = true)
        }
        rule.onNodeWithTag("aero_dynamic_mesh_background").assertExists()
    }

    @Test fun dynamicMeshUsesTheLatestLocalArtworkPalette() {
        val firstArtwork = createArtwork("mesh-first", intArrayOf(
            android.graphics.Color.RED,
            android.graphics.Color.GREEN,
            android.graphics.Color.BLUE,
        ))
        val latestArtwork = createArtwork("mesh-latest", intArrayOf(
            android.graphics.Color.MAGENTA,
            android.graphics.Color.CYAN,
            android.graphics.Color.YELLOW,
        ))
        val track = mutableStateOf<Track?>(null)
        rule.setContent {
            AeroTheme(VisualPreferences(reducedMotion = true)) {
                AeroDynamicMeshBackground(
                    track = track.value,
                    playing = true,
                    modifier = Modifier.width(180.dp).height(280.dp),
                )
            }
        }

        val fallback = meshSignature()
        rule.runOnIdle {
            track.value = localTrack("first", firstArtwork)
        }
        val first = waitForDifferentMesh(fallback)
        assertNotEquals(fallback, first)

        rule.runOnIdle {
            track.value = localTrack("intermediate", firstArtwork)
            track.value = localTrack("latest", latestArtwork)
        }
        val latest = waitForDifferentMesh(first)
        assertNotEquals(first, latest)

        // Re-selecting the latest identity must resolve to the same cached colors,
        // never a stale palette from the superseded request.
        rule.runOnIdle { track.value = null }
        rule.waitForIdle()
        rule.runOnIdle { track.value = localTrack("latest-again", latestArtwork) }
        rule.waitUntil(timeoutMillis = 5_000) { meshSignature() == latest }
        assertEquals(latest, meshSignature())
    }

    @Test fun dynamicMeshMovesOnlyDuringActiveForegroundPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        assumeFalse("Low-RAM devices intentionally freeze Dynamic Mesh", activityManager.isLowRamDevice)
        assumeFalse("Power Save intentionally freezes Dynamic Mesh", powerManager.isPowerSaveMode)

        val playing = mutableStateOf(true)
        val lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                AeroTheme {
                    AeroDynamicMeshBackground(
                        track = null,
                        playing = playing.value,
                        modifier = Modifier.width(180.dp).height(280.dp),
                    )
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()

        val activeStart = meshSignature()
        rule.mainClock.advanceTimeBy(625)
        val activeEnd = meshSignature()
        assertNotEquals("Active foreground playback must drift", activeStart, activeEnd)

        rule.runOnIdle { playing.value = false }
        rule.mainClock.advanceTimeByFrame()
        val pausedStart = meshSignature()
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("Pause must preserve the mesh phase", pausedStart, meshSignature())

        rule.runOnIdle {
            playing.value = true
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        rule.mainClock.advanceTimeByFrame()
        val backgroundStart = meshSignature()
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("Background lifecycle must suspend drift", backgroundStart, meshSignature())
    }

    @Test fun unknownProgressRemainsIndeterminateInHighContrast() {
        rule.setContent {
            AeroTheme(VisualPreferences(highContrast = true)) {
                AeroTruthfulProgress(
                    fraction = null,
                    color = Color.Cyan,
                    trackColor = Color.DarkGray,
                    modifier = Modifier.width(140.dp).height(8.dp).testTag("hc_unknown_progress"),
                )
            }
        }
        rule.onNodeWithTag("hc_unknown_progress").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
    }

    @Test fun unknownProgressRemainsIndeterminateWithReducedMotion() {
        rule.setContent {
            AeroTheme(VisualPreferences(reducedMotion = true)) {
                AeroTruthfulProgress(
                    fraction = null,
                    color = Color.Cyan,
                    trackColor = Color.DarkGray,
                    modifier = Modifier.width(140.dp).height(8.dp).testTag("unknown_progress"),
                )
            }
        }
        rule.onNodeWithTag("unknown_progress").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
    }

    private fun createArtwork(name: String, colors: IntArray): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "$name-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            val color = colors[(y * colors.size / bitmap.height).coerceAtMost(colors.lastIndex)]
            for (x in 0 until bitmap.width) bitmap.setPixel(x, y, color)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        generatedArtwork += file
        return file
    }

    private fun localTrack(id: String, artwork: File) = Track(
        id = id,
        title = id,
        coverId = Uri.fromFile(artwork).toString(),
    )

    private fun waitForDifferentMesh(previous: Long): Long {
        rule.waitUntil(timeoutMillis = 5_000) { meshSignature() != previous }
        return meshSignature()
    }

    private fun meshSignature(): Long {
        val pixels = rule.onNodeWithTag("aero_dynamic_mesh_background")
            .captureToImage()
            .toPixelMap()
        var hash = 17L
        val stepX = (pixels.width / 12).coerceAtLeast(1)
        val stepY = (pixels.height / 18).coerceAtLeast(1)
        for (y in 0 until pixels.height step stepY) {
            for (x in 0 until pixels.width step stepX) {
                hash = hash * 31 + pixels[x, y].toArgb()
            }
        }
        return hash
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
