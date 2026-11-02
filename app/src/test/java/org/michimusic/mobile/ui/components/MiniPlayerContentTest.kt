package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.playback.ActivePlaybackPresentation
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mini player used to resolve Koin, both playback authorities and the
 * canonical presentation mapper inside its own body, so it had no test at all.
 * These tests drive the plain content composable with a fixed presentation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MiniPlayerContentTest {
    @get:Rule val compose = createComposeRule()

    private val track = Track(
        id = "track-1",
        title = "Baby, Come to Me",
        artist = "Patti Austin",
        duration = 215_000,
    )

    private fun presentation(
        current: Track? = track,
        playing: Boolean = true,
        duration: Long = 215_000,
    ) = ActivePlaybackPresentation(
        currentTrack = current,
        isPlaying = playing,
        position = 11_000,
        duration = duration,
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
    fun `content renders the track, the endpoint and one accessible opening target`() {
        compose.setContent {
            MichiTheme {
                MiniPlayerContent(presentation = presentation())
            }
        }

        compose.onNodeWithTag("mini_player_bar").assertIsDisplayed()
        compose.onNodeWithText("Baby, Come to Me").assertIsDisplayed()
        compose.onNodeWithText("Patti Austin").assertIsDisplayed()
        compose.onNodeWithText("Este teléfono").assertIsDisplayed()
        compose.onNodeWithContentDescription("Abrir reproductor: Baby, Come to Me, Patti Austin")
            .assertIsDisplayed()
    }

    @Test
    fun `transport buttons dispatch their own intents instead of opening the player`() {
        val opened = mutableListOf<String>()
        val played = mutableListOf<String>()
        val nexted = mutableListOf<String>()

        compose.setContent {
            MichiTheme {
                MiniPlayerContent(
                    presentation = presentation(),
                    onClick = { opened += "open" },
                    onPlayPause = { played += "toggle" },
                    onNext = { nexted += "next" },
                )
            }
        }

        compose.onNodeWithContentDescription("Abrir reproductor: Baby, Come to Me, Patti Austin").performClick()
        assertEquals(listOf("open"), opened)
        assertEquals(emptyList<String>(), played)

        compose.onNodeWithTag("mini_player_play_pause").performClick()
        compose.onNodeWithTag("mini_player_next").performClick()
        assertEquals(listOf("toggle"), played)
        assertEquals(listOf("next"), nexted)
        assertEquals(listOf("open"), opened)
    }

    @Test
    fun `play pause describes the action it will perform`() {
        compose.setContent {
            MichiTheme {
                MiniPlayerContent(presentation = presentation(playing = true))
            }
        }
        compose.onNodeWithTag("mini_player_play_pause").assertContentDescriptionEquals("Pausar")
    }

    @Test
    fun `paused playback advertises play`() {
        compose.setContent {
            MichiTheme {
                MiniPlayerContent(presentation = presentation(playing = false))
            }
        }
        compose.onNodeWithTag("mini_player_play_pause").assertContentDescriptionEquals("Reproducir")
    }

    @Test
    fun `nothing is rendered without a track`() {
        compose.setContent {
            MichiTheme {
                MiniPlayerContent(presentation = presentation(current = null))
            }
        }
        compose.onNodeWithTag("mini_player_bar").assertDoesNotExist()
    }

    @Test
    fun `nothing is rendered when hidden`() {
        compose.setContent {
            MichiTheme {
                MiniPlayerContent(presentation = presentation(), visible = false)
            }
        }
        compose.onNodeWithTag("mini_player_bar").assertDoesNotExist()
    }
}
