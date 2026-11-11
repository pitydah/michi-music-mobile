package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

@RunWith(AndroidJUnit4::class)
class SignalTruthVisualPanelComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun twoLanesStayVisuallySeparatedAndSettingsRequireOwnerApproval() {
        rule.setContent {
            MichiTheme {
                SignalTruthVisualPanel(
                    summary = SignalTruthVisualSummary(
                        destination = "Salida solicitada: Este teléfono",
                        certainty = "La salida física no está verificada",
                    ),
                    lanes = listOf(
                        SignalTruthVisualLane("Ruta del emisor", listOf(
                            SignalTruthUiStep("source", "Archivo", "MP3", "Declarado",
                                SignalTruthUiTone.CONFIGURED),
                        )),
                        SignalTruthVisualLane("Monitor local", listOf(
                            SignalTruthUiStep("monitor", "AudioTrack", "Sin observación",
                                "Desconocido", SignalTruthUiTone.UNKNOWN),
                        )),
                    ),
                    onOpenTechnical = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithTag("signal_truth_visual_summary").assertExists()
        rule.onNodeWithText("Ruta del emisor").assertExists()
        rule.onNodeWithText("Monitor local").assertExists()
        rule.onNodeWithTag("signal_truth_visual_lane_0").assertExists()
        rule.onNodeWithTag("signal_truth_visual_lane_1").assertExists()
        rule.onNodeWithTag("signal_truth_visual_settings").assertIsNotEnabled()
    }
}
