package org.michimusic.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Local PCM PEQ is positioned AFTER RTP tap, so a personal headphone profile never reaches Michi Stream. */
@OptIn(UnstableApi::class)
class HeadphonePeqAudioProcessor : BaseAudioProcessor() {
    /** Verified output guard: evaluated on every audio buffer, not only when preferences change. */
    fun interface RouteGate { fun isAllowed(): Boolean }
    /** Atomically published control snapshot; no mix of old bands and new enable flag. */
    private data class PeqRequest(
        val bands: List<PeqBand>, val active: Boolean, val revision: Long,
        val gate: RouteGate,
        val prepared: Array<Array<Biquad>>?, val preparedRate: Int,
        val preparedChannels: Int, val headroomDb: Float,
    )
    @Volatile private var pending = PeqRequest(emptyList(), false, 0L, RouteGate { false }, null, 0, 0, 0f)
    /** Only profile publication / format negotiation take this lock, never queueInput. */
    private val configurationLock = Any()
    private var activeRevision = -1L
    @Volatile var framesProcessed = 0L
        private set
    @Volatile var processingRevision = -1L
        private set
    @Volatile var appliedHeadroomDb = 0f
        private set
    @Volatile var isApplied = false
        private set
    @Volatile var clippedSamples = 0L
        private set
    // These counters belong to one actual render revision and playback epoch.
    // No locks or allocations are added to the per-sample loop.
    @Volatile private var revisionFramesProcessed = 0L
    @Volatile private var revisionClipSamples = 0L
    @Volatile private var revisionObservedAtMs = 0L
    private val receiptEpoch = AtomicLong(0L)
    @Volatile private var renderedReceiptEpoch = -1L
    private var statisticsEpoch = -1L

    /** A seek, pause, track change or handoff cannot reuse old PCM evidence. */
    internal fun invalidateRenderEvidence() {
        receiptEpoch.incrementAndGet()
        renderedReceiptEpoch = -1L
    }

    internal fun currentRenderReceipt(): HeadphonePeqRenderReceipt? {
        val request = pending
        val epoch = receiptEpoch.get()
        val receipt = HeadphonePeqReceiptPolicy.current(
            request.revision, processingRevision, revisionFramesProcessed,
            revisionClipSamples, appliedHeadroomDb, revisionObservedAtMs, request.active,
            isApplied, request.gate.isAllowed())
        return receipt?.takeIf {
            pending === request && receiptEpoch.get() == epoch && renderedReceiptEpoch == epoch
        }
    }

    private var configured: List<PeqBand> = emptyList()
    @Volatile private var sampleRate = 0
    @Volatile private var channels = 0
    private var filters = emptyArray<Array<Biquad>>()
    private var oldFilters = emptyArray<Array<Biquad>>()
    private var oldPreamp = 1.0
    private var transitionFrames = 0
    private var transitionTotal = 1
    private var lastHeadroom = 0f

    fun setProfile(bands: List<PeqBand>, active: Boolean, gate: RouteGate) = synchronized(configurationLock) {
        // State is published atomically as a new immutable list. No locks on the audio thread.
        require(bands.size <= MICHI_PEQ_BAND_COUNT) { "PEQ profile exceeds supported filter count" }
        val validated = bands.map(PeqBand::safe)
        val previous = pending
        // Do not recompile/reset the bank on unrelated route-status or UI emissions.
        val shouldEnable = active && validated.isNotEmpty()
        if (previous.bands == validated && previous.active == shouldEnable && previous.gate === gate) return
        // Compile new coefficients and bank instances on the controlling thread,
        // NOT in queueInput on the real-time renderer. First unconfigured frame is
        // the only permitted fallback path when Media3 has not published its format.
        val rate = sampleRate
        val channelCount = channels
        val prepared = if (shouldEnable && rate > 0 && channelCount in 1..8) {
            Array(channelCount) { Array(validated.size) { n -> Biquad(validated[n], rate) } }
        } else null
        pending = PeqRequest(validated, shouldEnable, previous.revision + 1L, gate,
            prepared, rate, channelCount, peqHeadroomDb(validated))
        isApplied = false // pending revision is not yet a PCM receipt
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        synchronized(configurationLock) {
            sampleRate = inputAudioFormat.sampleRate
            channels = inputAudioFormat.channelCount
            // Rebuild the bank once per *format change* outside queueInput. Any
            // mismatched or unavailable bank in the real-time callback MUST bypass.
            val current = pending
            if (current.active && (current.preparedRate != sampleRate ||
                current.preparedChannels != channels || current.prepared == null)) {
                val compiled = if (sampleRate > 0 && channels in 1..8) {
                    Array(channels) { Array(current.bands.size) { n -> Biquad(current.bands[n], sampleRate) } }
                } else null
                pending = current.copy(prepared = compiled, preparedRate = sampleRate,
                    preparedChannels = channels, revision = current.revision + 1L)
            }
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        configured = emptyList()
        filters = emptyArray()
        oldFilters = emptyArray()
        transitionFrames = 0
        lastHeadroom = 0f
        activeRevision = -1L
        clippedSamples = 0L
        framesProcessed = 0L
        processingRevision = -1L
        revisionFramesProcessed = 0L
        revisionClipSamples = 0L
        revisionObservedAtMs = 0L
        statisticsEpoch = -1L
        invalidateRenderEvidence()
        appliedHeadroomDb = 0f
        isApplied = false
        // Flush retains the immutable coefficients but resets all IIR memories.
        pending.prepared?.forEach { channel -> channel.forEach(Biquad::reset) }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val encoding = inputAudioFormat.encoding
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> throw IllegalStateException("Unsupported PCM format: $encoding")
        }
        val bytesPerFrame = bytesPerSample * channels
        check(bytesPerFrame > 0 && inputBuffer.remaining() % bytesPerFrame == 0) {
            "Incomplete PCM frame: ${inputBuffer.remaining()} bytes, $bytesPerFrame bytes/frame"
        }
        val control = pending // one immutable revision per buffer
        val bufferEpoch = receiptEpoch.get()
        val target = control.bands
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        // A stale bank cannot execute with an old sample rate or channel layout.
        // Fail closed instead of allocating/rebuilding IIR state on the audio thread.
        val bankReady = control.prepared != null && control.preparedRate == sampleRate &&
            control.preparedChannels == channels && control.prepared.size == channels
        val active = control.active && bankReady && control.gate.isAllowed() &&
            sampleRate > 0 && channels in 1..8 && target.any { it.gainDb != 0f }
        if (!active) {
            output.put(inputBuffer) // byte-exact bypass, including float NaN payloads
            output.flip() // BaseAudioProcessor requires a readable output buffer
            configured = emptyList()
            filters = emptyArray()
            oldFilters = emptyArray()
            transitionFrames = 0
            activeRevision = control.revision
            isApplied = false
            processingRevision = -1L
            revisionFramesProcessed = 0L
            revisionClipSamples = 0L
            revisionObservedAtMs = 0L
            renderedReceiptEpoch = -1L
            appliedHeadroomDb = 0f
            return
        }
        if (activeRevision != control.revision || filters.size != channels) {
            oldFilters = if (configured.isNotEmpty() && filters.size == channels) filters else emptyArray()
            oldPreamp = 10.0.pow(-lastHeadroom / 20.0)
            transitionTotal = (sampleRate / 200).coerceAtLeast(1) // ~5ms at actual fs
            transitionFrames = if (oldFilters.isNotEmpty()) transitionTotal else 0
            filters = control.prepared ?: error("PEQ bank unexpectedly unavailable")
            configured = target
            activeRevision = control.revision
        }
        val gainDb = control.headroomDb
        val preamp = 10.0.pow(-gainDb / 20.0)
        appliedHeadroomDb = gainDb
        lastHeadroom = gainDb
        var channel = 0
        var clippedThisBuffer = 0L
        while (inputBuffer.hasRemaining()) {
            val value = when (encoding) {
                C.ENCODING_PCM_16BIT -> inputBuffer.order(ByteOrder.LITTLE_ENDIAN).short / 32768.0
                C.ENCODING_PCM_24BIT -> {
                    val b0 = inputBuffer.get().toInt() and 255
                    val b1 = inputBuffer.get().toInt() and 255
                    val b2 = inputBuffer.get().toInt()
                    ((b0 or (b1 shl 8) or (b2 shl 16)) shl 8 shr 8) / 8388608.0
                }
                C.ENCODING_PCM_32BIT -> inputBuffer.order(ByteOrder.LITTLE_ENDIAN).int / 2147483648.0
                else -> inputBuffer.order(ByteOrder.LITTLE_ENDIAN).float.toDouble()
            }
            var processed = value * preamp
            for (filter in filters[channel]) processed = filter.process(processed)
            if (transitionFrames > 0 && oldFilters.size == channels) {
                var old = value * oldPreamp
                for (filter in oldFilters[channel]) old = filter.process(old)
                val alpha = 1.0 - transitionFrames / transitionTotal.toDouble()
                processed = old * (1.0 - alpha) + processed * alpha
            }
            if (channel == channels - 1 && transitionFrames > 0) {
                transitionFrames--
                if (transitionFrames == 0) oldFilters = emptyArray()
            }
            if (!processed.isFinite()) processed = 0.0
            if (processed > 1.0 || processed < -1.0) clippedThisBuffer++
            val limited = processed.coerceIn(-1.0, 1.0)
            when (encoding) {
                C.ENCODING_PCM_16BIT -> output.putShort((limited * 32767.0).toInt().toShort())
                C.ENCODING_PCM_24BIT -> {
                    val n = (limited * 8388607.0).toInt()
                    output.put(n.toByte()); output.put((n shr 8).toByte()); output.put((n shr 16).toByte())
                }
                C.ENCODING_PCM_32BIT -> output.putInt((limited * 2147483647.0).toLong().toInt())
                else -> output.putFloat(limited.toFloat())
            }
            channel = (channel + 1) % channels
        }
        output.flip()
        clippedSamples += clippedThisBuffer
        framesProcessed += (output.limit() / bytesPerFrame).toLong()
        // Complete once per PCM buffer; never allocate or lock inside the sample loop.
        // A control change during rendering can never confirm the earlier revision.
        if (bufferEpoch == receiptEpoch.get() && pending === control && control.gate.isAllowed()) {
            if (processingRevision != control.revision || statisticsEpoch != bufferEpoch) {
                revisionFramesProcessed = 0L
                revisionClipSamples = 0L
                // Capture one timestamp at the first PCM buffer of this epoch/revision.
                revisionObservedAtMs = System.currentTimeMillis()
            }
            revisionFramesProcessed += (output.limit() / bytesPerFrame).toLong()
            revisionClipSamples += clippedThisBuffer
            processingRevision = control.revision
            statisticsEpoch = bufferEpoch
            isApplied = true
            renderedReceiptEpoch = bufferEpoch
        } else {
            // Do not revive evidence when hotplug or track change races queueInput.
            isApplied = false
            renderedReceiptEpoch = -1L
        }
    }

    private class Biquad(band: PeqBand, rate: Int) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        init {
            val f = band.frequencyHz.toDouble().coerceAtMost(rate * 0.46)
            val a = 10.0.pow(band.gainDb / 40.0)
            val w = 2.0 * PI * f / rate
            val alpha = sin(w) / (2.0 * band.q)
            val denom = 1.0 + alpha / a
            b0 = (1.0 + alpha * a) / denom
            b1 = (-2.0 * cos(w)) / denom
            b2 = (1.0 - alpha * a) / denom
            a1 = (-2.0 * cos(w)) / denom
            a2 = (1.0 - alpha / a) / denom
        }
        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
        fun process(x: Double): Double {
            val y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2
            x2=x1; x1=x; y2=y1; y1=if (y.isFinite()) y else 0.0
            return y1
        }
    }
}
