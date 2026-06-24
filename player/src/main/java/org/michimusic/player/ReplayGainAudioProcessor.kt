package org.michimusic.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.pow
import org.michimusic.core.models.Track

class ReplayGainAudioProcessor : BaseAudioProcessor() {

    private var volume = 1f
        set(value) {
            field = value
            flush()
        }

    private var mode: ReplayGainMode = ReplayGainMode.OFF
    private var preAmp: ReplayGainPreAmp = ReplayGainPreAmp()
    private var currentSong: Track? = null
    private var isInAlbumContext = false

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
        if (song == null || mode == ReplayGainMode.OFF) {
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
        volume = 10f.pow(amplified / 20f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT) {
            return inputAudioFormat
        }
        throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val buffer = replaceOutputBuffer(limit - pos)

        if (volume == 1f) {
            buffer.put(inputBuffer.slice())
        } else {
            for (i in pos until limit step 2) {
                var sample = getLeShort(inputBuffer, i)
                sample = (sample * volume)
                    .toInt()
                    .coerceAtLeast(Short.MIN_VALUE.toInt())
                    .coerceAtMost(Short.MAX_VALUE.toInt())
                    .toShort()
                putLeShort(buffer, sample)
            }
        }

        inputBuffer.position(limit)
        buffer.flip()
    }

    private fun getLeShort(buffer: ByteBuffer, at: Int): Short =
        (buffer.get(at + 1).toInt().shl(8) or (buffer.get(at).toInt() and 0xFF)).toShort()

    private fun putLeShort(buffer: ByteBuffer, short: Short) {
        buffer.put(short.toByte())
        buffer.put(short.toInt().shr(8).toByte())
    }
}
