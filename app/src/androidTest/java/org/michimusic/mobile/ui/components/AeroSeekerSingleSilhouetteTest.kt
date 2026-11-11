package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.AeroSeekerStyle

/** Pixel-level regression for the former double-lobe centre split. */
@RunWith(AndroidJUnit4::class)
class AeroSeekerSingleSilhouetteTest {
    @get:Rule val rule = createComposeRule()

    @Test fun waveformContinuousNeverDrawsMirroredLowerLobe() {
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
        assertTrue("A second lobe must not extend below the scrubber's centre",
            below.red < 0.08f && below.green < 0.08f && below.blue < 0.08f)
    }
}
