package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
}
