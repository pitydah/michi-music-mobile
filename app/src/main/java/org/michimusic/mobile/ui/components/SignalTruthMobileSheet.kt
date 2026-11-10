package org.michimusic.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.SignalEvidenceType
import org.michimusic.core.models.Track
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.playback.SignalDestination
import org.michimusic.mobile.playback.SignalGraphUi
import org.michimusic.mobile.playback.SignalStageAction
import org.michimusic.mobile.playback.SignalStageUi
import org.michimusic.mobile.playback.SignalTruthEvidencePolicy
import org.michimusic.mobile.playback.SignalTruthChainPresentation
import org.michimusic.mobile.playback.SignalTruthGraphProjector
import org.michimusic.mobile.playback.SignalTruthOwnerPolicy
import org.michimusic.mobile.playback.SignalTruthResolver
import org.michimusic.mobile.ui.components.premium.AeroFloatingSurface
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.player.AudioRouteStatus
import org.michimusic.player.UsbDacInfo
import org.michimusic.player.UsbDacManager


/** One coherent sender-side sample, never a receiver or DAC acknowledgement. */
private data class SenderSample(val sessionId: String? = null, val packets: Long = 0L,
    val active: Boolean = false)

/** Mobile-first inspector: real stages, one expanded stage, no playback side-effects.
 * DSP changes are delegated to the existing editor; mixer controls to UsbDacManager.
 * This is presentation, NOT a substitute for owner-bound playback evidence receipts.
 */
@Composable
fun SignalTruthMobileSheet(
    observation: AudioSignalObservation,
    activeEndpoint: PlaybackEndpoint,
    currentTrack: Track?,
    isRemote: Boolean,
    streamSelectedBySession: Boolean,
    queueEntryId: String? = null,
    onOpenEqualizer: () -> Unit,
    onOpenOutputRoutes: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isStream = SignalTruthEvidencePolicy.isMichiStream(isRemote, activeEndpoint, streamSelectedBySession)
    val destination = when {
        isStream -> SignalDestination.MICHI_STREAM
        isRemote -> SignalDestination.REMOTE_HOST
        else -> SignalDestination.LOCAL
    }
    // These are *sender* receipts sampled from the live ReceiverLite session,
    // NEVER proof that the remote receiver rendered PCM or that its DAC is active.
    val streamManager = if (isStream) runCatching { koinInject<PlaybackSessionManager>() }.getOrNull() else null
    var senderSample by remember(isStream, activeEndpoint.id) { mutableStateOf(SenderSample()) }
    LaunchedEffect(streamManager, isStream, activeEndpoint.id) {
        if (isStream && streamManager != null) {
            while (true) {
                val session = streamManager.sessionState.value
                val liveId = streamManager.activeReceiverSessionId
                val valid = session.isRemoteActive && session.activeEndpoint.id == activeEndpoint.id &&
                    streamManager.isStreamReceiver && !liveId.isNullOrBlank()
                val snapshot = if (valid) streamManager.getRtpMetrics() else null
                // A sender rotation can occur during sampling. Reject mixed generations.
                val sameSession = liveId == streamManager.activeReceiverSessionId
                val active = valid && sameSession && snapshot?.isActive == true
                senderSample = if (active) SenderSample(liveId, snapshot!!.packetsSent.coerceAtLeast(0L), true)
                    else SenderSample()
                delay(1000L) // lifecycle cancellation stops polling when sheet closes
            }
        } else {
            senderSample = SenderSample()
        }
    }
    // Do not inspect phone route or show USB controls while controlling a remote endpoint.
    val usbManager = if (destination == SignalDestination.LOCAL) {
        runCatching { koinInject<UsbDacManager>() }.getOrNull()
    } else null
    val routeStatus by (usbManager?.routeStatus?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf<AudioRouteStatus>(AudioRouteStatus.NoDevice)
    })
    val dacInfo by (usbManager?.dacState?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(UsbDacInfo())
    })
    val routeEvidence = if (destination == SignalDestination.LOCAL) audioRouteEvidence(routeStatus) else null
    val usbConfirmed = routeStatus is AudioRouteStatus.Confirmed &&
        dacInfo.isConnected && (routeStatus as AudioRouteStatus.Confirmed).device.androidDeviceId == dacInfo.deviceId

    // Do not join local player evidence with a different visible track. This
    // is a conservative guard, not a full library/queue/epoch transport receipt.
    val safeObservation = SignalTruthOwnerPolicy.forVisibleTrack(
        observation, currentTrack?.id?.toString(),
        usesLocalDecoder = destination != SignalDestination.REMOTE_HOST,
    )
    val provenanceItems = remember(safeObservation, activeEndpoint, currentTrack, isRemote) {
        SignalTruthResolver.resolveProvenance(safeObservation, activeEndpoint, currentTrack, isRemote,
            streamSelectedBySession)
    }
    // The queue entry/source/transport session disambiguate the UI expansion.
    // They are NOT independent receipts from the audio decoder or remote DAC.
    val ownerKey = listOf(activeEndpoint.id, currentTrack?.source?.name ?: "unknown-source",
        currentTrack?.id ?: "none", queueEntryId ?: "unknown-entry",
        senderSample.sessionId ?: "no-rtp-session", observation.trackGeneration.toString(),
        observation.audioTrackGeneration.toString(), observation.routeGeneration.toString())
        .joinToString("/")
    val graph = remember(provenanceItems, safeObservation, destination, ownerKey, routeEvidence, usbConfirmed, senderSample) {
        SignalTruthGraphProjector.project(
            provenance = provenanceItems,
            observation = safeObservation,
            destination = destination,
            ownerKey = ownerKey,
            routeDescription = routeEvidence?.let { "${it.headline}: ${it.deviceLabel}" },
            routeObserved = routeEvidence?.hasCurrentEvidence == true &&
                safeObservation.outputObservedAtMs > 0L,
            canConfigureUsb = usbConfirmed,
            rtpSenderSessionId = senderSample.sessionId,
            rtpPacketsSent = senderSample.packets,
            rtpSenderActive = senderSample.active,
        )
    }
    // Technical explanations are progressive disclosure, not obstacles to changing a setting.
    var showTechnicalDetails by remember(graph.ownerKey) { mutableStateOf(false) }
    // Only one stage can be expanded. A new track/epoch drops old expansion.
    var expandedStageId by remember(graph.ownerKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(graph.nodes.map { it.stableId }, graph.controls.map { it.stableId }) {
        if (expandedStageId != null && (graph.nodes + graph.controls).none { it.stableId == expandedStageId }) {
            expandedStageId = null
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        AeroFloatingSurface(
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(4.dp)
                .testTag("signal_truth_popup"),
        ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Signal Truth", color = PureWhite,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 23.sp))
                    Text(when (destination) {
                        SignalDestination.LOCAL -> "Cómo llega la música a tu salida"
                        SignalDestination.MICHI_STREAM -> "Qué sale del teléfono hacia Michi Stream"
                        SignalDestination.REMOTE_HOST -> "Qué sabemos del host remoto"
                    }, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cerrar")
                }
            }
            // A readable, reliable answer before the detailed signal flow.
            SignalTruthRouteOverview(
                destination = destination,
                currentTrack = currentTrack,
                routeEvidence = routeEvidence,
                onOpenOutputRoutes = onOpenOutputRoutes,
            )
            // Both settings and graph nodes live in one lazy scrolling surface.
            // This avoids hiding the stages on compact devices or at 200% font scale.
            SignalGraphList(graph, expandedStageId, onExpand = { stageId ->
                expandedStageId = if (expandedStageId == stageId) null else stageId
            }, onOpenEqualizer = onOpenEqualizer, onOpenOutputRoutes = onOpenOutputRoutes,
                showTechnicalDetails = showTechnicalDetails,
                onToggleTechnicalDetails = { showTechnicalDetails = !showTechnicalDetails },
                usbManager = usbManager, routeStatus = routeStatus,
                destination = destination, modifier = Modifier.weight(1f))
        }
        }
    }
}

/** Explain the destination without promoting requested/remembered routes to verified audio. */
@Composable
private fun SignalTruthRouteOverview(
    destination: SignalDestination,
    currentTrack: Track?,
    routeEvidence: AudioRouteEvidence?,
    onOpenOutputRoutes: () -> Unit,
) {
    val local = destination == SignalDestination.LOCAL
    val headline = when (destination) {
        SignalDestination.LOCAL -> routeEvidence?.deviceLabel?.takeIf { it.isNotBlank() }
            ?: "Salida aún no identificada"
        SignalDestination.MICHI_STREAM -> "Receptor Michi Stream"
        SignalDestination.REMOTE_HOST -> "Reproducción remota"
    }
    val explanation = when (destination) {
        SignalDestination.LOCAL -> if (routeEvidence?.hasCurrentEvidence == true)
            "Ruta observada por Android; formato físico del DAC sin comprobar."
            else "Salida pendiente de confirmar. Puedes elegir otra salida."
        SignalDestination.MICHI_STREAM -> "El envío RTP local no confirma lo que suena en el receptor."
        SignalDestination.REMOTE_HOST -> "Los efectos y el dispositivo del host no se miden aquí."
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF211A30),
        border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.13f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("signal_truth_route_overview"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(currentTrack?.title?.takeIf { it.isNotBlank() } ?: "Pista actual",
                color = OnSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(headline, color = PureWhite, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(explanation, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (local) {
                OutlinedButton(onClick = onOpenOutputRoutes, modifier = Modifier
                    .heightIn(min = 48.dp).testTag("signal_truth_change_output")) {
                    Text("Cambiar salida de audio")
                }
            }
        }
    }
}

/** One local processing-mode editor, without duplicating the controls already
 * embedded in the actual DSP and routing stages. */
@Composable
private fun SignalTruthQuickSettings(destination: SignalDestination, ownerKey: String) {
    if (destination != SignalDestination.LOCAL) return
    var showMode by remember(destination, ownerKey) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Modo de procesamiento", style = MaterialTheme.typography.titleSmall, color = PureWhite)
        Text("Este ajuste modifica el motor local; no verifica el DAC físico.",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        OutlinedButton(
            onClick = { showMode = !showMode },
            modifier = Modifier.heightIn(min = 48.dp).testTag("signal_truth_mode_toggle"),
        ) { Text(if (showMode) "Ocultar modo de audio" else "Cambiar modo de audio") }
        if (showMode) SignalTruthAudioModeControls()
    }
}

/** A stable, scrollable signal chain. The rail is drawn only between successive
 * stages of the same *real* edge, never between unrelated output branches. */
@Composable
private fun SignalGraphList(
    graph: SignalGraphUi,
    expandedStageId: String?,
    onExpand: (String) -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenOutputRoutes: () -> Unit,
    showTechnicalDetails: Boolean,
    onToggleTechnicalDetails: () -> Unit,
    usbManager: UsbDacManager?,
    routeStatus: AudioRouteStatus,
    destination: SignalDestination,
    modifier: Modifier = Modifier,
) {
    val sections = remember(graph.nodes) { SignalTruthChainPresentation.sections(graph.nodes) }
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("signal_truth_chain"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "path-explanation") {
            Column(modifier = Modifier.padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Ruta de la señal", color = PureWhite,
                    style = MaterialTheme.typography.titleMedium)
                Text("De la canción a la salida. Abre cada etapa para ajustar lo disponible.",
                    color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (destination == SignalDestination.LOCAL) {
            item(key = "processing-mode-settings") {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    SignalTruthQuickSettings(destination, graph.ownerKey)
                }
            }
        }
        sections.forEach { section ->
            if (section.branchId != "main") {
                item(key = "branch-${section.branchId}") {
                    Column(modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
                        Text(SignalTruthChainPresentation.branchTitle(section.branchId),
                            style = MaterialTheme.typography.titleSmall, color = TertiaryCyan)
                        Text("Bifurcación desde el procesamiento PCM; no es una continuación física de la rama anterior.",
                            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                }
            }
            itemsIndexed(section.stages, key = { _, stage -> stage.stableId }) { index, stage ->
                SignalGraphStage(
                    stage = stage, sequenceNumber = index + 1,
                    expanded = expandedStageId == stage.stableId,
                    onExpand = { onExpand(stage.stableId) },
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenOutputRoutes = onOpenOutputRoutes,
                    allowRouteSelection = destination == SignalDestination.LOCAL,
                    showTechnicalDetails = showTechnicalDetails,
                    usbManager = usbManager, routeStatus = routeStatus,
                )
                val next = section.stages.getOrNull(index + 1)
                if (next != null && graph.edges.any {
                        it.fromId == stage.stableId && it.toId == next.stableId
                    }) {
                    Box(modifier = Modifier.padding(start = 13.dp)
                        .width(2.dp).height(18.dp)
                        .background(TertiaryCyan.copy(alpha = 0.42f)))
                } else if (next != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
        if (graph.controls.isNotEmpty()) {
            item(key = "settings-heading") {
                Column(modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)) {
                    Text("Ajustes disponibles", style = MaterialTheme.typography.titleMedium,
                        color = PureWhite)
                    Text("No forman parte del procesamiento activo.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                }
            }
            itemsIndexed(graph.controls, key = { _, stage -> stage.stableId }) { _, stage ->
                SignalGraphStage(
                    stage = stage, sequenceNumber = null,
                    expanded = expandedStageId == stage.stableId,
                    onExpand = { onExpand(stage.stableId) },
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenOutputRoutes = onOpenOutputRoutes,
                    allowRouteSelection = destination == SignalDestination.LOCAL,
                    showTechnicalDetails = showTechnicalDetails,
                    usbManager = usbManager, routeStatus = routeStatus,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        item(key = "technical-details-toggle") {
            Column(modifier = Modifier.padding(top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onToggleTechnicalDetails,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("signal_truth_technical_toggle")) {
                    Text(if (showTechnicalDetails) "Ocultar explicaciones técnicas"
                        else "¿Qué significan estos datos?")
                }
                if (showTechnicalDetails) {
                    Text("Observado: dato del motor local. Configurado: ajuste solicitado. " +
                        "Declarado: información del archivo. Sin verificar: no hay evidencia suficiente. " +
                        "Ninguno certifica por sí solo el reloj físico del DAC ni la reproducción remota.",
                        color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SignalStageEditor(
    action: SignalStageAction,
    onOpenEqualizer: () -> Unit,
    onOpenOutputRoutes: () -> Unit,
    usbManager: UsbDacManager?,
    routeStatus: AudioRouteStatus,
) {
    when (action) {
        SignalStageAction.OPEN_EQ -> OutlinedButton(onClick = onOpenEqualizer,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Abrir ecualizador") }
        SignalStageAction.USB_MIXER -> if (usbManager != null) {
            UsbDacMixerControls(manager = usbManager, routeStatus = routeStatus)
        }
        SignalStageAction.REPLAY_GAIN -> SignalTruthReplayGainControls()
        SignalStageAction.HEADPHONE_PROFILE -> SignalTruthHeadphoneControls(
            onOpenOutputRoutes = onOpenOutputRoutes)
        SignalStageAction.NONE -> Unit
    }
}

@Composable
private fun SignalEvidenceLabel(evidenceType: SignalEvidenceType) {
    val (word, tint) = when (evidenceType) {
        SignalEvidenceType.OBSERVED -> "Observado" to TertiaryCyan
        SignalEvidenceType.DECLARED -> "Declarado" to OnSurfaceVariant
        SignalEvidenceType.CONFIGURED -> "Configurado" to SecondaryPurple
        SignalEvidenceType.UNKNOWN -> "Sin verificar" to Color(0xFFE8BB75)
    }
    Box(modifier = Modifier.clip(RoundedCornerShape(50))
        .background(tint.copy(alpha = 0.12f))
        .padding(horizontal = 9.dp, vertical = 5.dp)) {
        Text(word, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

/** The active action is always visible. Expanding only reveals diagnostics and
 * the real editor; the UI never creates a second DSP or playback authority. */
@Composable
private fun SignalGraphStage(
    stage: SignalStageUi,
    sequenceNumber: Int?,
    expanded: Boolean,
    onExpand: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenOutputRoutes: () -> Unit,
    allowRouteSelection: Boolean,
    showTechnicalDetails: Boolean,
    usbManager: UsbDacManager?,
    routeStatus: AudioRouteStatus,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val tint = when (stage.evidenceType) {
        SignalEvidenceType.OBSERVED -> TertiaryCyan
        SignalEvidenceType.DECLARED -> OnSurfaceVariant
        SignalEvidenceType.CONFIGURED -> SecondaryPurple
        SignalEvidenceType.UNKNOWN -> Color(0xFFE8BB75)
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.size(28.dp)
            .background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Text(sequenceNumber?.toString() ?: "•", color = tint,
                style = MaterialTheme.typography.labelMedium)
        }
        Surface(
            modifier = Modifier.weight(1f).testTag("signal_stage_${stage.stableId}"),
            shape = RoundedCornerShape(16.dp),
            color = if (highContrast) Color(0xFF151C27) else Color(0xFF211A30),
            border = BorderStroke(1.dp, PureWhite.copy(alpha =
                if (highContrast) 0.58f else if (expanded) 0.26f else 0.12f)),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Column(modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onExpand)
                    .semantics { contentDescription = "${stage.label}. ${stage.summary}. " +
                        if (expanded) "Ocultar detalles" else "Consultar detalles" }
                    .heightIn(min = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(SignalTruthChainPresentation.label(stage), color = PureWhite,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(end = 8.dp))
                        SignalEvidenceLabel(stage.evidenceType)
                    }
                    Text(SignalTruthChainPresentation.summary(stage), color = OnSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
                val isRouteStage = allowRouteSelection && stage.stableId == "main:route"
                val actionLabel = if (isRouteStage) "Cambiar salida"
                    else SignalTruthChainPresentation.actionLabel(stage.action)
                if (actionLabel != null) {
                    TextButton(
                        onClick = {
                            if (isRouteStage) onOpenOutputRoutes()
                            else if (stage.action == SignalStageAction.OPEN_EQ) onOpenEqualizer()
                            else onExpand()
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("signal_action_${stage.stableId}"),
                    ) { Text(if (expanded && !isRouteStage && stage.action != SignalStageAction.OPEN_EQ)
                        "Cerrar ajustes" else actionLabel, color = TertiaryCyan) }
                }
                if (expanded) {
                    if (showTechnicalDetails) {
                        stage.note?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = OnSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (stage.freshnessMs > 0L) {
                            "Existe una observación registrada; no demuestra flujo activo ahora."
                        } else "Esta etapa no tiene un recibo temporal propio.",
                            color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    } else if (stage.action == SignalStageAction.NONE && !isRouteStage) {
                        Text("Consulta la descripción de esta etapa. Activa las explicaciones técnicas " +
                            "al final si necesitas revisar su procedencia.",
                            color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (stage.action != SignalStageAction.NONE && stage.action != SignalStageAction.OPEN_EQ) {
                        SignalStageEditor(stage.action, onOpenEqualizer, onOpenOutputRoutes,
                            usbManager, routeStatus)
                    }
                }
            }
        }
    }
}
