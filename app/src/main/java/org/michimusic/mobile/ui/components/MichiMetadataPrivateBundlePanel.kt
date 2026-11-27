package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.michimusic.core.metadata.MetadataPrepareTicket
import org.michimusic.core.metadata.MetadataPrivateCandidateAudit
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSelectiveAssetStage
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.data.metadata.MetadataPrivateBundleCoordinator
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * ME-10I: ONE user-initiated, private-only copy. The coordinator owns all file IO.
 * This composable owns only a short-lived request and cancels it when review closes.
 */
@Composable
internal fun MichiMetadataPrivateBundlePanel(
    original: MetadataSnapshot,
    expectedBytes: Long,
    fieldReview: MetadataReview,
    artLyrics: MetadataArtLyricsUiState,
    coordinator: MetadataPrivateBundleCoordinator,
    stillOwns: () -> Boolean,
    playbackDescriptorClosed: () -> Boolean,
    currentSessionAllowsPreparation: Boolean,
    modifier: Modifier = Modifier,
    onInspect: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val inspected = artLyrics.inspected
    val fields = fieldReview as? MetadataReview.Ready
    // An audit receipt MUST belong to the exact draft, not merely the song.
    // Changing a field, described-text operation, picture or observation
    // cancels the old task and resets its previous "Prepared" display.
    val proposalKey = listOf(
        original.target,
        fields?.diffs?.toList(),
        artLyrics.artwork?.let {
            listOf(it.index, it.sourceSha256, it.proposedSha256,
                it.proposedMime, it.proposedSize)
        },
        artLyrics.operations.entries.sortedBy { it.key.toString() }.map { it.key to it.value },
        inspected?.format,
        inspected?.artworks?.map { it.info },
        inspected?.texts?.toList(),
    )
    val runningJob = remember(proposalKey) { mutableStateOf<Job?>(null) }
    var job by runningJob
    var state by remember(proposalKey) { mutableStateOf<PreparationState>(PreparationState.Idle) }
    var audit by remember(proposalKey) { mutableStateOf<MetadataPrivateCandidateAudit.Result?>(null) }
    var auditing by remember(proposalKey) { mutableStateOf(false) }
    val auditJob = remember(proposalKey) { mutableStateOf<Job?>(null) }
    val preparing = remember(proposalKey) { AtomicBoolean(false) }
    val alive = remember(proposalKey) { AtomicBoolean(true) }
    DisposableEffect(alive) {
        alive.set(true)
        onDispose { alive.set(false); runningJob.value?.cancel(); auditJob.value?.cancel() }
    }
    val assets = buildList<MetadataSelectiveAssetStage.Edit> {
        artLyrics.artwork?.let { add(MetadataSelectiveAssetStage.Edit.Picture(it)) }
        artLyrics.operations.values.forEach { add(MetadataSelectiveAssetStage.Edit.DescribedText(it)) }
    }
    val invalidText = artLyrics.textResult is org.michimusic.core.metadata.MetadataDescribedTextWorkbench.Result.Blocked
    val hasChanges = fields != null || assets.isNotEmpty()
    val eligible = inspected != null && hasChanges && assets.size <= 4 && !invalidText &&
        fieldReview !is MetadataReview.Blocked && expectedBytes in 20L..(128L * 1024L * 1024L) &&
        currentSessionAllowsPreparation && stillOwns() && playbackDescriptorClosed()
    val busy = state is PreparationState.Working || state == PreparationState.Cancelling
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Preparación privada", style = MaterialTheme.typography.titleMedium)
        Text("Prepara una copia independiente para inspeccionarla. No guarda las etiquetas en la canción, no modifica Room ni concede permisos de escritura.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        if (inspected == null) {
            when (val load = artLyrics.load) {
                MetadataArtLyricsUiState.Load.Loading -> Text(
                    "Inspeccionando el archivo…",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                is MetadataArtLyricsUiState.Load.Blocked -> Text(
                    "No se pudo inspeccionar: ${load.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                else -> {
                    Text(
                        "Antes de preparar, inspecciona los metadatos incrustados. " +
                            "No hace falta salir de esta revisión.",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                    )
                    if (onInspect != null) {
                        Button(
                            onClick = onInspect,
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                                .testTag("metadata_preflight_inspect"),
                        ) { Text("Inspeccionar el archivo") }
                    }
                }
            }
        }
        if (assets.size > 4) Text("Máximo cuatro cambios de carátula o texto por copia privada.",
            color = MaterialTheme.colorScheme.error)
        if (fieldReview is MetadataReview.Blocked || invalidText)
            Text("Corrige las propuestas bloqueadas antes de preparar.", color = MaterialTheme.colorScheme.error)
        if (!currentSessionAllowsPreparation || !stillOwns() || !playbackDescriptorClosed())
            Text("Preparación bloqueada: cambió la biblioteca, existe una sesión activa o no se puede demostrar que la pista está libre en el reproductor.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                val observed = inspected ?: return@Button
                if (!eligible || busy || state is PreparationState.Prepared ||
                    !preparing.compareAndSet(false,true)) return@Button
                // Copy image bytes before dispatching; the request must be immutable.
                val frozenAssets = assets.map { asset ->
                    when (asset) {
                        is MetadataSelectiveAssetStage.Edit.Picture -> asset.copy(
                            proposal = asset.proposal.copy(proposedBytes = asset.proposal.proposedBytes.copyOf()))
                        is MetadataSelectiveAssetStage.Edit.PictureAdd -> asset.copy(
                            proposal = asset.proposal.copy(proposedBytes = asset.proposal.proposedBytes.copyOf()))
                        is MetadataSelectiveAssetStage.Edit.PictureRemove -> asset
                        is MetadataSelectiveAssetStage.Edit.DescribedText -> asset
                    }
                }
                state = PreparationState.Working(MetadataPrivateBundleCoordinator.Phase.CHECKING_IDENTITY)
                job = scope.launch {
                    try {
                        val outcome = coordinator.prepare(MetadataPrivateBundleCoordinator.Request(
                            ticket = MetadataPrepareTicket(
                                original.target.sourceId, original.target.trackId, original.target.uri,
                                expectedBytes, explicitUserAction = true,
                                playbackClosed = false, ownerStillCurrent = false),
                            original = original,
                            fields = fields,
                            observed = observed,
                            assets = frozenAssets,
                            playbackDescriptorClosed = playbackDescriptorClosed,
                            stillOwns = { alive.get() && stillOwns() },
                            onProgress = { progress ->
                                // The coordinator reports from Dispatchers.IO. Deliver Compose
                                // state changes on the scope's main dispatcher, in order.
                                if (alive.get()) scope.launch {
                                    if (alive.get() && state is PreparationState.Working)
                                        state = PreparationState.Working(progress.phase,
                                            progress.copiedBytes, progress.totalBytes)
                                }
                            },
                        ))
                        if (alive.get()) state = when (outcome) {
                            is MetadataPrivateBundleCoordinator.Result.Prepared -> PreparationState.Prepared(outcome)
                            is MetadataPrivateBundleCoordinator.Result.Refused -> PreparationState.Refused(outcome.reason)
                        }
                    } catch (cancel: CancellationException) {
                        if (alive.get()) state = PreparationState.Cancelled
                        throw cancel
                    } catch (_: Exception) {
                        if (alive.get()) state = PreparationState.Failed
                    } finally { preparing.set(false) }
                }
            },
            enabled = eligible && !busy && state !is PreparationState.Prepared,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                .testTag("metadata_prepare_private_copy"),
        ) { Text("Preparar copia para revisar") }
        when (val current = state) {
            PreparationState.Idle -> Unit
            is PreparationState.Working -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text(current.phase.label, style = MaterialTheme.typography.bodySmall)
                }
                if (current.phase == MetadataPrivateBundleCoordinator.Phase.COPYING_SOURCE && current.total > 0)
                    Text("${current.copied / 1024 / 1024} de ${current.total / 1024 / 1024} MiB copiados",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                TextButton(onClick = {
                    state = PreparationState.Cancelling
                    job?.cancel()
                }, modifier = Modifier.testTag("metadata_cancel_private_copy")) {
                    Text("Cancelar preparación")
                }
            }
            PreparationState.Cancelling -> Text("Cancelación solicitada; se está cerrando la operación privada…",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            is PreparationState.Prepared -> {
                Text("Copia privada preparada. El archivo musical original NO fue modificado.",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Text("Sesión ${current.receipt.sessionId.take(8)}… · ${current.receipt.changes.size} cambios · ${current.receipt.candidateBytes / 1024} KiB",
                    style = MaterialTheme.typography.bodySmall)
                Text("SHA-256 candidato: ${current.receipt.candidateSha256.take(16)}… · CAN_COMMIT=false",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                TextButton(
                    enabled = !auditing,
                    onClick = {
                        audit = null
                        auditJob.value?.cancel()
                        auditJob.value = scope.launch {
                            auditing = true
                            try {
                                val outcome=coordinator.verifyPrivateSession(current.receipt.sessionId, original.target)
                                if(alive.get()) audit = outcome
                            } catch (cancel: CancellationException) { throw cancel }
                              catch (_: Exception) {
                                if(alive.get()) audit=MetadataPrivateCandidateAudit.Result.Refused(
                                    MetadataPrivateCandidateAudit.Reason.IO_UNAVAILABLE)
                            } finally { if(alive.get()) auditing=false }
                        }
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp)
                        .testTag("metadata_verify_prepared_private_copy"),
                ) { Text("Verificar integridad de la copia") }
                if (auditing) CircularProgressIndicator()
                audit?.let { Text(it.userMessage(),
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
            }
            is PreparationState.Refused -> Text("Preparación rechazada: ${current.reason.userMessage()}. No se guardaron cambios en la biblioteca.",
                color = MaterialTheme.colorScheme.error)
            PreparationState.Cancelled -> Text("Preparación cancelada. No se modificó la biblioteca.", color = TextSecondary)
            PreparationState.Failed -> Text("No pudo prepararse la copia. Inspecciona las sesiones privadas si el proceso se interrumpió.",
                color = MaterialTheme.colorScheme.error)
        }
    }
}

private sealed interface PreparationState {
    data object Idle : PreparationState
    data object Cancelling : PreparationState
    data class Working(val phase: MetadataPrivateBundleCoordinator.Phase,
        val copied: Long = 0, val total: Long = 0) : PreparationState
    data class Prepared(val receipt: MetadataPrivateBundleCoordinator.Result.Prepared) : PreparationState
    data class Refused(val reason: MetadataPrivateBundleCoordinator.Rejection) : PreparationState
    data object Cancelled : PreparationState
    data object Failed : PreparationState
}

private val MetadataPrivateBundleCoordinator.Phase.label: String get() = when (this) {
    MetadataPrivateBundleCoordinator.Phase.CHECKING_IDENTITY -> "Comprobando identidad y procedencia…"
    MetadataPrivateBundleCoordinator.Phase.COPYING_SOURCE -> "Copiando el documento local al espacio privado…"
    MetadataPrivateBundleCoordinator.Phase.VERIFYING_SOURCE -> "Verificando la copia y el origen…"
    MetadataPrivateBundleCoordinator.Phase.REWRITING_PRIVATE_COPY -> "Aplicando la propuesta a la copia privada…"
    MetadataPrivateBundleCoordinator.Phase.FINAL_VERIFICATION -> "Revisando integridad y cambios externos…"
    MetadataPrivateBundleCoordinator.Phase.RECEIPT_CREATED -> "Preparación privada completada…"
}

/** Session inventory is app-private, not track-scoped; never call this in composition. */
@Composable
internal fun MichiMetadataPrivateSessionsPanel(
    coordinator: MetadataPrivateBundleCoordinator,
    currentTarget: org.michimusic.core.metadata.MetadataTarget,
) {
    val scope = rememberCoroutineScope()
    var inventory by remember(coordinator) { mutableStateOf<MetadataPrivateBundleCoordinator.SessionInventory?>(null) }
    var loading by remember(coordinator) { mutableStateOf(false) }
    var pendingDiscard by remember(coordinator) { mutableStateOf<String?>(null) }
    var feedback by remember(coordinator) { mutableStateOf<String?>(null) }
    var visibleCount by remember(coordinator) { mutableStateOf(8) }
    var verifyingSession by remember(coordinator) { mutableStateOf<String?>(null) }
    var verification by remember(coordinator) { mutableStateOf<Pair<String, MetadataPrivateCandidateAudit.Result>?>(null) }
    val runningJob = remember(coordinator) { mutableStateOf<Job?>(null) }
    var job by runningJob
    DisposableEffect(coordinator) { onDispose { runningJob.value?.cancel() } }
    fun reload() {
        job?.cancel()
        job = scope.launch {
            loading = true
            try { inventory = coordinator.inspectPrivateSessions(); feedback = null; visibleCount = 8
                verification = null }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { feedback = "No se pudo inspeccionar el almacenamiento privado." }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Copias privadas del editor", style = MaterialTheme.typography.titleMedium)
        Text("El inventario es de toda la aplicación. Verificar exige además que el recibo V2 pertenezca a esta pista; los recibos V1 son históricos y no demuestran propietario. No contiene respaldos de restauración.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Button(onClick = { reload() }, enabled = !loading,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                .testTag("metadata_private_sessions_inspect")) { Text("Inspeccionar copias privadas") }
        if (loading) CircularProgressIndicator()
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        inventory?.let { result ->
            Text("Inventario: ${result.status.userMessage()} · ${result.sessions.size} sesiones visibles" +
                if (result.hasMore) " · Existen más sesiones, inventario parcial" else "",
                style = MaterialTheme.typography.bodySmall)
            result.sessions.take(visibleCount).forEach { session ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Sesión ${session.id.take(8)}…")
                        Text(session.state.userMessage(), color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        TextButton(
                            enabled = !loading && verifyingSession == null,
                            onClick = {
                                job?.cancel()
                                job = scope.launch {
                                    verifyingSession = session.id
                                    verification = null
                                    try { verification = session.id to coordinator.verifyPrivateSession(session.id, currentTarget) }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (_: Exception) { feedback = "No se pudo comprobar la integridad privada." }
                                    finally { verifyingSession = null }
                                }
                            },
                            modifier = Modifier.sizeIn(minHeight = 48.dp)
                                .testTag("metadata_private_verify_${session.id}"),
                        ) { Text("Verificar") }
                        TextButton(onClick = { pendingDiscard = session.id },
                            enabled = verifyingSession == null && !loading,
                            modifier = Modifier.sizeIn(minHeight = 48.dp)
                                .testTag("metadata_private_discard_${session.id}")) {
                            Text("Descartar")
                        }
                    }
                }
            }
            if (verifyingSession != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Verificando hashes privados de la sesión…")
                }
            }
            verification?.let { (id, audited) ->
                Text("Sesión ${id.take(8)}…: ${audited.userMessage()}",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            if (visibleCount < result.sessions.size) TextButton(onClick = {
                visibleCount = (visibleCount + 8).coerceAtMost(result.sessions.size)
            }) { Text("Mostrar ocho sesiones más") }
        }
    }
    pendingDiscard?.let { id ->
        AlertDialog(onDismissRequest = { pendingDiscard = null },
            title = { Text("¿Descartar esta copia privada?") },
            text = { Text("Solo se eliminarán los temporales privados de esta sesión. No se tocará ninguna canción, y esta acción no restaura ni confirma una escritura.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDiscard = null
                    job?.cancel()
                    job = scope.launch {
                        try {
                            val ok = coordinator.discardPrivateSession(id, userConfirmed = true)
                            feedback = if (ok) "Copia privada descartada." else "No se pudo descartar de forma segura."
                            inventory = coordinator.inspectPrivateSessions()
                            verification = null
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { feedback = "No se pudo completar el descarte seguro." }
                    }
                }) { Text("Confirmar descarte") }
            }, dismissButton = { TextButton(onClick = { pendingDiscard = null }) { Text("Conservar") } })
    }
}

private fun MetadataPrivateBundleCoordinator.Rejection.userMessage(): String = when (this) {
    MetadataPrivateBundleCoordinator.Rejection.USER_ACTION_REQUIRED -> "la operación requiere una acción explícita"
    MetadataPrivateBundleCoordinator.Rejection.BAD_OWNER_OR_TARGET -> "cambió la identidad de la pista o la biblioteca"
    MetadataPrivateBundleCoordinator.Rejection.ACTIVE_PLAYBACK -> "la pista sigue retenida por reproducción"
    MetadataPrivateBundleCoordinator.Rejection.UNVERIFIED_TAGS -> "faltan cambios o lecturas verificadas"
    MetadataPrivateBundleCoordinator.Rejection.INVALID_OR_UNSUPPORTED_SIZE -> "el tamaño del documento supera el límite privado"
    MetadataPrivateBundleCoordinator.Rejection.FIELD_REVIEW_REJECTED -> "la revisión de campos ya no es válida"
    MetadataPrivateBundleCoordinator.Rejection.SOURCE_CHANGED -> "el archivo original cambió desde la observación"
    MetadataPrivateBundleCoordinator.Rejection.PROVIDER_UNAVAILABLE -> "el proveedor de archivos no respondió"
    MetadataPrivateBundleCoordinator.Rejection.NOT_ALLOWED_PROVIDER -> "el proveedor o permiso de lectura no está autorizado"
    MetadataPrivateBundleCoordinator.Rejection.PRIVATE_STORAGE_UNAVAILABLE -> "no hay almacenamiento privado suficiente o seguro"
    MetadataPrivateBundleCoordinator.Rejection.PRIVATE_BUNDLE_REJECTED -> "los cambios no superaron la verificación del candidato"
    MetadataPrivateBundleCoordinator.Rejection.PROPOSAL_FAILED -> "la operación privada no pudo completarse"
    MetadataPrivateBundleCoordinator.Rejection.PRIVATE_SESSION_LIMIT_REACHED ->
        "se alcanzó el máximo de ocho sesiones privadas; revisa y descarta alguna manualmente"
}

private fun MetadataPrivateBundleCoordinator.SessionInspectionStatus.userMessage(): String = when (this) {
    MetadataPrivateBundleCoordinator.SessionInspectionStatus.READY -> "revisión disponible"
    MetadataPrivateBundleCoordinator.SessionInspectionStatus.NOT_INITIALIZED -> "sin copias previas"
    MetadataPrivateBundleCoordinator.SessionInspectionStatus.UNSAFE_ROOT -> "almacenamiento privado no seguro"
    MetadataPrivateBundleCoordinator.SessionInspectionStatus.ENUMERATION_FAILED -> "no se pudo enumerar"
}

private fun MetadataPrivateBundleCoordinator.SessionState.userMessage(): String = when (this) {
    MetadataPrivateBundleCoordinator.SessionState.PENDING_OR_INTERRUPTED -> "preparación pendiente o interrumpida"
    MetadataPrivateBundleCoordinator.SessionState.FILES_PRESENT_UNVERIFIED ->
        "candidato y recibo presentes, integridad todavía sin verificar"
    MetadataPrivateBundleCoordinator.SessionState.UNREADABLE -> "sesión ilegible o insegura"
}

/** Never translate matching hashes as verified metadata or safe Android write. */
private fun MetadataPrivateCandidateAudit.Result.userMessage(): String = when(this) {
    is MetadataPrivateCandidateAudit.Result.HashesMatchPrivateOnly ->
        "Hashes privados y del audio coinciden. " +
            (if (ownerMatched) "La huella del recibo V2 corresponde a esta pista. "
             else "No se contrastó la identidad de la pista. ") +
            "Esto NO verifica todos los tags ni autoriza modificar la canción (CAN_COMMIT=false)."
    is MetadataPrivateCandidateAudit.Result.Refused -> when(reason) {
        MetadataPrivateCandidateAudit.Reason.INVALID_RECEIPT ->
            "recibo incompleto, corrupto o de otra sesión"
        MetadataPrivateCandidateAudit.Reason.MISSING_OR_UNSAFE_FILES ->
            "archivos ausentes, incompletos o inseguros"
        MetadataPrivateCandidateAudit.Reason.ORIGINAL_HASH_MISMATCH ->
            "la copia privada del origen ya no coincide con el recibo"
        MetadataPrivateCandidateAudit.Reason.CANDIDATE_HASH_MISMATCH ->
            "el candidato privado ya no coincide con el recibo"
        MetadataPrivateCandidateAudit.Reason.AUDIO_HASH_MISMATCH ->
            "el audio de las copias privadas no conserva la misma huella"
        MetadataPrivateCandidateAudit.Reason.FORMAT_MISMATCH ->
            "las copias privadas no tienen el mismo formato"
        MetadataPrivateCandidateAudit.Reason.UNSUPPORTED_MEDIA ->
            "el formato de los archivos no admite comprobación conservadora"
        MetadataPrivateCandidateAudit.Reason.IO_UNAVAILABLE ->
            "no pudo completarse la lectura de verificación"
        MetadataPrivateCandidateAudit.Reason.OWNER_MISMATCH ->
            "la sesión no pertenece a la identidad y evidencia actuales de esta pista"
        MetadataPrivateCandidateAudit.Reason.LEGACY_OWNER_UNBOUND ->
            "recibo histórico V1 sin identidad de pista; puede descartarse, pero no acreditarse como suyo"
    }
}
