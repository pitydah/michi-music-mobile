package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.mobile.remote.RemoteConnectionState
import org.michimusic.mobile.remote.RemoteSourceMode
import org.michimusic.mobile.remote.RemoteUiState
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h1200dp", application = android.app.Application::class)
class RemoteScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun remoteScreenContent_disconnectedState_displaysEmptyStateAndConnectButton() {
        var syncNavigated = false
        val disconnectedState = RemoteUiState(
            mode = RemoteSourceMode.LOCAL,
            connState = RemoteConnectionState.DISCONNECTED,
            connected = false,
        )

        composeTestRule.setContent {
            MichiTheme {
                RemoteScreenContent(
                    state = disconnectedState,
                    onNavigateToSync = { syncNavigated = true },
                    onBack = {},
                    onPlayPause = {},
                    onSkipPrevious = {},
                    onSkipNext = {},
                    onSeek = {},
                    onHandoff = {},
                    handoffMessage = null,
                )
            }
        }

        // Title and disconnected texts
        composeTestRule.onNodeWithTag("remote_title")
            .assertIsDisplayed()
            .assertTextEquals("Michi Control")
        composeTestRule.onNodeWithText("Sin conexión").assertIsDisplayed()
        composeTestRule.onNodeWithText("No hay dispositivo conectado").assertIsDisplayed()

        // Connect CTA
        composeTestRule.onNodeWithTag("remote_connect_button").assertIsDisplayed().performClick()
        assertTrue(syncNavigated)
    }

    @Test
    fun remoteScreenContent_connectedState_displaysControlsAndTargetSizes() {
        var playPauseClicked = false
        var prevClicked = false
        var nextClicked = false
        var handoffClicked = false

        val connectedState = RemoteUiState(
            mode = RemoteSourceMode.REMOTE,
            connState = RemoteConnectionState.CONNECTED,
            connected = true,
            sourceName = "Sala de estar",
            playerState = PlaybackStateDto(
                state = "playing",
                title = "Aero Symphony",
                artist = "Michi Ensemble",
                album = "Convergence",
                positionMs = 64000L,
                durationMs = 232000L,
            ),
            pendingCommand = true,
            pendingCommandMessage = "Orden pendiente; espera confirmación",
        )

        composeTestRule.setContent {
            MichiTheme {
                RemoteScreenContent(
                    state = connectedState,
                    onNavigateToSync = {},
                    onBack = {},
                    onPlayPause = { playPauseClicked = true },
                    onSkipPrevious = { prevClicked = true },
                    onSkipNext = { nextClicked = true },
                    onSeek = {},
                    onHandoff = { handoffClicked = true },
                    handoffMessage = "Traspaso solicitado al teléfono",
                )
            }
        }

        // Header shows source name and Conectado
        composeTestRule.onNodeWithTag("remote_title")
            .assertIsDisplayed()
            .assertTextEquals("Sala de estar")
        composeTestRule.onNodeWithText("Conectado").assertIsDisplayed()

        // Pending banner in amber
        composeTestRule.onNodeWithText("Orden pendiente; espera confirmación").assertIsDisplayed()

        // Track metadata displayed
        composeTestRule.onNodeWithText("Aero Symphony").assertIsDisplayed()
        composeTestRule.onNodeWithText("Michi Ensemble • Convergence").assertIsDisplayed()

        // Transport controls: WCAG >= 48x48 dp, play/pause >= 64-72 dp
        composeTestRule.onNodeWithTag("remote_prev")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(prevClicked)

        composeTestRule.onNodeWithTag("remote_play_pause")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(64.dp)
            .assertHeightIsAtLeast(64.dp)
            .performClick()
        assertTrue(playPauseClicked)

        composeTestRule.onNodeWithTag("remote_next")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(nextClicked)

        // Selected output card
        composeTestRule.onNodeWithText("SALIDA SELECCIONADA").assertIsDisplayed()
        composeTestRule.onNodeWithText("Activo").assertIsDisplayed()

        // Handoff button and message
        composeTestRule.onNodeWithTag("remote_handoff_button").assertIsDisplayed().performClick()
        assertTrue(handoffClicked)
        composeTestRule.onNodeWithText("Traspaso solicitado al teléfono").assertIsDisplayed()
    }
}
