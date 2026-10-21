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

    @Volatile
    private var volume = 1f

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

    val currentVolume: Float get() = if (isBypassed) 1f else volume
    val isGainActive: Boolean get() = !isBypassed && mode != ReplayGainMode.OFF && volume != 1f

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
        currentSong = song
        applyGain(song)
    }

    private fun applyGain(song: Track?) {
        if (song == null || mode == ReplayGainMode.OFF || isBypassed) {
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

        val amplified = if (resolved != null) resolved + preAmp.with else preAmp.without
        val calculated = 10f.pow(amplified / 20f)
        volume = if (calculated.isNaN() || calculated.isInfinite() || calculated < 0f) 1f else calculated
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

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val remaining = limit - pos
        if (remaining == 0) return

        val buffer = replaceOutputBuffer(remaining)

        val activeVol = if (isBypassed) 1f else volume
        if (activeVol == 1f) {
            buffer.put(inputBuffer.slice())
            inputBuffer.position(limit)
            buffer.flip()
            return
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, buffer, pos, limit, activeVol)
            C.ENCODING_PCM_24BIT -> processPcm24(inputBuffer, buffer, pos, limit, activeVol)
            C.ENCODING_PCM_32BIT -> processPcm32(inputBuffer, buffer, pos, limit, activeVol)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, buffer, pos, limit, activeVol)
            else -> {
                buffer.put(inputBuffer.slice())
            }
        }

        inputBuffer.position(limit)
        buffer.flip()
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
            val scaled = (sample * vol)
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

            val scaled = (sample.toDouble() * vol)
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

            val scaled = (sample.toDouble() * vol)
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
