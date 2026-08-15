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
 */
@OptIn(UnstableApi::class)
class RtpPcmAudioTap : BaseAudioProcessor() {

    @Volatile
    var pcmChunkListener: ((ByteArray) -> Unit)? = null

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var muteLocalOutput: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            return inputAudioFormat
        }
        throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (isEnabled && pcmChunkListener != null) {
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
