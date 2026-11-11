package org.michimusic.mobile.ui.screens

import org.michimusic.mobile.playback.SettingsReplayGainGateway
import org.michimusic.mobile.playback.SettingsReplayGainEdit
import org.michimusic.mobile.playback.SettingsReplayGainSaveStatus
import org.michimusic.mobile.playback.settingsReplayGainTruth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.TextButton
import org.michimusic.core.models.AudioProcessingMode
import org.michimusic.player.PlayerDependencies
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.ReplayGainMode






/** ReplayGain UI is a thin client of the canonical SharedPreferences bridge. */
@Composable
internal fun SettingsPlaybackPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gateway = remember(context) { SettingsReplayGainGateway.shared(context) }
    var selectedMode by remember { mutableStateOf(ReplayGainMode.OFF) }
    val preAmpWith = remember { mutableFloatStateOf(0f) }
    val preAmpWithout = remember { mutableFloatStateOf(0f) }
    var replayGainLoaded by remember { mutableStateOf(false) }
    var writeError by remember { mutableStateOf<String?>(null) }
    var writeNotice by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var retryEdit by remember { mutableStateOf<SettingsReplayGainEdit?>(null) }
    val output by PlayerDependencies.outputAuthority.collectAsStateWithLifecycle()
    val processing = PlayerDependencies.audioModeManager?.currentMode?.collectAsStateWithLifecycle()

    LaunchedEffect(gateway) {
        gateway.observe().collect { snapshot ->
            // A pending edit does not borrow the previous UI's draft. The commit
            // handler owns recovery, then the listener refreshes the truth.
            if (!pending) {
                selectedMode = snapshot.mode
                preAmpWith.floatValue = snapshot.withTagsDb
                preAmpWithout.floatValue = snapshot.withoutTagsDb
            }
            replayGainLoaded = true
        }
    }

    val submit: (SettingsReplayGainEdit) -> Unit = { edit ->
        if (!pending) {
            pending = true
            writeError = null
            writeNotice = null
            retryEdit = null
            scope.launch {
                try {
                    val receipt = gateway.save(edit)
                    receipt.snapshot?.let { snapshot ->
                        selectedMode = snapshot.mode
                        preAmpWith.floatValue = snapshot.withTagsDb
                        preAmpWithout.floatValue = snapshot.withoutTagsDb
                    }
                    when (receipt.status) {
                        SettingsReplayGainSaveStatus.CONFIGURED -> Unit
                        SettingsReplayGainSaveStatus.SAVED_FOR_LOCAL ->
                            writeNotice = "Guardado para el teléfono; no se ha confirmado procesamiento activo."
                        SettingsReplayGainSaveStatus.ROLLED_BACK -> {
                            writeError = "El procesador rechazó el cambio; se restauró el valor anterior."
                            retryEdit = edit
                        }
                        SettingsReplayGainSaveStatus.EXTERNAL_CHANGE ->
                            writeError = "Otro control modificó ReplayGain. Se muestra el estado actualizado."
                        SettingsReplayGainSaveStatus.REJECTED -> {
                            writeError = "No se pudo guardar el cambio. Se mantiene el valor anterior."
                            retryEdit = edit
                        }
                        SettingsReplayGainSaveStatus.STATE_UNCERTAIN -> {
                            writeError = "No se pudo confirmar la escritura. Comprueba la configuración antes de reintentar."
                            // No blind retry if the current persisted state is uncertain.
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    writeError = "No se pudo confirmar ReplayGain. Vuelve a abrir Ajustes para comprobarlo."
                } finally { pending = false }
            }
        }
    }
    val persistMode: (ReplayGainMode) -> Unit = { submit(SettingsReplayGainEdit.Mode(it)) }
    val commitWithTags: () -> Unit = {
        submit(SettingsReplayGainEdit.WithTags(preAmpWith.floatValue))
    }
    val commitWithoutTags: () -> Unit = {
        submit(SettingsReplayGainEdit.WithoutTags(preAmpWithout.floatValue))
    }
                        if (pending) Text("Guardando cambio…", color = TextSecondary,
                            modifier = Modifier.testTag("settings_replaygain_pending"))
                        writeNotice?.let { notice -> Text(notice, color = TextSecondary,
                            modifier = Modifier.testTag("settings_replaygain_notice")) }
                        if (writeError != null) {
                            Text(writeError!!, color = PrimaryPink,
                                modifier = Modifier.testTag("settings_replaygain_error"))
                            retryEdit?.let { original ->
                                TextButton(onClick = { submit(original) }, enabled = !pending,
                                    modifier = Modifier.testTag("settings_replaygain_retry")) {
                                    Text("Reintentar")
                                }
                            }
                        }
                        if (!replayGainLoaded) {
                            Text("Cargando preferencias de reproducción…",
                                color = TextSecondary, modifier = Modifier.testTag("settings_replaygain_loading"))
                        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SettingsSectionCard(
                                icon = Icons.Default.GraphicEq,
                                title = "ReplayGain",
                                subtitle = "Normalización y preamplificación de reproducción",
                                accent = TertiaryCyan,
                            ) {
                                Text(
                                    "Normaliza el volumen usando las etiquetas de tus archivos. " +
                                        "El modo de procesamiento y los dispositivos tienen pantallas propias.",
                                    color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(settingsReplayGainTruth(
                                    selectedMode,
                                    localOutput = output.isLocal && output.endpointId == "local_phone",
                                    processorAvailable = PlayerDependencies.replayGainProcessor != null,
                                    transparent = processing?.value == AudioProcessingMode.TRANSPARENT,
                                ), color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("settings_replaygain_truth"))
                                Spacer(Modifier.height(16.dp))


                                Text(
                                    text = "Modo de Normalización ReplayGain",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                )


                                Spacer(Modifier.height(10.dp))


                                // AeroSegmentedChoice is the canonical selected-option control:
                                // one visual/semantics implementation, minimum 48dp target.
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        ReplayGainMode.OFF, ReplayGainMode.TRACK,
                                        ReplayGainMode.ALBUM, ReplayGainMode.DYNAMIC,
                                    ).chunked(2).forEach { pair ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            pair.forEach { mode ->
                                                val label = when (mode) {
                                                    ReplayGainMode.OFF -> "Desactivado"
                                                    ReplayGainMode.TRACK -> "Pista"
                                                    ReplayGainMode.ALBUM -> "Álbum"
                                                    ReplayGainMode.DYNAMIC -> "Dinámico"
                                                }
                                                org.michimusic.mobile.ui.components.AeroSegmentedChoice(
                                                    label = label,
                                                    selected = selectedMode == mode,
                                                    onClick = { if (!pending) persistMode(mode) },
                                                    modifier = Modifier.weight(1f),
                                                    treatment = org.michimusic.mobile.ui.components.AeroSegmentTreatment.Filter,
                                                    testTag = "rg_mode_${mode.name}",
                                                )
                                            }
                                        }
                                    }
                                }
                            }


                            SettingsSectionCard(
                                icon = Icons.Default.Tune,
                                title = "Pre-amplificación ReplayGain",
                                subtitle = "Ajuste de decibelios con y sin tags RG",
                                accent = PrimaryPink,
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Pre-amp con tags RG", fontSize = 13.sp, color = TextPrimary)
                                        Text(String.format("%.1f dB", preAmpWith.floatValue), fontSize = 13.sp, color = TertiaryCyan, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    org.michimusic.mobile.ui.components.MichiFineSlider(
                                        value = preAmpWith.floatValue,
                                        onValueChange = { if (!pending) preAmpWith.floatValue = it },
                                        onValueChangeFinished = { if (!pending) commitWithTags() },
                                        valueRange = -15f..15f,
                                        steps = 29,
                                        accentColor = TertiaryCyan,
                                    )
                                }


                                Spacer(Modifier.height(12.dp))


                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Pre-amp sin tags RG", fontSize = 13.sp, color = TextPrimary)
                                        Text(String.format("%.1f dB", preAmpWithout.floatValue), fontSize = 13.sp, color = PrimaryPink, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    org.michimusic.mobile.ui.components.MichiFineSlider(
                                        value = preAmpWithout.floatValue,
                                        onValueChange = { if (!pending) preAmpWithout.floatValue = it },
                                        onValueChangeFinished = { if (!pending) commitWithoutTags() },
                                        valueRange = -15f..15f,
                                        steps = 29,
                                        accentColor = PrimaryPink,
                                    )
                                }
                            }
                        }
}
