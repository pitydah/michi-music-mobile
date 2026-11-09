package org.michimusic.mobile.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.AeroSeekerStyle
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI4+UI5 host contract: exactly one seek semantics node for every visual style. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AeroSeekerCompositionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wavyKeepsOneTouchTargetAndOneSeekCallback() {
        var selected = -1f
        compose.setContent {
            MichiTheme {
                GradientProgressBar(currentValue = 25f, maxValue = 100f,
                    onSeek = { selected = it }, aeroStyle = AeroSeekerStyle.WAVY,
                    testTag = "now_playing_seeker")
            }
        }
        compose.onNodeWithTag("now_playing_seeker").assertExists().performTouchInput { click() }
        assertEquals(1, compose.onAllNodesWithTag("now_playing_seeker").fetchSemanticsNodes().size)
        assertEquals(50f, selected, 4f)
    }

    @Test fun waveformWithoutSamplesExplainsFallbackInSameHost() {
        compose.setContent {
            MichiTheme {
                GradientProgressBar(currentValue = 50f, maxValue = 100f,
                    onSeek = {}, aeroStyle = AeroSeekerStyle.WAVEFORM_CONTINUOUS,
                    waveformAmplitudes = null, testTag = "now_playing_seeker")
            }
        }
        compose.onNodeWithTag("now_playing_seeker")
            .assertContentDescriptionEquals("Forma de onda no disponible; barra de progreso original")
        assertEquals(1, compose.onAllNodesWithTag("now_playing_seeker").fetchSemanticsNodes().size)
    }

    @Test fun everyVisualStyleKeepsExactlyOneSemanticSeekControl() {
        var style by mutableStateOf(AeroSeekerStyle.ORIGINAL)
        var selected = -1f
        compose.setContent {
            MichiTheme {
                GradientProgressBar(currentValue = 25f, maxValue = 100f,
                    onSeek = { selected = it }, aeroStyle = style,
                    waveformAmplitudes = List(64) { 0.5f },
                    testTag = "now_playing_seeker")
            }
        }
        AeroSeekerStyle.entries.forEach { requested ->
            compose.runOnIdle {
                style = requested
                selected = -1f
            }
            compose.waitForIdle()
            compose.onNodeWithTag("now_playing_seeker").assertExists()
                .performTouchInput { click() }
            assertEquals("One host for $requested", 1,
                compose.onAllNodesWithTag("now_playing_seeker").fetchSemanticsNodes().size)
            assertEquals("Gesture kept for $requested", 50f, selected, 4f)
        }
    }
}
