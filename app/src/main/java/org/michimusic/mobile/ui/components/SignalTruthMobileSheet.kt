package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.SignalEvidenceType
import org.michimusic.core.models.Track
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.playback.SignalDestination
import org.michimusic.mobile.playback.SignalGraphUi
import org.michimusic.mobile.playback.SignalStageAction
import org.michimusic.mobile.playback.SignalStageUi
import org.michimusic.mobile.playback.SignalTruthEvidencePolicy
import org.michimusic.mobile.playback.SignalTruthGraphProjector
import org.michimusic.mobile.playback.SignalTruthOwnerPolicy
import org.michimusic.mobile.playback.SignalTruthResolver
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.player.AudioRouteStatus
import org.michimusic.player.UsbDacInfo
import org.michimusic.player.UsbDacManager

private enum class SignalQuickPanel { MENU, MODE, HEADPHONES, REPLAY_GAIN }

/** Mobile-first inspector: real stages, one expanded stage, no playback side-effects.
 * DSP changes are delegated to the existing editor; mixer controls to UsbDacManager.
 * This is presentation, NOT a substitute for owner-bound playback evidence receipts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalTruthMobileSheet(
    observation: AudioSignalObservation,
    activeEndpoint: PlaybackEndpoint,
    currentTrack: Track?,
    isRemote: Boolean,
    streamSelectedBySession: Boolean,
    onDismiss: () -> Unit,
) {
    var showEqEditor by remember { mutableStateOf(false) }
    val isStream = SignalTruthEvidencePolicy.isMichiStream(isRemote, activeEndpoint, streamSelectedBySession)
    val destination = when {
        isStream -> SignalDestination.MICHI_STREAM
        isRemote -> SignalDestination.REMOTE_HOST
        else -> SignalDestination.LOCAL
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
    // This is a provisional UI key, not full queue-entry / library ownership.
    // No proof is elevated because two StateFlows can update at different times.
    val ownerKey = "${activeEndpoint.id}/${currentTrack?.id ?: "none"}/" +
        "${observation.trackGeneration}/${observation.audioTrackGeneration}/${observation.routeGeneration}"
    val graph = remember(provenanceItems, safeObservation, destination, ownerKey, routeEvidence, usbConfirmed) {
        SignalTruthGraphProjector.project(
            provenance = provenanceItems,
            observation = safeObservation,
            destination = destination,
            ownerKey = ownerKey,
            routeDescription = routeEvidence?.let { "${it.headline}: ${it.deviceLabel}" },
            routeObserved = routeEvidence?.hasCurrentEvidence == true &&
                safeObservation.outputObservedAtMs > 0L,
            canConfigureUsb = usbConfirmed,
        )
    }
    // Only one stage can be expanded. A new track/epoch drops old expansion.
    var expandedStageId by remember(graph.ownerKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(graph.nodes.map { it.stableId }) {
        if (expandedStageId != null && graph.nodes.none { it.stableId == expandedStageId }) {
            expandedStageId = null
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceObsidian,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Signal Truth", color = PureWhite, style = MaterialTheme.typography.titleLarge)
                    Text(when (destination) {
                        SignalDestination.LOCAL -> "Ruta local del teléfono"
                        SignalDestination.MICHI_STREAM -> "Transmisión Michi Stream"
                        SignalDestination.REMOTE_HOST -> "Reproducción en host remoto"
                    }, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onDismiss) { Text("Cerrar") }
            }
            // Both settings and graph nodes live in one lazy scrolling surface.
            // This avoids hiding the stages on compact devices or at 200% font scale.
            SignalGraphList(graph, expandedStageId, onExpand = { stageId ->
                expandedStageId = if (expandedStageId == stageId) null else stageId
            }, onOpenEqualizer = { showEqEditor = true }, usbManager = usbManager,
                routeStatus = routeStatus, destination = destination)
        }
    }
    if (showEqEditor) {
        EqualizerDialog(onDismiss = { showEqEditor = false })
    }
}

/** Quick settings are outside the causal signal graph, but inside the scrolling sheet. */
@Composable
private fun SignalTruthQuickSettings(
    destination: SignalDestination,
    ownerKey: String,
    onOpenEqualizer: () -> Unit,
) {
    var quickPanel by remember(destination, ownerKey) { mutableStateOf<SignalQuickPanel?>(null) }
    if (destination == SignalDestination.LOCAL) {
        OutlinedButton(onClick = {
            quickPanel = if (quickPanel == null) SignalQuickPanel.MENU else null
        }, modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 48.dp)) {
            Text("Ajustes rápidos de audio")
        }
        if (quickPanel != null) {
            // Keep a single compact editor at a time; never duplicate DSP engines.
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { quickPanel = SignalQuickPanel.MODE },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Modo DSP")
                }
                OutlinedButton(onClick = { quickPanel = SignalQuickPanel.HEADPHONES },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Auriculares")
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenEqualizer() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Ecualizador")
                }
                OutlinedButton(onClick = { quickPanel = SignalQuickPanel.REPLAY_GAIN },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("ReplayGain")
                }
            }
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                when (quickPanel) {
                    SignalQuickPanel.MODE -> SignalTruthAudioModeControls()
                    SignalQuickPanel.HEADPHONES -> SignalTruthHeadphoneControls()
                    SignalQuickPanel.REPLAY_GAIN -> SignalTruthReplayGainControls()
                    SignalQuickPanel.MENU, null -> Unit
                }
            }
        }
    }
}

@Composable
private fun SignalGraphList(
    graph: SignalGraphUi,
    expandedStageId: String?,
    onExpand: (String) -> Unit,
    onOpenEqualizer: () -> Unit,
    usbManager: UsbDacManager?,
    routeStatus: AudioRouteStatus,
    destination: SignalDestination,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item(key = "quick-settings") {
            SignalTruthQuickSettings(destination, graph.ownerKey, onOpenEqualizer)
        }
        item(key = "evidence-explanation") {
            Text("Toca una etapa para consultar su evidencia. Un ajuste no demuestra su aplicación.",
                color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 10.dp))
        }
        itemsIndexed(graph.nodes, key = { _, stage -> stage.stableId }) { index, stage ->
            if (index > 0) {
                val previous = graph.nodes[index - 1]
                val edge = graph.edges.firstOrNull { it.toId == stage.stableId }
                if (edge?.fromId == previous.stableId) {
                    Spacer(Modifier.padding(start = 14.dp).width(1.dp).height(15.dp)
                        .background(OnSurfaceVariant.copy(alpha = 0.40f)))
                    if (previous.branchId != stage.branchId) {
                        Text("↳ Rama ${if (stage.branchId == "rtp") "RTP" else "local"}",
                            color = OnSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    // A real graph branch: do not draw a false physical connector
                    // from the preceding (unrelated) device to this stage.
                    Text("↳ Rama ${if (stage.branchId == "local") "local" else "RTP"} " +
                        "(desde la bifurcación PCM)", color = OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            val expanded = stage.stableId == expandedStageId
            SignalGraphStage(stage, expanded, onClick = { onExpand(stage.stableId) })
            if (expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 26.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stage.note?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Text(if (stage.freshnessMs > 0L) {
                        "Existe registro temporal para esta etapa. No confirma flujo activo ahora."
                    } else "Sin recibo temporal propio de esta etapa.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    when (stage.action) {
                        SignalStageAction.OPEN_EQ -> OutlinedButton(onClick = onOpenEqualizer) {
                            Text("Editar ecualizador")
                        }
                        SignalStageAction.USB_MIXER -> if (usbManager != null) {
                            UsbDacMixerControls(manager = usbManager, routeStatus = routeStatus)
                        }
                        SignalStageAction.REPLAY_GAIN -> SignalTruthReplayGainControls()
                        SignalStageAction.HEADPHONE_PROFILE -> SignalTruthHeadphoneControls()
                        SignalStageAction.NONE -> Unit
                    }
                }
            }
            HorizontalDivider(color = OnSurfaceVariant.copy(alpha = 0.12f))
        }
    }
}

@Composable
private fun SignalGraphStage(stage: SignalStageUi, expanded: Boolean, onClick: () -> Unit) {
    val (text, hue) = when (stage.evidenceType) {
        SignalEvidenceType.OBSERVED -> "Observado" to TertiaryCyan
        SignalEvidenceType.DECLARED -> "Declarado" to OnSurfaceVariant
        SignalEvidenceType.CONFIGURED -> "Configurado" to SecondaryPurple
        SignalEvidenceType.UNKNOWN -> "Desconocido" to Color(0xFFE5A93C)
    }
    Column(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .clickable(onClick = onClick)
        .semantics { contentDescription = "${stage.label}. ${stage.summary}. Evidencia: $text. " +
            if (expanded) "Contraer detalles" else "Expandir detalles" }
        .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stage.label, color = PureWhite, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            Text(text, color = hue, style = MaterialTheme.typography.labelSmall)
        }
        Text(stage.summary, color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (expanded) Text("Ocultar detalles", color = TertiaryCyan,
            style = MaterialTheme.typography.labelSmall)
    }
}
