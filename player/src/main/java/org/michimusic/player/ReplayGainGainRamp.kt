package org.michimusic.player

/**
 * Render-thread-only amplitude ramp for a new ReplayGain/DSP-preamp target.
 * The control thread publishes its target through ReplayGainAudioProcessor.volume;
 * no allocation, locks or preference access occur for individual PCM frames.
 *
 * One step means one multichannel frame, not one channel sample.  A first
 * buffer (or Media3 flush) starts directly at the requested level because it
 * contains no continuous audible predecessor. Subsequent retargets fade in
 * linear amplitude over ~10 ms, including bypass -> unity.
 */
internal class ReplayGainGainRamp(private val rampMs: Int = 10) {
    private var current: Float = Float.NaN
    private var target: Float = 1f
    private var increment: Float = 0f
    private var remainingFrames: Int = 0

    fun reset() {
        current = Float.NaN
        target = 1f
        increment = 0f
        remainingFrames = 0
    }

    fun prepare(requested: Float, sampleRate: Int, unityCeiling: Boolean = false) {
        val safe = requested.takeIf { it.isFinite() && it >= 0f } ?: 1f
        val next = if (unityCeiling) safe.coerceAtMost(1f) else safe
        if (!current.isFinite()) {
            current = next
            target = next
            increment = 0f
            remainingFrames = 0
            return
        }
        // Never continue emitting a pre-protection boost after the RTP safety
        // ceiling is enabled. Acoustic continuity cannot override this guard.
        val wasClamped = unityCeiling && current > 1f
        if (wasClamped) current = 1f
        if (next == target && !wasClamped) return
        target = next
        val count = ((sampleRate.coerceAtLeast(1).toLong() * rampMs.coerceIn(1, 50)) / 1000L)
            .coerceIn(1L, 3840L).toInt()
        remainingFrames = count
        increment = (target - current) / count
    }

    val isSteadyUnity: Boolean
        get() = remainingFrames == 0 && current == 1f

    val renderedGain: Float get() = if (current.isFinite()) current else 1f
    val framesPending: Int get() = remainingFrames

    fun nextFrameGain(): Float {
        if (remainingFrames > 0) {
            current += increment
            remainingFrames--
            if (remainingFrames == 0) current = target
        }
        return current
    }
}
