package org.michimusic.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * AudioProcessor that taps into the decoded PCM audio stream from ExoPlayer
 * and passes raw PCM chunks to a registered listener (such as RtpAudioSender).
 *
 * Exposes the active PCM format (sampleRate, channelCount, bitDepth, codec)
 * configured by the playback engine.
 */
@OptIn(UnstableApi::class)
class RtpPcmAudioTap : BaseAudioProcessor() {

    @Volatile
    var pcmChunkListener: ((ByteArray) -> Unit)? = null

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var isPaused: Boolean = false

    @Volatile
    var muteLocalOutput: Boolean = false

    @Volatile
    var currentSampleRate: Int = 48000
        private set

    @Volatile
    var currentChannelCount: Int = 2
        private set

    @Volatile
    var currentEncoding: Int = C.ENCODING_PCM_16BIT
        private set

    val bitDepth: Int
        get() = when (currentEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

    val codec: String
        get() = when (currentEncoding) {
            C.ENCODING_PCM_16BIT -> "pcm_s16le"
            C.ENCODING_PCM_24BIT -> "pcm_s24le"
            C.ENCODING_PCM_32BIT -> "pcm_s32le"
            C.ENCODING_PCM_FLOAT -> "pcm_f32le"
            else -> "pcm_s16le"
        }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            currentSampleRate = inputAudioFormat.sampleRate
            currentChannelCount = inputAudioFormat.channelCount
            currentEncoding = inputAudioFormat.encoding
            return inputAudioFormat
        }
        throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (isEnabled && !isPaused && pcmChunkListener != null) {
            val pcmBytes = ByteArray(remaining)
            val originalPos = inputBuffer.position()
            inputBuffer.get(pcmBytes)
            inputBuffer.position(originalPos)
            pcmChunkListener?.invoke(pcmBytes)
        }

        val buffer = replaceOutputBuffer(remaining)
        if (muteLocalOutput && isEnabled) {
            // Fill with silence for local speaker while streaming to remote receiver
            for (i in 0 until remaining) {
                buffer.put(0.toByte())
            }
            inputBuffer.position(inputBuffer.limit())
        } else {
            buffer.put(inputBuffer)
        }
        buffer.flip()
    }
}
