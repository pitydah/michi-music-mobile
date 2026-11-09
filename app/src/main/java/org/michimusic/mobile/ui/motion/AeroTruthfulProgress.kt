package org.michimusic.mobile.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion

/** Unknown, negative, NaN and infinite measurements must never become a fake 0%. */
fun aeroReportedFraction(fraction: Float?): Float? =
    fraction?.takeIf { it.isFinite() && it >= 0f }?.coerceIn(0f, 1f)

/** Only semantic progress is shown when large-print/high-contrast motion is disabled. */
fun aeroProgressMotionEnabled(reducedMotion: Boolean, highContrast: Boolean): Boolean =
    !reducedMotion && !highContrast

/** Use the exact observed byte counts, including zero; an unknown total remains null. */
fun aeroMeasuredFraction(completed: Long, total: Long): Float? {
    if (total <= 0L || completed < 0L) return null
    return (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/**
 * A determinate bar only when evidence supports a denominator. An indeterminate
 * bar conveys work without asserting fictitious percentage or speed. Once motion
 * reduction is enabled, values snap to the measured fraction immediately.
 */
@Composable
fun AeroTruthfulProgress(
    fraction: Float?,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val preferences = LocalVisualPreferences.current
    val motionEnabled = aeroProgressMotionEnabled(preferences.reducedMotion, preferences.highContrast)
    val measured = aeroReportedFraction(fraction)
    if (measured == null) {
        if (!motionEnabled) {
            // The transfer is still in progress, but a continuously moving
            // indeterminate stripe would violate the reduced-motion contract.
            Box(modifier = modifier
                .background(trackColor)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate })
        } else {
            LinearProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)
        }
    } else {
        val shown by animateFloatAsState(
            targetValue = measured,
            animationSpec = if (!motionEnabled) snap() else tween(
                durationMillis = MichiMotion.durationStandard,
                easing = MichiMotion.easingDecelerate,
            ),
            label = "aero_verified_transfer_progress",
        )
        LinearProgressIndicator(
            progress = { shown },
            modifier = modifier,
            color = color,
            trackColor = trackColor,
        )
    }
}
