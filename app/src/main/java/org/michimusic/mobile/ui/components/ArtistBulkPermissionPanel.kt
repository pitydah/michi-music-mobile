package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.michimusic.data.artist.ArtistBulkPermissionRecord
import org.michimusic.mobile.artist.ArtistBulkDurableSessionStore
import org.michimusic.mobile.artist.ArtistBulkPreparedSession
import org.michimusic.mobile.artist.ArtistBulkPreview
import org.michimusic.mobile.artist.ArtistBulkCredit
import org.michimusic.mobile.artist.ArtistBulkDryRun
import org.michimusic.mobile.artist.ArtistEnrichmentSelectionStore
import org.michimusic.mobile.artist.artistBulkCanRestoreDraft
import org.michimusic.mobile.library.ArtistBulkLibrarySnapshotReader
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/** D23: explicitly staged lookup consents. No HTTP, WorkManager or data edits. */
@Composable
internal fun ArtistBulkPermissionPanel(
    session: ArtistBulkPreparedSession,
    journal: ArtistBulkDurableSessionStore,
    reader: ArtistBulkLibrarySnapshotReader?,
    selectionStore: ArtistEnrichmentSelectionStore,
    preview: ArtistBulkPreview,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    suspend fun matchesCurrentReceipt(fingerprint: String): Boolean {
        val witness = reader ?: return false
        val receipt = session.receipts.singleOrNull { it.fingerprint == fingerprint } ?: return false
        val row = preview.rows.singleOrNull { it.diagnosticFingerprint == fingerprint } ?: return false
        val expected = session.snapshot
        val latest = ArtistBulkDryRun(selectionStore, witness).preview(expected,
            listOf(ArtistBulkCredit(expected.libraryId, receipt.artistCredit, receipt.albumIds, row.trackCount)))
        return artistBulkCanRestoreDraft(session.copy(receipts = listOf(receipt)), latest, witness.current())
    }
    var permits by remember(session.sessionId) {
        mutableStateOf<List<ArtistBulkPermissionRecord>>(emptyList())
    }
    var pendingFingerprint by remember(session.sessionId) { mutableStateOf<String?>(null) }
    var acknowledged by remember(session.sessionId) { mutableStateOf(false) }
    var busy by remember(session.sessionId) { mutableStateOf(false) }
    var message by remember(session.sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(session.sessionId, session.cancelled) {
        try {
            permits = withContext(Dispatchers.IO) { journal.permissions(session.sessionId) }
            pendingFingerprint = null
            acknowledged = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "No se pudieron comprobar los permisos locales."
        }
    }
    var now by remember(session.sessionId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(permits, session.sessionId) {
        // One bounded wait for each next expiry, not network or periodic polling.
        while (true) {
            now = System.currentTimeMillis()
            val next = permits.filter { it.isCurrent(now) }.minOfOrNull { it.expiresAtEpochMs } ?: break
            delay((next - now).coerceIn(1L, ArtistBulkPermissionRecord.MAX_LIFETIME_MS))
        }
    }
    val canEdit = enabled && !session.cancelled && reader != null && !busy
    Text("Permisos individuales · Sin ejecución", color = PureWhite, fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("artist_bulk_permissions_heading"))
    Text("Esta sección solo registra o revoca un permiso temporal de consulta de perfil. " +
        "No realiza solicitudes externas ni escribe etiquetas o fotografías.",
        color = TextSecondary, fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 20.dp))
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 212.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(session.receipts, key = { it.fingerprint }) { receipt ->
            val stored = permits.firstOrNull { it.fingerprint == receipt.fingerprint }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
                Text(receipt.artistCredit, color = PureWhite, fontSize = 12.sp)
                val status = when {
                    stored == null -> "Sin permiso"
                    stored.status == ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED -> "Revocado"
                    stored.status == ArtistBulkPermissionRecord.RESERVED_NO_IO -> "Reservado localmente · Sin ejecutar"
                    stored.status == ArtistBulkPermissionRecord.RELEASED_NO_IO -> "Reserva cerrada · Sin ejecutar"
                    !stored.isCurrent(now) -> "Caducado o no vigente · Nunca ejecutado"
                    else -> "Consentimiento guardado · Sin ejecutar"
                }
                Text(status, color = TextSecondary, fontSize = 11.sp)
                if (stored == null && canEdit) {
                    TextButton(
                        onClick = {
                            pendingFingerprint = receipt.fingerprint
                            acknowledged = false
                            message = null
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("artist_bulk_permission_review_${receipt.fingerprint.take(8)}"),
                    ) { Text("Revisar consentimiento", color = TertiaryCyan) }
                } else if (stored != null &&
                    stored.status == ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED &&
                    canEdit
                ) {
                    TextButton(
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            scope.launch {
                                try {
                                    val after = withContext(Dispatchers.IO) {
                                        journal.revokePermission(session.sessionId, receipt.fingerprint)
                                    }
                                    permits = withContext(Dispatchers.IO) {
                                        journal.permissions(session.sessionId)
                                    }
                                    message = if (after != null) "Permiso revocado en Room."
                                        else "La revocación no fue confirmada por Room."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    message = "No se pudo confirmar la revocación."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("artist_bulk_permission_revoke_${receipt.fingerprint.take(8)}"),
                    ) { Text("Revocar permiso", color = TertiaryCyan) }
                }
            }
        }
    }
    val chosen = session.receipts.singleOrNull { it.fingerprint == pendingFingerprint }
    if (chosen != null && canEdit && permits.none { it.fingerprint == chosen.fingerprint }) {
        Text("Permiso para ${chosen.artistCredit}: una futura consulta externa " +
            "del perfil público. No permite cambiar identidad, fotos ni etiquetas; " +
            "caduca en cinco minutos y no inicia ninguna tarea.",
            color = TextSecondary, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 20.dp).testTag("artist_bulk_permission_disclosure"))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it },
                modifier = Modifier.testTag("artist_bulk_permission_acknowledge"))
            Text("Confirmo solamente este permiso de consulta para este artista.",
                color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 14.dp))
        }
        OutlinedButton(
            enabled = acknowledged && !busy,
            onClick = {
                if (!acknowledged || busy) return@OutlinedButton
                val selectedFingerprint = chosen.fingerprint
                busy = true
                acknowledged = false
                scope.launch {
                    try {
                        val fresh = withContext(Dispatchers.IO) { reader?.current() } ?: error("Missing current library")
                        if (fresh != session.snapshot || withContext(Dispatchers.IO) {
                                withTimeoutOrNull(10_000L) { matchesCurrentReceipt(selectedFingerprint) } != true
                            }) {
                            message = "La biblioteca o la identidad cambió: no se guardó permiso alguno."
                        } else {
                            val status = withContext(Dispatchers.IO + NonCancellable) {
                                // Finish the local CAS and its compensating revoke even if the
                                // screen is disposed between Room commit and context check.
                                val created = journal.grantProfileLookup(session.sessionId, selectedFingerprint,
                                    fresh, acknowledgedNetworkConsent = true)
                                if (created == null) "Permiso rechazado por revisión, identidad o caducidad."
                                else {
                                    val same = try { withTimeoutOrNull(10_000L) { matchesCurrentReceipt(selectedFingerprint) } == true }
                                        catch (_: Exception) { false }
                                    if (!same) {
                                        val revoked = journal.revokePermission(session.sessionId, selectedFingerprint)
                                        if (revoked != null) "Cambió el contexto. Permiso revocado en Room."
                                        else "Cambió el contexto; no se confirmó la revocación. No existe autorización de ejecución."
                                    } else "Permiso guardado. No se ejecutó ninguna consulta."
                                }
                            }
                            message = status
                            permits = withContext(Dispatchers.IO) {
                                journal.permissions(session.sessionId)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = "No se pudo verificar el permiso en Room."
                    } finally {
                        pendingFingerprint = null
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 48.dp)
                .testTag("artist_bulk_permission_save"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TertiaryCyan),
        ) { Text("Guardar permiso · No ejecutar") }
        TextButton(onClick = { pendingFingerprint = null; acknowledged = false },
            modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 48.dp)) {
            Text("Volver sin autorizar", color = TertiaryCyan)
        }
    }
    message?.let {
        Text(it, color = TextSecondary, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
                .testTag("artist_bulk_permission_result"))
    }
}
