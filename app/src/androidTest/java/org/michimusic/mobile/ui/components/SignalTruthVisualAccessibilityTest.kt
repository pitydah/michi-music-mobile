package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

/** UI-only acceptance; never supplies or modifies functional audio evidence. */
@RunWith(AndroidJUnit4::class)
class SignalTruthVisualAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun narrowTwoHundredPercentFontKeepsMissingEvidenceLegible() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 2f)) {
                MichiTheme {
                    Box(Modifier.width(320.dp)) {
                        SignalTruthVisualPanel(
                            summary = SignalTruthVisualSummary("", ""),
                            lanes = emptyList(),
                            onOpenTechnical = {},
                            onOpenSettings = { error("Unavailable settings must never activate") },
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("signal_truth_visual_route_heading").assertExists()
        rule.onNodeWithText("Destino no informado").assertExists()
        rule.onNodeWithText("Sin información suficiente sobre la salida").assertExists()
        rule.onNodeWithTag("signal_truth_visual_no_route").assertExists()
        rule.onNodeWithTag("signal_truth_visual_settings").assertIsNotEnabled()
    }
}
