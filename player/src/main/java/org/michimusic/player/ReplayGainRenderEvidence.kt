package org.michimusic.player

import java.util.concurrent.atomic.AtomicLong

/**
 * Causal receipt for PCM buffers accepted by ReplayGainAudioProcessor.
 * A receipt describes rendered PCM in the phone's software pipeline, never DAC output.
 * Control changes/Media3 flush invalidate earlier evidence before new gain is published.
 */
internal class ReplayGainRenderEvidence {
    internal data class Receipt(
        val revision: Long,
        val processedFrames: Long,
        val rampFramesRemaining: Int,
        val lastFrameGain: Float,
    )

    private val requestedRevision = AtomicLong(0L)
    @Volatile private var lastReceipt: Receipt? = null

    fun invalidate() {
        requestedRevision.incrementAndGet()
        lastReceipt = null
    }

    fun captureRevision(): Long = requestedRevision.get()

    /** Called once per completed PCM buffer, not for each sample. */
    fun confirm(revision: Long, frames: Int, remaining: Int, gain: Float) {
        if (frames <= 0 || remaining < 0 || !gain.isFinite() || gain < 0f) return
        if (revision != requestedRevision.get()) return
        val previous = lastReceipt
        // Once settled, retain one immutable proof without allocating on each audio buffer.
        if (previous?.revision == revision && previous.rampFramesRemaining == 0) return
        val total = if (previous?.revision == revision) {
            (previous.processedFrames + frames.toLong()).coerceAtLeast(previous.processedFrames)
        } else frames.toLong()
        val next = Receipt(revision, total, remaining, gain)
        // A change arriving mid-buffer must not revive the old revision.
        if (revision == requestedRevision.get()) lastReceipt = next
    }

    /** Fail closed if a control change races with observation. */
    fun snapshot(): Receipt? {
        val before = requestedRevision.get()
        val receipt = lastReceipt
        val after = requestedRevision.get()
        return receipt?.takeIf { it.revision == before && before == after && it.processedFrames > 0L }
    }
}
