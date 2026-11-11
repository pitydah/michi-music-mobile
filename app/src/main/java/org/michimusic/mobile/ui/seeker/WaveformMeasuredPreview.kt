package org.michimusic.mobile.ui.seeker

/**
 * Immutable snapshot of measured PCM bins. Only [completedBins] are closed,
 * in chronological order; null means NOT ANALYSED and is never silence.
 * The decoder is the only producer, and the seeker only consumes these values.
 */
data class WaveformMeasuredPreview(
    val levels: List<Float>,
    val completedBins: Int,
    val totalBins: Int,
) {
    /** Fixed horizontal mapping; unknown windows are null, never made-up peaks. */
    fun barsForWidth(requestedBars: Int): List<Float?> {
        if (requestedBars <= 0 || totalBins < 16 || levels.size != totalBins ||
            completedBins !in 1..totalBins || levels.any { !it.isFinite() || it !in 0f..1f }
        ) return emptyList()
        val count = requestedBars.coerceIn(1, totalBins)
        return List(count) { bar ->
            val first = bar * totalBins / count
            val exclusive = (bar + 1) * totalBins / count
            if (exclusive <= completedBins && exclusive > first) {
                levels.subList(first, exclusive).maxOrNull()
            } else null
        }
    }
}
