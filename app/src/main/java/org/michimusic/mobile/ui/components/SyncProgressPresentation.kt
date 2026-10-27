package org.michimusic.mobile.ui.components

/** A null fraction means the server has not announced a total; never invent 0%/5%. */
data class SyncProgressPresentation(val fraction: Float?, val detail: String)

fun syncProgressPresentation(completed: Int, total: Int): SyncProgressPresentation {
    val done = completed.coerceAtLeast(0)
    if (total <= 0) return SyncProgressPresentation(null, "$done canciones transferidas · total pendiente")
    val clamped = done.coerceAtMost(total)
    val fraction = clamped.toFloat() / total.toFloat()
    return SyncProgressPresentation(fraction, "$clamped de $total canciones (${(fraction * 100).toInt()}%)")
}
