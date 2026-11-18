package org.michimusic.mobile.ui.screens.sync

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.devices.aircat.AirCatProgressEvent
import org.michimusic.mobile.devices.aircat.AirCatTransferState
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp", application = android.app.Application::class)
class AirCatSafeLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun airCatSafeLayout_activeTransfer_displays4StageStepperAndCheckpointNotice() {
        var pauseClicked = false
        var cancelClicked = false

        val activeProgress = AirCatProgressEvent(
            transferId = "xfer_01",
            currentTrackIndex = 2,
            totalTracks = 5,
            bytesTransferred = 25_000_000L,
            totalBytes = 100_000_000L,
            state = AirCatTransferState.TRANSFERRING,
            activeTrackTitle = "Quantum Solitude.flac",
        )

        composeTestRule.setContent {
            MichiTheme {
                AirCatTransferSafeLayout(
                    progress = activeProgress,
                    onPause = { pauseClicked = true },
                    onCancel = { cancelClicked = true },
                )
            }
        }

        // Stepper with 4 stages displayed
        composeTestRule.onNodeWithText("Preparación").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transferencia").assertIsDisplayed()
        composeTestRule.onNodeWithText("Verificando").assertIsDisplayed()
        composeTestRule.onNodeWithText("Completado").assertIsDisplayed()

        // Active track and checkpoint notice
        composeTestRule.onNodeWithText("Quantum Solitude.flac").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 de 5 pistas").assertIsDisplayed()
        composeTestRule.onNodeWithText("Checkpoint activo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transferencia atómica: si se interrumpe, se conservan los archivos validados.").assertIsDisplayed()

        // Controls with WCAG >= 48x48 dp
        composeTestRule.onNodeWithTag("aircat_pause_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(pauseClicked)

        composeTestRule.onNodeWithTag("aircat_cancel_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(cancelClicked)
    }

    @Test
    fun airCatSafeLayout_errorState_displaysRetainedFilesAndRetryCTA() {
        var retryClicked = false
        var dismissClicked = false

        val errorProgress = AirCatProgressEvent(
            transferId = "xfer_02",
            currentTrackIndex = 3,
            totalTracks = 8,
            bytesTransferred = 45_000_000L,
            totalBytes = 120_000_000L,
            state = AirCatTransferState.FAILED,
            activeTrackTitle = "Solar Winds.flac",
            error = "Pérdida de conexión Wi-Fi con el servidor",
        )

        composeTestRule.setContent {
            MichiTheme {
                AirCatTransferSafeLayout(
                    progress = errorProgress,
                    onRetry = { retryClicked = true },
                    onDismiss = { dismissClicked = true },
                )
            }
        }

        // Error title and message
        composeTestRule.onNodeWithText("Error de transferencia").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pérdida de conexión Wi-Fi con el servidor").assertIsDisplayed()

        // Retained validated files badge (V13_04_AIRCAT_ERROR)
        composeTestRule.onNodeWithText("Se conservaron los archivos validados").assertIsDisplayed()
        composeTestRule.onNodeWithText("Los fragmentos descargados y las pistas verificadas permanecen guardados intactos.").assertIsDisplayed()

        // Action CTAs
        composeTestRule.onNodeWithTag("aircat_retry_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(retryClicked)

        composeTestRule.onNodeWithTag("aircat_dismiss_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(dismissClicked)
    }

    @Test
    fun airCatSafeLayout_completedState_displaysSuccessAndAcceptCTA() {
        var dismissClicked = false

        val completedProgress = AirCatProgressEvent(
            transferId = "xfer_03",
            currentTrackIndex = 10,
            totalTracks = 10,
            bytesTransferred = 150_000_000L,
            totalBytes = 150_000_000L,
            state = AirCatTransferState.COMPLETED,
            activeTrackTitle = "Finale.flac",
            checksumVerified = true,
            libraryCommitted = true,
        )

        composeTestRule.setContent {
            MichiTheme {
                AirCatTransferSafeLayout(
                    progress = completedProgress,
                    onDismiss = { dismissClicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Transferencia completada").assertIsDisplayed()
        composeTestRule.onNodeWithText("Todos los archivos pasaron la verificación SHA-256 e ingresaron a la biblioteca.").assertIsDisplayed()

        composeTestRule.onNodeWithTag("aircat_complete_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(dismissClicked)
    }

    @Test
    fun airCatSafeLayout_completedState_unverifiedChecksum_doesNotClaimVerification() {
        val unverifiedProgress = AirCatProgressEvent(
            transferId = "xfer_04",
            currentTrackIndex = 5,
            totalTracks = 5,
            bytesTransferred = 50_000_000L,
            totalBytes = 50_000_000L,
            state = AirCatTransferState.COMPLETED,
            activeTrackTitle = "Unverified.flac",
            checksumVerified = false,
            libraryCommitted = false,
        )

        composeTestRule.setContent {
            MichiTheme {
                AirCatTransferSafeLayout(
                    progress = unverifiedProgress,
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Transferencia completada").assertIsDisplayed()
        // K47-05: Must show pending verification message, NOT fake "Todos los archivos pasaron la verificación SHA-256"
        composeTestRule.onNodeWithText("Archivos transferidos; verificación de integridad SHA-256 pendiente.").assertIsDisplayed()
    }

    // --- A69-03: an empty selection is finished, but it is not a completed transfer ---

    @Test
    fun noFilesReceiptNeverClaimsAVerifiedTransfer() {
        val noFilesProgress = AirCatProgressEvent(
            transferId = "xfer_empty",
            currentTrackIndex = 0,
            totalTracks = 0,
            bytesTransferred = 0L,
            totalBytes = 0L,
            state = AirCatTransferState.NO_FILES,
        )

        composeTestRule.setContent {
            MichiTheme {
                AirCatTransferSafeLayout(progress = noFilesProgress, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithTag("aircat_no_files_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("aircat_no_files_description")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("La selección no tenía pistas que transferir.")
            .assertIsDisplayed()

        // No stage of a transfer happened, so no stage may be drawn.
        listOf("Preparación", "Transferencia", "Verificando", "Completado").forEach { stage ->
            assertTrue(
                "a receipt that transferred nothing must not draw the '$stage' stage",
                composeTestRule.onAllNodesWithText(stage).fetchSemanticsNodes().isEmpty(),
            )
        }
        // And it must not borrow the completed wording either.
        assertTrue(
            "an empty receipt must not claim a completed transfer",
            composeTestRule.onAllNodesWithText("Transferencia completada").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "an empty receipt must not report synced tracks",
            composeTestRule.onAllNodesWithText("0 pistas sincronizadas · 0.0 MB").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun noFilesReceiptCanBeDismissed() {
        var dismissed = false
        val noFilesProgress = AirCatProgressEvent(
            transferId = "xfer_empty_dismiss",
            currentTrackIndex = 0,
            totalTracks = 0,
            bytesTransferred = 0L,
            totalBytes = 0L,
            state = AirCatTransferState.NO_FILES,
        )

        composeTestRule.setContent {
            MichiTheme {
                AirCatTransferSafeLayout(
                    progress = noFilesProgress,
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("aircat_no_files_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue("the only action of a nothing-to-transfer receipt must work", dismissed)
    }

    @Test
    fun aCompletedTransferKeepsItsStepperAndItsOwnLayout() {
        val completedProgress = AirCatProgressEvent(
            transferId = "xfer_done",
            currentTrackIndex = 2,
            totalTracks = 2,
            bytesTransferred = 10_000_000L,
            totalBytes = 10_000_000L,
            state = AirCatTransferState.COMPLETED,
        )

        composeTestRule.setContent {
            MichiTheme {
                AirCatTransferSafeLayout(progress = completedProgress, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("Completado").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transferencia completada").assertIsDisplayed()
        composeTestRule.onNodeWithTag("aircat_no_files_view").assertDoesNotExist()
    }

    @Test
    fun aFailedTransferIsStillAnErrorAndNotAnEmptyReceipt() {
        val failedProgress = AirCatProgressEvent(
            transferId = "xfer_failed_not_empty",
            currentTrackIndex = 0,
            totalTracks = 3,
            bytesTransferred = 0L,
            totalBytes = 30_000_000L,
            state = AirCatTransferState.FAILED,
            error = "Pérdida de conexión Wi-Fi con el servidor",
        )

        composeTestRule.setContent {
            MichiTheme {
                AirCatTransferSafeLayout(progress = failedProgress, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("Error de transferencia").assertIsDisplayed()
        composeTestRule.onNodeWithTag("aircat_no_files_view").assertDoesNotExist()
    }
}
