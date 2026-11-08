package org.michimusic.mobile.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion

/**
 * Reserved for authoritative *pending* operations. An animation entering the
 * viewport never changes an unacknowledged remote request into a success.
 * No unbounded animation or timer-based state machine is created here.
 */
@Composable
fun AeroPendingReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val preferences = LocalVisualPreferences.current
    val immediate = preferences.reducedMotion || preferences.highContrast
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (immediate) EnterTransition.None else
            fadeIn(tween(MichiMotion.durationFast)) + expandVertically(tween(MichiMotion.durationStandard)),
        exit = if (immediate) ExitTransition.None else
            fadeOut(tween(MichiMotion.durationFast)) + shrinkVertically(tween(MichiMotion.durationStandard)),
        label = "aero_authoritative_pending_command",
    ) { content() }
}
