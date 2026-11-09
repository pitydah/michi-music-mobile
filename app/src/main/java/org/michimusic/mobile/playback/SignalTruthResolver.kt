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
        streamSelectedBySession: Boolean = false,
    ): List<SignalProvenanceItem> {
        // AUDIO_OUTPUT is a generic capability: it does not establish that the
        // target is a Michi Stream receiver or that an RTP packet was delivered.
        val isStream = SignalTruthEvidencePolicy.isMichiStream(
            isRemote, activeEndpoint, streamSelectedBySession)
        val endpointId = activeEndpoint.id
        val observationFreshness = observation.observedAtMs
        val sourceFreshness = observation.sourceObservedAtMs
        // Never borrow freshness from another layer. A recent route callback is
        // not evidence that this decoder or AudioTrack was newly observed.
        val decodedFreshness = observation.decodedObservedAtMs
        val outputFreshness = observation.outputObservedAtMs

        // 1. FUENTE
        // K47-02: Remote playback MUST NEVER inherit observation.sourceFormat from local tracker.
        // It must solely reflect currentTrack declared metadata or UNKNOWN ("No declarado").
        val sourceItem = if (isRemote && !isStream) {
            val formatStr = SignalSourcePresentation.describe(null, currentTrack)
            if (formatStr != null) {
                SignalProvenanceItem(
                    layer = SignalLayer.SOURCE,
                    label = "Fuente",
                    value = formatStr,
                    evidenceType = SignalEvidenceType.DECLARED,
                    originEndpointId = endpointId,
                    freshnessMs = 0L,
                    confidenceNote = "Metadatos declarados por la biblioteca remota",
                )
            } else {
                SignalProvenanceItem(
                    layer = SignalLayer.SOURCE,
                    label = "Fuente",
                    value = "No declarado",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = 0L,
                    confidenceNote = "Host remoto sin metadatos de formato de audio",
                )
            }
        } else {
            val formatStr = SignalSourcePresentation.describe(observation.sourceFormat, currentTrack)
            if (formatStr != null) {
                SignalProvenanceItem(
                    layer = SignalLayer.SOURCE,
                    label = "Fuente",
                    value = formatStr,
                    evidenceType = SignalEvidenceType.DECLARED,
                    originEndpointId = if (isStream) "local_phone" else endpointId,
                    freshnessMs = sourceFreshness,
                )
            } else {
                SignalProvenanceItem(
                    layer = SignalLayer.SOURCE,
                    label = "Fuente",
                    value = "Sin formato declarado",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = 0L,
                )
            }
        }

        // 2. DECODIFICACIÓN
        // K47-03: Never default to 16/44.1 or claim OBSERVED when measurements are missing or <= 0.
        val decodingItem = when {
            isRemote && !isStream -> {
                SignalProvenanceItem(
                    layer = SignalLayer.DECODING,
                    label = "Decodificación",
                    value = "No observado",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = 0L,
                    confidenceNote = "Decodificación gestionada por el host remoto",
                )
            }
            SignalTruthEvidencePolicy.hasTimedTechnicalFormat(observation.decodedFormat, decodedFreshness) -> {
                val decoded = observation.decodedFormat!!
                val bitStr = "${decoded.bitDepth}"
                val rateKhz = if (decoded.sampleRate % 1000 == 0) "${decoded.sampleRate / 1000}"
                else String.format(java.util.Locale.US, "%.1f", decoded.sampleRate / 1000f)
                SignalProvenanceItem(
                    layer = SignalLayer.DECODING,
                    label = "Decodificación",
                    value = "PCM $bitStr/$rateKhz",
                    evidenceType = SignalEvidenceType.OBSERVED,
                    originEndpointId = "local_phone",
                    freshnessMs = decodedFreshness,
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
                    freshnessMs = 0L,
                    confidenceNote = if (observation.decodedFormat != null) "Medición de decodificación incompleta o no disponible" else null,
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
                    freshnessMs = 0L,
                    confidenceNote = "Sin telemetría DSP del host remoto",
                )
            }
            else -> {
                val dsp = observation.dspStatus
                // Android AudioEffect is bound AFTER AudioTrack, not to the RTP tap.
                // Only PCM processors ahead of the split may be attributed to Stream.
                val hasEq = !isStream && dsp.isEqActive
                val hasRg = dsp.isReplayGainActive
                val replayGainDb = SignalTruthEvidencePolicy.finiteDb(dsp.replayGainAppliedDb)
                val preampDb = SignalTruthEvidencePolicy.finiteDb(dsp.preampAppliedDb) ?: 0f
                val hasPreamp = kotlin.math.abs(preampDb) > 0.001f
                val processing = mutableListOf<String>()
                if (hasEq) processing += "EQ activo"
                if (hasRg) processing += if (replayGainDb != null) {
                    "ReplayGain activo (${String.format(java.util.Locale.US, "%.1f", replayGainDb)} dB)"
                } else "ReplayGain activo (ganancia no verificada)"
                if (hasPreamp) processing += "Preamp DSP (${String.format(java.util.Locale.US, "%+.1f", preampDb)} dB)"
                if (SignalTruthEvidencePolicy.finiteDb(dsp.upstreamSafetyReductionDb) != null &&
                    dsp.upstreamSafetyReductionDb < -0.01f)
                    processing += "Protección de ganancia pre-RTP (${String.format(java.util.Locale.US, "%.1f", dsp.upstreamSafetyReductionDb)} dB)"
                if (!isStream && dsp.headphonePeqActive) {
                    val headroomDb = SignalTruthEvidencePolicy.finiteDb(dsp.headphoneHeadroomDb)
                    processing += if (headroomDb != null) {
                        "PEQ auriculares (headroom −${String.format(java.util.Locale.US, "%.1f", headroomDb)} dB)"
                    } else "PEQ auriculares (headroom no verificado)"
                }
                val value = processing.joinToString(" · ").ifEmpty { "Sin EQ activo" }
                SignalProvenanceItem(
                    layer = SignalLayer.PROCESSING,
                    label = "Procesamiento",
                    value = value,
                    evidenceType = SignalEvidenceType.CONFIGURED,
                    originEndpointId = "local_phone",
                    freshnessMs = observationFreshness,
                    confidenceNote = when {
                        dsp.upstreamPcmClipSamples > 0L ->
                            "Recortes PCM antes de la salida PEQ: ${dsp.upstreamPcmClipSamples} muestras; bajar headroom después no los recupera"
                        dsp.headphoneClipSamples > 0L && !isStream ->
                            "Recortes en PEQ local: ${dsp.headphoneClipSamples} muestras; revisa ganancias y perfil"
                        isStream && dsp.isEqActive ->
                            "El EQ de sesión Android no forma parte del PCM enviado por RTP"
                        else -> null
                    },
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
                    freshnessMs = 0L,
                    confidenceNote = "Audio reproducido en hardware remoto",
                )
            }
            isStream -> {
                // Endpoint selection and decoded PCM do NOT prove RTP transmission,
                // reception, or the physical DAC. This needs a transport receipt.
                SignalProvenanceItem(
                    layer = SignalLayer.ANDROID_OUTPUT,
                    label = "Salida Android",
                    value = "RTP Stream (entrega no verificada)",
                    evidenceType = SignalEvidenceType.CONFIGURED,
                    originEndpointId = "local_phone",
                    freshnessMs = 0L,
                    confidenceNote = "Destino seleccionado; faltan recibos de envío y recepción RTP",
                )
            }
            SignalTruthEvidencePolicy.hasTimedTechnicalFormat(observation.outputConfig, outputFreshness) -> {
                SignalProvenanceItem(
                    layer = SignalLayer.ANDROID_OUTPUT,
                    label = "Salida Android",
                    value = SignalTruthEvidencePolicy.describeAudioTrack(observation.outputConfig!!),
                    evidenceType = SignalEvidenceType.OBSERVED,
                    originEndpointId = "local_phone",
                    freshnessMs = outputFreshness,
                )
            }
            else -> {
                SignalProvenanceItem(
                    layer = SignalLayer.ANDROID_OUTPUT,
                    label = "Salida Android",
                    value = "Pendiente",
                    evidenceType = SignalEvidenceType.UNKNOWN,
                    originEndpointId = endpointId,
                    freshnessMs = 0L,
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
            freshnessMs = 0L,
            confidenceNote = "Los metadatos no certifican la salida física.",
        )

        return listOf(sourceItem, decodingItem, processingItem, outputItem, physicalItem)
    }

}
