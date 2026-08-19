package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AddTracksDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tracks = listOf(
        Track(id = "t1", title = "Neon Skyline", artist = "Cyber Runner"),
        Track(id = "t2", title = "Midnight Drive", artist = "Vapor Wave"),
    )

    @Test
    fun `shows available tracks with title and artist`() {
        composeTestRule.setContent {
            AddTracksDialog(availableTracks = tracks, isLoadingTracks = false, isSaving = false, onConfirm = {}, onDismiss = {})
        }

        composeTestRule.onNodeWithText("Neon Skyline").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cyber Runner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Midnight Drive").assertIsDisplayed()
        composeTestRule.onNodeWithText("Vapor Wave").assertIsDisplayed()
    }

    @Test
    fun `selecting a track updates the selection count`() {
        composeTestRule.setContent {
            AddTracksDialog(availableTracks = tracks, isLoadingTracks = false, isSaving = false, onConfirm = {}, onDismiss = {})
        }

        composeTestRule.onNodeWithTag("add_tracks_dialog_track_t1").performClick()

        composeTestRule.onNodeWithText("1 seleccionadas").assertIsDisplayed()
    }

    @Test
    fun `tapping a selected track deselects it, so confirm does not send it`() {
        var confirmedIds: List<String>? = null
        composeTestRule.setContent {
            AddTracksDialog(
                availableTracks = tracks,
                isLoadingTracks = false,
                isSaving = false,
                onConfirm = { confirmedIds = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("add_tracks_dialog_track_t1").performClick()
        composeTestRule.onNodeWithTag("add_tracks_dialog_track_t1").performClick()
        composeTestRule.onNodeWithTag("add_tracks_dialog_confirm_button").performClick()

        assertEquals(null, confirmedIds)
    }

    @Test
    fun `confirm does nothing when nothing is selected`() {
        var confirmedIds: List<String>? = null
        composeTestRule.setContent {
            AddTracksDialog(
                availableTracks = tracks,
                isLoadingTracks = false,
                isSaving = false,
                onConfirm = { confirmedIds = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("add_tracks_dialog_confirm_button").performClick()

        assertEquals(null, confirmedIds)
    }

    @Test
    fun `confirm sends selected ids in the order they were tapped`() {
        var confirmedIds: List<String>? = null
        composeTestRule.setContent {
            AddTracksDialog(
                availableTracks = tracks,
                isLoadingTracks = false,
                isSaving = false,
                onConfirm = { confirmedIds = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("add_tracks_dialog_track_t2").performClick()
        composeTestRule.onNodeWithTag("add_tracks_dialog_track_t1").performClick()
        composeTestRule.onNodeWithText("2 seleccionadas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_tracks_dialog_confirm_button").performClick()

        assertEquals(listOf("t2", "t1"), confirmedIds)
    }

    @Test
    fun `cancel dismisses without confirming`() {
        var confirmCalled = false
        var dismissCalled = false
        composeTestRule.setContent {
            AddTracksDialog(
                availableTracks = tracks,
                isLoadingTracks = false,
                isSaving = false,
                onConfirm = { confirmCalled = true },
                onDismiss = { dismissCalled = true },
            )
        }

        composeTestRule.onNodeWithTag("add_tracks_dialog_track_t1").performClick()
        composeTestRule.onNodeWithContentDescription("Cerrar").performClick()

        assertTrue(dismissCalled)
        assertFalse(confirmCalled)
    }

    @Test
    fun `shows empty state when no tracks are available`() {
        composeTestRule.setContent {
            AddTracksDialog(availableTracks = emptyList(), isLoadingTracks = false, isSaving = false, onConfirm = {}, onDismiss = {})
        }

        composeTestRule.onNodeWithTag("add_tracks_dialog_empty").assertIsDisplayed()
        composeTestRule.onNodeWithText("No hay canciones disponibles para añadir").assertIsDisplayed()
    }

    @Test
    fun `shows loading indicator while tracks are being fetched`() {
        composeTestRule.setContent {
            AddTracksDialog(availableTracks = emptyList(), isLoadingTracks = true, isSaving = false, onConfirm = {}, onDismiss = {})
        }

        composeTestRule.onNodeWithTag("add_tracks_dialog_loading").assertIsDisplayed()
    }
}
