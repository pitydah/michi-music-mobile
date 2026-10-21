package org.michimusic.player

import androidx.media3.common.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.models.AudioProcessingMode
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

    fun onTrackChanged(track: Track?) {
        if (track == null) {
            currentSourceFormat = null
            recompute()
            return
        }
        val ext = track.filepath.substringAfterLast('.', "").lowercase()
        val codec = when (ext) {
            "flac" -> "FLAC"
            "mp3" -> "MP3"
            "m4a", "aac" -> "AAC"
            "ogg", "opus" -> "Opus/Vorbis"
            "wav" -> "WAV"
            else -> ext.uppercase().ifEmpty { "Audio" }
        }
        currentSourceFormat = SignalFormat(
            codec = codec,
            bitDepth = 0, // Desconocido hasta decodificación real
            sampleRate = 0,
            channels = 2,
        )
        recompute()
    }

    fun onDecoderInputFormat(format: Format) {
        val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else 0
        val channels = if (format.channelCount != Format.NO_VALUE) format.channelCount else 2
        val codec = format.sampleMimeType?.substringAfter('/')?.uppercase() ?: "PCM"

        currentSourceFormat = SignalFormat(
            codec = codec,
            bitDepth = 0,
            sampleRate = sampleRate,
            channels = channels,
        )
        recompute()
    }

    fun onPcmFormatDecoded(pcmFormat: PcmFormat?) {
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

    fun onAudioTrackInitialized(sampleRate: Int, channelCount: Int, encoding: Int) {
        val bits = when (encoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> 16
            androidx.media3.common.C.ENCODING_PCM_24BIT -> 24
            androidx.media3.common.C.ENCODING_PCM_32BIT,
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
        currentOutputConfig = SignalFormat(
            codec = "AudioTrack_PCM",
            bitDepth = bits,
            sampleRate = sampleRate,
            channels = channelCount,
        )
        recompute()
    }

    fun recompute() {
        val mode = audioModeManager?.currentMode?.value ?: AudioProcessingMode.DSP
        val eqActive = audioEffects?.effectsState?.value?.isEnabled == true
        val rgActive = replayGainProcessor?.isGainActive == true
        val rgVol = replayGainProcessor?.currentVolume ?: 1f
        val rgDb = if (rgVol > 0f) (20f * kotlin.math.log10(rgVol)) else 0f
        val bbActive = (audioEffects?.effectsState?.value?.bassBoostStrength ?: 0) > 0 && eqActive
        val virtActive = (audioEffects?.effectsState?.value?.virtualizerStrength ?: 0) > 0 && eqActive

        val dspStatus = DspPipelineStatus(
            isEqActive = eqActive,
            isReplayGainActive = rgActive,
            replayGainAppliedDb = rgDb,
            bassBoostActive = bbActive,
            virtualizerActive = virtActive,
        )

        val routeStatusStr = when (val s = usbDacManager?.routeStatus?.value) {
            is AudioRouteStatus.Confirmed -> "CONFIRMADO (${s.device.name})"
            is AudioRouteStatus.Requested -> "SOLICITADO (${s.device.name})"
            is AudioRouteStatus.Detected -> "DETECTADO (${s.device.name})"
            is AudioRouteStatus.Failed -> "ERROR"
            is AudioRouteStatus.Unknown -> "NO VERIFICABLE"
            AudioRouteStatus.NoDevice, null -> "DESCONECTADO"
        }

        // Bit-perfect solo puede ser solicitado si estamos en TRANSPARENT con USB DAC
        val bitPerfectStatus = when {
            mode == AudioProcessingMode.TRANSPARENT && usbDacManager?.dacState?.value?.isConnected == true ->
                BitPerfectStatus.REQUESTED
            else -> BitPerfectStatus.UNVERIFIABLE
        }

        _observation.value = AudioSignalObservation(
            sourceFormat = currentSourceFormat,
            decodedFormat = currentDecodedFormat,
            dspStatus = dspStatus,
            routeStatus = routeStatusStr,
            outputConfig = currentOutputConfig,
            bitPerfect = bitPerfectStatus,
            observedAtMs = System.currentTimeMillis(),
        )
    }
}
