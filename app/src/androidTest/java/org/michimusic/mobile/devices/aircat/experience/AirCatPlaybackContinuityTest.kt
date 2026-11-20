package org.michimusic.mobile.devices.aircat.experience

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.devices.aircat.AirCatTransferState
import org.michimusic.mobile.ui.theme.MichiTheme

/**
 * A71-S7: opening and operating the receive surface does not interrupt an
 * active Media3 playback.
 *
 * The runtime half of the ownership claim checked by
 * `AirCatReceiveDoesNotOwnPlaybackTest`. A real ExoPlayer plays a generated WAV
 * on the instrumented device; the AirCat hub and transfer screens are rendered
 * and their buttons are pressed through their real callbacks. The player must
 * still be playing and its position must have advanced afterwards.
 *
 * Limit, stated plainly: this environment has no Michi peer, so no byte is
 * received. EXTERNAL_INTEROP is BLOCKED_EXTERNAL. This test proves that the
 * receive *surface* does not own, pause or release playback; it does not prove
 * that a real socket transfer leaves playback untouched. The JVM scan proves
 * the receive *engine* has no playback or audio-focus dependency at all, which
 * is the only way this code could reach Media3.
 */
@RunWith(AndroidJUnit4::class)
class AirCatPlaybackContinuityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun operatingTheReceiveSurfaceLeavesAnActivePlayerPlaying() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumented = InstrumentationRegistry.getInstrumentation()
        val tone = File(context.cacheDir, "a71-playback-continuity.wav")
        writeTone(tone)

        val player = ExoPlayer.Builder(context).build()
        val started = CountDownLatch(1)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) started.countDown()
            }
        })
        try {
            instrumented.runOnMainSync {
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(tone)))
                player.prepare()
                player.play()
            }
            assertTrue(
                "ExoPlayer did not start the local tone within 15s",
                started.await(15, TimeUnit.SECONDS),
            )

            var phase by mutableStateOf(AirCatPhase.HUB)
            compose.setContent {
                MichiTheme {
                    AirCatExperienceContent(
                        state = stateFor(phase),
                        onIntent = {},
                    )
                }
            }
            compose.waitForIdle()

            val before = readPlayer(player, instrumented)
            assertEquals(Player.STATE_READY, before.state)
            assertTrue("the player was not playing before the surface opened", before.playing)

            // Press the real controls: the contract is that they only emit
            // intents, so nothing here can reach the player.
            compose.onNodeWithTag("aircat_receive_button").performClick()
            compose.runOnIdle { phase = AirCatPhase.TRANSFER }
            compose.waitForIdle()
            compose.onNodeWithText("Pausar").performClick()
            compose.onNodeWithText("Cancelar").performClick()
            compose.waitForIdle()

            // Wait for media time to advance; this is playback time, not Compose.
            Thread.sleep(800)

            val after = readPlayer(player, instrumented)
            assertTrue(
                "the AirCat surface stopped playback",
                after.playing,
            )
            assertEquals(
                "the player left the ready state while the receive surface was open",
                Player.STATE_READY,
                after.state,
            )
            assertTrue(
                "playback position did not advance (${before.position}ms -> ${after.position}ms)",
                after.position > before.position,
            )
        } finally {
            instrumented.runOnMainSync { player.release() }
        }
    }

    private data class PlayerSnapshot(val playing: Boolean, val state: Int, val position: Long)

    private fun readPlayer(player: ExoPlayer, instrumented: android.app.Instrumentation): PlayerSnapshot {
        var snapshot: PlayerSnapshot? = null
        instrumented.runOnMainSync {
            snapshot = PlayerSnapshot(player.isPlaying, player.playbackState, player.currentPosition)
        }
        return requireNotNull(snapshot)
    }

    private fun stateFor(phase: AirCatPhase): AirCatExperienceState {
        val source = AirCatSourceUi(
            deviceId = "dev-1",
            name = "Micro Server",
            kindLabel = "Servidor de música",
            discovered = false,
            known = true,
            connected = true,
            homeEnrolled = true,
            airCatAdvertised = true,
        )
        val track = AirCatTrackUi(
            remoteTrackId = "rem-1",
            logicalTrackId = "logical-rem-1",
            title = "Track",
            artist = "Artist",
            album = "Album",
            format = "flac",
            size = 1_048_576L,
            sha256 = "a".repeat(64),
        )
        val catalogue = AirCatCatalogueUi("dev-1", "Micro Server", listOf(track))
        return AirCatExperienceState(
            phase = phase,
            selectedSourceId = "dev-1",
            sources = listOf(source),
            catalogue = catalogue,
            selection = setOf("rem-1"),
            transfer = if (phase == AirCatPhase.TRANSFER) {
                AirCatTransferUi(
                    transferId = "transfer-1",
                    state = AirCatTransferState.TRANSFERRING,
                    completedTracks = 0,
                    totalTracks = 1,
                    transferredBytes = 0L,
                    totalBytes = 1_048_576L,
                    currentTrackTitle = "Track",
                    paused = false,
                    error = null,
                )
            } else {
                null
            },
        )
    }

    /** 8 seconds of a 440 Hz tone, 16-bit mono PCM, so ExoPlayer has real audio. */
    private fun writeTone(file: File) {
        val sampleRate = 44_100
        val samples = sampleRate * 8
        val dataSize = samples * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            fun le16(value: Int) {
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
            }
            fun le32(value: Int) {
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
                out.write((value shr 16) and 0xFF)
                out.write((value shr 24) and 0xFF)
            }
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            le32(36 + dataSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            le32(16)
            le16(1)
            le16(1)
            le32(sampleRate)
            le32(sampleRate * 2)
            le16(2)
            le16(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            le32(dataSize)
            for (index in 0 until samples) {
                val value = (sin(2.0 * PI * 440.0 * index / sampleRate) * 0.2 * Short.MAX_VALUE)
                le16(value.toInt())
            }
        }
    }
}
