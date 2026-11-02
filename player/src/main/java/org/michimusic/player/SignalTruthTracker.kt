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
    private val headphonePeq: HeadphonePeqAudioProcessor? = null,
) {
    private val _observation = MutableStateFlow(AudioSignalObservation())
    val observation: StateFlow<AudioSignalObservation> = _observation.asStateFlow()

    private var currentSourceFormat: SignalFormat? = null
    private var currentDecodedFormat: SignalFormat? = null
    private var currentOutputConfig: SignalFormat? = null
    private var currentFileFacts: SourceAudioFileFacts? = null
    private var currentMetadataTrack: Track? = null
    private var sourceObservedAtMs = 0L
    private var decodedObservedAtMs = 0L
    private var outputObservedAtMs = 0L
    private var routeObservedAtMs = 0L
    private var trackGeneration = 0L
    private var routeGeneration = 0L
    private var lastRouteFingerprint: String? = null
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
        currentFileFacts = null
        currentMetadataTrack = null
        sourceObservedAtMs = 0L
        decodedObservedAtMs = 0L
        outputObservedAtMs = 0L
        routeObservedAtMs = 0L
        // Keep the route fingerprint during a track reset: a cached route is not
        // new hardware evidence and must not gain a fresh observation timestamp.
        // A real route callback changes the fingerprint and renews route time.
        trackGeneration++
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
        decodedObservedAtMs = 0L
        outputObservedAtMs = 0L
        recompute()
    }

    private var currentAudioTrackGeneration: Long = 0L

    fun onTrackChanged(track: Track?) {
        if (track == null) {
            clearPlayback()
            return
        }
        // Continuous playback: invalidate only source format awaiting MediaCodec event for new track.
        // Preserves currentOutputConfig and valid decodedFormat across gapless/same-sink transitions.
        currentSourceFormat = null
        currentFileFacts = null
        currentMetadataTrack = null
        sourceObservedAtMs = 0L
        trackGeneration++
        recompute()
    }

    @OptIn(UnstableApi::class)
    fun onDecoderInputFormat(format: Format) {
        val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else 0
        val channels = if (format.channelCount != Format.NO_VALUE) format.channelCount else 0
        val codec = format.sampleMimeType?.substringAfter('/')?.uppercase() ?: "Desconocido"

        val actualMime = format.sampleMimeType ?: ""
        val facts = currentFileFacts
        val avg = format.averageBitrate.takeIf { it in 8_000..50_000_000 }
        val peak = format.peakBitrate.takeIf { it in 8_000..50_000_000 }
        val bitrate = facts?.bitrateBps?.takeIf { it > 0 } ?: avg ?: peak ?: 0
        val kind = when { (facts?.bitrateBps ?: 0) > 0 -> "FILE_AVG"; avg != null -> "AVERAGE"; peak != null -> "PEAK"; else -> "UNKNOWN" }
        val lossless = sourceHasRealDepth(actualMime, currentMetadataTrack?.format)
        val next = SignalFormat(codec = actualMime.ifBlank { codec },
            bitDepth = if (lossless) facts?.bitsPerSample ?: 0 else 0,
            sampleRate = sampleRate.takeIf { it > 0 } ?: facts?.sampleRateHz ?: 0,
            channels = channels, bitrateBps = bitrate, bitrateKind = kind)
        if (currentSourceFormat != next) sourceObservedAtMs = System.currentTimeMillis()
        currentSourceFormat = next
        recompute()
    }

    /** File-only metadata is delivered asynchronously and guarded by the service transition epoch. */
    fun onTrackFileMetadata(track: Track, facts: SourceAudioFileFacts?) {
        currentMetadataTrack = track
        currentFileFacts = facts
        val previous = currentSourceFormat
        val codec = previous?.codec?.takeUnless { it.isBlank() || it == "Desconocido" }
            ?: facts?.mimeType ?: track.format
        val rate = previous?.sampleRate?.takeIf { it > 0 } ?: facts?.sampleRateHz?.takeIf { it > 0 }
            ?: track.sampleRate.takeIf { it > 0 } ?: 0
        val fileBps = facts?.bitrateBps?.takeIf { it > 0 }
        val declaredBps = track.bitrate.let { when (it) {
            in 8..8000 -> it * 1000
            in 8001..50_000_000 -> it
            else -> 0
        } }
        val next = SignalFormat(codec = codec, bitDepth =
            if (sourceHasRealDepth(codec, track.format)) facts?.bitsPerSample ?: 0 else 0,
            sampleRate = rate, channels = previous?.channels ?: track.channels,
            bitrateBps = fileBps ?: previous?.bitrateBps?.takeIf { it > 0 } ?: declaredBps,
            bitrateKind = when {
                fileBps != null -> "FILE_AVG"
                previous != null && previous.bitrateBps > 0 -> previous.bitrateKind
                else -> "UNKNOWN"
            })
        if (next != previous) sourceObservedAtMs = System.currentTimeMillis()
        currentSourceFormat = next
        recompute()
    }

    private fun sourceHasRealDepth(codec: String?, trackFormat: String?): Boolean {
        val declared = "${codec.orEmpty()} ${trackFormat.orEmpty()}".lowercase()
        // WAV/AIFF/MP4/Ogg containers alone do not prove original PCM bit depth.
        // WavPack hybrid may be lossy without a validated correction file.
        if (listOf("adpcm", "mpeg", "mp3", "aac", "opus", "vorbis", "wma", "ogg", "wavpack")
                .any { it in declared }) return false
        return listOf("flac", "alac", "lpcm", "audio/raw", "audio/pcm", "pcm_s", "pcm_u")
            .any { it in declared }
    }

    fun onPcmFormatDecoded(pcmFormat: PcmFormat?) {
        if (!instrumentationAvailable) return
        val next = pcmFormat?.let {
            SignalFormat(codec = it.codec, bitDepth = it.bitDepth, sampleRate = it.sampleRate, channels = it.channels)
        }
        if (currentDecodedFormat != next) decodedObservedAtMs = if (next != null) System.currentTimeMillis() else 0L
        currentDecodedFormat = next
        recompute()
    }

    @OptIn(UnstableApi::class)
    fun onAudioTrackInitialized(sampleRate: Int, channelCount: Int, encoding: Int, generation: Long = 0L) {
        if (!instrumentationAvailable) return
        if (generation > 0L && generation < currentAudioTrackGeneration) return
        val newAudioTrack = generation > currentAudioTrackGeneration
        if (generation > 0L) currentAudioTrackGeneration = generation
        val bits = when (encoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> 16
            androidx.media3.common.C.ENCODING_PCM_24BIT -> 24
            androidx.media3.common.C.ENCODING_PCM_32BIT,
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> 32
            else -> 0 // unsupported/unknown encoding must not be reported as 16-bit
        }
        val next = SignalFormat(codec = "AudioTrack_PCM", bitDepth = bits, sampleRate = sampleRate, channels = channelCount)
        if (newAudioTrack || currentOutputConfig != next) outputObservedAtMs =
            if (bits > 0 && sampleRate > 0 && channelCount > 0) System.currentTimeMillis() else 0L
        currentOutputConfig = next
        recompute()
    }

    fun onAudioTrackReleased(generation: Long = 0L) {
        if (generation > 0L && generation < currentAudioTrackGeneration) {
            // Callback from an older AudioTrack that finished releasing late;
            // keep the current active AudioTrack's measurement intact.
            return
        }
        currentOutputConfig = null
        outputObservedAtMs = 0L
        recompute()
    }

    fun recompute() {
        val eqActive = playbackActive && audioEffects?.effectsState?.value?.isEnabled == true &&
            audioEffects?.hasActiveAudioSession == true
        val rgActive = playbackActive && instrumentationAvailable && replayGainProcessor?.isGainActive == true
        val rgVol = replayGainProcessor?.currentReplayGainVolume ?: 1f
        val rgDb = if (rgVol > 0f) (20f * kotlin.math.log10(rgVol)) else 0f
        val preampDb = if (playbackActive && instrumentationAvailable) {
            replayGainProcessor?.currentDspPreampDb ?: 0f
        } else 0f
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
            preampAppliedDb = preampDb,
            headphonePeqActive = playbackActive && headphonePeq?.isApplied == true,
            headphoneHeadroomDb = if (playbackActive && headphonePeq?.isApplied == true) headphonePeq.appliedHeadroomDb else 0f,
            headphoneFramesProcessed = if (playbackActive) headphonePeq?.framesProcessed ?: 0L else 0L,
            headphoneClipSamples = if (playbackActive) headphonePeq?.clippedSamples ?: 0L else 0L,
            upstreamPcmClipSamples = if (playbackActive) replayGainProcessor?.pcmClipSamples ?: 0L else 0L,
            upstreamSafetyReductionDb = if (playbackActive) replayGainProcessor?.safetyReductionDb ?: 0f else 0f,
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

        val newFingerprint = usbDacManager?.routeStatus?.value.toString()
        if (newFingerprint != lastRouteFingerprint) {
            lastRouteFingerprint = newFingerprint
            routeGeneration++
            routeObservedAtMs = when (usbDacManager?.routeStatus?.value) {
                is AudioRouteStatus.Confirmed, is AudioRouteStatus.RoutedElsewhere -> System.currentTimeMillis()
                else -> 0L
            }
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
            observedAtMs = maxOf(sourceObservedAtMs, decodedObservedAtMs, outputObservedAtMs, routeObservedAtMs),
            sourceObservedAtMs = sourceObservedAtMs,
            decodedObservedAtMs = decodedObservedAtMs,
            outputObservedAtMs = outputObservedAtMs,
            routeObservedAtMs = routeObservedAtMs,
            trackGeneration = trackGeneration,
            audioTrackGeneration = currentAudioTrackGeneration,
            routeGeneration = routeGeneration,
        )
    }
}
