package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.michimusic.mobile.playback.SettingsReplayGainGateway
import org.michimusic.mobile.playback.SettingsReplayGainEdit
import org.michimusic.mobile.playback.SettingsReplayGainSaveStatus
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.michimusic.core.models.AudioProcessingMode
import org.michimusic.mobile.playback.ReplayGainPreferenceModeObserver
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.player.PlayerDependencies
import org.michimusic.player.ReplayGainMode

/** Existing preference authority is SettingsScreen / michi_settings.
 * This panel submits local intents through one coordinator; it is not a second DSP.
 */
@Composable
internal fun SignalTruthReplayGainControls() {
    val context = LocalContext.current
    val outputAuthority by PlayerDependencies.outputAuthority.collectAsStateWithLifecycle()
    // Compose reads lifecycle-aware State, never StateFlow.value during composition.
    val audioModeState = PlayerDependencies.audioModeManager?.currentMode?.collectAsStateWithLifecycle()
    // Same application-scoped gateway and mutex as Settings > Reproducción.
    val gateway = remember(context) { SettingsReplayGainGateway.shared(context) }
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    // Settings/Wizard and this sheet use the same persisted mode. This observer
    // updates the sheet when Settings changes it while Signal Truth is visible.
    val savedModeReader = remember(context) { ReplayGainPreferenceModeObserver(context) }
    val savedMode by produceState<ReplayGainMode?>(initialValue = null,
        key1 = savedModeReader) {
        savedModeReader.observe().collect { value = it }
    }
    val active = PlayerDependencies.replayGainMode.takeIf { PlayerDependencies.replayGainProcessor != null }
    val enabled = active != null && outputAuthority.isLocal && outputAuthority.endpointId == "local_phone"
    val modes = listOf(ReplayGainMode.OFF, ReplayGainMode.TRACK,
        ReplayGainMode.ALBUM, ReplayGainMode.DYNAMIC)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Normalización ReplayGain", style = MaterialTheme.typography.titleSmall)
        Text("Selecciona el modo del procesador local; no modifica el DAC físico.",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        modes.chunked(2).forEach { rowModes ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowModes.forEach { mode ->
                    val title = when (mode) {
                        ReplayGainMode.OFF -> "Desactivado"
                        ReplayGainMode.TRACK -> "Pista"
                        ReplayGainMode.ALBUM -> "Álbum"
                        ReplayGainMode.DYNAMIC -> "Dinámico"
                    }
                    OutlinedButton(
                        onClick = {
                            if (!pending) {
                                pending = true
                                feedback = null
                                // Capture route epoch: never claim ownership of a later session.
                                val lease = PlayerDependencies.outputAuthority.value
                                scope.launch {
                                    try {
                                        val current = PlayerDependencies.outputAuthority.value
                                        if (!lease.isLocal || lease.endpointId != "local_phone" ||
                                            current.endpointId != lease.endpointId || current.revision != lease.revision) {
                                            feedback = "La salida cambió. No se envió ReplayGain."
                                        } else {
                                            val receipt = gateway.save(SettingsReplayGainEdit.Mode(mode))
                                            val after = PlayerDependencies.outputAuthority.value
                                            feedback = if (after.revision != lease.revision ||
                                                after.endpointId != lease.endpointId || !after.isLocal) {
                                                "La salida cambió durante el ajuste. Comprueba el modo guardado."
                                            } else when (receipt.status) {
                                                SettingsReplayGainSaveStatus.CONFIGURED ->
                                                    "Modo configurado y guardado; no certifica muestras procesadas."
                                                SettingsReplayGainSaveStatus.SAVED_FOR_LOCAL ->
                                                    "Modo guardado; procesador no confirmado."
                                                SettingsReplayGainSaveStatus.ROLLED_BACK ->
                                                    "El procesador rechazó el cambio; se restauró el estado anterior."
                                                SettingsReplayGainSaveStatus.REJECTED ->
                                                    "El cambio fue rechazado; no se guardó."
                                                SettingsReplayGainSaveStatus.EXTERNAL_CHANGE ->
                                                    "Otra pantalla modificó ReplayGain. Revisa el estado actualizado."
                                                SettingsReplayGainSaveStatus.STATE_UNCERTAIN ->
                                                    "No se pudo confirmar la persistencia de ReplayGain."
                                            }
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        feedback = "Error al cambiar ReplayGain; comprueba la configuración."
                                    } finally { pending = false }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = enabled && !pending,
                    ) { Text(if (mode == active) "✓ $title" else title) }
                }
            }
        }
        if (!enabled) {
            Text("Ajustes deshabilitados: procesador local o autorización de salida no disponible.",
                style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        } else if (audioModeState?.value == AudioProcessingMode.TRANSPARENT) {
            Text("Modo transparente: la selección se guarda, pero ReplayGain está omitido.",
                style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
        val persistedMode = savedMode
        if (persistedMode != null && active != null && persistedMode != active) {
            Text("Preferencia guardada: ${persistedMode.name}; procesador actual: ${active.name}. " +
                "La configuración no es evidencia de efecto aplicado al audio.",
                style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
        feedback?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
    }
}
