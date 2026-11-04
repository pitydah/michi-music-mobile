package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.screens.settings.SettingsRadioGroup
import org.michimusic.mobile.ui.screens.settings.SettingsToggle
import org.michimusic.mobile.ui.theme.AeroTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AMR-08: smallest possible UI contracts for the canonical settings cards and
 * PEQ graph. These tests must still be run with the project's Gradle/Robolectric
 * toolchain; adding the source does not constitute a completed test run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AeroSecondaryMaterialCoverageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsToggleCallsBusinessCallbackOnce() {
        var calls = 0
        compose.setContent {
            AeroTheme {
                SettingsToggle(
                    title = "Descargar portadas",
                    description = "Solo por Wi-Fi",
                    checked = false,
                    onCheckedChange = { calls++ },
                )
            }
        }
        compose.onNodeWithText("Descargar portadas").assertExists()
        compose.onNode(hasClickAction()).performClick()
        assertEquals(1, calls)
    }

    @Test fun radioChoicePreservesOriginalSelectionCallback() {
        var selected = "a"
        compose.setContent {
            AeroTheme {
                SettingsRadioGroup(
                    title = "Modo de procesamiento",
                    options = listOf("a" to "Automático", "b" to "Sin efectos"),
                    selectedValue = "a",
                    onSelect = { selected = it },
                )
            }
        }
        compose.onNodeWithText("Sin efectos").performClick()
        assertEquals("b", selected)
    }

    @Test fun theoreticalPeqCurveHasAccessibleChartAndDisclaimer() {
        compose.setContent {
            AeroTheme {
                PeqResponseCurve(bands = emptyList(), referenceSampleRate = 48_000)
            }
        }
        compose.onNodeWithTag("peq_calculated_curve").assertExists()
        compose.onNodeWithText("Respuesta calculada · filtros PEQ guardados").assertExists()
    }
}
