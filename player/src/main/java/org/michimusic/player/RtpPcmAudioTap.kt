package org.michimusic.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.models.PcmFormat
import java.nio.ByteBuffer

/**
 * AudioProcessor that taps into the decoded PCM audio stream from ExoPlayer
 * and passes raw PCM chunks to a registered listener (such as RtpAudioSender).
 *
 * Exposes the active PCM format via [currentFormat] (starts as null / UNKNOWN until Media3 configures it).
 * Enforces atomic format transitions via [isRenegotiating] to guarantee zero bytes of a new format
 * reach a sender configured for a previous format.
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
    var isRenegotiating: Boolean = false

    @Volatile
    var muteLocalOutput: Boolean = false

    private val _currentFormat = MutableStateFlow<PcmFormat?>(null)
    val currentFormat: StateFlow<PcmFormat?> = _currentFormat.asStateFlow()

    val activeFormat: PcmFormat? get() = _currentFormat.value

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            
            val bitDepth = when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                else -> 16
            }

            val codec = when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> "pcm_s16le"
                C.ENCODING_PCM_24BIT -> "pcm_s24le"
                C.ENCODING_PCM_32BIT -> "pcm_s32le"
                C.ENCODING_PCM_FLOAT -> "pcm_f32le"
                else -> "pcm_s16le"
            }

            val newFormat = PcmFormat(
                sampleRate = inputAudioFormat.sampleRate,
                channels = inputAudioFormat.channelCount,
                bitDepth = bitDepth,
                codec = codec,
            )

            val oldFormat = _currentFormat.value
            if (oldFormat != null && oldFormat != newFormat) {
                // Atomic transition lock: immediately prevent delivery of new format chunks to old sender
                isRenegotiating = true
            }

            _currentFormat.value = newFormat
            return inputAudioFormat
        }
        throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (isEnabled && !isPaused && !isRenegotiating && pcmChunkListener != null) {
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

    override fun onReset() {
        super.onReset()
        isRenegotiating = false
        _currentFormat.value = null
    }

    fun setFormatForTesting(format: PcmFormat?) {
        _currentFormat.value = format
    }
}
