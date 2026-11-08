package org.michimusic.mobile.ui.motion

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.michimusic.mobile.ui.theme.AeroTheme
import org.michimusic.mobile.ui.theme.VisualPreferences
import androidx.compose.material3.Text

/** Run on emulator by OpenCode, never as a Drive-side Android test. */
class AeroMotionComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sourceTextChangesWithoutInventingConnectionState() {
        val observed = mutableStateOf("Sin conexión")
        rule.setContent {
            AeroTheme(VisualPreferences(reducedMotion = true)) {
                AeroObservedText(value = observed.value, color = Color.White, style = TextStyle.Default)
            }
        }
        rule.onNodeWithText("Sin conexión").assertExists()
        rule.runOnIdle { observed.value = "Controlando Michi Desktop" }
        rule.onNodeWithText("Controlando Michi Desktop").assertExists()
    }

    @Test fun pendingBannerDisappearsWhenAuthorityClearsIt() {
        val pending = mutableStateOf(true)
        rule.setContent {
            AeroTheme(VisualPreferences(reducedMotion = true)) {
                AeroPendingReveal(visible = pending.value) { Text("Orden pendiente") }
            }
        }
        rule.onNodeWithText("Orden pendiente").assertExists()
        rule.runOnIdle { pending.value = false }
        rule.onNodeWithText("Orden pendiente").assertDoesNotExist()
    }

    @Test fun dynamicMeshFallsBackWithoutAnArtworkOrPlaybackSession() {
        // The canvas must mount without a local track or bitmap decoder; drawing
        // the canonical Aero fallback must never require playback services.
        val preferences = mutableStateOf(VisualPreferences(reducedMotion = true))
        rule.setContent {
            AeroTheme(preferences.value) {
                AeroDynamicMeshBackground(
                    track = null,
                    playing = false,
                    modifier = Modifier.width(180.dp).height(280.dp),
                )
            }
        }
        rule.onNodeWithTag("aero_dynamic_mesh_background").assertExists()
        rule.runOnIdle {
            preferences.value = VisualPreferences(reducedMotion = true, highContrast = true)
        }
        rule.onNodeWithTag("aero_dynamic_mesh_background").assertExists()
    }

    @Test fun unknownProgressRemainsIndeterminateInHighContrast() {
        rule.setContent {
            AeroTheme(VisualPreferences(highContrast = true)) {
                AeroTruthfulProgress(
                    fraction = null,
                    color = Color.Cyan,
                    trackColor = Color.DarkGray,
                    modifier = Modifier.width(140.dp).height(8.dp).testTag("hc_unknown_progress"),
                )
            }
        }
        rule.onNodeWithTag("hc_unknown_progress").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
    }

    @Test fun unknownProgressRemainsIndeterminateWithReducedMotion() {
        rule.setContent {
            AeroTheme(VisualPreferences(reducedMotion = true)) {
                AeroTruthfulProgress(
                    fraction = null,
                    color = Color.Cyan,
                    trackColor = Color.DarkGray,
                    modifier = Modifier.width(140.dp).height(8.dp).testTag("unknown_progress"),
                )
            }
        }
        rule.onNodeWithTag("unknown_progress").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
    }
}
