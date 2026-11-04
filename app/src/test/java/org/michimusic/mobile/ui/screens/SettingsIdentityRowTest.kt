package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.link.identity.IdentityState
import org.michimusic.link.identity.MichiIdentity
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A failed identity resolution used to be terminal until the app restarted, so the
 * row that surfaces it has to expose the retry action and nothing more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SettingsIdentityRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun readyStateDescribesTheIdentityAndOffersNoRetry() {
        compose.setContent {
            MichiTheme {
                IdentityStatusRow(
                    state = IdentityState.Ready(mockk<MichiIdentity>(relaxed = true)),
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithText("Identidad lista").assertIsDisplayed()
        compose.onNodeWithText("El dispositivo puede emparejarse y firmar comandos.").assertIsDisplayed()
        compose.onNodeWithTag("settings_identity_retry").assertDoesNotExist()
    }

    @Test
    fun failedStateShowsTheReasonAndOffersRetry() {
        var retried = 0
        compose.setContent {
            MichiTheme {
                IdentityStatusRow(
                    state = IdentityState.Failed("Keystore no disponible"),
                    onRetry = { retried++ },
                )
            }
        }

        compose.onNodeWithText("Identidad no disponible").assertIsDisplayed()
        compose.onNodeWithText("Keystore no disponible").assertIsDisplayed()
        compose.onNodeWithTag("settings_identity_retry").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun loadingStateHasNoRetryAction() {
        compose.setContent {
            MichiTheme {
                IdentityStatusRow(state = IdentityState.Loading, onRetry = {})
            }
        }

        compose.onNodeWithText("Cargando identidad…").assertIsDisplayed()
        compose.onNodeWithTag("settings_identity_retry").assertDoesNotExist()
    }
}
