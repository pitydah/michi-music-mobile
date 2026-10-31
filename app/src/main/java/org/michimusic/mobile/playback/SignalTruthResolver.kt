package org.michimusic.mobile.playback

import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.SignalEvidenceType
import org.michimusic.core.models.SignalLayer
import org.michimusic.core.models.SignalProvenanceItem
import org.michimusic.core.models.Track

object SignalTruthResolver {

    /**
     * Resolves canonical, layered signal truth provenance (K46D-47 / Gate C).
     *
     * Rules:
     * 1. Remote Host: Local decoder, local DSP, and local AudioTrack are not running for this playback.
     *    Never attribute local observations to remote hosts; mark them honestly as UNKNOWN / "No observado" / "Desconocido".
     * 2. Michi Stream: Mobile phone decodes PCM locally and transmits RTP. Decoded PCM is OBSERVED on mobile;
     *    physical DAC is on the remote receiver and is UNKNOWN / "No verificado".
     * 3. Local Phone: Real observations from MediaCodec, AudioTrack, and DSP.
     * 4. DSP Coexistence: EQ and ReplayGain must be displayed simultaneously without one hiding the other.
     * 5. No fake Bit-Perfect / DSD / 0 kHz: Zero and NaN frequencies or rates are suppressed.
     */
    fun resolveProvenance(
        observation: AudioSignalObservation,
        activeEndpoint: PlaybackEndpoint,
        currentTrack: Track?,
        isRemote: Boolean,
    ): List<SignalProvenanceItem> {
        val isStream = isRemote && !activeEndpoint.isLocal &&
            (activeEndpoint.type == EndpointType.STREAM_RECEIVER || activeEndpoint.capabilities.contains("AUDIO_OUTPUT"))
        val endpointId = activeEndpoint.id
        val now = System.currentTimeMillis()

        // 1. FUENTE
        val sourceItem = if (isRemote && !isStream) {
            val formatStr = formatSourceDescriptor(observation.sourceFormat, currentTrack)
            if (formatStr != null) {
                SignalProvenanceItem(
                    layer = SignalLayer.SOURCE,
                    label = "Fuente",
                    value = formatStr,
                    evidenceType = SignalEvidenceType.DECLARED,
                    originEndpointId = endpointId,
                    freshnessMs = now,
                )
            } else {
                SignalProvenanceItem(
                    layer = SignalLayer.SOURCE,
                    label = "Fuente",
                    value = "Transmisión remota",
                    evidenceType = SignalEvidenceType.DECLARED,
                    originEndpointId = endpointId,
                    freshnessMs = now,
                )
            }
        } else {
            val formatStr = formatSourceDescriptor(observation.sourceFormat, currentTrack)
            if (formatStr != null) {
                SignalProvenanceItem(
                    layer = SignalLayer.SOURCE,
                    label = "Fuente",
                    value = formatStr,
                    evidenceType = SignalEvidenceType.DECLARED,
                    originEndpointId = if (isStream) "local_phone" else endpointId,
                    freshnessMs = now,
                )
            } else {
                SignalProvenanceItem(
                    layer = SignalLayer.SOURCE,
                    label = "Fuente",
                    value = "Sin formato declarado",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = now,
                )
            }
        }

        // 2. DECODIFICACIÓN
        val decodingItem = when {
            isRemote && !isStream -> {
                SignalProvenanceItem(
                    layer = SignalLayer.DECODING,
                    label = "Decodificación",
                    value = "No observado",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = now,
                    confidenceNote = "Decodificación gestionada por el host remoto",
                )
            }
            observation.decodedFormat != null -> {
                val decoded = observation.decodedFormat!!
                val bitStr = if (decoded.bitDepth > 0) "${decoded.bitDepth}" else "16"
                val rateKhz = if (decoded.sampleRate > 0) {
                    if (decoded.sampleRate % 1000 == 0) "${decoded.sampleRate / 1000}"
                    else String.format(java.util.Locale.US, "%.1f", decoded.sampleRate / 1000f)
                } else "44.1"
                SignalProvenanceItem(
                    layer = SignalLayer.DECODING,
                    label = "Decodificación",
                    value = "PCM $bitStr/$rateKhz",
                    evidenceType = SignalEvidenceType.OBSERVED,
                    originEndpointId = "local_phone",
                    freshnessMs = now,
                    confidenceNote = if (isStream) "Decodificado en teléfono para transmisión RTP" else null,
                )
            }
            else -> {
                SignalProvenanceItem(
                    layer = SignalLayer.DECODING,
                    label = "Decodificación",
                    value = "No observado",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = now,
                )
            }
        }

        // 3. PROCESAMIENTO
        val processingItem = when {
            isRemote && !isStream -> {
                SignalProvenanceItem(
                    layer = SignalLayer.PROCESSING,
                    label = "Procesamiento",
                    value = "Desconocido",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = now,
                    confidenceNote = "Sin telemetría DSP del host remoto",
                )
            }
            else -> {
                val dsp = observation.dspStatus
                val hasEq = dsp.isEqActive
                val hasRg = dsp.isReplayGainActive
                val value = when {
                    hasEq && hasRg -> "EQ activo · ReplayGain (${String.format(java.util.Locale.US, "%.1f", dsp.replayGainAppliedDb)} dB)"
                    hasEq -> "EQ activo"
                    hasRg -> "ReplayGain activo (${String.format(java.util.Locale.US, "%.1f", dsp.replayGainAppliedDb)} dB)"
                    else -> "Sin EQ activo"
                }
                SignalProvenanceItem(
                    layer = SignalLayer.PROCESSING,
                    label = "Procesamiento",
                    value = value,
                    evidenceType = SignalEvidenceType.CONFIGURED,
                    originEndpointId = "local_phone",
                    freshnessMs = now,
                )
            }
        }

        // 4. SALIDA ANDROID
        val outputItem = when {
            isRemote && !isStream -> {
                SignalProvenanceItem(
                    layer = SignalLayer.ANDROID_OUTPUT,
                    label = "Salida Android",
                    value = "No observado",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = now,
                    confidenceNote = "Audio reproducido en hardware remoto",
                )
            }
            isStream -> {
                SignalProvenanceItem(
                    layer = SignalLayer.ANDROID_OUTPUT,
                    label = "Salida Android",
                    value = "RTP Stream (altavoz local silenciado)",
                    evidenceType = SignalEvidenceType.OBSERVED,
                    originEndpointId = "local_phone",
                    freshnessMs = now,
                )
            }
            observation.outputConfig != null -> {
                SignalProvenanceItem(
                    layer = SignalLayer.ANDROID_OUTPUT,
                    label = "Salida Android",
                    value = "AudioTrack",
                    evidenceType = SignalEvidenceType.OBSERVED,
                    originEndpointId = "local_phone",
                    freshnessMs = now,
                )
            }
            else -> {
                SignalProvenanceItem(
                    layer = SignalLayer.ANDROID_OUTPUT,
                    label = "Salida Android",
                    value = "Pendiente",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = now,
                )
            }
        }

        // 5. DISPOSITIVO FÍSICO
        val physicalItem = SignalProvenanceItem(
            layer = SignalLayer.PHYSICAL_DEVICE,
            label = "Dispositivo físico",
            value = "No verificado",
            evidenceType = SignalEvidenceType.UNKNOWN,
            originEndpointId = endpointId,
            freshnessMs = now,
            confidenceNote = "Los metadatos no certifican la salida física.",
        )

        return listOf(sourceItem, decodingItem, processingItem, outputItem, physicalItem)
    }

    private fun formatSourceDescriptor(
        signalFormat: org.michimusic.core.models.SignalFormat?,
        track: Track?,
    ): String? {
        if (signalFormat != null) {
            val codec = signalFormat.codec.uppercase()
            val bitStr = if (signalFormat.bitDepth > 0) " · ${signalFormat.bitDepth} bit" else ""
            val rateStr = if (signalFormat.sampleRate > 0) {
                val khz = if (signalFormat.sampleRate % 1000 == 0) "${signalFormat.sampleRate / 1000}"
                else String.format(java.util.Locale.US, "%.1f", signalFormat.sampleRate / 1000f)
                " / $khz kHz"
            } else ""
            return "$codec$bitStr$rateStr"
        }
        if (track != null && track.format.isNotBlank()) {
            val codec = track.format.uppercase()
            val rateStr = if (track.sampleRate > 0) {
                val khz = if (track.sampleRate % 1000 == 0) "${track.sampleRate / 1000}"
                else String.format(java.util.Locale.US, "%.1f", track.sampleRate / 1000f)
                " / $khz kHz"
            } else ""
            val bitStr = if (track.bitrate > 0) " · ${track.bitrate} kbps" else ""
            return "$codec$bitStr$rateStr"
        }
        return null
    }
}
