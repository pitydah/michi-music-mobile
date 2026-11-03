package org.michimusic.mobile.ui.components

import org.michimusic.player.PeqBand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Frequency-domain simulation of the SAME peaking biquad topology as HeadphonePeqAudioProcessor.
 * This is a calculated electrical transfer function, NOT a physical acoustic measurement.
 * [referenceSampleRate] must be clearly identified by the UI as a reference, not a measured route.
 */
data class PeqResponsePoint(val frequencyHz: Float, val gainDb: Float)

fun calculatedPeqResponse(
    bands: List<PeqBand>,
    referenceSampleRate: Int = 48_000,
    pointCount: Int = 97,
): List<PeqResponsePoint> {
    if (referenceSampleRate !in 8_000..384_000 || pointCount !in 16..512) return emptyList()
    val nyquistSafeMax = min(20_000.0, referenceSampleRate * 0.46)
    if (nyquistSafeMax <= 20.0) return emptyList()
    val filters = bands.take(6).map { band ->
        val safe = band.safe()
        val f = min(safe.frequencyHz.toDouble(), referenceSampleRate * 0.46)
        val a = 10.0.pow(safe.gainDb.toDouble() / 40.0)
        val w = 2.0 * PI * f / referenceSampleRate
        val alpha = sin(w) / (2.0 * safe.q)
        val denominator = 1.0 + alpha / a
        doubleArrayOf(
            (1.0 + alpha * a) / denominator,
            (-2.0 * cos(w)) / denominator,
            (1.0 - alpha * a) / denominator,
            (-2.0 * cos(w)) / denominator,
            (1.0 - alpha / a) / denominator,
        )
    }
    val logSpan = ln(nyquistSafeMax / 20.0)
    return List(pointCount) { index ->
        val frequency = 20.0 * kotlin.math.exp(logSpan * index / (pointCount - 1))
        val w = 2.0 * PI * frequency / referenceSampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)
        val totalDb = filters.sumOf { co ->
            val numeratorReal = co[0] + co[1] * c1 + co[2] * c2
            val numeratorImag = -co[1] * s1 - co[2] * s2
            val denomReal = 1.0 + co[3] * c1 + co[4] * c2
            val denomImag = -co[3] * s1 - co[4] * s2
            val numeratorSquared = numeratorReal * numeratorReal + numeratorImag * numeratorImag
            val denominatorSquared = denomReal * denomReal + denomImag * denomImag
            10.0 * kotlin.math.log10(numeratorSquared.coerceAtLeast(1e-24) / denominatorSquared.coerceAtLeast(1e-24))
        }
        PeqResponsePoint(frequency.toFloat(), totalDb.takeIf(Double::isFinite)?.toFloat() ?: 0f)
    }
}
