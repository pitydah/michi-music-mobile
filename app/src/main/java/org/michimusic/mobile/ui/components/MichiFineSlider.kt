package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MichiFineSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    accentColor: Color = TertiaryCyan,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp), // 48dp gesture target, 3dp visual track below
        colors = SliderDefaults.colors(
            thumbColor = accentColor,
            activeTrackColor = accentColor,
            inactiveTrackColor = GlassFillHigh,
            disabledThumbColor = TextMuted,
            disabledActiveTrackColor = TextMuted.copy(alpha = 0.3f),
            disabledInactiveTrackColor = GlassFillLow,
        ),
        thumb = {
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(if (enabled) PureWhite else TextMuted)
                    .border(2.dp, if (enabled) accentColor else TextMuted, CircleShape),
            )
        },
        track = { sliderState ->
            // A 4dp recessed channel, still using Material3's real interactive slider.
            // Only the paint changes; its gesture target, value range and callbacks do not.
            Box(
                modifier = Modifier.fillMaxWidth().height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF121923))
                    .border(0.5.dp, Color.White.copy(alpha = 0.11f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = accentColor,
                        inactiveTrackColor = GlassFillHigh,
                        disabledActiveTrackColor = TextMuted.copy(alpha = 0.3f),
                        disabledInactiveTrackColor = GlassFillLow,
                    ),
                    drawStopIndicator = null,
                )
            }
        },
    )
}
