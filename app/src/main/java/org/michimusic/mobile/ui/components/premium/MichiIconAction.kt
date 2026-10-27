package org.michimusic.mobile.ui.components.premium

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.PureWhite

/**
 * MichiIconAction provides an accessible, standardized icon action target.
 *
 * Guarantees a minimum touch target >= 48dp (WCAG 2.5.5 / Android Accessibility),
 * with a neutral transparent background by default and subtle ripple indication.
 */
@Composable
fun MichiIconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = PureWhite,
    iconSize: Dp = 24.dp,
    minTargetSize: Dp = MichiSpacing.minTouchTarget,
    testTag: String? = null,
) {
    val accessibilityLabel = contentDescription?.takeIf { it.isNotBlank() } ?: "Acción"
    Box(
        modifier = modifier
            .size(minTargetSize.coerceAtLeast(MichiSpacing.minTouchTarget))
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = tint.copy(alpha = 0.35f)),
            )
            .semantics { this.contentDescription = accessibilityLabel }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(iconSize),
        )
    }
}
