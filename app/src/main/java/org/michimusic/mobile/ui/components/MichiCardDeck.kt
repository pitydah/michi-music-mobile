package org.michimusic.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * UI6: bounded, local-first Compose deck. Only the front item is actionable.
 * Cards never mutate library data, trigger discovery, or start playback on swipe.
 */
@Composable
fun <T> MichiCardDeck(
    items: List<T>,
    itemKey: (T) -> String,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    circularArtwork: Boolean = false,
    navigationButtons: Boolean = true,
    onOpenSelected: ((T) -> Unit)? = null,
    itemLabel: (T) -> String = itemKey,
    cardContent: @Composable (T, Boolean) -> Unit,
) {
    if (items.isEmpty()) return
    val safeItems = remember(items) { michiCardDistinctItems(items, itemKey) }
    if (safeItems.isEmpty()) return
    val itemKeys = remember(safeItems) { safeItems.map(itemKey) }
    val index = remember(itemKeys, selectedKey) {
        selectedKey?.let { itemKeys.indexOf(it) }?.coerceAtLeast(0) ?: 0
    }
    val frontKey = itemKeys[index]
    val preferences = LocalVisualPreferences.current
    // High contrast must NOT discard depth: it keeps the fan with white outlines,
    // zero rotation, and no motion. Only the user's reduced-motion setting flattens it.
    val motionEnabled = !preferences.reducedMotion && !preferences.highContrast
    val visibleCount = michiCardVisibleCount(safeItems.size, preferences.reducedMotion)
    // Transient state belongs to the exact selected identity and catalog key order.
    // A new scope or external selection cannot inherit an unfinished swipe.
    val swipeAnim = remember(itemKeys, frontKey, motionEnabled) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled by rememberUpdatedState(preferences.hapticsEnabled)
    var dragPx by remember(itemKeys, frontKey, motionEnabled) { mutableFloatStateOf(0f) }
    var isDragging by remember(itemKeys, frontKey, motionEnabled) { mutableStateOf(false) }
    var isSettling by remember(itemKeys, frontKey, motionEnabled) { mutableStateOf(false) }
    var selectionEpoch by remember(itemKeys, frontKey, motionEnabled) { mutableIntStateOf(0) }
    var transitionJob by remember(itemKeys, frontKey, motionEnabled) { mutableStateOf<Job?>(null) }

    val latestItems by rememberUpdatedState(safeItems)
    val latestItemKey by rememberUpdatedState(itemKey)
    val latestKeys by rememberUpdatedState(itemKeys)
    val latestFront by rememberUpdatedState(frontKey)
    val latestSelect by rememberUpdatedState(onSelect)

    DisposableEffect(itemKeys, frontKey, motionEnabled) {
        // Effect disposal runs when the keyed selection/catalog is replaced or leaves UI.
        onDispose {
            selectionEpoch++
            transitionJob?.cancel()
            transitionJob = null
            isDragging = false
            isSettling = false
            dragPx = 0f
        }
    }

    // The deck is controlled by the parent. A rejected/no-op selection must not
    // permanently lock the navigation controls awaiting a new frontKey.
    LaunchedEffect(isSettling, frontKey, itemKeys) {
        if (isSettling && transitionJob == null) {
            withFrameNanos { /* Allow the parent one composition frame to acknowledge selection. */ }
            if (isSettling && transitionJob == null && latestFront == frontKey) {
                isSettling = false
            }
        }
    }

    fun advance(step: Int) {
        if (safeItems.size < 2 || isSettling || isDragging) return
        michiCardStepKey(safeItems, frontKey, itemKey, step)
            ?.takeIf { it != frontKey }
            ?.let { target ->
                selectionEpoch++
                transitionJob?.cancel()
                isSettling = true // Wait for parent acknowledgment; one-frame recovery handles rejected selection.
                latestSelect(target)
            }
    }

    BoxWithConstraints(modifier = modifier) {
        val accessibilityScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val smallestHeight = (if (maxWidth < 128.dp) 172f else 132f) +
            64f * (accessibilityScale - 1f)
        if (maxHeight < smallestHeight.dp) {
            CompactDeckFallback(
                position = index + 1,
                count = safeItems.size,
                label = itemLabel(safeItems[index]),
                circularArtwork = circularArtwork,
                enabled = safeItems.size > 1 && !isSettling && !isDragging,
                onPrevious = { advance(-1) },
                onNext = { advance(1) },
                onOpen = onOpenSelected?.let { open -> { open(safeItems[index]) } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            val (stageWidth, stageHeight) = michiCardStageSize(
                maxWidth, maxHeight, circularArtwork, LocalDensity.current.fontScale,
            )
            val widthPx = with(LocalDensity.current) { stageWidth.toPx() }
            if (michiCardNeedsCompactFallback(stageWidth, stageHeight)) {
                CompactDeckFallback(
                    position = index + 1,
                    count = safeItems.size,
                    label = itemLabel(safeItems[index]),
                    circularArtwork = circularArtwork,
                    enabled = false,
                    onPrevious = null,
                    onNext = null,
                    onOpen = onOpenSelected?.let { open -> { open(safeItems[index]) } },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.width(stageWidth).height(stageHeight).clipToBounds()) {
                    Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp,
                        top = 8.dp, bottom = 32.dp)) {
                    val previewDirection = if (dragPx > 0f) -1 else 1
                    for (depth in (visibleCount - 1) downTo 0) {
                        val item = safeItems[michiCardPreviewIndex(index, depth, previewDirection, safeItems.size)]
                        val isFront = depth == 0
                        val cardKey = itemKey(item)
                        val style = michiCardDepthStyle(
                            depth, circularArtwork,
                            motionEnabled = !preferences.reducedMotion,
                            highContrast = preferences.highContrast,
                        )
                        key(cardKey) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .offset(x = style.offsetX, y = style.offsetY)
                                    .graphicsLayer {
                                        scaleX = style.scale
                                        scaleY = style.scale
                                        rotationZ = if (isFront && motionEnabled && widthPx > 0f) {
                                            ((if (isDragging) dragPx else swipeAnim.value) / widthPx * 10f)
                                                .coerceIn(-10f, 10f)
                                        } else style.rotation
                                        translationX = if (isFront && motionEnabled)
                                            if (isDragging) dragPx else swipeAnim.value else 0f
                                    }
                                    .zIndex((visibleCount - depth).toFloat())
                                    .then(
                                        if (isFront && safeItems.size > 1) {
                                            Modifier.pointerInput(frontKey, itemKeys, widthPx, motionEnabled) {
                                                val tracker = VelocityTracker()
                                                var gestureKey = frontKey
                                                var gestureKeys = itemKeys
                                                var gestureEpoch = selectionEpoch
                                                var thresholdNotified = false
                                                detectHorizontalDragGestures(
                                                    onDragStart = {
                                                        gestureKey = latestFront
                                                        gestureKeys = latestKeys
                                                        gestureEpoch = selectionEpoch
                                                        isDragging = !isSettling && widthPx > 0f
                                                        if (isDragging) {
                                                            tracker.resetTracking()
                                                            dragPx = 0f
                                                            thresholdNotified = false
                                                        }
                                                    },
                                                    onHorizontalDrag = { change, delta ->
                                                        if (!isDragging || isSettling || !michiCardGestureIsCurrent(
                                                                MichiCardGestureToken(gestureKey, gestureKeys, gestureEpoch),
                                                                latestFront, latestKeys, selectionEpoch,
                                                            )) return@detectHorizontalDragGestures
                                                        change.consume()
                                                        dragPx = (dragPx + delta).coerceIn(-widthPx, widthPx)
                                                        tracker.addPosition(change.uptimeMillis, change.position)
                                                        if (!thresholdNotified && abs(dragPx) >= widthPx * 0.20f) {
                                                            if (hapticsEnabled) {
                                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            }
                                                            thresholdNotified = true
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        if (!isDragging || isSettling) return@detectHorizontalDragGestures
                                                        if (!michiCardGestureIsCurrent(
                                                                MichiCardGestureToken(gestureKey, gestureKeys, gestureEpoch),
                                                                latestFront, latestKeys, selectionEpoch,
                                                            )) {
                                                            isDragging = false
                                                            dragPx = 0f
                                                            return@detectHorizontalDragGestures
                                                        }
                                                        val step = michiCardSwipeStep(dragPx, tracker.calculateVelocity().x, widthPx)
                                                        val released = dragPx
                                                        val token = MichiCardGestureToken(gestureKey, gestureKeys, selectionEpoch)
                                                        val capturedEpoch = token.epoch
                                                        isDragging = false
                                                        isSettling = true
                                                        transitionJob?.cancel()
                                                        transitionJob = scope.launch {
                                                            try {
                                                                if (!michiCardGestureIsCurrent(token, latestFront, latestKeys,
                                                                        selectionEpoch)) return@launch
                                                                swipeAnim.snapTo(if (motionEnabled) released else 0f)
                                                                if (step != 0) {
                                                                    if (motionEnabled) swipeAnim.animateTo(
                                                                        -step * widthPx * 1.12f,
                                                                        tween(
                                                                            durationMillis = MichiMotion.durationStandard,
                                                                            easing = MichiMotion.easingDecelerate,
                                                                        ),
                                                                    )
                                                                    if (michiCardGestureIsCurrent(token, latestFront,
                                                                            latestKeys, selectionEpoch)) {
                                                                        val next = michiCardStepKey(
                                                                            latestItems, gestureKey, latestItemKey, step,
                                                                        )
                                                                        swipeAnim.snapTo(0f)
                                                                        if (next != null) latestSelect(next)
                                                                    }
                                                                } else if (motionEnabled) {
                                                                    swipeAnim.animateTo(
                                                                        0f, tween(
                                                                            durationMillis = MichiMotion.durationFast,
                                                                            easing = MichiMotion.easingDecelerate,
                                                                        ),
                                                                    )
                                                                } else swipeAnim.snapTo(0f)
                                                            } catch (cancelled: CancellationException) {
                                                                throw cancelled
                                                            } finally {
                                                                if (capturedEpoch == selectionEpoch) {
                                                                    dragPx = 0f
                                                                    isSettling = false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        if (!isDragging) return@detectHorizontalDragGestures
                                                        isDragging = false
                                                        val capturedEpoch = ++selectionEpoch // Cancels any older commit token.
                                                        isSettling = true
                                                        transitionJob?.cancel()
                                                        transitionJob = scope.launch {
                                                            try {
                                                                if (motionEnabled) swipeAnim.animateTo(
                                                                        0f, tween(
                                                                            durationMillis = MichiMotion.durationFast,
                                                                            easing = MichiMotion.easingDecelerate,
                                                                        ),
                                                                    )
                                                                else swipeAnim.snapTo(0f)
                                                            } finally {
                                                                if (capturedEpoch == selectionEpoch) {
                                                                    dragPx = 0f
                                                                    isSettling = false
                                                                }
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                        } else Modifier,
                                    )
                                    .testTag(if (isFront) "michi_card_front" else "michi_card_back_$depth")
                                    .then(if (isFront) Modifier else Modifier.clearAndSetSemantics {}),
                            ) { cardContent(item, isFront) }
                        }
                    }
                    }
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            val compact = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.45f
            if (!navigationButtons) {
                Text(
                    "${index + 1} / ${safeItems.size}",
                    style = MichiTypography.metadata,
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "${index + 1} de ${safeItems.size} artistas"
                        customActions = listOf(
                            CustomAccessibilityAction("Artista siguiente") { advance(1); true },
                            CustomAccessibilityAction("Artista anterior") { advance(-1); true },
                        )
                    }.testTag("michi_card_position"),
                )
            } else if (maxWidth < 128.dp) {
                // Two 48dp touch targets cannot fit beside a counter on tiny windows.
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DeckNavigationButton(previous = true, compact = true,
                        circularArtwork = circularArtwork,
                        enabled = safeItems.size > 1 && !isSettling && !isDragging,
                        onClick = { advance(-1) })
                    DeckNavigationButton(previous = false, compact = true,
                        circularArtwork = circularArtwork,
                        enabled = safeItems.size > 1 && !isSettling && !isDragging,
                        onClick = { advance(1) })
                }
            } else Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                DeckNavigationButton(
                    previous = true,
                    compact = compact,
                    circularArtwork = circularArtwork,
                    enabled = safeItems.size > 1 && !isSettling && !isDragging,
                    onClick = { advance(-1) },
                )
                Text(
                    "${index + 1} / ${safeItems.size}",
                    style = MichiTypography.metadata,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "${index + 1} de ${safeItems.size} ${if (circularArtwork) "artistas" else "álbumes"}"
                    }.testTag("michi_card_position"),
                )
                DeckNavigationButton(
                    previous = false,
                    compact = compact,
                    circularArtwork = circularArtwork,
                    enabled = safeItems.size > 1 && !isSettling && !isDragging,
                    onClick = { advance(1) },
                )
            }
        }
        }
        }
    }
}
