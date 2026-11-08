package org.michimusic.mobile.ui.motion

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion

/** Stages represent observed service states, not elapsed time or estimated progress. */
enum class AeroTransferStageVisual { UPCOMING, ACTIVE, COMPLETED, FAILED }

fun aeroTransferStageVisual(index: Int, currentStage: Int, isError: Boolean): AeroTransferStageVisual {
    if (index < 0 || index > 3 || currentStage !in 0..3) return AeroTransferStageVisual.UPCOMING
    return when {
        index == currentStage && isError -> AeroTransferStageVisual.FAILED
        index < currentStage || (index == 3 && currentStage == 3) -> AeroTransferStageVisual.COMPLETED
        index == currentStage -> AeroTransferStageVisual.ACTIVE
        else -> AeroTransferStageVisual.UPCOMING
    }
}

/** Finite color interpolation; no repeated pulse, simulated throughput or shader loop. */
@Composable
fun aeroStageColor(target: Color, label: String): Color {
    val prefs = LocalVisualPreferences.current
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = if (prefs.reducedMotion || prefs.highContrast) snap() else tween(
            durationMillis = MichiMotion.durationStandard,
            easing = MichiMotion.easingDecelerate,
        ),
        label = label,
    )
    return color
}
