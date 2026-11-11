package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

@RunWith(AndroidJUnit4::class)
class SignalTruthUiTrailComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renderIsTextualAndSettingsRemainLockedUntilOwnerAuthorizesThem() {
        var details = 0
        var settings = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 2f)) {
                MichiTheme {
                    Column {
                        SignalTruthUiTrail(listOf(
                            SignalTruthUiStep("a", "Archivo", "Datos no verificados",
                                "Pendiente", SignalTruthUiTone.UNKNOWN),
                            SignalTruthUiStep("b", "Ruta Android", "Recepción aún no probada",
                                "Sin confirmar", SignalTruthUiTone.UNKNOWN, connectedToPrevious = false),
                        ))
                        SignalTruthUiDisclosureActions(
                            onOpenTechnical = { details++ },
                            onOpenSettings = { settings++ },
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("signal_truth_visual_trail").assertExists()
        rule.onNodeWithTag("signal_truth_ui_stage_0").assertExists()
        rule.onNodeWithTag("signal_truth_ui_stage_1").assertExists()
        rule.onNodeWithTag("signal_truth_visual_details").assertIsEnabled().performClick()
        rule.onNodeWithTag("signal_truth_visual_settings").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, details); assertEquals(0, settings) }
    }

    @Test fun functionalOwnerCanExplicitlyEnableSettingsAction() {
        var settings = 0
        rule.setContent {
            MichiTheme {
                SignalTruthUiDisclosureActions(
                    onOpenTechnical = {},
                    onOpenSettings = { settings++ },
                    settingsAvailable = true,
                )
            }
        }
        rule.onNodeWithTag("signal_truth_visual_settings").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, settings) }
    }
}
