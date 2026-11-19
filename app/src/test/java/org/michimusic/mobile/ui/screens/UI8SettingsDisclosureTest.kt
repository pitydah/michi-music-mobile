package org.michimusic.mobile.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Dense settings keep explanations reachable but not mandatory for basic use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UI8SettingsDisclosureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun helperStartsCollapsedAndRevealsDetailsOnlyOnRequest() {
        compose.setContent {
            SettingsDisclosureNote(
                title = "Más información",
                detail = "Detalle completo y verificable",
                testTag = "ui8_help",
            )
        }
        compose.onNodeWithTag("ui8_help").assertExists().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("ui8_help_detail").assertDoesNotExist()
        compose.onNodeWithTag("ui8_help").performClick()
        compose.onNodeWithTag("ui8_help_detail").assertExists()
        compose.onNodeWithTag("ui8_help").performClick()
        compose.onNodeWithTag("ui8_help_detail").assertDoesNotExist()
    }
}
