package org.michimusic.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/** Distinct interaction hierarchy instead of indistinguishable Material buttons. */
enum class AeroActionKind { Primary, Secondary, Quiet }

/** Pure policy: can be unit tested without an Android renderer. */
fun aeroActionMinimumHeightDp(fontScale: Float): Int = when {
    fontScale >= 1.7f -> 64
    fontScale >= 1.25f -> 56
    else -> 52
}

@Composable
fun AeroActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: AeroActionKind = AeroActionKind.Secondary,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val visual = LocalVisualPreferences.current
    val contrast = visual.highContrast
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed && !visual.reducedMotion) 0.975f else 1f,
        animationSpec = if (visual.reducedMotion) snap() else tween(125),
        label = "aero_button_pressed_scale",
    )
    val shape = RoundedCornerShape(16.dp)
    val base = when {
        !enabled -> listOf(Color(0xFF353941), Color(0xFF292D34))
        contrast && kind == AeroActionKind.Primary -> listOf(Color(0xFFFFB1BC), Color(0xFFFF8EA0))
        contrast -> listOf(Color(0xFF303640), Color(0xFF20252D))
        kind == AeroActionKind.Primary -> listOf(Color(0xFFFF788B), PrimaryPinkContainer)
        kind == AeroActionKind.Secondary -> listOf(Color(0xFF343D48), Color(0xFF242B35))
        else -> listOf(Color(0xFF292F38), Color(0xFF1B222B))
    }
    val line = when {
        !enabled -> Color.White.copy(alpha = 0.09f)
        contrast -> Color.White.copy(alpha = 0.72f)
        kind == AeroActionKind.Primary -> Color(0xFFFFB2BD).copy(alpha = 0.58f)
        else -> Color.White.copy(alpha = 0.22f)
    }
    val ink = if (enabled && kind == AeroActionKind.Primary) SurfaceObsidian else
        if (enabled) PureWhite else Color(0xFFACB2BD)
    Box(
        modifier = modifier
            .heightIn(min = aeroActionMinimumHeightDp(androidx.compose.ui.platform.LocalConfiguration.current.fontScale).dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (!contrast && kind == AeroActionKind.Primary && enabled)
                Modifier.shadow(7.dp, shape, ambientColor = PrimaryPinkContainer.copy(alpha = 0.13f))
                else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(base))
            .border(if (contrast) 1.3.dp else 0.8.dp, line, shape)
            .clickable(
                enabled = enabled,
                interactionSource = source,
                indication = ripple(color = if (kind == AeroActionKind.Primary) SurfaceObsidian else PureWhite),
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            // 10dp, not 14dp: in a three-action row every dp of inner padding
            // competed with the longest label and forced it to ellipsize.
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(19.dp))
                Spacer(Modifier.size(5.dp))
            }
            // An action label is one text unit. With maxLines = 2 a single word
            // ("Mezclar") broke mid-word into "Mezcl"/"ar" as soon as the row
            // squeezed it. Auto-size keeps the whole word by shrinking at most
            // 2.sp instead of splitting it or ellipsizing it away.
            BasicText(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 12.sp,
                    maxFontSize = 14.sp,
                    stepSize = 0.5.sp,
                ),
                style = TextStyle(
                    fontFamily = InterFontFamily,
                    color = ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}
