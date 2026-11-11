package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

@RunWith(AndroidJUnit4::class)
class SettingsHubComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun audioSectionsNavigateWithoutAddingTabsToAppDock() {
        var selected: SettingsSection? = null
        rule.setContent {
            MichiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsHubIndex("Pista", "Equilibrado", "Historial desactivado",
                        "Sincronización desactivada") { selected = it }
                }
            }
        }
        rule.onNodeWithTag("settings_route_processing").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(SettingsSection.PROCESSING, selected) }
    }

    @Test fun localSearchFiltersDestinationsWithoutHidingFinalDonation() {
        rule.setContent {
            MichiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsHubIndex("Pista", "Equilibrado", "Activado", "Desactivada") { }
                }
            }
        }
        rule.onNodeWithTag("settings_search_input").performTextInput("PEQ")
        rule.onNodeWithTag("settings_route_processing").assertExists()
        rule.onNodeWithTag("settings_route_playback").assertDoesNotExist()
        rule.onNodeWithTag("settings_donate_card").assertExists()
        rule.onNodeWithTag("settings_search_clear").performClick()
        rule.onNodeWithTag("settings_route_playback").assertExists()
    }

    @Test fun indexKeepsAboutAndDonationAsIndependentActions() {
        rule.setContent {
            MichiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsHubIndex("Pista", "Equilibrado", "Activado", "Desactivada") { }
                }
            }
        }
        rule.onNodeWithTag("settings_route_about").assertExists()
        rule.onNodeWithTag("settings_donate_card").assertExists()
    }
}
