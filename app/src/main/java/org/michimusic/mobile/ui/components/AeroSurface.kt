package org.michimusic.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.aeroAppearanceFinishSpec
import org.michimusic.mobile.ui.theme.aeroInteractiveFinishSpec
import org.michimusic.mobile.ui.theme.aeroFocusStrokeWidthDp
import org.michimusic.mobile.ui.theme.aeroResolvedFinish
import org.michimusic.mobile.R
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.aeroReadableInk
import org.michimusic.mobile.ui.theme.aeroMaterialStyle

/**
 * Single Aero surface renderer. Only the semantic [role] chooses its material.
 * No hardware blur, no nested halo, no dynamic background based on untrusted album art.
 * Explicit stroke exists only to preserve legacy callers and specialized surfaces.
 */
@Composable
fun AeroSurface(
    role: AeroSurfaceRole,
    modifier: Modifier = Modifier,
    shape: Shape = MichiShapes.md,
    borderStroke: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp? = null,
    finish: AeroFinish = AeroFinish.Plain,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val visual = LocalVisualPreferences.current
    val highContrast = visual.highContrast
    val resolvedFinish = aeroResolvedFinish(role, finish, highContrast)
    // UI5: these settings alter real finish paint, without any new surface renderer.
    // Defaults match existing tokens and high contrast still forces Plain.
    val finishSpec = aeroAppearanceFinishSpec(
        resolvedFinish, visual.appearanceProfile, highContrast, visual.microtexturesEnabled,
    )
    // Android caches drawable decoding; there is no per-frame bitmap decoding.
    val grain = if (finishSpec.grainAlpha > 0f) ImageBitmap.imageResource(R.drawable.aero_graphite_grain) else null
    val material = aeroMaterialStyle(role, LocalVisualPreferences.current.highContrast)
    val border = borderStroke ?: material.takeIf { it.borderWidth > 0.dp }?.let {
        BorderStroke(it.borderWidth, it.outline)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val interactiveFinishSpec = aeroInteractiveFinishSpec(
        resolvedFinish, finishSpec, (isPressed || isFocused) && onClick != null, highContrast,
    )
    val focusStroke = aeroFocusStrokeWidthDp(isFocused, onClick != null, highContrast)
    val visibleBorder = if (focusStroke > 0f) BorderStroke(focusStroke.dp,
        if (highContrast) Color.White else PrimaryPinkContainer.copy(alpha = .85f)) else border
    // Inherit readable default ink even where a child omits an explicit Text color.
    // Callers with semantic status colors still own their explicit text/icon choices.
    CompositionLocalProvider(LocalContentColor provides aeroReadableInk(TextPrimary, material.fill)) {
        Box(
            modifier = modifier
            .then(if (material.elevation > 0.dp) Modifier.shadow(material.elevation, shape) else Modifier)
            .clip(shape)
            .background(material.fill)
            .aeroFinishDrawing(resolvedFinish, interactiveFinishSpec, grain)
            .then(if (visibleBorder != null) Modifier.border(visibleBorder, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(
                interactionSource = interactionSource,
                indication = ripple(color = PrimaryPinkContainer),
                role = Role.Button,
                onClick = onClick,
            ) else Modifier)
            .then(if (contentPadding != null) Modifier.padding(contentPadding) else Modifier),
            contentAlignment = contentAlignment,
            content = content,
        )
    }
}

/** Canonical root for all Aero screens; no second Premium/Glass canvas policy. */
@Composable
fun AeroScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AeroSurface(role = AeroSurfaceRole.Canvas,
        modifier = modifier.fillMaxSize(), shape = RectangleShape) { content() }
}

/** Canonical raised card. Optional click is a single semantic button, not a nested gesture. */
@Composable
fun AeroCard(
    modifier: Modifier = Modifier,
    shape: Shape = MichiShapes.md,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp? = null,
    finish: AeroFinish = AeroFinish.Plain,
    content: @Composable BoxScope.() -> Unit,
) {
    AeroSurface(role = AeroSurfaceRole.Raised, modifier = modifier, shape = shape,
        onClick = onClick, contentPadding = contentPadding, finish = finish, content = content)
}
