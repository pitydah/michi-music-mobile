package org.michimusic.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.mobile.playback.RemoteQueueAction
import org.michimusic.mobile.playback.RemoteQueuePreflightDecision
import org.michimusic.mobile.ui.components.RemoteQueueConfirmationDialog
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.components.premium.PlaybackControlUiState
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences

@RunWith(AndroidJUnit4::class)
class AeroQualityGateInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun verifyRemoteQueueConfirmationDialog_rendersAllAeroActionsAndDismiss() {
        val proposed = listOf(
            Track("srv_1", "Proposed Song 1", "Artist 1", source = TrackSource.STREAMING),
            Track("srv_2", "Proposed Song 2", "Artist 2", source = TrackSource.STREAMING),
        )
        val conf = RemoteQueuePreflightDecision.ConfirmationRequired(
            destinationId = "desktop_node_1",
            destinationName = "Studio Desktop",
            remoteActiveTrackTitle = "Currently Playing Symphony",
            remoteQueueSize = 5,
            proposedTracks = proposed,
            proposedStartIndex = 0,
        )

        var replaceClicked = false
        var appendClicked = false
        var cancelClicked = false

        composeTestRule.setContent {
            MichiTheme(visualPreferences = VisualPreferences()) {
                RemoteQueueConfirmationDialog(
                    confirmation = conf,
                    onConfirmReplace = { replaceClicked = true },
                    onConfirmAppend = { appendClicked = true },
                    onDismiss = { cancelClicked = true },
                )
            }
        }

        // Verify title & contextual subtitle are rendered
        composeTestRule.onNodeWithText("Cola en Studio Desktop", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Currently Playing Symphony", substring = true).assertIsDisplayed()

        // Verify actions
        composeTestRule.onNodeWithTag("remote_queue_replace_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("remote_queue_append_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("remote_queue_cancel_button").assertIsDisplayed()

        // Click Append
        composeTestRule.onNodeWithTag("remote_queue_append_button").performClick()
        assertTrue("onConfirmAppend callback must fire when clicking 'Añadir al final'", appendClicked)
        assertFalse(replaceClicked)
        assertFalse(cancelClicked)
    }

    @Test
    fun verifyMichiIconActionPureIconTouchTargetAndAccessibility() {
        var clicked = false
        composeTestRule.setContent {
            MichiTheme(visualPreferences = VisualPreferences()) {
                MichiIconAction(
                    icon = Icons.Filled.PlayArrow,
                    contentDescription = "Reproducir pista actual",
                    onClick = { clicked = true },
                    minTargetSize = 48.dp,
                )
            }
        }

        val node = composeTestRule.onNodeWithContentDescription("Reproducir pista actual")
        node.assertIsDisplayed()
        node.performClick()
        assertTrue("MichiIconAction must trigger onClick upon tap", clicked)
    }

    @Test
    fun verifyAccessibilitySemanticsDescriptions() {
        val playingState = PlaybackControlUiState(isPlaying = true, isLoading = false)
        val pausedState = PlaybackControlUiState(isPlaying = false, isLoading = false)
        val loadingState = PlaybackControlUiState(isPlaying = true, isLoading = true)

        assertEquals("Pausar", playingState.playPauseContentDescription())
        assertEquals("Reproducir", pausedState.playPauseContentDescription())
        assertEquals("Cargando audio", loadingState.playPauseContentDescription())
    }
}
