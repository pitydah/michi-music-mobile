package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.AeroSeekerTrack
import org.michimusic.mobile.ui.theme.AeroSeekerStyle
import org.michimusic.mobile.ui.theme.MichiAuroraCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/** A tiny demonstrator, never a measurement or another seek controller.
 * Waveform amplitudes are a fixed synthetic drawing sample; no audio decoding,
 * audio state, sensor, timeline, timer, animation, I/O or extra gesture target.
 * The Now Playing renderer remains the only interactive owner of progress.
 */

@Composable
internal fun SettingsSeekerPreview(style: AeroSeekerStyle) {
    val demonstration = remember { seekerPreviewSamples() }
    Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp)
        .testTag("settings_seeker_demo_${style.name.lowercase()}"),
        contentAlignment = Alignment.Center) {
        if (style == AeroSeekerStyle.ORIGINAL) {
            // The actual Original uses a legacy seeker. This line is only a static
            // silhouette, not an embedded second copy of that control.
            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                val y = size.height * .5f
                drawLine(TextSecondary, Offset(0f,y), Offset(size.width,y),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(MichiAuroraCyan, Offset(0f,y), Offset(size.width*.38f,y),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(MichiAuroraCyan, radius = 4.dp.toPx(),
                    center = Offset(size.width*.38f,y))
            }
        } else {
            AeroSeekerTrack(
                style = style, progress = .38f, playing = false,
                reducedMotion = true, dragging = false,
                amplitudes = if (seekerPreviewUsesExample(style)) demonstration else null,
                modifier = Modifier.fillMaxWidth().height(32.dp),
            )
        }
    }
}
