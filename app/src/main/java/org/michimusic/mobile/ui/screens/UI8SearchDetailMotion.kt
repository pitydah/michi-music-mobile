package org.michimusic.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.UI8VisualSpec

/** Window motion only; source identity, selection and Media3 are unchanged. */
internal data class UI8SearchDialogMotion(val enterMs: Int, val exitMs: Int)

internal fun searchDialogMotion(reducedMotion: Boolean): UI8SearchDialogMotion =
    if (reducedMotion) UI8SearchDialogMotion(0, 0)
    else UI8SearchDialogMotion(
        UI8VisualSpec.searchDetailOpenMillis, UI8VisualSpec.searchDetailCloseMillis,
    )

/**
 * Use one animated window for both artist and album details.
 * On system Back/outside tap, keep the Dialog alive until its exit completes;
 * an upstream result replacement that disposes this composable cancels the effect.
 * Motion is completely disabled when the user's reduced-motion setting is active.
 */
@Composable
internal fun UI8SearchDetailDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalVisualPreferences.current.reducedMotion
    val timing = searchDialogMotion(reducedMotion)
    val visibility = remember { MutableTransitionState(false) }
    var closing by remember { mutableStateOf(false) }
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(Unit) { if (!closing) visibility.targetState = true }
    LaunchedEffect(closing, visibility.isIdle, visibility.currentState) {
        if (closing && visibility.isIdle && !visibility.currentState) latestOnDismiss()
    }

    Dialog(onDismissRequest = {
        if (!closing) {
            closing = true
            visibility.targetState = false
        }
    }) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = if (timing.enterMs == 0) EnterTransition.None else
                fadeIn(tween(timing.enterMs)) + scaleIn(
                    initialScale = 0.98f, animationSpec = tween(timing.enterMs),
                ),
            exit = if (timing.exitMs == 0) ExitTransition.None else
                fadeOut(tween(timing.exitMs)) + scaleOut(
                    targetScale = 0.98f, animationSpec = tween(timing.exitMs),
                ),
        ) {
            Box(Modifier.testTag("ui8_search_detail_motion_frame")) { content() }
        }
    }
}
