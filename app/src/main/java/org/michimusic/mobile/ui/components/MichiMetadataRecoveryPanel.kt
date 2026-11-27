package org.michimusic.mobile.ui.components

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.data.metadata.MetadataRecoveryConsole
import org.michimusic.data.metadata.MetadataRecoveryCoordinator
import org.koin.compose.koinInject
import org.michimusic.data.cache.MichiDatabase

/**
 * Explicit, manually invoked recovery. Inspection and the private-only discard
 * never touch audio. A separate two-click confirmation can restore the original
 * bytes through the typed permit when the durable journal says the physical
 * mutation is uncertain. No background polls or automatic repair.
 */
@Composable
internal fun MichiMetadataRecoveryPanel(
    sourceId: String,
    trackId: String,
    uri: String,
    ownerStillCurrent: () -> Boolean,
    playbackClosed: () -> Boolean,
) {
    val context = LocalContext.current
    val database: MichiDatabase = koinInject()
    val console = remember(context, database) {
        MetadataRecoveryConsole(context.applicationContext, database)
    }
    val scope = rememberCoroutineScope()
    var state by remember(sourceId, trackId, uri) {
        mutableStateOf<MetadataRecoveryConsole.Status?>(null)
    }
    var pendingConfirm by remember(sourceId, trackId, uri) { mutableStateOf(false) }
    var pendingRestoreConfirm by remember(sourceId, trackId, uri) { mutableStateOf(false) }
    var busy by remember(sourceId, trackId, uri) { mutableStateOf(false) }
    var message by remember(sourceId, trackId, uri) { mutableStateOf<String?>(null) }
    var restoreMessage by remember(sourceId, trackId, uri) { mutableStateOf<String?>(null) }
    var finishMessage by remember(sourceId, trackId, uri) { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            try { state = console.inspect(sourceId, trackId, uri, explicitUserAction = true) }
            finally { busy = false }
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        restoreMessage = if (result.resultCode == Activity.RESULT_OK)
            "Permiso concedido. Confirma de nuevo la restauración del original."
        else "Permiso de escritura no concedido. No se modificó el archivo."
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).testTag("metadata_recovery_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Text("Recuperación de metadatos", style = MaterialTheme.typography.titleSmall)
        Text(
            "Solo bajo solicitud. Comprobar o descartar una preparación privada no modifica " +
                "la música; restaurar el original es una acción destructiva aparte.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(enabled = !busy, onClick = {
            pendingConfirm = false
            pendingRestoreConfirm = false
            message = null
            restoreMessage = null
            busy = true
            refresh()
        }) { Text(if (busy) "Comprobando…" else "Comprobar operación pendiente") }
        when (val current = state) {
            null -> Unit
            MetadataRecoveryConsole.Status.NoPending ->
                Text("No hay una operación privada pendiente.")
            MetadataRecoveryConsole.Status.OtherTrackHasPendingWork ->
                Text("Hay otra operación pendiente. No se tocarán sus respaldos.")
            MetadataRecoveryConsole.Status.CorruptOrInaccessibleKeepBackup ->
                Text("Registro no verificable. Se conservan los respaldos para revisión.")
            MetadataRecoveryConsole.Status.ActionMustBeExplicit ->
                Text("La comprobación requiere una acción explícita.")
            is MetadataRecoveryConsole.Status.ProtectedKeepBackup -> {
                Text(
                    "Operación potencialmente interrumpida " +
                        "(${current.phase?.name ?: "DESCONOCIDA"}). Respaldos protegidos.",
                )
                val finishable = current.phase in setOf(
                    MetadataRecoveryPhase.MUTATING,
                    MetadataRecoveryPhase.MUTATION_UNKNOWN,
                    MetadataRecoveryPhase.POSTCHECK_VERIFIED,
                )
                if (finishable && current.transactionId.isNotBlank()) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val result = console.resumeExact(
                                    transactionId = current.transactionId,
                                    sourceId = sourceId, trackId = trackId, uri = uri,
                                    explicitlyConfirmed = true,
                                    ownerStillCurrent = ownerStillCurrent,
                                    playbackClosed = playbackClosed,
                                )
                                finishMessage = when (result) {
                                    is MetadataRecoveryCoordinator.Outcome.Indexed ->
                                        "Guardado finalizado y biblioteca actualizada."
                                    is MetadataRecoveryCoordinator.Outcome.IndexPending ->
                                        "Archivo verificado; biblioteca pendiente."
                                    is MetadataRecoveryCoordinator.Outcome.RecoveryRequired ->
                                        "Recuperación pendiente: ${result.code}."
                                    is MetadataRecoveryCoordinator.Outcome.Blocked ->
                                        "No se pudo finalizar: ${result.code}."
                                }
                                refresh()
                            } finally { busy = false }
                        }
                    }, modifier = Modifier.testTag("metadata_recovery_finish_save")) {
                        Text("Finalizar guardado / actualizar biblioteca")
                    }
                }
                finishMessage?.let { Text(it) }
                val restorable = current.phase == MetadataRecoveryPhase.MUTATING ||
                    current.phase == MetadataRecoveryPhase.MUTATION_UNKNOWN
                if (restorable) {
                    if (!pendingRestoreConfirm) {
                        TextButton(enabled = !busy, onClick = {
                            pendingRestoreConfirm = true
                            restoreMessage = null
                        }) { Text("Solicitar restauración del original") }
                    } else {
                        Text(
                            "Se escribirá el respaldo verificado sobre el archivo musical y se " +
                                "releerá su SHA. Si la restauración no puede confirmarse, se " +
                                "conservarán el respaldo y la copia candidata.",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(enabled = !busy, onClick = {
                                pendingRestoreConfirm = false
                            }) { Text("Cancelar") }
                            TextButton(
                                enabled = !busy,
                                modifier = Modifier.testTag("metadata_restore_confirm"),
                                onClick = {
                                    pendingRestoreConfirm = false
                                    busy = true
                                    restoreMessage = null
                                    scope.launch {
                                        try {
                                            val outcome = console.restoreOriginal(
                                                sourceId, trackId, uri,
                                                explicitlyConfirmed = true,
                                                ownerStillCurrent = ownerStillCurrent,
                                                playbackClosed = playbackClosed,
                                            )
                                            restoreMessage = when (outcome) {
                                                MetadataRecoveryConsole.RestoreResult.RestoredVerified ->
                                                    "Restauración completada. El original fue releído y verificado."
                                                MetadataRecoveryConsole.RestoreResult.OriginalWasIntact ->
                                                    "El original ya estaba intacto; no se escribió nada."
                                                MetadataRecoveryConsole.RestoreResult.CandidateInstalledFinalizeRequired ->
                                                    "La escritura ya estaba instalada; falta finalizar la biblioteca."
                                                is MetadataRecoveryConsole.RestoreResult.PermissionRequired -> {
                                                    consentLauncher.launch(
                                                        IntentSenderRequest.Builder(
                                                            outcome.intent.intentSender,
                                                        ).build(),
                                                    )
                                                    "Se necesita permiso de escritura para este archivo."
                                                }
                                                is MetadataRecoveryConsole.RestoreResult.KeptPrivateCopies ->
                                                    "No se pudo confirmar la restauración (${outcome.observed}). " +
                                                        "Se conservan el respaldo y la copia candidata; sigue pendiente."
                                                is MetadataRecoveryConsole.RestoreResult.Blocked ->
                                                    "No se pudo restaurar (${outcome.reason}). " +
                                                        "Se conservan las protecciones."
                                                MetadataRecoveryConsole.RestoreResult.ActionRequired ->
                                                    "La restauración requiere confirmación explícita."
                                                MetadataRecoveryConsole.RestoreResult.NoExactPendingRestore ->
                                                    "No hay una restauración exacta pendiente."
                                            }
                                        } finally {
                                            state = console.inspect(
                                                sourceId, trackId, uri,
                                                explicitUserAction = true,
                                            )
                                            busy = false
                                        }
                                    }
                                },
                            ) { Text("Confirmar restauración") }
                        }
                    }
                }
            }
            is MetadataRecoveryConsole.Status.DiscardablePrivateStage -> {
                Text(
                    "Preparación privada pendiente (${current.phase.name}). " +
                        "El original no se ha modificado mediante esta operación.",
                )
                if (!pendingConfirm) {
                    TextButton(enabled = !busy, onClick = { pendingConfirm = true }) {
                        Text("Solicitar descarte de preparación")
                    }
                } else {
                    Text(
                        "Se borrarán únicamente los archivos temporales privados. " +
                            "No se restaurará ni modificará música.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(enabled = !busy, onClick = { pendingConfirm = false }) {
                            Text("Cancelar")
                        }
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            pendingConfirm = false
                            scope.launch {
                                try {
                                    val outcome = console.discardPrivatePreparation(
                                        sourceId, trackId, uri,
                                        current.transactionId,
                                        explicitlyConfirmed = true,
                                    )
                                    message = if (outcome ==
                                        MetadataRecoveryConsole.DiscardResult.DISCARDED_PRIVATE_ONLY)
                                        "Preparación privada descartada. Música sin modificar."
                                    else
                                        "No se pudo descartar (${outcome.name}). " +
                                            "Se conservan las protecciones."
                                } finally {
                                    state = console.inspect(
                                        sourceId, trackId, uri,
                                        explicitUserAction = true,
                                    )
                                    busy = false
                                }
                            }
                        }) { Text("Confirmar descarte") }
                    }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        restoreMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("metadata_restore_message"),
            )
        }
    }
}
