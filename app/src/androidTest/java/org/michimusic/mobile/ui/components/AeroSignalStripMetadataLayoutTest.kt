package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

@RunWith(AndroidJUnit4::class)
class AeroSignalStripMetadataLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun commonSourceQualityLabelDoesNotLoseBitrate() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                MichiTheme {
                    Box(Modifier.width(336.dp)) {
                        AeroSignalRouteStrip(
                            signalLabel = "MP3 · 44,1 kHz · 320 kb/s",
                            signalDescription = "PCM interno no observado; salida física sin verificar.",
                            destination = "Este teléfono",
                            onSignalClick = {}, onDestinationClick = {},
                        )
                    }
                }
            }
        }
        val node = rule.onNodeWithTag("signal_strip_source_value", useUnmergedTree = true).assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        val semanticAction = node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
        assertTrue("Must expose a text layout result", semanticAction.action?.invoke(layouts) == true)
        val result = layouts.single()
        assertTrue("Bitrate text should fit in the allocated lines", result.lineCount <= 3)
        for (index in 0 until result.lineCount) {
            assertFalse("Unexpected ellipsis on line $index", result.isLineEllipsized(index))
        }
    }

    @Test
    fun commonSourceQualityLabelFitsAcrossResponsiveWidthsAndFontScales() {
        val width = mutableStateOf(320)
        val fontScale = mutableStateOf(1f)

        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = fontScale.value)) {
                MichiTheme {
                    Box(Modifier.width(width.value.dp)) {
                        AeroSignalRouteStrip(
                            signalLabel = "MP3 · 44,1 kHz · 320 kb/s",
                            signalDescription = "PCM interno no observado; salida física sin verificar.",
                            destination = "Este teléfono",
                            onSignalClick = {}, onDestinationClick = {},
                        )
                    }
                }
            }
        }

        for (screenWidth in listOf(320, 360, 393, 412, 600)) {
            for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
                width.value = screenWidth
                fontScale.value = scale
                rule.waitForIdle()

                val node = rule.onNodeWithTag("signal_strip_source_value", useUnmergedTree = true)
                    .assertExists()
                val layouts = mutableListOf<TextLayoutResult>()
                val action = node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
                assertTrue(
                    "Missing text layout at ${screenWidth}dp/${scale}x",
                    action.action?.invoke(layouts) == true,
                )
                val result = layouts.single()
                assertTrue(
                    "Bitrate text must fit at ${screenWidth}dp/${scale}x (lines=${result.lineCount})",
                    result.lineCount <= 4,
                )
                for (index in 0 until result.lineCount) {
                    assertFalse(
                        "Unexpected ellipsis at ${screenWidth}dp/${scale}x line $index",
                        result.isLineEllipsized(index),
                    )
                }

                rule.onNodeWithTag("signal_truth_button")
                    .assertHeightIsAtLeast(48.dp)
                    .assertWidthIsAtLeast(48.dp)
                rule.onNodeWithTag("audio_route_button")
                    .assertHeightIsAtLeast(48.dp)
                    .assertWidthIsAtLeast(48.dp)
            }
        }
    }
}
