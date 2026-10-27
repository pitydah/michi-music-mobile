package org.michimusic.mobile.ui.coverflow

import kotlin.math.abs

/** Pure visual math: keep rendering decisions testable without a Compose runtime. */
internal data class CoverFlowGeometry(
    val rotationY: Float,
    val scale: Float,
    val opacity: Float,
    val raised: Boolean,
)

internal fun coverFlowGeometry(offset: Float, reducedMotion: Boolean): CoverFlowGeometry {
    if (reducedMotion || !offset.isFinite()) {
        return CoverFlowGeometry(rotationY = 0f, scale = 1f, opacity = 1f, raised = true)
    }
    val distance = abs(offset).coerceIn(0f, 1.5f)
    return CoverFlowGeometry(
        rotationY = (offset * 38f).coerceIn(-45f, 45f),
        scale = 1f - distance * 0.16f,
        opacity = (1f - distance * 0.23f).coerceAtLeast(0.55f),
        raised = distance < 0.5f,
    )
}

/** Valid for empty, shrinking or reconstructed album libraries. */
internal fun safeCoverFlowIndex(requested: Int, itemCount: Int): Int =
    if (itemCount <= 0) 0 else requested.coerceIn(0, itemCount - 1)
