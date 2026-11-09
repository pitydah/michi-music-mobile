package org.michimusic.mobile.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion

/**
 * For observed endpoint, route and remote state strings. Animate only a change
 * supplied by the existing authority; never infer a connection or quality tier.
 */
@Composable
fun AeroObservedText(
    value: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    textAlign: TextAlign = TextAlign.Center,
) {
    val prefs = LocalVisualPreferences.current
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        contentAlignment = Alignment.Center,
        transitionSpec = {
            if (prefs.reducedMotion || prefs.highContrast) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(MichiMotion.durationStandard)) togetherWith
                    fadeOut(tween(MichiMotion.durationFast))
            }
        },
        label = "aero_observed_status_change",
    ) { observed ->
        Text(
            text = observed,
            color = color,
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
