package org.michimusic.mobile.playback

import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.SignalEvidenceType
import org.michimusic.core.models.SignalLayer
import org.michimusic.core.models.SignalProvenanceItem
import java.util.Locale
import kotlin.math.abs

/** Presentation-only projection of existing Signal Truth observations.
 * No USB, DSP or playback command is executed here.
 * IMPORTANT: ownerKey is a *UI invalidation key*, not a validated playback identity.
 */
internal enum class SignalDestination { LOCAL, MICHI_STREAM, REMOTE_HOST }
internal enum class SignalStageAction { NONE, OPEN_EQ, USB_MIXER, REPLAY_GAIN, HEADPHONE_PROFILE }

internal data class SignalStageUi(
    val stableId: String,
    val branchId: String,
    val label: String,
    val summary: String,
    val evidenceType: SignalEvidenceType,
    val origin: String,
    val freshnessMs: Long,
    val note: String? = null,
    val action: SignalStageAction = SignalStageAction.NONE,
)
internal data class SignalEdgeUi(val fromId: String, val toId: String, val branchId: String)
internal data class SignalGraphUi(
    val ownerKey: String,
    val destination: SignalDestination,
    val nodes: List<SignalStageUi>,
    val edges: List<SignalEdgeUi>,
    /** Available settings are NEVER edges or stages of the active signal path. */
    val controls: List<SignalStageUi> = emptyList(),
)

/** R3-A: turns the legacy 5-layer read model into variable-length paths and explicit RTP branches.
 * The legacy resolver remains the sole authority for per-layer evidence and timestamps.
 * Detailed effects are CONFIGURED, never upgraded to APPLIED without causal receipts.
 */
internal object SignalTruthGraphProjector {
    fun project(
        provenance: List<SignalProvenanceItem>,
        observation: AudioSignalObservation,
        destination: SignalDestination,
        ownerKey: String,
        routeDescription: String? = null,
        routeObserved: Boolean = false,
        canConfigureUsb: Boolean = false,
        rtpSenderSessionId: String? = null,
        rtpPacketsSent: Long = 0L,
        rtpSenderActive: Boolean = false,
    ): SignalGraphUi {
        val nodes = mutableListOf<SignalStageUi>()
        val edges = mutableListOf<SignalEdgeUi>()
        val controls = mutableListOf<SignalStageUi>()
        fun inactiveControl(id: String, label: String, summary: String, action: SignalStageAction) {
            controls += SignalStageUi(
                stableId = "settings:$id", branchId = "settings", label = label,
                summary = summary, evidenceType = SignalEvidenceType.CONFIGURED,
                origin = "local_phone", freshnessMs = 0L,
                note = "Configuración disponible fuera de la ruta de procesamiento activa",
                action = action,
            )
        }
        fun legacy(layer: SignalLayer): SignalProvenanceItem? = provenance.firstOrNull { it.layer == layer }
        fun add(
            id: String, branch: String, label: String, summary: String,
            evidence: SignalEvidenceType, origin: String, freshness: Long = 0,
            note: String? = null, action: SignalStageAction = SignalStageAction.NONE,
            after: String? = nodes.lastOrNull()?.stableId,
        ): String {
            val key = "$branch:$id"
            require(nodes.none { it.stableId == key }) { "Duplicate Signal Truth stage: $key" }
            nodes += SignalStageUi(key, branch, label, summary, evidence, origin, freshness, note, action)
            if (after != null) edges += SignalEdgeUi(after, key, branch)
            return key
        }
        fun addLegacy(
            layer: SignalLayer, branch: String, id: String, label: String,
            after: String? = nodes.lastOrNull()?.stableId,
        ): String {
            val item = legacy(layer)
            return add(id, branch, label, item?.value ?: "No observado",
                item?.evidenceType ?: SignalEvidenceType.UNKNOWN,
                item?.originEndpointId ?: "unknown", item?.freshnessMs ?: 0L,
                item?.confidenceNote, after = after)
        }
        val source = addLegacy(SignalLayer.SOURCE, "main", "source", "Archivo / fuente", after = null)
        if (destination == SignalDestination.REMOTE_HOST) {
            val remote = legacy(SignalLayer.PROCESSING)
            val host = add("remote-processing", "main", "Procesamiento en host remoto",
                remote?.value ?: "Sin telemetría", SignalEvidenceType.UNKNOWN,
                "remote_host", note = "Los efectos y el AudioTrack del teléfono no describen este host")
            addLegacy(SignalLayer.PHYSICAL_DEVICE, "main", "remote-device", "Salida del host", after = host)
            return SignalGraphUi(ownerKey, destination, nodes, edges)
        }

        addLegacy(SignalLayer.DECODING, "main", "decode", "PCM decodificado", after = source)
        val dsp = observation.dspStatus
        val processor = legacy(SignalLayer.PROCESSING)
        val origin = "local_phone"
        // The PCM tap for RTP is before Android's session EQ / headphone PEQ.
        if (dsp.isReplayGainActive) {
            val gain = dsp.replayGainAppliedDb.takeIf { it.isFinite() }
            val pcmObserved = dsp.replayGainPcmObserved && dsp.replayGainFramesProcessed > 0L
            val settled = pcmObserved && dsp.replayGainRampPendingFrames == 0
            val gainLabel = if (gain == null) "Ganancia no verificada" else
                String.format(Locale.US, "%+.1f dB", gain)
            add("replaygain", "main", "ReplayGain",
                when {
                    settled -> "$gainLabel · PCM procesado"
                    pcmObserved -> "$gainLabel · transición PCM en curso"
                    else -> "$gainLabel · configurado; sin recibo PCM"
                },
                if (settled) SignalEvidenceType.OBSERVED else SignalEvidenceType.CONFIGURED, origin,
                note = if (pcmObserved) "Recibo del procesador PCM local; no confirma AudioTrack, RTP ni DAC físico"
                    else "El modo guardado no certifica muestras procesadas de esta revisión",
                action = if (destination == SignalDestination.LOCAL)
                    SignalStageAction.REPLAY_GAIN else SignalStageAction.NONE)
        } else if (destination == SignalDestination.LOCAL) {
            inactiveControl("replaygain", "ReplayGain", "Desactivado · ajuste disponible",
                SignalStageAction.REPLAY_GAIN)
        }
        val preamp = dsp.preampAppliedDb.takeIf { it.isFinite() }
        if (preamp != null && abs(preamp) > 0.001f) {
            add("preamp", "main", "Preamplificación PCM",
                String.format(Locale.US, "%+.1f dB (configurado)", preamp),
                SignalEvidenceType.CONFIGURED, origin)
        }
        val safety = dsp.upstreamSafetyReductionDb.takeIf { it.isFinite() }
        if (safety != null && safety < -0.01f) {
            add("headroom", "main", "Protección pre-RTP",
                String.format(Locale.US, "%.1f dB", safety), SignalEvidenceType.CONFIGURED, origin)
        }
        if (destination == SignalDestination.MICHI_STREAM) {
            val split = nodes.last().stableId
            // A successful sender-side UDP call proves only local send, not arrival.
            // The session is explicitly supplied by the active ReceiverLite authority.
            val hasSenderReceipt = SignalTruthRtpSenderReceipt.isValid(
                destination, rtpSenderSessionId, rtpPacketsSent, rtpSenderActive)
            val rtp = add("rtp", "rtp", "Transporte Michi Stream",
                if (hasSenderReceipt) "UDP enviado desde teléfono · $rtpPacketsSent paquetes" else
                    "RTP solicitado · envío no observado",
                if (hasSenderReceipt) SignalEvidenceType.OBSERVED else SignalEvidenceType.CONFIGURED,
                origin, note = if (hasSenderReceipt)
                    "Contador de envíos UDP de sesión activa; recepción, reproducción y DAC remoto no verificados" else
                    "Sin contador válido de envíos de la sesión; ningún recibo del receptor",
                after = split)
            add("remote-receiver", "rtp", "Receptor remoto",
                "Estado de reproducción desconocido", SignalEvidenceType.UNKNOWN,
                "remote_receiver", after = rtp)
            addLegacy(SignalLayer.PHYSICAL_DEVICE, "rtp", "physical", "DAC / salida remota")
            add("local-monitor", "local", "Rama local de AudioTrack",
                "Silenciamiento solicitado; no es la señal RTP", SignalEvidenceType.CONFIGURED,
                origin, note = "PEQ/EQ de sesión Android no modifica los bytes RTP", after = split)
            return SignalGraphUi(ownerKey, destination, nodes, edges)
        }

        // The phone's PCM PEQ runs upstream of AudioTrack; Android session EQ,
        // BassBoost and Virtualizer are attached to the AudioTrack audio session.
        // These are distinct causal boundaries and must not be drawn out of order.
        if (dsp.headphonePeqActive) {
            val peqObserved = dsp.headphonePeqActive &&
                dsp.headphonePeqAppliedRevision >= 0L && dsp.headphoneFramesProcessed > 0L &&
                dsp.headphonePeqObservedAtMs > 0L
            add("peq", "main", "Perfil PEQ de auriculares",
                if (peqObserved) "PEQ · PCM procesado" else "Perfil seleccionado · pendiente de ruta o muestras",
                if (peqObserved) SignalEvidenceType.OBSERVED else SignalEvidenceType.CONFIGURED,
                origin, freshness = if (peqObserved) dsp.headphonePeqObservedAtMs else 0L,
                note = if (peqObserved)
                    "Recibo de fotogramas PCM de la revisión PEQ vigente; no certifica AudioTrack, RTP ni DAC físico"
                else "El preset elegido no demuestra aplicación: requiere ruta local autorizada y nuevos fotogramas PCM",
                action = SignalStageAction.HEADPHONE_PROFILE)
        } else {
            inactiveControl("headphones", "Perfil de auriculares",
                if (dsp.headphonePeqConfigured) "Perfil seleccionado · aplicación pendiente de ruta"
                else "Sin perfil PEQ aplicado", SignalStageAction.HEADPHONE_PROFILE)
        }
        addLegacy(SignalLayer.ANDROID_OUTPUT, "main", "audio-track", "AudioTrack Android")
        if (dsp.isEqActive) add("eq", "main", "Ecualizador de sesión Android",
            "EQ configurado", SignalEvidenceType.CONFIGURED, origin,
            note = processor?.confidenceNote, action = SignalStageAction.OPEN_EQ)
        if (!dsp.isEqActive) inactiveControl("eq", "Ecualizador",
            "EQ desactivado · ajuste disponible", SignalStageAction.OPEN_EQ)
        if (dsp.bassBoostActive) add("bass", "main", "Refuerzo de graves",
            "Efecto de sesión configurado", SignalEvidenceType.CONFIGURED, origin)
        if (dsp.virtualizerActive) add("virtualizer", "main", "Virtualización",
            "Efecto de sesión configurado", SignalEvidenceType.CONFIGURED, origin)
        add("route", "main", "Ruta de salida Android",
            routeDescription ?: "Ruta actual no confirmada",
            if (routeObserved) SignalEvidenceType.OBSERVED else SignalEvidenceType.UNKNOWN,
            origin, note = "La ruta observada no confirma el formato físico del DAC",
            action = if (routeObserved && canConfigureUsb) SignalStageAction.USB_MIXER else SignalStageAction.NONE)
        addLegacy(SignalLayer.PHYSICAL_DEVICE, "main", "physical", "Dispositivo físico")
        return SignalGraphUi(ownerKey, destination, nodes, edges, controls)
    }
}
