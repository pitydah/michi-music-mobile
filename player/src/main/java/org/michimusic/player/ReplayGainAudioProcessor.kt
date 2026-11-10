package org.michimusic.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow
import org.michimusic.core.models.Track

@OptIn(UnstableApi::class)
class ReplayGainAudioProcessor : BaseAudioProcessor() {

    /** Render-thread ramp; shared by 16/24/32-bit and floating PCM. */
    private val liveRamp = ReplayGainGainRamp()
    private val pcmReceipt = ReplayGainRenderEvidence()

    @Volatile
    private var volume = 1f

    // Never attribute DSP preamp gain to ReplayGain in Signal Truth.
    @Volatile
    private var replayGainVolume = 1f

    @Volatile
    private var dspPreampDb = 0f

    @Volatile
    private var effectiveDspPreampDb = 0f

    // A pre-PEQ amplifier cannot safely promise boost without verified source peak.
    // This explicit unity ceiling is enabled by the playback service, not by UI state.
    @Volatile private var protectUpstreamUnity = false
    @Volatile var safetyReductionDb: Float = 0f
        private set
    /** Saturation upstream of RTP and local PEQ, not a physical DAC measurement. */
    @Volatile var pcmClipSamples: Long = 0L
        private set

    @Volatile
    var isBypassed: Boolean = false
        set(value) {
            field = value
            applyGain(currentSong)
        }

    private var mode: ReplayGainMode = ReplayGainMode.OFF
    private var preAmp: ReplayGainPreAmp = ReplayGainPreAmp()
    private var currentSong: Track? = null
    private var isInAlbumContext = false

    val currentMode: ReplayGainMode get() = mode
    val currentPreAmp: ReplayGainPreAmp get() = preAmp
    val currentVolume: Float get() = if (isBypassed) 1f else volume
    val currentReplayGainVolume: Float get() = if (isBypassed) 1f else replayGainVolume
    val currentDspPreampDb: Float get() = if (isBypassed) 0f else effectiveDspPreampDb
    val isGainActive: Boolean get() = !isBypassed && mode != ReplayGainMode.OFF && replayGainVolume != 1f
    internal fun currentRenderReceipt(): ReplayGainRenderEvidence.Receipt? = pcmReceipt.snapshot()
    internal fun invalidateRenderEvidence() { pcmReceipt.invalidate() }

    /** Set by the service from the DSP state, never directly from a Composable. */
    fun setUpstreamUnityProtection(enabled: Boolean) {
        protectUpstreamUnity = enabled
        applyGain(currentSong)
    }

    fun setDspPreampDb(db: Float) {
        dspPreampDb = if (db.isFinite()) db.coerceIn(-12f, 12f) else 0f
        applyGain(currentSong)
    }

    fun configure(
        mode: ReplayGainMode,
        preAmp: ReplayGainPreAmp,
        inAlbumContext: Boolean = false,
    ) {
        this.mode = mode
        this.preAmp = preAmp
        this.isInAlbumContext = inAlbumContext
        applyGain(currentSong)
    }

    fun onSongChanged(song: Track?) {
        pcmClipSamples = 0L
        currentSong = song
        applyGain(song)
    }

    private fun applyGain(song: Track?) {
        // Invalidate the previous buffer receipt before publishing a new target.
        pcmReceipt.invalidate()
        if (song == null || isBypassed) {
            replayGainVolume = 1f
            effectiveDspPreampDb = 0f
            safetyReductionDb = 0f
            volume = 1f
            return
        }

        val trackGain = song.replayGainTrack
        val albumGain = song.replayGainAlbum

        val resolved = when (mode) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> trackGain.takeIf { !it.isNaN() } ?: albumGain.takeIf { !it.isNaN() }
            ReplayGainMode.ALBUM -> albumGain.takeIf { !it.isNaN() } ?: trackGain.takeIf { !it.isNaN() }
            ReplayGainMode.DYNAMIC -> {
                if (isInAlbumContext) {
                    albumGain.takeIf { !it.isNaN() } ?: trackGain.takeIf { !it.isNaN() }
                } else {
                    trackGain.takeIf { !it.isNaN() } ?: albumGain.takeIf { !it.isNaN() }
                }
            }
        }

        val replayGainDb = when {
            mode == ReplayGainMode.OFF -> 0f
            resolved != null -> resolved + preAmp.with
            else -> preAmp.without
        }
        val rgCalculated = 10f.pow(replayGainDb / 20f)
        replayGainVolume = if (rgCalculated.isFinite() && rgCalculated >= 0f) rgCalculated else 1f
        effectiveDspPreampDb = dspPreampDb
        val dspCalculated = 10f.pow(effectiveDspPreampDb / 20f)
        val combined = replayGainVolume * dspCalculated
        val nominal = if (combined.isFinite() && combined >= 0f) combined else 1f
        val safe = if (protectUpstreamUnity) nominal.coerceAtMost(1f) else nominal
        safetyReductionDb = if (safe > 0f && nominal > safe)
            (20f * kotlin.math.log10(safe / nominal)) else 0f
        volume = safe
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
    }

    override fun onFlush() {
        // Media3 seek/format reconfiguration is a discontinuity: no gain from
        // the prior AudioTrack epoch may be carried into the first new buffer.
        liveRamp.reset()
        pcmReceipt.invalidate()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val remaining = limit - pos
        if (remaining == 0) return

        val encoding = inputAudioFormat.encoding
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> throw IllegalStateException("Unsupported ReplayGain PCM encoding: $encoding")
        }
        val channels = inputAudioFormat.channelCount
        check(channels in 1..8 && remaining % (bytesPerSample * channels) == 0) {
            "Incomplete ReplayGain PCM frame: $remaining bytes, $channels channels"
        }
        val frameBytes = bytesPerSample * channels
        val buffer = replaceOutputBuffer(remaining)
        val renderRevision = pcmReceipt.captureRevision()
        val activeVol = if (isBypassed) 1f else volume
        liveRamp.prepare(activeVol, inputAudioFormat.sampleRate, protectUpstreamUnity)
        if (liveRamp.isSteadyUnity) {
            // Byte-exact bypass for PCM, including float NaN payloads.
            buffer.put(inputBuffer.slice())
            inputBuffer.position(limit)
            buffer.flip()
            pcmReceipt.confirm(renderRevision, remaining / frameBytes, 0, 1f)
            return
        }

        // Advance ONE envelope tick per multichannel PCM frame. Do not reset
        // the ramp on each Media3 buffer or each left/right channel. No PCM
        // coefficients, heap allocations or locks are created in this loop.
        for (frameStart in pos until limit step frameBytes) {
            val frameGain = liveRamp.nextFrameGain()
            val frameEnd = frameStart + frameBytes
            when (encoding) {
                C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, buffer, frameStart, frameEnd, frameGain)
                C.ENCODING_PCM_24BIT -> processPcm24(inputBuffer, buffer, frameStart, frameEnd, frameGain)
                C.ENCODING_PCM_32BIT -> processPcm32(inputBuffer, buffer, frameStart, frameEnd, frameGain)
                C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, buffer, frameStart, frameEnd, frameGain)
            }
        }
        inputBuffer.position(limit)
        buffer.flip()
        pcmReceipt.confirm(renderRevision, remaining / frameBytes, liveRamp.framesPending, liveRamp.renderedGain)
    }

    private fun processPcm16(
        input: ByteBuffer,
        output: ByteBuffer,
        pos: Int,
        limit: Int,
        vol: Float,
    ) {
        for (i in pos until limit step 2) {
            val sample = getLeShort(input, i)
            val raw = sample.toDouble() * vol
            if (raw < Short.MIN_VALUE.toDouble() || raw > Short.MAX_VALUE.toDouble()) pcmClipSamples++
            val scaled = raw
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            putLeShort(output, scaled)
        }
    }

    private fun processPcm24(
        input: ByteBuffer,
        output: ByteBuffer,
        pos: Int,
        limit: Int,
        vol: Float,
    ) {
        for (i in pos until limit step 3) {
            val b0 = input.get(i).toInt() and 0xFF
            val b1 = input.get(i + 1).toInt() and 0xFF
            val b2 = input.get(i + 2).toInt() // signed byte
            val sample = (b2 shl 16) or (b1 shl 8) or b0

            val raw = sample.toDouble() * vol
            if (raw < -8388608.0 || raw > 8388607.0) pcmClipSamples++
            val scaled = raw
                .toLong()
                .coerceIn(-8388608L, 8388607L)
                .toInt()

            output.put((scaled and 0xFF).toByte())
            output.put(((scaled shr 8) and 0xFF).toByte())
            output.put(((scaled shr 16) and 0xFF).toByte())
        }
    }

    private fun processPcm32(
        input: ByteBuffer,
        output: ByteBuffer,
        pos: Int,
        limit: Int,
        vol: Float,
    ) {
        for (i in pos until limit step 4) {
            val sample = (input.get(i + 3).toInt() shl 24) or
                ((input.get(i + 2).toInt() and 0xFF) shl 16) or
                ((input.get(i + 1).toInt() and 0xFF) shl 8) or
                (input.get(i).toInt() and 0xFF)

            val raw = sample.toDouble() * vol
            if (raw < Int.MIN_VALUE.toDouble() || raw > Int.MAX_VALUE.toDouble()) pcmClipSamples++
            val scaled = raw
                .toLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()

            output.put((scaled and 0xFF).toByte())
            output.put(((scaled shr 8) and 0xFF).toByte())
            output.put(((scaled shr 16) and 0xFF).toByte())
            output.put(((scaled shr 24) and 0xFF).toByte())
        }
    }

    private fun processPcmFloat(
        input: ByteBuffer,
        output: ByteBuffer,
        pos: Int,
        limit: Int,
        vol: Float,
    ) {
        for (i in pos until limit step 4) {
            val bits = (input.get(i + 3).toInt() shl 24) or
                ((input.get(i + 2).toInt() and 0xFF) shl 16) or
                ((input.get(i + 1).toInt() and 0xFF) shl 8) or
                (input.get(i).toInt() and 0xFF)
            val sample = java.lang.Float.intBitsToFloat(bits)
            val scaled = sample * vol
            if (scaled.isFinite() && (scaled < -1f || scaled > 1f)) pcmClipSamples++
            val outBits = java.lang.Float.floatToIntBits(scaled)

            output.put((outBits and 0xFF).toByte())
            output.put(((outBits shr 8) and 0xFF).toByte())
            output.put(((outBits shr 16) and 0xFF).toByte())
            output.put(((outBits shr 24) and 0xFF).toByte())
        }
    }

    private fun getLeShort(buffer: ByteBuffer, at: Int): Short =
        (buffer.get(at + 1).toInt().shl(8) or (buffer.get(at).toInt() and 0xFF)).toShort()

    private fun putLeShort(buffer: ByteBuffer, short: Short) {
        buffer.put(short.toByte())
        buffer.put(short.toInt().shr(8).toByte())
    }
}
