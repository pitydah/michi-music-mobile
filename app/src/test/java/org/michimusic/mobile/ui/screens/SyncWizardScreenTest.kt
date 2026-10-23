package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.SyncPlan
import org.michimusic.mobile.ui.screens.sync.SyncWizardScreen
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SyncWizardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val oneMb = 1024L * 1024L

    private val sampleTracks = listOf(
        ManifestTrack(trackId = "trk-1", title = "Midnight City", artist = "M83", album = "Hurry Up", size = 15 * oneMb),
        ManifestTrack(trackId = "trk-2", title = "Reunion", artist = "M83", album = "Hurry Up", size = 12 * oneMb),
    )

    @Test
    fun g14_aircat_select_stepDisplaysScopeOptionsAndAdvances() {
        composeTestRule.setContent {
            SyncWizardScreen(
                endpointId = "ep-test-1",
                endpointName = "Studio Desktop",
                availableStorageBytes = 5000 * oneMb,
                totalStorageBytes = 64000 * oneMb,
                currentLocalMusicBytes = 500 * oneMb,
                candidateTracks = sampleTracks,
                serverCapabilities = setOf("SYNC_V1"),
                onStartSync = {},
                onBack = {},
            )
        }

        // Verify Step 1 elements
        composeTestRule.onNodeWithText("¿Qué deseas sincronizar?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sync_wizard_selection_full_library").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sync_wizard_selection_playlists").assertExists()
        composeTestRule.onNodeWithTag("sync_wizard_selection_albums").assertExists()

        // Advance to Quality step
        composeTestRule.onNodeWithTag("sync_wizard_next_button").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Perfil de Entrega y Calidad").assertIsDisplayed()
    }

    @Test
    fun serverWithoutTranscoding_disablesBalancedAndLowStorageWithHonestNotice() {
        composeTestRule.setContent {
            SyncWizardScreen(
                endpointId = "ep-test-1",
                endpointName = "Studio Desktop",
                availableStorageBytes = 5000 * oneMb,
                totalStorageBytes = 64000 * oneMb,
                currentLocalMusicBytes = 500 * oneMb,
                candidateTracks = sampleTracks,
                serverCapabilities = setOf("SYNC_V1"), // No transcoding capability
                onStartSync = {},
                onBack = {},
            )
        }

        // Advance to Quality Step
        composeTestRule.onNodeWithTag("sync_wizard_next_button").performScrollTo().performClick()

        // Verify disabled badges and honest explanation (present on both Balanced and Low Storage cards)
        composeTestRule.onNodeWithText("Calidad Original").assertIsDisplayed()
        composeTestRule.onNodeWithText("Balanceado").assertExists()
        composeTestRule.onNodeWithText("Almacenamiento Reducido").assertExists()
        composeTestRule.onAllNodesWithText("El servidor no tiene transcodificación habilitada; solo se transferirá Calidad Original").assertCountEquals(2)
    }

    @Test
    fun g15_aircat_review_stepDisplaysStorageBreakdownAndExecutablePlan() {
        var dispatchedPlan: SyncPlan? = null

        composeTestRule.setContent {
            SyncWizardScreen(
                endpointId = "ep-test-1",
                endpointName = "Studio Desktop",
                availableStorageBytes = 5000 * oneMb,
                totalStorageBytes = 64000 * oneMb,
                currentLocalMusicBytes = 500 * oneMb,
                candidateTracks = sampleTracks,
                serverCapabilities = setOf("SYNC_V1"),
                onStartSync = { dispatchedPlan = it },
                onBack = {},
            )
        }

        // Navigate Step 1 -> Step 2 -> Step 3
        composeTestRule.onNodeWithTag("sync_wizard_next_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sync_wizard_quality_next_button").performScrollTo().performClick()

        // Verify Step 3: Preflight & Storage Review
        composeTestRule.onNodeWithText("Revisión y Preflight de Almacenamiento").assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_breakdown_bar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pistas a descargar:").assertExists()

        // Button should be executable
        val startButton = composeTestRule.onNodeWithTag("sync_wizard_start_button")
        startButton.performScrollTo().assertIsDisplayed()
        startButton.performClick()

        assertTrue(dispatchedPlan != null)
        assertTrue(dispatchedPlan!!.isExecutable)
        assertEquals(2, dispatchedPlan!!.tracksToDownloadCount)
        assertEquals(27 * oneMb, dispatchedPlan!!.totalDownloadBytes)
    }

    @Test
    fun g29_preflight_blocked_whenStorageInsufficient() {
        // Only 120 MB free:
        // Download is 27 MB + 100 MB safety reserve + 10 MB atomic buffer = 137 MB needed.
        // 120 MB < 137 MB -> must block!
        composeTestRule.setContent {
            SyncWizardScreen(
                endpointId = "ep-test-1",
                endpointName = "Studio Desktop",
                availableStorageBytes = 120 * oneMb,
                totalStorageBytes = 64000 * oneMb,
                currentLocalMusicBytes = 500 * oneMb,
                candidateTracks = sampleTracks,
                serverCapabilities = setOf("SYNC_V1"),
                onStartSync = {},
                onBack = {},
            )
        }

        // Navigate Step 1 -> Step 2 -> Step 3
        composeTestRule.onNodeWithTag("sync_wizard_next_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sync_wizard_quality_next_button").performScrollTo().performClick()

        // Verify blocking issue is shown
        composeTestRule.onNodeWithTag("sync_wizard_issue_blocking").performScrollTo().assertIsDisplayed()

        // Start button must be disabled
        composeTestRule.onNodeWithTag("sync_wizard_start_button_disabled")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun granularAlbumSelection_blocksWhenZeroSelected_andEnablesWhenAlbumChecked() {
        val mixedTracks = listOf(
            ManifestTrack(trackId = "trk-1", title = "Track A", artist = "Artist 1", album = "Rock Album", size = 10 * oneMb),
            ManifestTrack(trackId = "trk-2", title = "Track B", artist = "Artist 1", album = "Rock Album", size = 10 * oneMb),
            ManifestTrack(trackId = "trk-3", title = "Track C", artist = "Artist 2", album = "Jazz Album", size = 10 * oneMb),
        )

        composeTestRule.setContent {
            SyncWizardScreen(
                endpointId = "ep-test-1",
                endpointName = "Studio Desktop",
                availableStorageBytes = 5000 * oneMb,
                totalStorageBytes = 64000 * oneMb,
                currentLocalMusicBytes = 500 * oneMb,
                candidateTracks = mixedTracks,
                serverCapabilities = setOf("SYNC_V1"),
                onStartSync = {},
                onBack = {},
            )
        }

        // Switch to Albums scope
        composeTestRule.onNodeWithTag("sync_wizard_selection_albums").performScrollTo().performClick()

        // When newly selected, albums are empty -> next button should be disabled
        composeTestRule.onNodeWithText("Selecciona al menos 1 pista para continuar").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sync_wizard_next_button").performScrollTo().assertIsNotEnabled()

        // Click Rock Album checkbox
        composeTestRule.onNodeWithTag("sync_wizard_album_Rock_Album").performScrollTo().performClick()

        // Verify next button is now enabled
        composeTestRule.onNodeWithTag("sync_wizard_next_button").performScrollTo().assertIsEnabled()
    }

    @Test
    fun granularTracksSelection_selectAllAndClearAllWorkCorrectly() {
        composeTestRule.setContent {
            SyncWizardScreen(
                endpointId = "ep-test-1",
                endpointName = "Studio Desktop",
                availableStorageBytes = 5000 * oneMb,
                totalStorageBytes = 64000 * oneMb,
                currentLocalMusicBytes = 500 * oneMb,
                candidateTracks = sampleTracks,
                serverCapabilities = setOf("SYNC_V1"),
                onStartSync = {},
                onBack = {},
            )
        }

        // Switch to Individual Tracks scope
        composeTestRule.onNodeWithTag("sync_wizard_selection_tracks").performScrollTo().performClick()

        // Click Select All
        composeTestRule.onNodeWithTag("sync_wizard_select_all").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sync_wizard_next_button").performScrollTo().assertIsEnabled()

        // Click Clear All
        composeTestRule.onNodeWithTag("sync_wizard_clear_all").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sync_wizard_next_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun loadingState_displaysProgressIndicator() {
        composeTestRule.setContent {
            SyncWizardScreen(
                endpointId = "ep-test-1",
                endpointName = "Studio Desktop",
                availableStorageBytes = 5000 * oneMb,
                totalStorageBytes = 64000 * oneMb,
                currentLocalMusicBytes = 500 * oneMb,
                candidateTracks = emptyList(),
                isLoading = true,
                onStartSync = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag("sync_wizard_loading").assertIsDisplayed()
    }

    @Test
    fun errorState_displaysErrorMessageAndRetryButton() {
        var retried = false
        composeTestRule.setContent {
            SyncWizardScreen(
                endpointId = "ep-test-1",
                endpointName = "Studio Desktop",
                availableStorageBytes = 5000 * oneMb,
                totalStorageBytes = 64000 * oneMb,
                currentLocalMusicBytes = 500 * oneMb,
                candidateTracks = emptyList(),
                errorMessage = "Error al conectar con el servidor",
                onRetry = { retried = true },
                onStartSync = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag("sync_wizard_error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Error al conectar con el servidor").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sync_wizard_retry_button").performClick()
        assertTrue(retried)
    }
}
