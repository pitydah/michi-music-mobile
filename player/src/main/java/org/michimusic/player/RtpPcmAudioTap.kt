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
 * Audio-only PCM tap between decoder and AudioTrack. Never let a downstream
 * network sender exception unwind into Media3's real-time audio renderer.
 * On send failure pause the sender; keep a requested local mute (fail-closed)
 * to avoid unexpectedly transferring remote audio to the handset speaker.
 * The sender must explicitly start again to recover.
 */
@OptIn(UnstableApi::class)
class RtpPcmAudioTap : BaseAudioProcessor() {

    @Volatile var pcmChunkListener: ((ByteArray) -> Unit)? = null
    @Volatile var isEnabled: Boolean = false
    @Volatile var isPaused: Boolean = false
    @Volatile var isRenegotiating: Boolean = false
    @Volatile var muteLocalOutput: Boolean = false

    @Volatile var hasDeliveryFault: Boolean = false
        private set

    private val senderLock = Any()
    private var senderGeneration = 0L

    /** Start a distinct remote sender. Late exceptions from the preceding
     * callback must never suspend the replacement stream. */
    fun startSender(listener: (ByteArray) -> Unit) {
        synchronized(senderLock) {
            senderGeneration++
            pcmChunkListener = listener
            hasDeliveryFault = false
            isPaused = false
            isEnabled = true
            muteLocalOutput = true
        }
    }

    /**
     * Called by the isolated network worker on a failed send or queue overflow.
     * Listener identity protects a replacement sender from a late worker error.
     */
    fun reportSenderFailureIfCurrent(expectedListener: (ByteArray) -> Unit) {
        synchronized(senderLock) {
            if (isEnabled && pcmChunkListener === expectedListener) {
                hasDeliveryFault = true
                isPaused = true
            }
        }
    }

    /** Drop pre-transition network buffers whenever Media3 changes PCM format. */
    @Volatile var onFormatDiscontinuity: (() -> Unit)? = null

    /** Detach the sender and invalidate callbacks already executing on the
     * audio thread. This is an explicit user/transport stop, not a fallback. */
    fun stopSender() {
        synchronized(senderLock) {
            senderGeneration++
            isEnabled = false
            isPaused = false
            isRenegotiating = false
            muteLocalOutput = false
            pcmChunkListener = null
        }
    }

    private val _currentFormat = MutableStateFlow<PcmFormat?>(null)
    val currentFormat: StateFlow<PcmFormat?> = _currentFormat.asStateFlow()
    val activeFormat: PcmFormat? get() = _currentFormat.value

    /** Explicit recovery for a newly established sender connection. */
    fun resetDeliveryFault() {
        hasDeliveryFault = false
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val (depth, codec) = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 16 to "pcm_s16le"
            C.ENCODING_PCM_24BIT -> 24 to "pcm_s24le"
            C.ENCODING_PCM_32BIT -> 32 to "pcm_s32le"
            C.ENCODING_PCM_FLOAT -> 32 to "pcm_f32le"
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val newFormat = PcmFormat(
            sampleRate = inputAudioFormat.sampleRate,
            channels = inputAudioFormat.channelCount,
            bitDepth = depth,
            codec = codec,
        )
        val old = _currentFormat.value
        if ((old == null && isEnabled) || (old != null && old != newFormat)) {
            // Must be explicitly renegotiated by sender: never transmit new PCM
            // with the previous network framing configuration.
            isRenegotiating = true
            try { onFormatDiscontinuity?.invoke() } catch (_: Exception) { /* keep renderer safe */ }
        }
        _currentFormat.value = newFormat
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val captured = synchronized(senderLock) {
            val listener = pcmChunkListener
            if (isEnabled && !isPaused && !isRenegotiating && !hasDeliveryFault && listener != null) {
                senderGeneration to listener
            } else null
        }
        if (captured != null) {
            val data = ByteArray(remaining)
            val origin = inputBuffer.position()
            inputBuffer.get(data)
            inputBuffer.position(origin)
            try {
                captured.second.invoke(data)
            } catch (_: Exception) {
                synchronized(senderLock) {
                    if (senderGeneration == captured.first) {
                        // Network faults cannot unwind into Media3. The mute
                        // policy remains intact until explicit sender recovery.
                        hasDeliveryFault = true
                        isPaused = true
                    }
                }
            }
        }
        val output = replaceOutputBuffer(remaining)
        if (muteLocalOutput && isEnabled) {
            // Preserve the user's remote-only destination if sender failed:
            // no audible fallback on the handset without explicit user action.
            repeat(remaining) { output.put(0.toByte()) }
            inputBuffer.position(inputBuffer.limit())
        } else {
            output.put(inputBuffer)
        }
        output.flip()
    }

    override fun onReset() {
        super.onReset()
        // Reset while streaming must force a new format handshake.
        isRenegotiating = isEnabled
        try { onFormatDiscontinuity?.invoke() } catch (_: Exception) { /* keep renderer safe */ }
        _currentFormat.value = null
    }

    fun setFormatForTesting(format: PcmFormat?) {
        _currentFormat.value = format
    }
}
