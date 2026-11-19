package org.michimusic.mobile.ui.screens

import kotlin.math.abs

/** Pure contracts for the circular playlist deck; no playback or data mutation. */
internal object PlaylistDeckPolicy {
    private const val horizontalCommitFraction = 0.20f

    fun shouldCommit(offsetX: Float, offsetY: Float, widthPx: Float, heightPx: Float): Boolean {
        // Zero-size containers and invalid pointer coordinates must not rotate cards.
        if (!offsetX.isFinite() || !offsetY.isFinite() ||
            !widthPx.isFinite() || !heightPx.isFinite() ||
            widthPx <= 0f || heightPx <= 0f
        ) return false
        // Vertical scroll belongs to the parent catalog; reject diagonal ambiguity.
        return abs(offsetX) >= horizontalCommitFraction * widthPx &&
            abs(offsetX) >= abs(offsetY) * 1.25f
    }

    fun <K> canAdvance(order: List<K>, expectedFront: K, enabled: Boolean): Boolean =
        enabled && order.size > 1 && order.firstOrNull() == expectedFront

    fun <K> advance(order: List<K>, expectedFront: K, enabled: Boolean): List<K> =
        if (canAdvance(order, expectedFront, enabled)) order.drop(1) + expectedFront else order

    /** Accessibility reverse action; never mutates source or bypasses current-front checks. */
    fun <K> retreat(order: List<K>, expectedFront: K, enabled: Boolean): List<K> =
        if (canAdvance(order, expectedFront, enabled)) listOf(order.last()) + order.dropLast(1) else order
}
