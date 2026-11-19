package org.michimusic.mobile.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.UI8VisualSpec
import kotlin.math.abs

/**
 * UI8 minimal MIT SwipingCards-compatible behavior for the app's Kotlin 2.1 toolchain.
 * Upstream: https://github.com/makzimi/SwipingCards (v0.3.0).
 * Keep caller data immutable. A swipe only rotates the local, keyed visual order.
 * Copyright attribution and license: assets/third_party/swipingcards_LICENSE.txt.
 */
@Composable
internal fun <T> PlaylistSwipingDeck(
    cards: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    maxVisibleCards: Int = 3,
    isSwipeEnabled: Boolean = true,
    cardContent: @Composable (T) -> Unit,
) {
    require(maxVisibleCards >= 1)
    // The caller owns immutable playlist identities and their chosen sort order.
    val ids = remember(cards) { cards.map(key).distinct() }
    val byKey = remember(cards) { cards.associateBy(key) }
    val indexById = remember(ids) { ids.withIndex().associate { (i, id) -> id to (i + 1) } }
    val order = remember { mutableStateListOf<Any>().also { it.addAll(ids) } }
    var lastExternalIds by remember { mutableStateOf(ids) }
    var activeSettleJob by remember { mutableStateOf<Job?>(null) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val latestSwipeEnabled by rememberUpdatedState(isSwipeEnabled)

    LaunchedEffect(ids) {
        if (lastExternalIds != ids) {
            // A new playlist, deletion or external sort revokes in-flight gestures.
            // An obsolete animation must never rotate the new deck or leave it offset.
            activeSettleJob?.cancel()
            activeSettleJob = null
            settling = false
            offsetX = 0f
            offsetY = 0f
            order.clear()
            order.addAll(ids)
            lastExternalIds = ids
        }
    }

    val visualPreferences = LocalVisualPreferences.current
    val reducedMotion = visualPreferences.reducedMotion
    val latestHapticsEnabled by rememberUpdatedState(visualPreferences.hapticsEnabled)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current.density
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)
        val visible = order.take(maxVisibleCards).filter { it in byKey }

        fun settle(frontId: Any, commit: Boolean, backward: Boolean = false) {
            if (settling) return
            settling = true
            val vx = offsetX
            val vy = offsetY
            val acceptedCommit = commit && lastExternalIds == ids &&
                PlaylistDeckPolicy.canAdvance(order, frontId, isSwipeEnabled)
            // Assign the Job before it can run: reduced-motion completion can be immediate.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!reducedMotion) {
                        val finalX = if (acceptedCommit) {
                            if (abs(vx) >= abs(vy))
                                (if (vx == 0f && backward) -1.25f else if (vx >= 0f) 1.25f else -1.25f) * widthPx
                            else vx
                        } else 0f
                        val finalY = if (acceptedCommit && abs(vy) > abs(vx))
                            (if (vy >= 0f) 1.25f else -1.25f) * heightPx
                            else if (acceptedCommit) vy else 0f
                        coroutineScope {
                            launch {
                                val anim = Animatable(vx)
                                if (acceptedCommit) anim.animateTo(finalX, tween(UI8VisualSpec.deckExitMillis)) { offsetX = value }
                                else anim.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) { offsetX = value }
                            }
                            launch {
                                val anim = Animatable(vy)
                                if (acceptedCommit) anim.animateTo(finalY, tween(UI8VisualSpec.deckExitMillis)) { offsetY = value }
                                else anim.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) { offsetY = value }
                            }
                        }
                    }
                    // The action can become disabled (e.g. playlist overflow menu opens)
                    // while the outgoing card is still animating. Recheck before commit.
                    if (acceptedCommit && latestSwipeEnabled && lastExternalIds == ids &&
                        PlaylistDeckPolicy.canAdvance(order, frontId, true)
                    ) {
                        val next = if (backward) PlaylistDeckPolicy.retreat(order, frontId, true)
                            else PlaylistDeckPolicy.advance(order, frontId, true)
                        order.clear()
                        order.addAll(next)
                        if (latestHapticsEnabled) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                } finally {
                    // A cancelled old job must not overwrite the offsets of a new one.
                    if (activeSettleJob === coroutineContext[Job]) {
                        offsetX = 0f
                        offsetY = 0f
                        settling = false
                        activeSettleJob = null
                    }
                }
            }
            activeSettleJob = job
            job.start()
        }

        visible.asReversed().forEachIndexed { reverseIndex, id ->
            val position = visible.size - 1 - reverseIndex
            val item = byKey[id] ?: return@forEachIndexed
            androidx.compose.runtime.key(id) {
                val isFront = position == 0
                val canDrag = isFront && !settling && lastExternalIds == ids &&
                    PlaylistDeckPolicy.canAdvance(order, id, isSwipeEnabled)
                val pull = (abs(offsetX) / widthPx).coerceIn(0f, 1f)
                val layer = Modifier
                    .fillMaxSize()
                    .zIndex((maxVisibleCards - position).toFloat())
                    .graphicsLayer {
                        val depth = position.toFloat()
                        scaleX = 1f - depth * 0.055f + if (!isFront) pull * 0.01f else 0f
                        scaleY = 1f - depth * 0.055f + if (!isFront) pull * 0.01f else 0f
                        translationY = if (isFront) offsetY else depth * 15.dp.toPx()
                        translationX = if (isFront) offsetX else depth * 5.dp.toPx()
                        rotationY = if (isFront && !reducedMotion)
                            -(offsetX / widthPx).coerceIn(-1f, 1f) * 24f else 0f
                        rotationZ = if (isFront && !reducedMotion)
                            (offsetX / widthPx).coerceIn(-1f, 1f) * 7f else 0f
                        cameraDistance = 12f * density
                    }
                    .then(if (canDrag) Modifier.pointerInput(id, widthPx, heightPx) {
                        // Do not steal vertical swipes from the scrollable playlist screen.
                        // A click on Play/Options stays a click until horizontal slop wins.
                        detectHorizontalDragGestures(
                            onDragEnd = { settle(id, PlaylistDeckPolicy.shouldCommit(offsetX, 0f, widthPx, heightPx)) },
                            onDragCancel = { settle(id, false) },
                        ) { change, dragX ->
                            change.consume()
                            offsetX = (offsetX + dragX).coerceIn(-widthPx * 1.4f, widthPx * 1.4f)
                        }
                    } else Modifier)
                    .then(if (isFront) Modifier
                        .testTag("playlist_swiping_front")
                        .semantics {
                            stateDescription = "Playlist ${indexById[id] ?: 1} de ${ids.size}"
                            if (canDrag) customActions = listOf(
                                CustomAccessibilityAction("Siguiente playlist") {
                                    if (!latestSwipeEnabled || settling || lastExternalIds != ids ||
                                        !PlaylistDeckPolicy.canAdvance(order, id, true)
                                    ) false else { settle(id, true); true }
                                },
                                CustomAccessibilityAction("Playlist anterior") {
                                    if (!latestSwipeEnabled || settling || lastExternalIds != ids ||
                                        !PlaylistDeckPolicy.canAdvance(order, id, true)
                                    ) false else { settle(id, true, backward = true); true }
                                },
                            )
                        }
                        // Back cards are graphics only. Consume their taps at Initial pass
                        // before child clickable modifiers can open the wrong playlist.
                        else Modifier
                            .pointerInput(id) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                            .clearAndSetSemantics { })
                Box(modifier = layer) { cardContent(item) }
            }
        }
    }
}
