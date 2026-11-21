package org.michimusic.mobile.ui.components

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
import org.michimusic.data.metadata.MetadataRecoveryConsole

/**
 * Explicit, read-only recovery inspection. The second click can delete ONLY
 * a private staging copy for the same exact selected local track. No audio
 * provider writes, permission prompts, background polls or automatic repair.
 */
@Composable
internal fun MichiMetadataRecoveryPanel(
    sourceId: String, trackId: String, uri: String,
) {
    val context=LocalContext.current
    val console=remember(context) { MetadataRecoveryConsole(context.applicationContext) }
    val scope=rememberCoroutineScope()
    var state by remember(sourceId,trackId,uri) {
        mutableStateOf<MetadataRecoveryConsole.Status?>(null)
    }
    var pendingConfirm by remember(sourceId,trackId,uri) { mutableStateOf(false) }
    var busy by remember(sourceId,trackId,uri) { mutableStateOf(false) }
    var message by remember(sourceId,trackId,uri) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(top=12.dp).testTag("metadata_recovery_panel"),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Recuperación de metadatos", style=MaterialTheme.typography.titleSmall)
        Text("Solo bajo solicitud. No se modificará el archivo musical.",
            style=MaterialTheme.typography.bodySmall)
        TextButton(enabled=!busy,onClick={
            pendingConfirm=false
            message=null
            busy=true
            scope.launch {
                try { state=console.inspect(sourceId,trackId,uri,explicitUserAction=true) }
                finally { busy=false }
            }
        }) { Text(if(busy) "Comprobando…" else "Comprobar operación pendiente") }
        when(val current=state) {
            null -> Unit
            MetadataRecoveryConsole.Status.NoPending ->
                Text("No hay una operación privada pendiente.")
            MetadataRecoveryConsole.Status.OtherTrackHasPendingWork ->
                Text("Hay otra operación pendiente. No se tocarán sus respaldos.")
            MetadataRecoveryConsole.Status.CorruptOrInaccessibleKeepBackup ->
                Text("Registro no verificable. Se conservan los respaldos para revisión.")
            MetadataRecoveryConsole.Status.ActionMustBeExplicit ->
                Text("La comprobación requiere una acción explícita.")
            is MetadataRecoveryConsole.Status.ProtectedKeepBackup ->
                Text("Operación potencialmente interrumpida (${current.phase?.name ?: "DESCONOCIDA"}). Respaldos protegidos; requiere recuperación certificada.")
            is MetadataRecoveryConsole.Status.DiscardablePrivateStage -> {
                Text("Preparación privada pendiente (${current.phase.name}). El original no se ha modificado mediante esta operación.")
                if (!pendingConfirm) {
                    TextButton(enabled=!busy,onClick={pendingConfirm=true}) { Text("Solicitar descarte de preparación") }
                } else {
                    Text("Se borrarán únicamente los archivos temporales privados. No se restaurará ni modificará música.")
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        TextButton(enabled=!busy,onClick={pendingConfirm=false}) { Text("Cancelar") }
                        TextButton(enabled=!busy,onClick={
                            busy=true
                            pendingConfirm=false
                            scope.launch {
                                try {
                                    val outcome=console.discardPrivatePreparation(sourceId,trackId,uri,
                                        current.transactionId,explicitlyConfirmed=true)
                                    message=if(outcome==MetadataRecoveryConsole.DiscardResult.DISCARDED_PRIVATE_ONLY)
                                        "Preparación privada descartada. Música sin modificar."
                                        else "No se pudo descartar (${outcome.name}). Se conservan las protecciones."
                                    state=console.inspect(sourceId,trackId,uri,explicitUserAction=true)
                                } finally { busy=false }
                            }
                        }) { Text("Confirmar descarte") }
                    }
                }
            }
        }
        message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
    }
}
