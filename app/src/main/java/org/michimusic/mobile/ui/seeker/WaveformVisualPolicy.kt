package org.michimusic.mobile.ui.seeker

/** Renderer-only; never a sound-quality or signal-path inference. */
internal fun waveformBarsForWidth(samples: List<Float>, maximumBarCount: Int): List<Float> {
    if (samples.isEmpty() || maximumBarCount <= 0) return emptyList()
    val buckets = minOf(samples.size, maximumBarCount)
    return List(buckets) { index ->
        val first = index * samples.size / buckets
        val lastExclusive = (index + 1) * samples.size / buckets
        var peak = 0f
        for (sampleIndex in first until lastExclusive) {
            val v = samples[sampleIndex]
            if (v.isFinite()) peak = maxOf(peak, v.coerceIn(0f, 1f))
        }
        peak
    }
}
