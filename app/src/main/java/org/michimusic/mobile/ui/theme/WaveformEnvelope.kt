package org.michimusic.mobile.ui.theme

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * UI5: a bounded reducer for REAL decoded PCM samples (mono or interleaved).
 * This class does not read files, decode codecs, fabricate a waveform or own playback.
 * Feed decoder-owned frames in temporal order on a background dispatcher.
 */
class WaveformEnvelope(
    expectedFrames: Long,
    private val channels: Int,
    private val bins: Int = 256,
) {
    init {
        require(expectedFrames > 0L) { "Expected decoded frame count is required" }
        require(channels in 1..8) { "Unsupported channel count" }
        require(bins in 16..1024) { "Invalid seeker bin count" }
    }

    private val targetFrames = expectedFrames
    private val framesPerBin = max(1L, ceil(expectedFrames.toDouble() / bins).toLong())
    private val peaks = FloatArray(bins)
    private val energy = DoubleArray(bins)
    private val counts = IntArray(bins)
    private var decodedFrames = 0L
    private var invalid = false

    /** Entire interleaved frames only; empty buffers are permitted. */
    fun acceptInterleaved(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= samples.size && length <= samples.size - offset)
        require(length % channels == 0) { "Incomplete interleaved PCM frame" }
        val limit = offset + length
        var i = offset
        while (i < limit) {
            val bin = (decodedFrames / framesPerBin).coerceAtMost((bins - 1).toLong()).toInt()
            var peak = 0f
            var sumSquare = 0.0
            for (c in 0 until channels) {
                val sample = samples[i + c]
                if (!sample.isFinite()) {
                    invalid = true
                } else {
                    val limited = sample.coerceIn(-1f, 1f)
                    peak = max(peak, abs(limited))
                    sumSquare += limited.toDouble() * limited.toDouble()
                }
            }
            peaks[bin] = max(peaks[bin], peak)
            energy[bin] += sumSquare / channels
            if (counts[bin] < Int.MAX_VALUE) counts[bin]++
            decodedFrames++
            i += channels
        }
    }

    /** Only bins whose complete frame window has been decoded may be published. */
    data class CompletedPreview(val levels: List<Float>, val completedBins: Int, val totalBins: Int)

    fun completedPreview(): CompletedPreview? {
        if (invalid || decodedFrames == 0L) return null
        if (decodedFrames.toDouble() > targetFrames.toDouble() * 1.25) return null
        // Exclude the still-open window AND final bin until the decoder reaches EOS.
        val finished = (decodedFrames / framesPerBin).coerceAtMost((bins - 1).toLong()).toInt()
        if (finished == 0) return null
        val values = List(bins) { i ->
            if (i >= finished || counts[i] == 0) 0f else {
                val rms = sqrt(energy[i] / counts[i]).toFloat().coerceIn(0f, 1f)
                (0.65f * peaks[i] + 0.35f * rms).coerceIn(0f, 1f)
            }
        }
        return CompletedPreview(values, finished, bins)
    }

    /**
     * A waveform only exists after real PCM was processed. Never extrapolate empty
     * bins or return placeholders. A corrupt decoder chunk invalidates the result.
     * For a successful decode, return per-bin hybrid peak/RMS amplitudes [0,1].
     */
    fun finish(): List<Float>? {
        if (invalid || decodedFrames == 0L) return null
        // A clearly wrong duration makes the x-axis temporally misleading. Small
        // container/sample-padding tolerances remain valid; large overruns do not.
        if (decodedFrames.toDouble() > targetFrames.toDouble() * 1.25) return null
        // Reject materially incomplete decodes; silent padding would invent evidence.
        if (decodedFrames.toDouble() < targetFrames * 0.90) return null
        val populated = counts.indexOfLast { it > 0 } + 1
        if (populated < 16) return null
        return List(populated) { i ->
            val rms = sqrt(energy[i] / counts[i]).toFloat().coerceIn(0f, 1f)
            (0.65f * peaks[i] + 0.35f * rms).coerceIn(0f, 1f)
        }
    }
}
