package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.AeroSeekerStyle

@RunWith(AndroidJUnit4::class)
class AeroSeekerSingleSilhouetteTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun waveformContinuousNeverDrawsMirroredLowerLobe() {
        rule.setContent {
            Box(Modifier.width(320.dp).height(44.dp).background(Color.Black)) {
                AeroSeekerTrack(
                    style = AeroSeekerStyle.WAVEFORM_CONTINUOUS,
                    progress = 0.5f,
                    playing = false,
                    reducedMotion = true,
                    dragging = false,
                    amplitudes = List(64) { 0.72f },
                    modifier = Modifier.fillMaxSize().testTag("single_silhouette_canvas"),
                )
            }
        }
        rule.waitForIdle()
        val image = rule.onNodeWithTag("single_silhouette_canvas").captureToImage().toPixelMap()
        val below = image[image.width / 4, (image.height * 0.83f).toInt()]
        assertTrue(
            "A second lobe must not extend below the scrubber's centre",
            below.red < 0.08f && below.green < 0.08f && below.blue < 0.08f,
        )
    }

    @Test
    fun waveformContinuousHasNoMirroredLobeOrCentralSeamAtAllProgressValues() {
        val progressState = mutableStateOf(0f)
        val amplitudes = List(64) { index -> 0.25f + 0.6f * ((index % 9) / 8f) }

        rule.setContent {
            Box(Modifier.width(320.dp).height(44.dp).background(Color.Black)) {
                AeroSeekerTrack(
                    style = AeroSeekerStyle.WAVEFORM_CONTINUOUS,
                    progress = progressState.value,
                    playing = false,
                    reducedMotion = true,
                    dragging = false,
                    amplitudes = amplitudes,
                    modifier = Modifier.fillMaxSize().testTag("single_silhouette_canvas"),
                )
            }
        }

        val xFractions = listOf(0.12f, 0.28f, 0.5f, 0.72f, 0.88f)
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            progressState.value = progress
            rule.waitForIdle()
            val image = rule.onNodeWithTag("single_silhouette_canvas")
                .captureToImage()
                .toPixelMap()

            for (fraction in xFractions) {
                val x = (image.width * fraction).toInt().coerceIn(0, image.width - 1)
                val y = (image.height * 0.82f).toInt().coerceIn(0, image.height - 1)
                val lower = image[x, y]
                assertTrue(
                    "Mirrored lower lobe at progress=$progress x=$fraction",
                    lower.red < 0.08f && lower.green < 0.08f && lower.blue < 0.08f,
                )
            }

            // The fill must reach the centre baseline, without a black seam
            // replacing the single silhouette. Sample inside the played region
            // when possible so the quiet-state alpha cannot mask the result.
            val seamX = (image.width * (0.1f + progress * 0.5f))
                .toInt()
                .coerceIn(0, image.width - 1)
            val seamY = (image.height * 0.49f).toInt().coerceIn(0, image.height - 1)
            val seam = image[seamX, seamY]
            assertTrue(
                "Black central seam at progress=$progress",
                seam.red + seam.green + seam.blue > 0.05f,
            )
        }
    }
}
