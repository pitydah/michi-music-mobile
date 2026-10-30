package org.michimusic.mobile.ui.components.premium

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite

/**
 * MichiIconAction provides an accessible, standardized Apple Music icon-only action target.
 *
 * Guarantees:
 * - Pure icon-only rendering: NO circular backgrounds, disks, rings, bubbles or outer borders.
 * - Minimum touch target >= 48dp (WCAG 2.5.5 / Android Accessibility).
 * - Discrete scale and opacity feedback instead of noisy circular ripple discs (K46D-13).
 * - Full support for enabled, disabled, loading, selected, and pressed states.
 * - Respects TalkBack semantics, high-contrast, and reduced-motion preferences.
 */
fun validateIconActionDescription(contentDescription: String) {
    require(contentDescription.isNotBlank()) {
        "MichiIconAction requires an explicit, meaningful contentDescription for TalkBack accessibility (K46D-15)."
    }
}

@Composable
fun MichiIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    isLoading: Boolean = false,
    tint: Color = PureWhite,
    selectedTint: Color = PrimaryPinkContainer,
    iconSize: Dp = 24.dp,
    minTargetSize: Dp = MichiSpacing.minTouchTarget,
    testTag: String? = null,
) {
    validateIconActionDescription(contentDescription)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val visualPrefs = LocalVisualPreferences.current
    val haptic = LocalHapticFeedback.current

    val effectiveTint = when {
        !enabled -> tint.copy(alpha = 0.38f)
        selected -> selectedTint
        visualPrefs.highContrast -> PureWhite
        else -> tint
    }

    // Discrete tactile response without disruptive circular disc ripples (Apple Music style)
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !visualPrefs.reducedMotion) 0.90f else 1.0f,
        animationSpec = tween(durationMillis = 120),
        label = "michi_icon_scale",
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.60f else 1.0f,
        animationSpec = tween(durationMillis = 120),
        label = "michi_icon_alpha",
    )

    Box(
        modifier = modifier
            .size(minTargetSize.coerceAtLeast(MichiSpacing.minTouchTarget))
            .clickable(
                enabled = enabled && !isLoading,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null, // Discrete tactile opacity/scale replaces intrusive circular discs
            )
            .semantics {
                this.contentDescription = if (isLoading) "$contentDescription, cargando" else contentDescription
                if (selected) {
                    this.selected = true
                }
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    alpha = animatedAlpha
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    color = effectiveTint,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null, // Semantics hoisted on parent touch target
                    tint = effectiveTint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}
