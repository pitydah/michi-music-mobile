package org.michimusic.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion
import kotlin.math.abs

/**
 * UI6 Vinyl Crate: album sleeve == card. Distinct from horizontal artist cards.
 * Uses the existing shared MichiCardDeckPolicy identity/step contracts; never
 * modifies collection order and never asks the player to begin playback.
 */
@Composable
internal fun <T> MichiVinylCrate(
    items: List<T>,
    itemKey: (T) -> String,
    itemLabel: (T) -> String,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onOpenSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    sleeveContent: @Composable (T) -> Unit,
) {
    val cards = remember(items) { michiCardDistinctItems(items, itemKey) }
    if (cards.isEmpty()) return
    val keys = remember(cards) { cards.map(itemKey) }
    val position = selectedKey?.let(keys::indexOf)?.takeIf { it >= 0 } ?: 0
    val frontKey = keys[position]
    val preferences = LocalVisualPreferences.current
    val motionEnabled = !preferences.reducedMotion && !preferences.highContrast
    val visibleCount = michiCardVisibleCount(cards.size, preferences.reducedMotion)
    val currentCards by rememberUpdatedState(cards)
    val currentKeys by rememberUpdatedState(keys)
    val currentFront by rememberUpdatedState(frontKey)
    val currentItemKey by rememberUpdatedState(itemKey)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentOpen by rememberUpdatedState(onOpenSelected)
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled by rememberUpdatedState(preferences.hapticsEnabled)
    val scope = rememberCoroutineScope()
    val flight = remember(keys, frontKey, motionEnabled) { Animatable(0f) }
    var displacement by remember(keys, frontKey, motionEnabled) { mutableFloatStateOf(0f) }
    var dragging by remember(keys, frontKey, motionEnabled) { mutableStateOf(false) }
    var settling by remember(keys, frontKey, motionEnabled) { mutableStateOf(false) }
    var epoch by remember(keys, frontKey, motionEnabled) { mutableIntStateOf(0) }
    var job by remember(keys, frontKey, motionEnabled) { mutableStateOf<Job?>(null) }

    DisposableEffect(keys, frontKey, motionEnabled) {
        onDispose {
            epoch++
            job?.cancel()
            job = null
            dragging = false
            settling = false
            displacement = 0f
        }
    }
    fun requestStep(step: Int): Boolean {
        if (cards.size < 2 || settling || dragging) return false
        val target = michiCardStepKey(currentCards, currentFront, currentItemKey, step)
            ?: return false
        if (target == currentFront) return false
        currentSelect(target)
        return true
    }

    BoxWithConstraints(modifier = modifier.testTag("album_vinyl_stage"), contentAlignment = Alignment.Center) {
        val available = minOf(maxWidth - 36.dp, maxHeight - 34.dp, 288.dp)
        if (available < 132.dp) {
            CompactDeckFallback(
                position = position + 1, count = cards.size, label = itemLabel(cards[position]),
                circularArtwork = false, enabled = cards.size > 1 && !settling,
                onPrevious = { requestStep(-1) }, onNext = { requestStep(1) },
                onOpen = { currentOpen(cards[position]) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val heightPx = with(LocalDensity.current) { available.toPx() }
            Box(
                modifier = Modifier.size(available, available + 34.dp).clipToBounds(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val previewDirection = if (displacement > 0f) -1 else 1
                for (depth in (visibleCount - 1) downTo 0) {
                    val album = cards[michiCardPreviewIndex(position, depth, previewDirection, cards.size)]
                    val front = depth == 0
                    key(itemKey(album)) {
                        Box(
                            modifier = Modifier.size(available)
                                .graphicsLayer {
                                    val scale = 1f - (depth * 0.035f)
                                    scaleX = scale
                                    scaleY = scale
                                    translationY = if (front) {
                                        if (dragging) displacement else flight.value
                                    } else (-depth * 18.dp.toPx())
                                    shadowElevation = if (front) 4.dp.toPx() else 1.dp.toPx()
                                }
                                .zIndex((visibleCount - depth).toFloat())
                                .then(
                                    if (front && cards.size > 1) Modifier.pointerInput(keys, frontKey, heightPx) {
                                        val tracker = VelocityTracker()
                                        var token = MichiCardGestureToken(frontKey, keys, epoch)
                                        var didHaptic = false
                                        detectVerticalDragGestures(
                                            onDragStart = {
                                                token = MichiCardGestureToken(currentFront, currentKeys, epoch)
                                                dragging = !settling && heightPx > 0f
                                                displacement = 0f
                                                didHaptic = false
                                                tracker.resetTracking()
                                            },
                                            onVerticalDrag = { change, amount ->
                                                if (!dragging || settling || !michiCardGestureIsCurrent(
                                                        token, currentFront, currentKeys, epoch,
                                                    )) return@detectVerticalDragGestures
                                                change.consume()
                                                displacement = (displacement + amount).coerceIn(-heightPx, heightPx)
                                                tracker.addPosition(change.uptimeMillis, change.position)
                                                if (!didHaptic && abs(displacement) >= heightPx * .20f) {
                                                    if (hapticsEnabled) haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress,
                                                    )
                                                    didHaptic = true
                                                }
                                            },
                                            onDragEnd = {
                                                if (!dragging) return@detectVerticalDragGestures
                                                dragging = false
                                                if (!michiCardGestureIsCurrent(token, currentFront, currentKeys, epoch)) {
                                                    displacement = 0f
                                                    return@detectVerticalDragGestures
                                                }
                                                val step = vinylCrateSwipeStep(
                                                    displacement, tracker.calculateVelocity().y, heightPx,
                                                )
                                                val released = displacement
                                                val currentToken = token
                                                settling = true
                                                job?.cancel()
                                                job = scope.launch {
                                                    try {
                                                        if (motionEnabled) {
                                                            flight.snapTo(released)
                                                            flight.animateTo(
                                                                if (step == 0) 0f else -step * heightPx * 1.1f,
                                                                tween(
                                                                    durationMillis = MichiMotion.durationStandard,
                                                                    easing = MichiMotion.easingDecelerate,
                                                                ),
                                                            )
                                                        } else flight.snapTo(0f)
                                                        if (step != 0 && michiCardGestureIsCurrent(
                                                                currentToken, currentFront, currentKeys, epoch,
                                                            )) {
                                                            val target = michiCardStepKey(
                                                                currentCards, currentToken.frontKey, currentItemKey, step,
                                                            )
                                                            if (target != null) currentSelect(target)
                                                        }
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } finally {
                                                        if (currentToken.epoch == epoch) {
                                                            flight.snapTo(0f)
                                                            displacement = 0f
                                                            settling = false
                                                        }
                                                    }
                                                }
                                            },
                                            onDragCancel = {
                                                dragging = false
                                                epoch++
                                                val cancellingEpoch = epoch
                                                job?.cancel()
                                                settling = true
                                                job = scope.launch {
                                                    try {
                                                        if (motionEnabled) flight.animateTo(0f) else flight.snapTo(0f)
                                                    } finally {
                                                        if (epoch == cancellingEpoch) {
                                                            displacement = 0f
                                                            settling = false
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    } else Modifier,
                                )
                                .then(if (front) Modifier.clickable(
                                    enabled = !dragging && !settling,
                                    onClickLabel = "Abrir álbum ${itemLabel(album)}",
                                ) { currentOpen(album) } else Modifier)
                                .then(if (front) Modifier.semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction("Álbum siguiente") { requestStep(1) },
                                        CustomAccessibilityAction("Álbum anterior") { requestStep(-1) },
                                    )
                                } else Modifier)
                                .then(if (front) Modifier else Modifier.clearAndSetSemantics {})
                                .testTag(if (front) "album_vinyl_front" else "album_vinyl_back_$depth"),
                        ) {
                            // No title, year, count or artist may be composed inside any sleeve.
                            sleeveContent(album)
                        }
                    }
                }
            }
        }
    }
}
