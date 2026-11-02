package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pairing dialog accepts the michi://pair URI that the node to be paired
 * (Michi Music Player or a server) displays. QrPairingParser rejects every other
 * shape, so the dialog must refuse them before the action is offered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QrScannerDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anEmptyFieldOffersNoAction() {
        compose.setContent {
            MichiTheme {
                QrScannerDialog(onScanSuccess = {}, onDismiss = {})
            }
        }

        compose.onNodeWithTag("pairing_link_confirm").assertIsNotEnabled()
    }

    @Test
    fun aShortTokenIsRejectedBeforeItReachesPairing() {
        compose.setContent {
            MichiTheme {
                QrScannerDialog(onScanSuccess = {}, onDismiss = {})
            }
        }

        compose.onNodeWithTag("pairing_link_field").performTextInput("MLINK-9281")

        compose.onNodeWithTag("pairing_link_confirm").assertIsNotEnabled()
        compose.onNodeWithText("El enlace de Michi Link empieza con michi://pair")
            .assertIsDisplayed()
    }

    @Test
    fun aPairingLinkIsForwardedToThePairingFlow() {
        var forwarded: String? = null
        var dismissed = false
        val link = "michi://pair?format=michi-link-pairing&version=1&session_id=abc"

        compose.setContent {
            MichiTheme {
                QrScannerDialog(
                    onScanSuccess = { forwarded = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithTag("pairing_link_field").performTextInput(link)
        compose.onNodeWithTag("pairing_link_confirm").performClick()

        assertEquals(link, forwarded)
        assertTrue(dismissed)
    }
}
