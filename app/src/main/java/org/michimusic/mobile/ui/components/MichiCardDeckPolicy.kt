package org.michimusic.mobile.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** The canonical library order is immutable. UI6 only selects a key in that order. */
internal fun <T> michiCardIndex(items: List<T>, selectedKey: String?, key: (T) -> String): Int =
    items.indexOfFirst { key(it) == selectedKey }.coerceAtLeast(0)

internal fun <T> michiCardStepKey(
    items: List<T>, selectedKey: String?, key: (T) -> String, step: Int,
): String? {
    if (items.isEmpty()) return null
    val index = michiCardIndex(items, selectedKey, key)
    return key(items[Math.floorMod(index + step, items.size)])
}

/** A removed artist cannot remain active after the scope or library graph changes. */
internal fun <T> michiCardReconciledKey(
    items: List<T>, selectedKey: String?, key: (T) -> String,
): String? = selectedKey?.takeIf { selected -> items.any { key(it) == selected } }
    ?: items.firstOrNull()?.let(key)

/** Defensive list policy: duplicate keys must never compose two cards with the same identity. */
internal fun <T> michiCardDistinctItems(items: List<T>, key: (T) -> String): List<T> =
    items.distinctBy(key)

/**
 * Returns +1 for left / next, -1 for right / previous, 0 for cancelled gestures.
 * A fling only commits when there is meaningful displacement; tiny jitters cannot navigate.
 */
internal fun michiCardSwipeStep(displacementPx: Float, velocityPx: Float, widthPx: Float): Int {
    if (!widthPx.isFinite() || widthPx <= 0f || !displacementPx.isFinite() || !velocityPx.isFinite()) return 0
    val distance = abs(displacementPx)
    val matchingFlingDirection = (displacementPx > 0f && velocityPx > 0f) ||
        (displacementPx < 0f && velocityPx < 0f)
    val committed = distance >= widthPx * 0.20f ||
        (matchingFlingDirection && distance >= widthPx * 0.06f && abs(velocityPx) >= 1500f)
    if (!committed || displacementPx == 0f) return 0
    return if (displacementPx < 0f) 1 else -1
}

/** Captures the only three facts allowed to authorize a completed swipe. */
internal data class MichiCardGestureToken(
    val frontKey: String,
    val itemKeys: List<String>,
    val epoch: Int,
)

internal fun michiCardGestureIsCurrent(
    token: MichiCardGestureToken,
    frontKey: String,
    itemKeys: List<String>,
    epoch: Int,
): Boolean = token.frontKey == frontKey && token.itemKeys == itemKeys && token.epoch == epoch

/** Right drags preview the previous item; left drags preview the next one. */
internal fun michiCardPreviewIndex(index: Int, depth: Int, direction: Int, size: Int): Int =
    if (size <= 0) 0 else Math.floorMod(index + depth * if (direction < 0) -1 else 1, size)

/**
 * Bounded stage including the 24dp side safety and 32dp bottom bleed for the rear fan.
 * Explicitly includes multi-line editorial text and unlimited font scaling. At extreme
 * accessibility scales the stage fails closed to the text+Open fallback, not clipped art.
 */
internal fun michiCardStageSize(
    width: Dp, height: Dp, circularArtwork: Boolean, fontScale: Float = 1f,
): Pair<Dp, Dp> {
    val w = if (width.value.isFinite()) width.coerceAtLeast(0.dp) else 0.dp
    val h = if (height.value.isFinite()) height.coerceAtLeast(0.dp) else 0.dp
    if (w == 0.dp || h == 0.dp) return 0.dp to 0.dp
    val scale = if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f
    val metadataBase = if (circularArtwork) 100f else 116f
    val metadataGrowth = if (circularArtwork) 64f else 76f
    val metadata = (metadataBase + metadataGrowth * (scale - 1f)).dp
    val horizontalSafety = 48.dp
    val verticalSafety = 40.dp
    val cap = if (circularArtwork) 280.dp else 312.dp
    val art = minOf(
        (w - horizontalSafety).coerceAtLeast(0.dp),
        (h - metadata - verticalSafety).coerceAtLeast(0.dp),
        cap,
    )
    return minOf(w, art + horizontalSafety) to minOf(h, art + metadata + verticalSafety)
}

/** A compact layout cannot safely display a deck and its editorial metadata. */
internal fun michiCardNeedsCompactFallback(width: Dp, height: Dp): Boolean =
    width < 164.dp || height < 216.dp

internal data class MichiCardDepthStyle(
    val scale: Float, val rotation: Float, val offsetX: Dp, val offsetY: Dp,
)

/** High contrast retains a visible fan; reduced motion alone flattens the deck. */
internal fun michiCardVisibleCount(size: Int, reducedMotion: Boolean): Int =
    minOf(size.coerceAtLeast(0), if (reducedMotion) 1 else 3)

/** Back cards remain visibly offset in high contrast, without rotation or animation. */
internal fun michiCardDepthStyle(
    depth: Int, circularArtwork: Boolean, motionEnabled: Boolean, highContrast: Boolean = false,
): MichiCardDepthStyle {
    if (!motionEnabled || depth <= 0) return MichiCardDepthStyle(1f, 0f, 0.dp, 0.dp)
    val layer = depth.coerceAtMost(2)
    val style = if (circularArtwork) MichiCardDepthStyle(
        scale = 1f - 0.06f * layer,
        rotation = if (layer == 1) 6f else -6f,
        offsetX = if (layer == 1) 18.dp else (-18).dp,
        offsetY = if (layer == 1) 21.dp else 39.dp,
    ) else MichiCardDepthStyle(
        scale = 1f - 0.06f * layer,
        rotation = if (layer == 1) -3f else 4f,
        offsetX = if (layer == 1) (-7).dp else 12.dp,
        offsetY = if (layer == 1) 27.dp else 45.dp,
    )
    return if (highContrast) style.copy(rotation = 0f) else style
}

/**
 * Vertical lower edge of a rear card relative to the front card, before rotation.
 * Useful for checking that rear artwork is genuinely visible and fits the 32dp bleed.
 * The visible protrusion must be positive, but never exceed the reserved bleed.
 */
internal fun michiCardBottomProtrusion(style: MichiCardDepthStyle, cardHeight: Dp): Dp =
    style.offsetY - cardHeight * ((1f - style.scale) / 2f)

/**
 * Upper bound for how far a rotated rear card extends below the front card.
 * Models a rotated scaled rectangle; circular portraits are conservatively
 * bounded by their enclosing rect. Keep this <= the reserved 32dp stage bleed.
 */
internal fun michiCardRotatedBottomProtrusion(
    style: MichiCardDepthStyle, cardWidth: Dp, cardHeight: Dp,
): Dp {
    val radians = abs(style.rotation).toDouble() * PI / 180.0
    val rearHeight = style.scale * (
        cardHeight.value * abs(cos(radians)).toFloat() +
            cardWidth.value * abs(sin(radians)).toFloat()
    )
    return style.offsetY + ((rearHeight - cardHeight.value) / 2f).dp
}
