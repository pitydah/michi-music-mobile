package org.michimusic.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.aeroResolveMaterial
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/** M55: a single, tactile material language for source, content-type and search filters. */
enum class AeroSegmentTreatment {
    Rail,
    Filter,

    /**
     * UI8-KILLCRITIC: a secondary control that lives in a screen header. The visible
     * pill is deliberately shorter than 48 dp so it does not compete with the title,
     * while the selectable host still owns the full 48 dp target.
     */
    Micro,
}

/** Designed for font scaling rather than fixed-height chips that cut labels. */
fun aeroSegmentMinHeightDp(fontScale: Float): Int = when {
    fontScale >= 1.7f -> 64
    fontScale >= 1.3f -> 56
    else -> 48
}

/**
 * Three options assigned equal thirds of a narrow dialog cannot hold a word such
 * as "Personalizado", so the group stacks instead of ellipsizing its labels.
 * Same thresholds as the editorial cards: below 330 dp or from 1.3 font scale the
 * horizontal rail stops fitting.
 */
fun aeroSegmentStacking(availableWidthDp: Float, fontScale: Float): Boolean =
    availableWidthDp < 330f || fontScale >= 1.3f

/** A bounded material rail; only the selected segment receives the luminous inset. */
@Composable
fun AeroSegmentRail(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /**
     * Default keeps every existing caller full width. A header that needs a compact
     * control passes `false` and lets the rail size to its own content.
     */
    fillWidth: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    AeroSegmentRow(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 14.dp else 17.dp),
        padding = (if (compact) 3.dp else 4.dp),
        spacing = (if (compact) 3.dp else 4.dp),
        borderAlpha = 0.12f,
        fillWidth = fillWidth,
        content = content,
    )
}

/**
 * UI8-KILLCRITIC: the header variant. Same material, border and semantics as the
 * rail, with a thinner shell so a two-word mode switch reads as a secondary action
 * instead of a section selector. Biblioteca, Search and Settings keep using
 * [AeroSegmentRail]; nothing about its geometry changed.
 */
@Composable
fun AeroMicroSegmentRail(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    AeroSegmentRow(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        padding = 0.dp,
        spacing = 2.dp,
        borderAlpha = 0.10f,
        fillWidth = false,
        content = content,
    )
}

@Composable
private fun AeroSegmentRow(
    modifier: Modifier,
    shape: RoundedCornerShape,
    padding: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
    borderAlpha: Float,
    fillWidth: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val visual = LocalVisualPreferences.current
    val highContrast = visual.highContrast
    val material = aeroResolveMaterial(
        role = AeroSurfaceRole.Content,
        requestedFinish = AeroFinish.Inset,
        profile = visual.appearanceProfile,
        highContrast = highContrast,
        microtexturesEnabled = visual.microtexturesEnabled,
    )
    Row(
        // UI8-KILLCRITIC: fillWidth = false is what makes a compact header control
        // possible. Everything else about the rail is unchanged, so Library, Search
        // and Settings keep the full-width rail they already had.
        modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(if (highContrast) Color(0xFF111820) else Color(0xFF161D26))
            .aeroFinishDrawing(material.finish, material.spec, null, shape)
            .border(0.65.dp, Color.White.copy(alpha = if (highContrast) 0.8f else borderAlpha), shape)
            .selectableGroup()
            .padding(padding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Vertical counterpart of the rail for narrow widths or large font scales. */
@Composable
fun AeroSegmentStack(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visual = LocalVisualPreferences.current
    val highContrast = visual.highContrast
    val material = aeroResolveMaterial(
        role = AeroSurfaceRole.Content,
        requestedFinish = AeroFinish.Inset,
        profile = visual.appearanceProfile,
        highContrast = highContrast,
        microtexturesEnabled = visual.microtexturesEnabled,
    )
    val shape = RoundedCornerShape(if (compact) 14.dp else 17.dp)
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(shape)
            .background(if (highContrast) Color(0xFF111820) else Color(0xFF161D26))
            .aeroFinishDrawing(material.finish, material.spec, null, shape)
            .border(0.65.dp, Color.White.copy(alpha = if (highContrast) 0.8f else 0.12f), shape)
            .selectableGroup()
            .padding(if (compact) 3.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
        content = content,
    )
}

/** Selected state is expressed in geometry, tint AND accessibility semantics. */
@Composable
fun AeroSegmentedChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    treatment: AeroSegmentTreatment = AeroSegmentTreatment.Rail,
    compact: Boolean = false,
    testTag: String? = null,
    enabled: Boolean = true,
) {
    val visual = LocalVisualPreferences.current
    val contrast = visual.highContrast
    val activeTint = if (contrast) Color(0xFFFFBBC7) else PrimaryPinkContainer
    val ink by animateColorAsState(
        targetValue = if (selected || contrast) PureWhite else TextSecondary,
        animationSpec = if (visual.reducedMotion) snap() else tween(durationMillis = 160),
        label = "aero_segment_ink",
    )
    val shape = RoundedCornerShape(
        when {
            treatment == AeroSegmentTreatment.Micro -> 8.dp
            compact -> 11.dp
            treatment == AeroSegmentTreatment.Rail -> 13.dp
            else -> 14.dp
        },
    )
    val colors = if (selected && contrast) listOf(Color(0xFF493440), Color(0xFF312834))
        else if (selected) listOf(Color(0xFF39303B), Color(0xFF282630))
        else if (treatment == AeroSegmentTreatment.Filter) listOf(Color(0xFF252C36), Color(0xFF1E252D))
        else listOf(Color.Transparent, Color.Transparent)
    val border = if (selected) activeTint.copy(alpha = if (contrast) 0.85f else if (compact) 0.30f else 0.36f)
        else if (contrast) Color.White.copy(alpha = 0.65f)
        else Color.White.copy(alpha = if (treatment == AeroSegmentTreatment.Filter) 0.13f else 0f)
    val visualMinHeight = when (treatment) {
        // UI8-KILLCRITIC: measured on device, a 34 dp pill inside an overflowing 48 dp
        // host cannot keep a 48 dp hit area: Compose clamps the child to the parent, the
        // pill reported 38 dp of semantics, and a tap 3 dp above it landed on nothing.
        // The host therefore draws at the target height and the size reduction comes from
        // width, radius, padding and type instead.
        AeroSegmentTreatment.Micro -> 48.dp
        AeroSegmentTreatment.Filter -> 36.dp
        AeroSegmentTreatment.Rail -> aeroSegmentMinHeightDp(LocalConfiguration.current.fontScale).dp
    }
    // UI8-KILLCRITIC: a header control must not move when the mode changes. The
    // selected weight used to widen "Tarjetas" and shift the "+" beside it, so the
    // micro variant keeps one weight and lets tint, border and fill carry the state.
    val labelWeight = if (treatment == AeroSegmentTreatment.Micro) {
        FontWeight.Medium
    } else {
        if (selected) FontWeight.SemiBold else FontWeight.Medium
    }
    Box(
        // The selectable host owns the 48dp touch target; the visible pill can be
        // lighter without shrinking the hit area or changing semantics.
        modifier = modifier
            // The drawn control is the tap target: same rectangle for the eye and the finger.
            .heightIn(min = visualMinHeight)
            .clip(shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = if (treatment == AeroSegmentTreatment.Rail) Role.Tab else Role.Button,
                onClick = onClick,
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.verticalGradient(colors))
                .border(if (contrast) 1.2.dp else 0.8.dp, border, shape)
                .padding(
                    horizontal = when (treatment) {
                        // Measured on device: 7 dp gave a 125.33 dp rail, 1.3 dp over the
                        // 124 dp design ceiling. 6 dp lands it at ~121 dp.
                        AeroSegmentTreatment.Micro -> 6.dp
                        AeroSegmentTreatment.Filter -> 15.dp
                        AeroSegmentTreatment.Rail -> 8.dp
                    },
                    vertical = when (treatment) {
                        AeroSegmentTreatment.Micro -> 3.dp
                        AeroSegmentTreatment.Filter -> 8.dp
                        AeroSegmentTreatment.Rail -> if (compact) 4.dp else 8.dp
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // A single-word label such as "Personalizado" cannot wrap, so a fixed 13.sp
            // broke it mid-word on a 320 dp screen. One line without soft wrap lets
            // auto-size shrink it by at most 3.sp; multi-word labels keep two lines so
            // they still wrap naturally before any shrinking happens.
            val singleWord = label.none { it.isWhitespace() }
            val maxLines = if (singleWord) 1 else 2
            val softWrap = !singleWord
            val autoSize = TextAutoSize.StepBased(
                minFontSize = 10.sp,
                maxFontSize = if (compact || treatment == AeroSegmentTreatment.Micro) 12.sp else 13.sp,
                stepSize = 0.5.sp,
            )
            BasicText(
                text = label,
                maxLines = maxLines,
                softWrap = softWrap,
                overflow = TextOverflow.Ellipsis,
                modifier = if (testTag != null) Modifier.testTag("${testTag}_label") else Modifier,
                autoSize = autoSize,
                style = TextStyle(
                    color = ink,
                    fontFamily = InterFontFamily,
                    fontWeight = labelWeight,
                    fontSize = if (treatment == AeroSegmentTreatment.Micro) 12.sp else if (compact) 12.sp else 13.sp,
                    lineHeight = if (treatment == AeroSegmentTreatment.Micro) 15.sp else if (compact) 17.sp else 19.sp,
                ),
            )
        }
    }
}

/**
 * Secondary filter chip: lighter than a tab rail or an action button while
 * retaining the same 48 dp interaction contract and selected semantics.
 */
@Composable
fun AeroFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val visual = LocalVisualPreferences.current
    val contrast = visual.highContrast
    val shape = RoundedCornerShape(50)
    val ink by animateColorAsState(
        targetValue = if (selected || contrast) PureWhite else TextSecondary,
        animationSpec = if (visual.reducedMotion) snap() else tween(140),
        label = "aero_filter_chip_ink",
    )
    Box(
        // 48dp selectable host, 34dp visible chip. The extra transparent ring is
        // intentional: it preserves the touch target without inflating the pill.
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 34.dp)
                .clip(shape)
                .background(
                    if (selected) PrimaryPinkContainer.copy(alpha = if (contrast) 0.28f else 0.13f)
                    else Color.Transparent,
                )
                .border(
                    width = if (contrast) 1.1.dp else 0.6.dp,
                    color = when {
                        selected -> PrimaryPinkContainer.copy(alpha = if (contrast) 0.88f else 0.42f)
                        contrast -> Color.White.copy(alpha = 0.7f)
                        else -> Color.White.copy(alpha = 0.13f)
                    },
                    shape = shape,
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (testTag != null) Modifier.testTag("${testTag}_label") else Modifier,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                style = TextStyle(
                    color = ink,
                    fontFamily = InterFontFamily,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
            )
        }
    }
}
