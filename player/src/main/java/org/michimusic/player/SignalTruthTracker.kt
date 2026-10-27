package org.michimusic.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.BitPerfectStatus
import org.michimusic.core.models.DspPipelineStatus
import org.michimusic.core.models.PcmFormat
import org.michimusic.core.models.SignalFormat
import org.michimusic.core.models.Track

/**
 * Rastreador centralizado de la señal de audio que construye [AudioSignalObservation]
 * basándose únicamente en mediciones y eventos reales de MediaCodec, AudioTrack y DSP.
 * Nunca inventa tasas de muestreo ni certifica bit-perfect sin confirmación explícita.
 */
class SignalTruthTracker(
    private val audioModeManager: AudioModeManager? = null,
    private val audioEffects: MichiAudioEffects? = null,
    private val replayGainProcessor: ReplayGainAudioProcessor? = null,
    private val usbDacManager: UsbDacManager? = null,
) {
    private val _observation = MutableStateFlow(AudioSignalObservation())
    val observation: StateFlow<AudioSignalObservation> = _observation.asStateFlow()

    private var currentSourceFormat: SignalFormat? = null
    private var currentDecodedFormat: SignalFormat? = null
    private var currentOutputConfig: SignalFormat? = null
    private var instrumentationAvailable = true
    // A configured effect may still exist while paused/stopped: do not label it
    // as actively processing samples unless Media3 reports actual playback.
    private var playbackActive = false

    fun setPlaybackActive(isPlaying: Boolean) {
        if (playbackActive != isPlaying) {
            playbackActive = isPlaying
            recompute()
        }
    }

    fun clearPlayback(resetInstrumentation: Boolean = false) {
        playbackActive = false
        currentSourceFormat = null
        currentDecodedFormat = null
        currentOutputConfig = null
        if (resetInstrumentation) instrumentationAvailable = true
        recompute()
    }

    /** Playback finished: never display measurements belonging to the previous track. */
    fun onPlaybackStopped() {
        clearPlayback()
    }

    /** Media3 switched to an uninstrumented fallback; never present previous PCM measurements. */
    fun markInstrumentationDegraded() {
        instrumentationAvailable = false
        currentDecodedFormat = null
        currentOutputConfig = null
        recompute()
    }

    fun onTrackChanged(track: Track?) {
        if (track == null) {
            clearPlayback()
            return
        }
        currentDecodedFormat = null
        currentOutputConfig = null
        // Track metadata or filename is not a decoder measurement.
        currentSourceFormat = null
        recompute()
    }

    fun onDecoderInputFormat(format: Format) {
        val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else 0
        val channels = if (format.channelCount != Format.NO_VALUE) format.channelCount else 0
        val codec = format.sampleMimeType?.substringAfter('/')?.uppercase() ?: "Desconocido"

        currentSourceFormat = SignalFormat(
            codec = codec,
            bitDepth = 0,
            sampleRate = sampleRate,
            channels = channels,
        )
        recompute()
    }

    fun onPcmFormatDecoded(pcmFormat: PcmFormat?) {
        if (!instrumentationAvailable) return
        if (pcmFormat == null) {
            currentDecodedFormat = null
        } else {
            currentDecodedFormat = SignalFormat(
                codec = pcmFormat.codec,
                bitDepth = pcmFormat.bitDepth,
                sampleRate = pcmFormat.sampleRate,
                channels = pcmFormat.channels,
            )
        }
        recompute()
    }

    @OptIn(UnstableApi::class)
    fun onAudioTrackInitialized(sampleRate: Int, channelCount: Int, encoding: Int) {
        if (!instrumentationAvailable) return
        val bits = when (encoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> 16
            androidx.media3.common.C.ENCODING_PCM_24BIT -> 24
            androidx.media3.common.C.ENCODING_PCM_32BIT,
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> 32
            else -> 0 // unsupported/unknown encoding must not be reported as 16-bit
        }
        currentOutputConfig = SignalFormat(
            codec = "AudioTrack_PCM",
            bitDepth = bits,
            sampleRate = sampleRate,
            channels = channelCount,
        )
        recompute()
    }

    fun onAudioTrackReleased() {
        currentOutputConfig = null
        recompute()
    }

    fun recompute() {
        val eqActive = playbackActive && audioEffects?.effectsState?.value?.isEnabled == true &&
            audioEffects?.hasActiveAudioSession == true
        val rgActive = playbackActive && instrumentationAvailable && replayGainProcessor?.isGainActive == true
        val rgVol = replayGainProcessor?.currentVolume ?: 1f
        val rgDb = if (rgVol > 0f) (20f * kotlin.math.log10(rgVol)) else 0f
        val bbActive = eqActive && audioEffects?.isBassBoostEffective == true &&
            ((audioEffects?.effectsState?.value?.bassBoostStrength ?: 0) > 0)
        val virtActive = eqActive && audioEffects?.isVirtualizerEffective == true &&
            ((audioEffects?.effectsState?.value?.virtualizerStrength ?: 0) > 0)

        val dspStatus = DspPipelineStatus(
            isEqActive = eqActive,
            isReplayGainActive = rgActive,
            replayGainAppliedDb = if (rgActive) rgDb else 0f,
            bassBoostActive = bbActive,
            virtualizerActive = virtActive,
        )

        val routeStatusStr = when (val s = usbDacManager?.routeStatus?.value as? AudioRouteStatus) {
            is AudioRouteStatus.Confirmed -> "RUTA CONFIRMADA (${s.device.name})"
            is AudioRouteStatus.RoutedElsewhere -> "DESVIADO: ${s.actual.name} (solicitado: ${s.requested.name})"
            is AudioRouteStatus.LastObserved -> "ÚLTIMA RUTA OBSERVADA (${s.device.name})"
            is AudioRouteStatus.Requested -> "SOLICITADO (${s.device.name})"
            is AudioRouteStatus.Detected -> "DETECTADO (${s.device.name})"
            is AudioRouteStatus.Failed -> "ERROR"
            is AudioRouteStatus.Unknown -> "NO VERIFICABLE"
            AudioRouteStatus.NoDevice -> "SIN RUTA OBSERVADA"
            null -> "SIN OBSERVADOR"
        }

        // Conforme a D-03 del Plan Maestro V5, Mobile no ofrece ni infiere certificación bit-perfect
        val bitPerfectStatus = BitPerfectStatus.UNVERIFIABLE

        _observation.value = AudioSignalObservation(
            sourceFormat = currentSourceFormat,
            decodedFormat = currentDecodedFormat.takeIf { instrumentationAvailable },
            dspStatus = dspStatus,
            routeStatus = routeStatusStr,
            outputConfig = currentOutputConfig.takeIf { instrumentationAvailable },
            bitPerfect = bitPerfectStatus,
            observedAtMs = System.currentTimeMillis(),
        )
    }
}
