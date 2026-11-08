package org.michimusic.mobile.ui.motion

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import org.michimusic.mobile.ui.theme.MichiMotion
import org.michimusic.mobile.ui.theme.LocalVisualPreferences

/**
 * Morph only an existing, real icon state; keep semantics on the caller's touch target.
 * Crossfade is short and bounded. Reduced motion changes the glyph atomically.
 * Neither a second playback action nor a navigation destination is created here.
 */
@Composable
fun AeroAnimatedGlyph(
    icon: ImageVector,
    tint: Color,
    size: Dp,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    label: String = "aero_glyph_change",
) {
    val motionDisabled = reducedMotion || LocalVisualPreferences.current.highContrast
    Crossfade(
        targetState = icon,
        animationSpec = if (motionDisabled) snap() else tween(
            durationMillis = MichiMotion.durationFast,
            easing = MichiMotion.easingDecelerate,
        ),
        label = label,
        modifier = modifier,
    ) { image ->
        Icon(
            imageVector = image,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}
