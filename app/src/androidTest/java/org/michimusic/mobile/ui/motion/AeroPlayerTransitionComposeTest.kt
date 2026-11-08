package org.michimusic.mobile.ui.motion

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.mobile.playback.ActivePlaybackPresentation
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.ui.components.MiniPlayerContent
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences

/**
 * Exercises the complete local player artwork policy without resolving Koin or
 * opening a second playback authority. Physical QA still owns real NavHost and
 * session evidence; this harness makes the source/destination lifecycle
 * deterministic enough to catch detached shared overlays in CI.
 */
class AeroPlayerTransitionComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val track = Track(
        id = "transition-track",
        title = "Transition Track",
        artist = "Michi Artist",
        duration = 180_000,
    )

    private fun presentation(current: Track?, playing: Boolean = true) = ActivePlaybackPresentation(
        currentTrack = current,
        isPlaying = playing,
        position = 12_000,
        duration = current?.duration ?: 0,
        queue = listOfNotNull(current),
        queueIndex = if (current == null) -1 else 0,
        repeatMode = 0,
        shuffleMode = 0,
        endpoint = PlaybackEndpoint.LocalPhone,
        isRemoteActive = false,
        isStreamReceiver = false,
        endpointDescription = "Este teléfono",
    )

    @Test
    fun localFallbackCompletesTwentyOpenAndReturnCyclesWithoutTransportCommands() {
        val nowPlaying = mutableStateOf(false)
        val currentTrack = mutableStateOf<Track?>(track)
        val transportCommands = AtomicInteger(0)

        rule.setContent {
            MichiTheme {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayerContent(
                        presentation = presentation(currentTrack.value),
                        visible = !nowPlaying.value,
                        onClick = { nowPlaying.value = true },
                        onPlayPause = { transportCommands.incrementAndGet() },
                        onNext = { transportCommands.incrementAndGet() },
                    )
                    if (nowPlaying.value) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("transition_now_playing")
                                .clickable { nowPlaying.value = false },
                        ) {
                            AeroTrackArtworkMotion(
                                track = currentTrack.value,
                                modifier = Modifier
                                    .size(240.dp)
                                    .aeroNowPlayingHeroEnter(currentTrack.value != null)
                                    .testTag("transition_now_playing_art"),
                            )
                        }
                    }
                }
            }
        }

        repeat(20) {
            rule.onNodeWithContentDescription(
                "Abrir reproductor: Transition Track, Michi Artist",
            ).performClick()
            rule.mainClock.advanceTimeBy(1_000)
            rule.onNodeWithTag("transition_now_playing_art").assertExists()
            rule.onNodeWithTag("mini_player_bar").assertDoesNotExist()

            rule.onNodeWithTag("transition_now_playing").performClick()
            rule.mainClock.advanceTimeBy(1_000)
            rule.onNodeWithTag("mini_player_bar").assertExists()
            rule.onNodeWithTag("transition_now_playing_art").assertDoesNotExist()
        }

        assertEquals(0, transportCommands.get())
    }

    @Test
    fun rapidTrackPauseAndMissingArtworkChangesKeepOneLocalDestination() {
        val nowPlaying = mutableStateOf(true)
        val currentTrack = mutableStateOf<Track?>(track)
        val playing = mutableStateOf(true)

        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayerContent(
                        presentation = presentation(currentTrack.value, playing.value),
                        visible = !nowPlaying.value,
                    )
                    if (nowPlaying.value) {
                        AeroTrackArtworkMotion(
                            track = currentTrack.value,
                            modifier = Modifier
                                .size(240.dp)
                                .aeroNowPlayingHeroEnter(currentTrack.value != null)
                                .testTag("transition_now_playing_art"),
                        )
                    }
                }
            }
        }

        rule.runOnIdle {
            currentTrack.value = track.copy(id = "replacement", title = "Replacement")
            playing.value = false
            currentTrack.value = null
        }
        rule.mainClock.advanceTimeBy(1_000)

        rule.onNodeWithTag("transition_now_playing_art").assertExists()
        rule.onNodeWithTag("mini_player_bar").assertDoesNotExist()
        assertEquals(false, aeroNowPlayingTransitionPolicy(reducedMotion = true).shareArtwork)
    }
}
