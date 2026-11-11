package org.michimusic.mobile.ui.seeker

import kotlin.math.abs

/**
 * Presentation-only policy for the "Continuo" seeker.
 * Audio samples are supplied by the existing decoder; nothing is synthesized here.
 * The baseline MUST coincide with the parent's centre-aligned scrubber thumb.
 */
internal object ContinuousWaveformPresentation {
    internal data class Geometry(val baselinePx: Float, val peakRisePx: Float, val floorPx: Float)

    fun envelope(levels: List<Float>): List<Float> = levels.map { level ->
        if (level.isFinite()) abs(level).coerceIn(0f, 1f) else 0f
    }

    fun geometry(heightPx: Float, insetPx: Float, floorPx: Float): Geometry {
        if (!heightPx.isFinite() || heightPx <= 0f) return Geometry(0f, 0f, 0f)
        val baseline = heightPx * 0.5f
        val inset = insetPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val floor = floorPx.takeIf { it.isFinite() }?.coerceAtLeast(0f)
            ?.coerceAtMost((baseline - inset).coerceAtLeast(0f)) ?: 0f
        return Geometry(baseline, (baseline - inset - floor).coerceAtLeast(0f), floor)
    }

    fun contourY(normalizedLevel: Float, geometry: Geometry): Float {
        val value = if (normalizedLevel.isFinite()) normalizedLevel.coerceIn(0f, 1f) else 0f
        return geometry.baselinePx - geometry.floorPx - geometry.peakRisePx * value
    }
}
