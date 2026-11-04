package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.AeroTheme
import org.michimusic.mobile.ui.theme.VisualPreferences
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AMR-81/82: source tests to run with Robolectric, no full-app DI or network.
 * Proves ownership of input callbacks and semantic error/disabled branches.
 * A passing parser alone must NOT be represented as a passing UI test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AeroDialogMaterialAndFormsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedInsetFieldPreservesUserInputUnderHighContrast() {
        var input by mutableStateOf("")
        compose.setContent {
            AeroTheme(visualPreferences = VisualPreferences(highContrast = true)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    shape = AeroDialogFieldShape,
                    colors = aeroDialogFieldColors(),
                    modifier = Modifier.testTag("shared_field"),
                )
            }
        }
        compose.onNodeWithTag("shared_field").performTextInput("Michi")
        compose.runOnIdle { assertEquals("Michi", input) }
    }

    @Test fun createPlaylistOnlySubmitsTrimmedNonEmptyName() {
        var created: String? = null
        compose.setContent {
            AeroTheme {
                CreatePlaylistDialog(onCreate = { created = it }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("create_playlist_confirm_button").assertIsNotEnabled()
        compose.onNodeWithTag("create_playlist_name_field").performTextInput("  Mi música  ")
        compose.onNodeWithTag("create_playlist_confirm_button").performClick()
        compose.runOnIdle { assertEquals("Mi música", created) }
    }

    @Test fun manualConnectionRejectsInvalidEndpointWithoutCallback() {
        var connections = 0
        compose.setContent {
            AeroTheme {
                ManualConnectionDialog(
                    onConnect = { _, _ -> connections++ }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("manual_connection_endpoint_field").performTextInput("not an endpoint ?")
        compose.onNodeWithTag("manual_connection_confirm_button").performClick()
        compose.onNodeWithTag("manual_connection_error").assertExists()
        compose.runOnIdle { assertEquals(0, connections) }
    }

    @Test fun renamePlaylistDisablesConfirmWhileSaving() {
        var renames = 0
        compose.setContent {
            AeroTheme {
                RenamePlaylistDialog(
                    currentName = "Colección",
                    isSaving = true,
                    onConfirm = { renames++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("rename_playlist_dialog_confirm_button").assertIsNotEnabled()
        compose.onNodeWithTag("rename_playlist_dialog_cancel_button").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, renames) }
    }

    @Test fun deletePlaylistDisablesConfirmWhileDeleting() {
        var deletes = 0
        compose.setContent {
            AeroTheme {
                DeletePlaylistDialog(isDeleting = true, onConfirm = { deletes++ }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("delete_playlist_dialog_confirm_button").assertIsNotEnabled()
        compose.onNodeWithTag("delete_playlist_dialog_cancel_button").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, deletes) }
    }

    @Test fun playerPasswordRequiresSecretAndPreservesCredentialCallback() {
        var credentials: Pair<String, String>? = null
        var dismisses = 0
        compose.setContent {
            AeroTheme {
                PlayerPasswordPairingDialog(
                    deviceName = "Michi Server",
                    onConfirm = { username, password -> credentials = username to password },
                    onDismiss = { dismisses++ },
                )
            }
        }
        compose.onNodeWithTag("confirm_player_password_button").performClick()
        compose.runOnIdle { assertEquals(null, credentials) }
        compose.onNodeWithTag("player_username_input_field").performTextInput("  cat  ")
        compose.onNodeWithTag("player_password_input_field").performTextInput("whisker123")
        compose.onNodeWithTag("confirm_player_password_button").performClick()
        compose.runOnIdle {
            assertEquals("cat" to "whisker123", credentials)
            assertEquals(1, dismisses)
        }
    }
}
