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
        rotationY = (offset * 24f).coerceIn(-30f, 30f),
        scale = 1f - distance * 0.13f,
        opacity = (1f - distance * 0.17f).coerceAtLeast(0.68f),
        raised = distance < 0.5f,
    )
}

/** CoverFlow selection and perspective are bounded by the real phone viewport. */
internal fun coverFlowPageWidthDp(viewportDp: Float): Float {
    if (!viewportDp.isFinite() || viewportDp <= 0f) return 0f
    return (viewportDp * 0.68f).coerceIn(140f, 272f).coerceAtMost(viewportDp)
}

/** Valid for empty, shrinking or reconstructed album libraries. */
internal fun safeCoverFlowIndex(requested: Int, itemCount: Int): Int =
    if (itemCount <= 0) 0 else requested.coerceIn(0, itemCount - 1)

/** Give the taller focused cover a measured stage, never a hard-coded 260 dp crop. */
internal fun coverFlowStageHeightDp(viewportDp: Float): Float =
    if (!viewportDp.isFinite() || viewportDp <= 0f) 0f
    else coverFlowPageWidthDp(viewportDp) + 28f

/** A quiet orientation cue; hide it when there is just one album. */
internal fun coverFlowPositionLabel(index: Int, total: Int): String? =
    if (total <= 1 || index !in 0 until total) null
    else "${index + 1} de $total"

/** Only genuinely duplicate identities need a position suffix. A reordering must not
 *  invalidate the keys for ordinary covers (and therefore reset their pager state). */
internal fun coverFlowPageKey(albumId: String, page: Int): String = "$albumId#$page"

internal fun coverFlowStablePageKeys(albumIds: List<String>): List<String> {
    val counts = albumIds.groupingBy { it }.eachCount()
    return albumIds.mapIndexed { index, id ->
        if (counts[id] == 1) id else coverFlowPageKey(id, index)
    }
}
