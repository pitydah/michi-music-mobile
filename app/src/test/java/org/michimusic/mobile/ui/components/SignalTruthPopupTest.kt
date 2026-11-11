package org.michimusic.mobile.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.Track
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SignalTruthPopupTest {
    @get:Rule val compose = createComposeRule()

    private val track = Track(
        id = "t1",
        title = "I Want to Know What Love Is",
        artist = "Foreigner",
        filepath = "/music/foreigner.flac",
    )

    @Test
    fun popupHasNoVisibleCloseButtonAndKeepsOneOutputCta() {
        compose.setContent {
            MichiTheme {
                SignalTruthMobileSheet(
                    observation = AudioSignalObservation(),
                    activeEndpoint = PlaybackEndpoint.LocalPhone,
                    currentTrack = track,
                    isRemote = false,
                    streamSelectedBySession = false,
                    onOpenEqualizer = {},
                    onOpenOutputRoutes = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Signal Truth").assertIsDisplayed()
        compose.onNodeWithText("Cerrar").assertDoesNotExist()
        compose.onNodeWithTag("signal_truth_change_output").assertIsDisplayed()
        compose.onNodeWithText("Cambiar salida").assertIsDisplayed()
    }

    @Test
    fun outsideScrimExposesDismissAction() {
        var dismissed = 0
        compose.setContent {
            MichiTheme {
                SignalTruthMobileSheet(
                    observation = AudioSignalObservation(),
                    activeEndpoint = PlaybackEndpoint.LocalPhone,
                    currentTrack = track,
                    isRemote = false,
                    streamSelectedBySession = false,
                    onOpenEqualizer = {},
                    onOpenOutputRoutes = {},
                    onDismiss = { dismissed++ },
                )
            }
        }

        compose.onNodeWithTag("signal_truth_dismiss_scrim")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, dismissed)
    }

    @Test
    fun unobservedRouteNeverBorrowsARequestedDeviceLabel() {
        compose.setContent {
            MichiTheme {
                SignalTruthMobileSheet(
                    observation = AudioSignalObservation(),
                    activeEndpoint = PlaybackEndpoint.LocalPhone,
                    currentTrack = track,
                    isRemote = false,
                    streamSelectedBySession = false,
                    onOpenEqualizer = {},
                    onOpenOutputRoutes = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Salida Android sin identificar").assertIsDisplayed()
        compose.onNodeWithText("Pendiente de confirmar; la salida física no está verificada.")
            .assertIsDisplayed()
    }
}
