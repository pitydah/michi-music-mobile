package org.michimusic.mobile.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion

/** The tab role/label is always authoritative; this value is visual emphasis only. */
fun aeroDockEmphasisTarget(isSelected: Boolean): Float = if (isSelected) 1f else 0f

/** Preserve the original Aero high-contrast panel, with no layout re-measure each frame. */
fun aeroDockPanelOpacity(emphasis: Float, highContrast: Boolean): Float =
    emphasis.coerceIn(0f, 1f) * if (highContrast) 0.22f else 0.10f

fun aeroDockBorderOpacity(emphasis: Float, highContrast: Boolean): Float =
    emphasis.coerceIn(0f, 1f) * if (highContrast) 0.90f else 0.32f

@Composable
fun aeroDockSelectionEmphasis(selected: Boolean): Float {
    val prefs = LocalVisualPreferences.current
    val value by animateFloatAsState(
        targetValue = aeroDockEmphasisTarget(selected),
        animationSpec = if (prefs.reducedMotion || prefs.highContrast) snap() else
            tween(MichiMotion.durationStandard, easing = MichiMotion.easingDecelerate),
        label = "aero_bottom_dock_emphasis",
    )
    return value
}
