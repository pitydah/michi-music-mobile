package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.screens.sync.ConnectionPrompt
import org.michimusic.mobile.ui.screens.sync.StorageBreakdownBar
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.AeroTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AMR-80 supplemental surface contracts.
 * These tests are SOURCE ONLY until executed through the repository Gradle toolchain.
 * No remote discovery, USB, AirCat network transfer or physical Android is required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AeroSupplementalMaterialFlowsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun discoveryPromptRetainsExplicitUserInitiatedAction() {
        var searches = 0
        compose.setContent {
            AeroTheme {
                ConnectionPrompt(onStart = { searches += 1 })
            }
        }
        compose.onNodeWithText("Conecta con Michi Music Player en tu red local").assertExists()
        compose.onNodeWithText("Buscar servidores").performClick()
        assertEquals(1, searches)
    }

    @Test fun storageInstrumentShowsDeclaredSegmentsNotACompletionClaim() {
        compose.setContent {
            AeroTheme {
                StorageBreakdownBar(
                    totalStorageBytes = 1_000_000_000L,
                    systemAndOtherBytes = 230_000_000L,
                    currentLocalMusicBytes = 200_000_000L,
                    newSyncBytes = 100_000_000L,
                )
            }
        }
        compose.onNodeWithTag("storage_breakdown_bar").assertExists()
        compose.onNodeWithText("Almacenamiento del Dispositivo").assertExists()
        compose.onNodeWithText("Nueva Descarga: ", substring = true).assertExists()
    }

    @Test fun floatingDialogShellDoesNotStealSelectionAction() {
        var selections = 0
        compose.setContent {
            AeroTheme {
                AeroSurface(
                    role = AeroSurfaceRole.Floating,
                    finish = AeroFinish.Smoked,
                    modifier = Modifier.testTag("dialog_shell"),
                ) {
                    Column {
                        AeroCard(
                            finish = AeroFinish.Plain,
                            modifier = Modifier.testTag("playlist_option"),
                            onClick = { selections += 1 },
                        ) { Text("Lista del usuario") }
                    }
                }
            }
        }
        compose.onNodeWithTag("dialog_shell").assertExists()
        compose.onNodeWithTag("playlist_option").assertHasClickAction().performClick()
        assertEquals(1, selections)
    }
}
