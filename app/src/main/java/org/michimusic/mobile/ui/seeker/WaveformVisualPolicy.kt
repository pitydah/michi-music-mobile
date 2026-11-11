package org.michimusic.mobile.ui.seeker

import kotlin.math.max
import kotlin.math.pow

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

/**
 * Display-only dynamics. Compressed masters often sit in a narrow band, so a
 * robust percentile window plus a gentle contrast curve restores readable
 * variation without inventing peaks or turning silence into sound.
 *
 * The input still comes exclusively from decoded PCM. This function never
 * claims absolute loudness or dynamic-range certification.
 */
internal fun waveformRenderLevels(samples: List<Float>, maximumPoints: Int): List<Float> {
    val reduced = waveformBarsForWidth(samples, maximumPoints)
    if (reduced.size < 2) return reduced

    val finite = reduced.filter { it.isFinite() }.sorted()
    if (finite.isEmpty()) return reduced.map { 0f }
    val p10 = percentile(finite, 0.10f)
    val p90 = percentile(finite, 0.90f)
    val span = p90 - p10
    if (span < 0.015f) {
        return reduced.map { raw -> raw.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f }
    }

    return reduced.map { raw ->
        val value = raw.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (value <= 0.001f) return@map 0f
        val normalized = ((value - p10) / span).coerceIn(0f, 1f)
        (0.12f + 0.88f * normalized.pow(0.72f)).coerceIn(0f, 1f)
    }
}

/**
 * Continuous silhouette points: a bounded reduction, the same relative
 * dynamics, and a small three-tap smoothing that yields to the original peak
 * so transients survive. Bars and continuous share the PCM source but not the
 * geometry.
 */
internal fun waveformContinuousForWidth(samples: List<Float>, maximumPoints: Int): List<Float> {
    val levels = waveformRenderLevels(samples, maximumPoints)
    if (levels.size < 3) return levels
    return levels.mapIndexed { index, value ->
        val previous = levels[(index - 1).coerceAtLeast(0)]
        val next = levels[(index + 1).coerceAtMost(levels.lastIndex)]
        val smoothed = (previous + 2f * value + next) / 4f
        max(smoothed, value * 0.86f).coerceIn(0f, 1f)
    }
}

private fun percentile(sorted: List<Float>, fraction: Float): Float {
    if (sorted.isEmpty()) return 0f
    val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
    return sorted[index]
}
