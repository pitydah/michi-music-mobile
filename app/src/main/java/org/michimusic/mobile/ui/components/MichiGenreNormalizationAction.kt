package org.michimusic.mobile.ui.components

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.intelligence.MichiGenreLookupAuthorization
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.intelligence.MichiGenreTagWriteGateway
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.repository.MichiGenrePostWriteReconciler
import org.michimusic.mobile.intelligence.MichiGenreMusicBrainzGateway
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.screens.AlbumsViewModel

/**
 * Until Android 31–36 MediaStore/SAF write, crash-recovery and codec matrices pass,
 * production editing remains FAIL-CLOSED. Manual lookup and genre preview work.
 * OpenCode must not flip this merely because unit tests or a synthetic diff pass.
 */
private const val MI23_TAG_WRITES_CERTIFIED = false

/** User-opened metadata detail only. No search on composition or while scrolling. */
@Composable
internal fun MichiGenreNormalizationAction(track: Track, embeddedGenres: List<String>, tagsVerified: Boolean) {
    if (track.source != TrackSource.LOCAL) return
    val context = LocalContext.current
    val scopeStore: LibraryScopeStore = koinInject()
    val rootAuthority: MusicRootAuthority = koinInject()
    val session: PlaybackSessionManager = koinInject()
    val albumsViewModel: AlbumsViewModel = koinViewModel()
    val trackDao: TrackDao = koinInject()
    val coroutineScope = rememberCoroutineScope()
    // A new source/URI/metadata receipt cannot inherit another song's remote results.
    // Including the observed tag list also invalidates stale proposals after a refresh.
    val trackReceipt = remember(track.sourceId, track.id, track.filepath, track.title,
        track.artist, track.album, track.size, embeddedGenres) { Any() }
    // New metadata receipts own their permits; a previously opened song cannot reuse one.
    val authorization = remember(trackReceipt) { MichiGenreLookupAuthorization() }
    val gateway = remember(authorization) { MichiGenreMusicBrainzGateway(authorization = authorization) }
    val writer = remember(context) { MichiGenreTagWriteGateway(context.applicationContext) }
    val reconciler = remember(context, trackDao) {
        MichiGenrePostWriteReconciler(context.applicationContext, trackDao)
    }
    val intent = remember(track.title, track.artist, track.album) {
        MichiGenreNormalizationPolicy.prepareSearch(track.title,track.artist,track.album)
    }
    val uri = remember(track.filepath) { Uri.parse(track.filepath) }
    var showDialog by remember(trackReceipt) { mutableStateOf(false) }
    var previewNetwork by remember(trackReceipt) { mutableStateOf(false) }
    var confirmWrite by remember(trackReceipt) { mutableStateOf(false) }
    var confirmRecovery by remember(trackReceipt) { mutableStateOf(false) }
    var confirmUnknownRecovery by remember(trackReceipt) { mutableStateOf(false) }
    var recoveryGrantPending by remember(trackReceipt) { mutableStateOf(false) }
    var recoveryGrantUnknownOverride by remember(trackReceipt) { mutableStateOf(false) }
    var recoveryUnknownBytes by remember(trackReceipt) { mutableStateOf<Long?>(null) }
    var recoveryUnknownSha256 by remember(trackReceipt) { mutableStateOf<String?>(null) }
    var busy by remember(trackReceipt) { mutableStateOf(false) }
    var message by remember(trackReceipt) { mutableStateOf<String?>(null) }
    var pendingRecovery by remember(trackReceipt) {
        mutableStateOf<MichiGenreTagWriteGateway.RecoveryStatus?>(null)
    }
    var matches by remember(trackReceipt) {
        mutableStateOf<List<MichiGenreMusicBrainzGateway.Choice>>(emptyList())
    }
    var selectedRecording by remember(trackReceipt) {
        mutableStateOf<MichiGenreNormalizationPolicy.RecordingCandidate?>(null)
    }
    var proposals by remember(trackReceipt) {
        mutableStateOf<List<MichiGenreNormalizationPolicy.GenreProposal>>(emptyList())
    }
    var chosenGenre by remember(trackReceipt) { mutableStateOf("") }
    var pendingRecordingLookup by remember(trackReceipt) {
        mutableStateOf<MichiGenreNormalizationPolicy.RecordingCandidate?>(null)
    }
    var lookupRevision by remember(trackReceipt) { mutableStateOf(0L) }
    var lookupJob by remember(trackReceipt) { mutableStateOf<Job?>(null) }
    val capturedScope = remember(showDialog, trackReceipt) { scopeStore.activeScope.value }
    val capturedRoot = remember(showDialog, trackReceipt) { rootAuthority.revision.value }
    fun stillOwns(): Boolean {
        val s = session.sessionState.value
        // Even paused/current media may hold a descriptor; editing it is forbidden.
        val selectedInPlayer = s.currentTrack?.filepath == track.filepath
        return scopeStore.activeScope.value == capturedScope &&
            rootAuthority.revision.value == capturedRoot &&
            !s.isRemoteActive && !selectedInPlayer &&
            s.handoffState == org.michimusic.mobile.playback.HandoffState.IDLE &&
            s.activeEndpoint.isLocal && track.filepath == uri.toString()
    }
    // Neither a dismissed dialog nor a replaced track can receive late HTTP results.
    // A cancelled coroutine does not revoke an already completed network disclosure.
    DisposableEffect(trackReceipt) {
        onDispose { authorization.revoke(); lookupJob?.cancel() }
    }
    fun closeLookupDialog() {
        authorization.revoke()
        lookupRevision++
        lookupJob?.cancel()
        lookupJob = null
        pendingRecordingLookup = null
        previewNetwork = false
        showDialog = false
        busy = false
    }
    // A cold dialog reads one local receipt only; nothing runs in Home/scroll/idle.
    LaunchedEffect(showDialog, track.sourceId, track.id, track.filepath) {
        pendingRecovery = if (showDialog) try { writer.pendingRecovery() }
        catch (_: Exception) {
            MichiGenreTagWriteGateway.RecoveryStatus.Blocked("RECOVERY_CHECK_FAILED")
        } else null
    }

    suspend fun reportVerifiedWrite(ticket: MichiGenreTagWriteGateway.Ticket,
        saved: MichiGenreTagWriteGateway.Result.SavedVerified): String {
        if (saved.format == "UNCHANGED") return "El género ya coincide: ningún archivo fue modificado."
        val indexed = reconciler.reconcile(ticket, saved, ::stillOwns)
        // The normal library authority owns any MediaStore/SAF re-read. The DAO can
        // publish owner-exact evidence first, but never invent a cache commit.
        albumsViewModel.loadMedia(forceRefresh = true)
        return when (indexed) {
            MichiGenrePostWriteReconciler.Outcome.Indexed ->
                "Género guardado y verificado. Índice de esa pista actualizado; refrescando biblioteca."
            is MichiGenrePostWriteReconciler.Outcome.RefreshRequired ->
                "Archivo verificado; la biblioteca debe actualizar su índice (${indexed.reason})."
        }
    }

    fun recoveryBelongsToTrack(): Boolean = (pendingRecovery as?
        MichiGenreTagWriteGateway.RecoveryStatus.Pending)?.let { pending ->
        pending.sourceId == track.sourceId && pending.trackId == track.id &&
            pending.uri == track.filepath
    } == true

    suspend fun restoreFromExplicitRequest(allowUnknownBytes: Boolean = false) {
        val ticket = MichiGenreTagWriteGateway.Ticket(
            track.sourceId, track.id, track.filepath, embeddedGenres,
            track.title, track.artist, track.album, track.size,
        )
        // Bind extraordinary consent to the SHA-256 shown by the initial rejection.
        // A different file state MUST force a new preview and separate approval.
        val approvedSize = if (allowUnknownBytes) recoveryUnknownBytes else null
        val approvedHash = if (allowUnknownBytes) recoveryUnknownSha256 else null
        recoveryUnknownBytes = null
        recoveryUnknownSha256 = null
        val result = writer.restorePending(ticket, userConfirmed = true,
            writeGrantConfirmed = true, stillOwns = ::stillOwns,
            unknownBytesOverwriteConfirmed = allowUnknownBytes,
            expectedUnknownBytes = approvedSize, expectedUnknownSha256 = approvedHash)
        message = when (result) {
            MichiGenreTagWriteGateway.RecoveryResult.RestoredAndVerified -> {
                albumsViewModel.loadMedia(forceRefresh = true)
                "Archivo original restaurado y verificado. Actualizando biblioteca."
            }
            MichiGenreTagWriteGateway.RecoveryResult.OriginalAlreadyVerified -> {
                albumsViewModel.loadMedia(forceRefresh = true)
                "El archivo original ya estaba intacto. Se cerró la recuperación y se actualiza la biblioteca."
            }
            MichiGenreTagWriteGateway.RecoveryResult.CleanupCompleted -> {
                albumsViewModel.loadMedia(forceRefresh = true)
                "Se completó la limpieza de la operación anterior. Actualizando biblioteca."
            }
            MichiGenreTagWriteGateway.RecoveryResult.NoOriginalMutation ->
                "Recibo previo a escritura cerrado. El archivo nunca entró en fase destructiva."
            is MichiGenreTagWriteGateway.RecoveryResult.Rejected -> {
                val currentBytes = result.currentBytes
                val currentSha256 = result.currentSha256
                if (result.code == "UNKNOWN_CURRENT_BYTES_REQUIRES_SECOND_CONFIRMATION" &&
                    currentBytes != null && currentBytes >= 0 &&
                    currentSha256?.matches(Regex("[0-9a-f]{64}")) == true) {
                    // One-shot proof for the specific unknown content seen at this prompt.
                    recoveryUnknownBytes = currentBytes
                    recoveryUnknownSha256 = currentSha256
                    confirmUnknownRecovery = true
                    "El archivo contiene bytes inesperados. No se sobrescribió. Revisa la advertencia de recuperación."
                } else "No se restauró: ${result.code}"
            }
            is MichiGenreTagWriteGateway.RecoveryResult.Failed ->
                "Recuperación pendiente: ${result.code}. No borres los datos de la app."
        }
        pendingRecovery = writer.pendingRecovery()
    }

    val recoveryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val wasConfirmed = recoveryGrantPending
        val overrideUnknownBytes = recoveryGrantUnknownOverride
        recoveryGrantPending = false
        recoveryGrantUnknownOverride = false
        if (result.resultCode == Activity.RESULT_OK && wasConfirmed &&
            recoveryBelongsToTrack() && stillOwns() && MI23_TAG_WRITES_CERTIFIED) {
            busy = true
            coroutineScope.launch {
                try { restoreFromExplicitRequest(overrideUnknownBytes) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { message = "No se pudo completar la recuperación." }
                finally { busy = false }
            }
        } else message = "Permiso de restauración denegado o contexto cambiado."
    }

    // One pending request, scoped to the current dialog and selected track only.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (tagsVerified && MI23_TAG_WRITES_CERTIFIED && result.resultCode == Activity.RESULT_OK &&
            stillOwns() && confirmWrite) {
            val newGenre = chosenGenre
            confirmWrite = false
            busy = true
            coroutineScope.launch {
                try {
                    val ticket = MichiGenreTagWriteGateway.Ticket(
                        track.sourceId,track.id,track.filepath,embeddedGenres,
                        track.title,track.artist,track.album,track.size)
                    message = when (val result = writer.write(ticket,listOf(newGenre),true,true,true,::stillOwns)) {
                        is MichiGenreTagWriteGateway.Result.SavedVerified ->
                            reportVerifiedWrite(ticket, result)
                        is MichiGenreTagWriteGateway.Result.Rejected ->
                            "No se guardó: ${result.code}"
                        is MichiGenreTagWriteGateway.Result.Failed ->
                            "Error de escritura: ${result.code}. Revisa la copia de seguridad."
                    }
                    pendingRecovery = writer.pendingRecovery()
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { message="No se confirmó la escritura." }
                finally { busy=false }
            }
        } else { confirmWrite=false;message="Permiso denegado o biblioteca cambiada." }
    }

    TextButton(onClick = { showDialog=true }, modifier=Modifier.testTag("mi23_open_genre_normalization")) {
        Text("Normalizar género")
    }
    if (!showDialog) return
    AlertDialog(
        onDismissRequest = {
            if (!busy || lookupJob != null) closeLookupDialog()
        },
        title = { Text("Michi Intelligence · Género") },
        text = {
            Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                when (val recovery = pendingRecovery) {
                    is MichiGenreTagWriteGateway.RecoveryStatus.Pending -> Text(
                        "Recuperación local pendiente (${recovery.phase}). No se podrá guardar otra etiqueta " +
                            "hasta restaurar o verificar la copia original. El proceso no se reanuda solo."
                    )
                    is MichiGenreTagWriteGateway.RecoveryStatus.Blocked -> Text(
                        "El diario de recuperación no es verificable (${recovery.code}). Edición bloqueada."
                    )
                    else -> Unit
                }
                if (recoveryBelongsToTrack()) {
                    if (MI23_TAG_WRITES_CERTIFIED) {
                        TextButton(
                            onClick = { confirmRecovery = true },
                            enabled = !busy && stillOwns(),
                            modifier = Modifier.testTag("mi24_begin_explicit_recovery"),
                        ) { Text("Restaurar copia original") }
                    } else Text("La copia original está protegida. La restauración requiere certificación Android.")
                }
                Text(if (tagsVerified) "Género verificado: ${embeddedGenres.joinToString(" · ").ifBlank { "Sin género" }}"
                    else "Género del archivo no verificable. Puedes buscar sugerencias, pero no editar la etiqueta.")
                Text("Solo se buscará por título, artista y, si existe, álbum. Nunca se enviarán rutas, archivos, biblioteca, historial ni gustos.")
                if (intent == null) Text("No hay título y artista válidos para consultar MusicBrainz.")
                else {
                    Text("Datos exactos de búsqueda:")
                    intent.outgoingFields().forEach { (key,value) -> Text("$key: $value") }
                    TextButton(
                        onClick = { previewNetwork=true }, enabled = !busy,
                        modifier = Modifier.testTag("mi23_preview_external_search")) {
                        Text("Buscar géneros en MusicBrainz")
                    }
                }
                if (busy) CircularProgressIndicator()
                if (lookupJob != null) {
                    TextButton(
                        onClick = {
                            lookupRevision++
                            lookupJob?.cancel()
                            lookupJob = null
                            busy = false
                            message = "Consulta cancelada. No se publicaron resultados tardíos."
                        }, modifier = Modifier.testTag("mi27_cancel_lookup"),
                    ) { Text("Cancelar consulta") }
                }
                matches.forEach { choice ->
                    TextButton(
                        enabled = !busy && showDialog && stillOwns(),
                        onClick = {
                            // Selection is NOT consent to a second HTTP request.
                            pendingRecordingLookup = choice.recording
                        }, modifier=Modifier.fillMaxWidth().testTag("mi23_choose_recording")) {
                        Text("${choice.recording.title} · ${choice.recording.artist}" +
                            if(choice.plausible) " · Coincidencia revisable" else " · Identidad incierta")
                    }
                }
                if(selectedRecording!=null) Text("Grabación elegida: ${selectedRecording!!.mbid}")
                proposals.forEach { proposal ->
                    val selected = selectedRecording
                    val provenanceVerified = selected != null &&
                        MichiGenreNormalizationPolicy.approvedProposal(
                            selected, proposal, selected.mbid)
                    TextButton(
                        onClick = {
                            if (provenanceVerified && stillOwns())
                                chosenGenre = MichiGenreDisambiguationPolicy.suggestedTag(proposal.name)
                        }, enabled = !busy && provenanceVerified && stillOwns(),
                        modifier=Modifier.fillMaxWidth().testTag("mi23_proposed_genre")) {
                        Text("${proposal.name} · Clasificación: ${MichiGenreDisambiguationPolicy.suggestedTag(proposal.name)} · votos: ${proposal.votes ?: "s/d"}")
                    }
                }
                OutlinedTextField(
                    value=chosenGenre,onValueChange={chosenGenre=it},singleLine=true,
                    label={Text("Género elegido o escrito manualmente")},
                    modifier=Modifier.fillMaxWidth().testTag("mi23_manual_genre"))
                if (MichiGenreNormalizationPolicy.validateGenres(listOf(chosenGenre)) != null) {
                    Text("Actual: ${embeddedGenres.joinToString(" · ").ifBlank { "Sin género" }}")
                    val canonicalPreview = MichiGenreDisambiguationPolicy.suggestedTag(chosenGenre)
                    Text("Propuesto: $chosenGenre")
                    Text("Categoría en biblioteca: $canonicalPreview")
                    if (canonicalPreview != chosenGenre) {
                        TextButton(
                            modifier = Modifier.testTag("mi26_use_canonical_genre"),
                            enabled = !busy,
                            onClick = { chosenGenre = canonicalPreview },
                        ) { Text("Usar etiqueta normalizada: $canonicalPreview") }
                    }
                    if (MI23_TAG_WRITES_CERTIFIED && tagsVerified) {
                        TextButton(onClick={confirmWrite=true},enabled=!busy && stillOwns(),
                            modifier=Modifier.testTag("mi23_request_write")) {
                            Text(if (embeddedGenres.isEmpty()) "Guardar GENRE en archivo" else "Reemplazar GENRE existente")
                        }
                    } else Text("Escritura de etiquetas deshabilitada hasta certificar MediaStore/SAF y los formatos MP3/FLAC en Android.")
                }
                message?.let {Text(it)}
            }
        },
        confirmButton={ TextButton(
            onClick={closeLookupDialog()}, enabled = !busy || lookupJob != null,
        ) { Text("Cerrar") } },
    )
    if (previewNetwork && intent!=null) {
        AlertDialog(
            onDismissRequest={previewNetwork=false},
            title={Text("¿Consultar MusicBrainz?")},
            text={Column {Text("Se enviarán únicamente estos campos públicos:")
                intent.outgoingFields().forEach{(k,v)->Text("$k: $v")}
                Text("MusicBrainz puede registrar la IP y los parámetros de búsqueda. No se enviarán otros datos.")}},
            confirmButton={ TextButton(onClick={
                previewNetwork=false
                if (!showDialog || !stillOwns() ||
                    !MichiGenreNormalizationPolicy.isPreparedSearch(intent)) {
                    message = "Consulta cancelada: cambió la canción o la biblioteca."
                } else {
                    lookupRevision++
                    val requestedRevision = lookupRevision
                    lookupJob?.cancel()
                    busy=true;matches=emptyList();selectedRecording=null;proposals=emptyList();chosenGenre=""
                    pendingRecordingLookup=null
                    // Mint on the confirmed user click, NOT after coroutine dispatch.
                    // Closing the dialog before dispatch revokes this exact grant.
                    val approvedSearch = authorization.issueSearch(intent)
                    lookupJob = coroutineScope.launch {
                        try {
                            val results = gateway.search(intent, approvedSearch,
                                track.duration.takeIf {it>0})
                            if (lookupRevision == requestedRevision && showDialog && stillOwns()) {
                                matches = results
                                message = if (results.isEmpty()) "Sin grabaciones coincidentes." else null
                            }
                        } catch (cancel:CancellationException){ throw cancel }
                        catch (_:Exception){
                            if (lookupRevision == requestedRevision) message="MusicBrainz no respondió."
                        } finally {
                            if (lookupRevision == requestedRevision) {
                                busy=false
                                lookupJob=null
                            }
                        }
                    }
                }
            }, modifier=Modifier.testTag("mi23_send_once")) {Text("Enviar esta vez")} },
            dismissButton={TextButton(onClick={previewNetwork=false}){Text("Cancelar")}},
        )
    }
    pendingRecordingLookup?.let { chosen ->
        AlertDialog(
            onDismissRequest = { pendingRecordingLookup = null },
            title = { Text("¿Consultar los géneros de esta grabación?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Se enviará a MusicBrainz este identificador exacto:")
                    Text(chosen.mbid)
                    Text("Grabación: ${chosen.title} · ${chosen.artist}")
                    Text("Es una segunda solicitud de red, independiente de la búsqueda anterior. " +
                        "No se enviarán archivos, rutas, historial ni preferencias locales.")
                    if (matches.none { it.recording == chosen && it.plausible })
                        Text("La coincidencia es incierta: revisa la grabación manualmente.")
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("mi27_confirm_recording_lookup"),
                    enabled = !busy,
                    onClick = {
                        pendingRecordingLookup = null
                        if (!showDialog || !stillOwns() ||
                            matches.none { it.recording == chosen } ||
                            MichiGenreNormalizationPolicy.canonicalMbid(chosen.mbid) == null) {
                            message = "La grabación ya no pertenece a esta consulta."
                        } else {
                            lookupRevision++
                            val requestedRevision = lookupRevision
                            lookupJob?.cancel()
                            selectedRecording = chosen
                            proposals = emptyList()
                            chosenGenre = "" // An old preview is never reused for a new recording.
                            busy = true
                            // Issued synchronously with second, independently confirmed click.
                            val approvedRecording = authorization.issueRecording(chosen.mbid)
                            lookupJob = coroutineScope.launch {
                                try {
                                    val received = gateway.genresForSelectedRecording(chosen,
                                        approvedRecording)
                                    if (lookupRevision == requestedRevision && showDialog && stillOwns() &&
                                        selectedRecording == chosen) {
                                        proposals = received.filter {
                                            MichiGenreNormalizationPolicy.approvedProposal(
                                                chosen, it, chosen.mbid)
                                        }
                                        message = if (proposals.isEmpty())
                                            "MusicBrainz no aportó géneros verificados para esta grabación."
                                            else null
                                    }
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) {
                                    if (lookupRevision == requestedRevision)
                                        message = "No se pudieron obtener géneros."
                                } finally {
                                    if (lookupRevision == requestedRevision) {
                                        busy = false
                                        lookupJob = null
                                    }
                                }
                            }
                        }
                    },
                ) { Text("Consultar esta grabación") }
            },
            dismissButton = { TextButton(onClick = { pendingRecordingLookup = null }) {
                Text("Cancelar")
            } },
        )
    }
    if (confirmWrite && MI23_TAG_WRITES_CERTIFIED && tagsVerified) {
        AlertDialog(
            onDismissRequest={confirmWrite=false},
            title={Text("Confirmar edición de género")},
            text={Text("Se editará solo el campo GENRE de esta canción. El género previo será sustituido si existía. Se realizará una copia temporal de seguridad y verificación posterior.")},
            confirmButton={TextButton(onClick={
                if (uri.authority==MediaStore.AUTHORITY) {
                    try {
                        val request=MediaStore.createWriteRequest(context.contentResolver,listOf(uri))
                        permissionLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    } catch (_:Exception) {confirmWrite=false;message="No se pudo solicitar el permiso de escritura."}
                } else if (context.checkUriPermission(uri,Process.myPid(),Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED) {
                    // SAF permission is already retained; use the same guarded write method.
                    confirmWrite=false
                    busy=true
                    coroutineScope.launch {
                        try {
                            val ticket=MichiGenreTagWriteGateway.Ticket(track.sourceId,track.id,
                                track.filepath,embeddedGenres,track.title,track.artist,track.album,track.size)
                            message=when(val result=writer.write(ticket,listOf(chosenGenre),true,true,true,::stillOwns)) {
                                is MichiGenreTagWriteGateway.Result.SavedVerified ->
                                    reportVerifiedWrite(ticket, result)
                                is MichiGenreTagWriteGateway.Result.Rejected -> "No se guardó: ${result.code}"
                                is MichiGenreTagWriteGateway.Result.Failed -> "Fallo: ${result.code}"
                            }
                            pendingRecovery = writer.pendingRecovery()
                        }catch(cancel:CancellationException){throw cancel}
                        catch(_:Exception){message="No se confirmó la escritura."}
                        finally{busy=false}
                    }
                } else {confirmWrite=false;message="Concede acceso SAF de escritura antes de editar."}
            },modifier=Modifier.testTag("mi23_write_confirm")){Text("Autorizar cambio")}},
            dismissButton={TextButton(onClick={confirmWrite=false}){Text("Cancelar")}},
        )
    }
    if (confirmRecovery && MI23_TAG_WRITES_CERTIFIED) {
        AlertDialog(
            onDismissRequest = { confirmRecovery = false },
            title = { Text("Restaurar el archivo original") },
            text = { Text("Se reemplazará el contenido actual de esta canción por la copia local preservada antes de la operación. No recupera ediciones externas posteriores. Requiere el permiso de Android y se verificará el SHA-256 del original.") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("mi24_confirm_explicit_recovery"),
                    onClick = {
                        if (!recoveryBelongsToTrack() || !stillOwns()) {
                            confirmRecovery = false
                            message = "No se restauró: cambió la biblioteca o el archivo."
                        } else if (uri.authority == MediaStore.AUTHORITY) {
                            try {
                                val grant = MediaStore.createWriteRequest(
                                    context.contentResolver, listOf(uri))
                                recoveryGrantPending = true
                                recoveryGrantUnknownOverride = false
                                confirmRecovery = false
                                recoveryPermissionLauncher.launch(
                                    IntentSenderRequest.Builder(grant.intentSender).build())
                            } catch (_: Exception) {
                                recoveryGrantPending = false
                                confirmRecovery = false
                                message = "No se pudo solicitar permiso de recuperación."
                            }
                        } else if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                            confirmRecovery = false
                            busy = true
                            coroutineScope.launch {
                                try { restoreFromExplicitRequest() }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { message = "No se pudo completar la recuperación." }
                                finally { busy = false }
                            }
                        } else {
                            confirmRecovery = false
                            message = "Concede permiso de escritura SAF para restaurar."
                        }
                    }
                ) { Text("Restaurar con verificación") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRecovery = false }) { Text("Cancelar") }
            },
        )
    }

    // KILLCRITIC MI-25: NEVER treat an unknown current file as ordinary rollback.
    // This second, conspicuous confirmation is shown only when the provider bytes
    // differ from BOTH the original SHA-256 and the expected staged SHA-256.
    if (confirmUnknownRecovery && MI23_TAG_WRITES_CERTIFIED &&
        recoveryUnknownBytes != null && recoveryUnknownSha256 != null) {
        AlertDialog(
            onDismissRequest = {
                confirmUnknownRecovery = false
                recoveryUnknownBytes = null
                recoveryUnknownSha256 = null
            },
            title = { Text("Advertencia: el archivo cambió") },
            text = { Text(
                "Los bytes actuales no coinciden con el original ni con la versión editada " +
                    "prevista. Podría ser una escritura interrumpida O una modificación de " +
                    "otra aplicación. Restaurar la copia BORRARÁ esos bytes actuales, " +
                    "incluidos posibles cambios posteriores. No continúes si desconoces " +
                    "el origen de la modificación."
            ) },
            confirmButton = {
                TextButton(
                    enabled = !busy && recoveryBelongsToTrack() && stillOwns() &&
                        recoveryUnknownBytes != null && recoveryUnknownSha256 != null,
                    modifier = Modifier.testTag("mi25_override_unknown_file_confirm"),
                    onClick = {
                        if (!recoveryBelongsToTrack() || !stillOwns()) {
                            confirmUnknownRecovery = false
                            message = "La biblioteca cambió; no se restauró ningún archivo."
                        } else if (uri.authority == MediaStore.AUTHORITY) {
                            try {
                                val grant = MediaStore.createWriteRequest(
                                    context.contentResolver, listOf(uri))
                                recoveryGrantPending = true
                                recoveryGrantUnknownOverride = true
                                confirmUnknownRecovery = false
                                recoveryPermissionLauncher.launch(
                                    IntentSenderRequest.Builder(grant.intentSender).build())
                            } catch (_: Exception) {
                                recoveryGrantPending = false
                                recoveryGrantUnknownOverride = false
                                confirmUnknownRecovery = false
                                message = "No fue posible solicitar permiso para la restauración extraordinaria."
                            }
                        } else if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                            confirmUnknownRecovery = false
                            busy = true
                            coroutineScope.launch {
                                try { restoreFromExplicitRequest(allowUnknownBytes = true) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { message = "La recuperación extraordinaria falló; se conservó la copia." }
                                finally { busy = false }
                            }
                        } else {
                            confirmUnknownRecovery = false
                            message = "Falta el permiso de escritura SAF. No se alteró el archivo."
                        }
                    },
                ) { Text("Sobrescribir bytes actuales con la copia original") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmUnknownRecovery = false
                    recoveryUnknownBytes = null
                    recoveryUnknownSha256 = null
                }) {
                    Text("Conservar archivo actual")
                }
            },
        )
    }
}
