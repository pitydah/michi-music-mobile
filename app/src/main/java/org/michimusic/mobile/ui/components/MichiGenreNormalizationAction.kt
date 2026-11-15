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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary
import org.koin.androidx.compose.koinViewModel
import org.michimusic.core.intelligence.MichiGenreAccessPolicy
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy
import org.michimusic.core.intelligence.MichiGenreLookupAuthorization
import org.michimusic.core.intelligence.MichiGenreLookupOutcomePolicy
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.intelligence.MichiGenrePreviewPolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.intelligence.MichiGenreTagWriteGateway
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.repository.ExtendedTagUnavailableReason
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
internal object MichiGenreWriteCertification {
    const val WRITES_CERTIFIED = false
}

private const val MI23_TAG_WRITES_CERTIFIED = MichiGenreWriteCertification.WRITES_CERTIFIED

/**
 * Trigger used by the Now Playing metadata block. The editor itself is the same
 * implementation the Genres route uses through [MichiGenreNormalizationHost];
 * nothing here searches on composition or while scrolling.
 */
@Composable
internal fun MichiGenreNormalizationAction(
    track: Track,
    embeddedGenres: List<String>,
    tagsVerified: Boolean,
    origin: MichiGenreEditorOrigin = MichiGenreEditorOrigin.NOW_PLAYING,
) {
    if (track.source != TrackSource.LOCAL) return
    var open by remember(track.sourceId, track.id, track.filepath, track.size, embeddedGenres) {
        mutableStateOf(false)
    }
    TextButton(
        onClick = { open = true },
        modifier = Modifier.testTag("mi23_open_genre_normalization"),
    ) {
        Text("Revisar géneros")
    }
    if (!open) return
    MichiGenreNormalizationEditor(
        track = track,
        embeddedGenres = embeddedGenres,
        tagsVerified = tagsVerified,
        origin = origin,
        onClose = { open = false },
    )
}

/**
 * The single editor body. [showDialog] is now always true: the container is the
 * modal sheet and closing it is the caller's `onClose`, so both routes share one
 * receipt and one state machine.
 */
@Composable
internal fun MichiGenreNormalizationEditor(
    track: Track,
    embeddedGenres: List<String>,
    tagsVerified: Boolean,
    origin: MichiGenreEditorOrigin,
    onClose: () -> Unit,
    evidenceReason: ExtendedTagUnavailableReason? = null,
) {
    if (track.source != TrackSource.LOCAL) return
    val context = LocalContext.current
    val scopeStore: LibraryScopeStore = koinInject()
    val rootAuthority: MusicRootAuthority = koinInject()
    val session: PlaybackSessionManager = koinInject()
    // Observed once for the whole editor: reading `StateFlow.value` inside composition
    // is a lint error and would not recompose the honest mode badge on a handoff.
    val sessionState by session.sessionState.collectAsStateWithLifecycle()
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
    // The editor is composed only while its container is open; the receipt keeps
    // every state local to this exact track.
    val showDialog = true
    var previewNetwork by remember(trackReceipt) { mutableStateOf(false) }
    var showDraftPreview by remember(trackReceipt) { mutableStateOf(false) }
    var showEvidenceReason by remember(trackReceipt) { mutableStateOf(false) }
    var confirmWrite by remember(trackReceipt) { mutableStateOf(false) }
    var confirmRecovery by remember(trackReceipt) { mutableStateOf(false) }
    var confirmUnknownRecovery by remember(trackReceipt) { mutableStateOf(false) }
    var recoveryGrantPending by remember(trackReceipt) { mutableStateOf(false) }
    var recoveryGrantUnknownOverride by remember(trackReceipt) { mutableStateOf(false) }
    var recoveryUnknownBytes by remember(trackReceipt) { mutableStateOf<Long?>(null) }
    var recoveryUnknownSha256 by remember(trackReceipt) { mutableStateOf<String?>(null) }
    var busy by remember(trackReceipt) { mutableStateOf(false) }
    // Explicit, mutually distinguishable network stage (contract §10.2.7).
    var lookupPhase by remember(trackReceipt) {
        mutableStateOf(MichiGenreLookupPhase.IDLE)
    }
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
    // MI-23 5: the preview is a LIST. Every verified value is kept by default; one
    // explicit add/replace/remove never discards the rest of the tag set.
    var genrePreview by remember(trackReceipt) {
        mutableStateOf(MichiGenrePreviewPolicy.of(embeddedGenres))
    }
    var manualGenre by remember(trackReceipt) { mutableStateOf("") }
    var replaceIndex by remember(trackReceipt) { mutableStateOf<Int?>(null) }
    var relaxedSearchOffered by remember(trackReceipt) { mutableStateOf(false) }
    var relaxedIntent by remember(trackReceipt) {
        mutableStateOf<MichiGenreNormalizationPolicy.SearchIntent?>(null)
    }
    var relaxedPreview by remember(trackReceipt) { mutableStateOf(false) }
    var pendingRecordingLookup by remember(trackReceipt) {
        mutableStateOf<MichiGenreNormalizationPolicy.RecordingCandidate?>(null)
    }
    var lookupRevision by remember(trackReceipt) { mutableStateOf(0L) }
    var lookupJob by remember(trackReceipt) { mutableStateOf<Job?>(null) }
    val capturedScope = remember(showDialog, trackReceipt) { scopeStore.activeScope.value }
    val capturedRoot = remember(showDialog, trackReceipt) { rootAuthority.revision.value }

    /**
     * The receipt is the `remember(trackReceipt)` state itself: if the source, URI or
     * metadata changed, this whole composition is replaced, so the local receipt
     * cannot belong to another song. Playback/handoff/endpoint are inputs, not
     * implicit vetoes: a read-only lookup must work while the track is loaded.
     */
    fun accessContext(): MichiGenreAccessPolicy.Context {
        val s = sessionState
        return MichiGenreAccessPolicy.Context(
            trackLocal = track.source == TrackSource.LOCAL,
            receiptMatches = true,
            uriMatches = track.filepath == uri.toString(),
            scopeUnchanged = scopeStore.activeScope.value == capturedScope,
            revisionUnchanged = rootAuthority.revision.value == capturedRoot,
            dialogOpen = showDialog,
            trackSelectedInPlayer = s.currentTrack?.filepath == track.filepath,
            remoteSessionActive = s.isRemoteActive,
            handoffActive = s.handoffState != org.michimusic.mobile.playback.HandoffState.IDLE,
            localEndpoint = s.activeEndpoint.isLocal,
        )
    }
    /** Strict contract for a physical GENRE mutation (kept fail-closed). */
    fun stillOwns(): Boolean = MichiGenreAccessPolicy.canMutateAudioFile(accessContext())
    /** Read-only contract: playback of the same stable local file never blocks it. */
    fun canLookupNow(): Boolean = MichiGenreAccessPolicy.canLookup(accessContext())

    fun dispatchSearch(requested: MichiGenreNormalizationPolicy.SearchIntent) {
        if (!showDialog || !canLookupNow() ||
            !MichiGenreNormalizationPolicy.isPreparedSearch(requested)) {
            message = MichiGenreLookupOutcomePolicy.message(
                MichiGenreLookupOutcomePolicy.SearchOutcome.CONSENT_REVOKED)
            return
        }
        lookupRevision++
        val requestedRevision = lookupRevision
        lookupJob?.cancel()
        busy = true
        lookupPhase = MichiGenreLookupPhase.PENDING
        matches = emptyList()
        selectedRecording = null
        proposals = emptyList()
        pendingRecordingLookup = null
        relaxedSearchOffered = false
        // A new query restarts the preview from the verified file tags.
        genrePreview = MichiGenrePreviewPolicy.of(embeddedGenres)
        manualGenre = ""
        replaceIndex = null
        // Mint on the confirmed user click, NOT after coroutine dispatch.
        // Closing the dialog before dispatch revokes this exact grant.
        val approvedSearch = authorization.issueSearch(requested)
        if (approvedSearch == null) {
            message = MichiGenreLookupOutcomePolicy.message(
                MichiGenreLookupOutcomePolicy.SearchOutcome.CONSENT_REVOKED)
            busy = false
            return
        }
        lookupJob = coroutineScope.launch {
            try {
                val results = gateway.search(requested, approvedSearch,
                    track.duration.takeIf { it > 0 })
                if (lookupRevision == requestedRevision && showDialog && canLookupNow()) {
                    matches = results
                    lookupPhase = MichiGenreLookupPhase.RESULTS
                    val outcome = MichiGenreLookupOutcomePolicy.search(
                        matchCount = results.size,
                        reviewableCount = results.count { it.plausible },
                    )
                    // Only a completed strict search with zero matches may offer the
                    // optional second query; it still needs a fresh confirmation.
                    relaxedSearchOffered =
                        outcome == MichiGenreLookupOutcomePolicy.SearchOutcome.NO_MATCH &&
                            requested.album.isNotEmpty()
                    message = MichiGenreLookupOutcomePolicy.message(outcome)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (lookupRevision == requestedRevision) {
                    lookupPhase = MichiGenreLookupPhase.ERROR
                    message = MichiGenreLookupOutcomePolicy.message(
                        MichiGenreLookupOutcomePolicy.SearchOutcome.ERROR)
                }
            } finally {
                if (lookupRevision == requestedRevision) {
                    busy = false
                    lookupJob = null
                }
            }
        }
    }

    fun dispatchRecordingLookup(chosen: MichiGenreNormalizationPolicy.RecordingCandidate) {
        val mbid = MichiGenreNormalizationPolicy.canonicalMbid(chosen.mbid)
        if (!showDialog || !canLookupNow() || mbid == null ||
            matches.none { it.recording == chosen }) {
            message = "La grabación ya no pertenece a esta consulta."
            return
        }
        lookupRevision++
        val requestedRevision = lookupRevision
        lookupJob?.cancel()
        selectedRecording = chosen
        proposals = emptyList()
        busy = true
        lookupPhase = MichiGenreLookupPhase.PENDING
        // Issued synchronously with the second, independently confirmed click.
        val approvedRecording = authorization.issueRecording(mbid)
        if (approvedRecording == null) {
            busy = false
            message = MichiGenreLookupOutcomePolicy.message(
                MichiGenreLookupOutcomePolicy.RecordingOutcome.CONSENT_REVOKED)
            return
        }
        lookupJob = coroutineScope.launch {
            try {
                val received = gateway.genresForSelectedRecording(chosen, approvedRecording)
                if (lookupRevision == requestedRevision && showDialog && canLookupNow() &&
                    selectedRecording == chosen) {
                    proposals = received.filter {
                        MichiGenreNormalizationPolicy.approvedProposal(chosen, it, chosen.mbid)
                    }
                    lookupPhase = MichiGenreLookupPhase.RESULTS
                    val outcome = MichiGenreLookupOutcomePolicy.recording(proposals.size)
                    message = MichiGenreLookupOutcomePolicy.message(outcome)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (lookupRevision == requestedRevision) {
                    lookupPhase = MichiGenreLookupPhase.ERROR
                    message = MichiGenreLookupOutcomePolicy.message(
                        MichiGenreLookupOutcomePolicy.RecordingOutcome.ERROR)
                }
            } finally {
                if (lookupRevision == requestedRevision) {
                    busy = false
                    lookupJob = null
                }
            }
        }
    }

    fun applyPreviewEdit(edit: MichiGenrePreviewPolicy.Edit) {
        when (edit) {
            is MichiGenrePreviewPolicy.Edit.Accepted -> {
                genrePreview = edit.preview
                manualGenre = ""
                replaceIndex = null
                message = null
            }
            is MichiGenrePreviewPolicy.Edit.Rejected -> message = when (edit.reason) {
                "MAX_GENRES" -> "Se conservan como máximo 32 géneros verificados."
                "DUPLICATE_GENRE" -> "Ese género ya está en la previsualización."
                "NOT_A_MUSICAL_GENRE" ->
                    "Un marcador de ausencia o un contenedor no es un género musical."
                "INVALID_INDEX" -> "Selecciona un género de la lista."
                else -> "La etiqueta escrita no es válida."
            }
        }
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
        busy = false
        onClose()
    }
    // A cold editor reads one local receipt only; nothing runs in Home/scroll/idle.
    LaunchedEffect(showDialog, track.sourceId, track.id, track.filepath) {
        pendingRecovery = try { writer.pendingRecovery() }
        catch (_: Exception) {
            MichiGenreTagWriteGateway.RecoveryStatus.Blocked("RECOVERY_CHECK_FAILED")
        }
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
            val newGenres = genrePreview.proposed.toList()
            confirmWrite = false
            busy = true
            coroutineScope.launch {
                try {
                    val ticket = MichiGenreTagWriteGateway.Ticket(
                        track.sourceId,track.id,track.filepath,embeddedGenres,
                        track.title,track.artist,track.album,track.size)
                    message = when (val result = writer.write(ticket,newGenres,true,true,true,::stillOwns)) {
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

    val editorStatus = MichiGenreEditorContract.status(
        tagsVerified = tagsVerified,
        verifiedGenres = embeddedGenres,
        previewModified = genrePreview.isModified,
        lookup = lookupPhase,
        writesCertified = MI23_TAG_WRITES_CERTIFIED,
        descriptorOpenInPlayer = sessionState.currentTrack?.filepath == track.filepath,
        recoveryPending = pendingRecovery is MichiGenreTagWriteGateway.RecoveryStatus.Pending,
    )
    MichiGenreEditorSheet(
        onDismissRequest = {
            if (!busy || lookupJob != null) closeLookupDialog()
        },
        trackTitle = track.title,
        trackArtist = track.artist,
        state = editorStatus.state,
        writesCertified = MI23_TAG_WRITES_CERTIFIED,
        footerAction = {
            // K47-NORM-03: while WRITES_CERTIFIED is false there is no inert
            // save button at all. The preview is functional, it just never
            // pretends the tags will be written.
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Estos cambios no se guardan en los archivos",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.testTag("mi26_genre_editor_legend"),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { closeLookupDialog() },
                        enabled = !busy || lookupJob != null,
                        modifier = Modifier.testTag("mi26_genre_editor_close"),
                    ) { Text("Cerrar") }
                    Spacer(Modifier.weight(1f))
                    if (genrePreview.isModified) {
                        TextButton(
                            onClick = { showDraftPreview = !showDraftPreview },
                            modifier = Modifier.testTag("mi26_genre_editor_preview_toggle"),
                        ) { Text("Ver previsualización") }
                    }
                }
                if (showDraftPreview) {
                    Text(
                        MichiGenreEditorContract.proposedLabel(tagsVerified, genrePreview.proposed),
                        style = MaterialTheme.typography.bodySmall,
                        color = PureWhite,
                        modifier = Modifier.testTag("mi26_genre_preview_summary"),
                    )
                }
            }
        },
        body = {
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
                if (tagsVerified) {
                    Text(
                        MichiGenreEditorContract.evidenceLabel(true, embeddedGenres),
                        modifier = Modifier.testTag("mi26_genre_evidence"),
                    )
                } else {
                    // K47-NORM-02: the local index classification and the physical
                    // tag are two independent pieces of evidence. An unavailable
                    // read is never presented as an empty tag.
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "En Biblioteca: " + (track.genre?.takeIf(String::isNotBlank)
                                ?: "Sin clasificación"),
                            color = PureWhite,
                            modifier = Modifier.testTag("mi26_genre_library_classification"),
                        )
                        Text(
                            "Clasificación del índice local, no prueba de la etiqueta física.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                        Text("Etiqueta original del archivo",
                            style = MaterialTheme.typography.labelMedium, color = TextMuted)
                        Text(
                            "Aún no verificable",
                            color = PureWhite,
                            modifier = Modifier.testTag("mi26_genre_evidence"),
                        )
                        Text("Conservar la clasificación actual. No inventar ausencia de metadatos.")
                        TextButton(
                            onClick = { showEvidenceReason = !showEvidenceReason },
                            modifier = Modifier.testTag("mi26_genre_evidence_reason"),
                        ) { Text("¿Por qué no se verificó?") }
                        if (showEvidenceReason) {
                            Text(
                                evidenceUnavailableLabel(evidenceReason),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.testTag("mi26_genre_evidence_reason_text"),
                            )
                        }
                    }
                }
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
                            lookupPhase = MichiGenreLookupPhase.CANCELLED
                            message = "Consulta cancelada. No se publicaron resultados tardíos."
                        }, modifier = Modifier.testTag("mi27_cancel_lookup"),
                    ) { Text("Cancelar consulta") }
                }
                if (relaxedSearchOffered && intent?.album?.isNotEmpty() == true) {
                    Text("La búsqueda estricta (título, artista y álbum) no encontró coincidencias.")
                    TextButton(
                        onClick = {
                            intent?.let { currentIntent ->
                                relaxedIntent = MichiGenreNormalizationPolicy.relaxedSearch(currentIntent)
                                relaxedPreview = relaxedIntent != null
                            }
                        },
                        enabled = !busy && canLookupNow(),
                        modifier = Modifier.testTag("mi26_open_relaxed_search"),
                    ) { Text("Buscar sin álbum (segunda consulta)") }
                }
                matches.forEach { choice ->
                    TextButton(
                        enabled = !busy && canLookupNow(),
                        onClick = {
                            // Selection is NOT consent to a second HTTP request.
                            pendingRecordingLookup = choice.recording
                        }, modifier=Modifier.fillMaxWidth().testTag("mi23_choose_recording")) {
                        Text("${choice.recording.title} · ${choice.recording.artist}" +
                            if(choice.plausible) " · Coincidencia revisable" else " · Identidad incierta")
                    }
                }
                selectedRecording?.let { sel ->
                    Text("Grabación elegida: ${sel.mbid}")
                    Text("Procedencia: " + MichiGenreNormalizationPolicy.provenanceLabel(
                        MichiGenreNormalizationPolicy.provenanceOf(
                            MichiGenreNormalizationPolicy.Entity.RECORDING)))
                }
                proposals.forEach { proposal ->
                    val selected = selectedRecording
                    val provenanceVerified = selected != null &&
                        MichiGenreNormalizationPolicy.approvedProposal(
                            selected, proposal, selected.mbid)
                    val provenance = MichiGenreNormalizationPolicy.provenanceOf(proposal.entity)
                    TextButton(
                        onClick = {
                            if (provenanceVerified && canLookupNow()) {
                                // Additive: choosing a proposal never drops existing tags.
                                applyPreviewEdit(genrePreview.add(
                                    MichiGenreDisambiguationPolicy.suggestedTag(proposal.name)))
                            }
                        }, enabled = !busy && provenanceVerified && canLookupNow(),
                        modifier=Modifier.fillMaxWidth().testTag("mi23_proposed_genre")) {
                        Text("${proposal.name} · " +
                            MichiGenreNormalizationPolicy.provenanceLabel(provenance) +
                            " · votos: ${proposal.votes ?: "s/d"}" +
                            if (MichiGenreNormalizationPolicy.isContextOnly(provenance))
                                " · Solo contexto: no se aplica" else "")
                    }
                }
                Text("Previsualización: no modifica el archivo.",
                    modifier = Modifier.testTag("mi26_preview_only_notice"))
                Text(
                    MichiGenreEditorContract.originalLabel(tagsVerified, genrePreview.original),
                    modifier = Modifier.testTag("mi26_preview_original"),
                )
                Text(
                    MichiGenreEditorContract.proposedLabel(tagsVerified, genrePreview.proposed),
                    modifier = Modifier.testTag("mi26_preview_proposed"),
                )
                val previewDiff = genrePreview.diff()
                if (previewDiff.added.isNotEmpty())
                    Text("Añadido: ${previewDiff.added.joinToString(" · ")}")
                if (previewDiff.removed.isNotEmpty())
                    Text("Quitado: ${previewDiff.removed.joinToString(" · ")}")
                // One explicit operation per value; the remaining values are preserved.
                genrePreview.proposed.forEachIndexed { index, value ->
                    Row {
                        TextButton(
                            onClick = { applyPreviewEdit(genrePreview.removeAt(index)) },
                            enabled = !busy,
                            modifier = Modifier.testTag("mi26_preview_remove"),
                        ) { Text("Quitar") }
                        TextButton(
                            onClick = { replaceIndex = index; manualGenre = value },
                            enabled = !busy,
                            modifier = Modifier.testTag("mi26_preview_select_replace"),
                        ) { Text("Sustituir") }
                        Text(value + if (replaceIndex == index) " · seleccionado" else "")
                    }
                }
                OutlinedTextField(
                    value=manualGenre,onValueChange={manualGenre=it},singleLine=true,
                    label={Text("Género para añadir o sustituir")},
                    modifier=Modifier.fillMaxWidth().testTag("mi23_manual_genre"))
                Row {
                    TextButton(
                        onClick = { applyPreviewEdit(genrePreview.add(manualGenre)) },
                        enabled = !busy && manualGenre.isNotBlank(),
                        modifier = Modifier.testTag("mi26_preview_add"),
                    ) { Text("Añadir") }
                    TextButton(
                        onClick = {
                            replaceIndex?.let {
                                applyPreviewEdit(genrePreview.replaceAt(it, manualGenre))
                            }
                        },
                        enabled = !busy && replaceIndex != null && manualGenre.isNotBlank(),
                        modifier = Modifier.testTag("mi26_preview_replace"),
                    ) { Text("Sustituir seleccionado") }
                    TextButton(
                        onClick = {
                            genrePreview = MichiGenrePreviewPolicy.of(embeddedGenres)
                            manualGenre = ""
                            replaceIndex = null
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag("mi26_preview_keep_all"),
                    ) { Text("Mantener todo") }
                }
                genrePreview.structuralError?.let {
                    Text("Previsualización no válida ($it). No se puede guardar.")
                }
                if (MI23_TAG_WRITES_CERTIFIED && tagsVerified &&
                    genrePreview.structuralError == null && genrePreview.isModified) {
                    TextButton(onClick={confirmWrite=true},enabled=!busy && stillOwns(),
                        modifier=Modifier.testTag("mi23_request_write")) {
                        Text(if (embeddedGenres.isEmpty()) "Guardar GENRE en archivo" else "Reemplazar GENRE existente")
                    }
                } else Text("Escritura de etiquetas deshabilitada hasta certificar MediaStore/SAF y los formatos MP3/FLAC en Android. Esta vista solo previsualiza.")
                message?.let { Text(it) }
        },
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
                dispatchSearch(intent)
            }, modifier=Modifier.testTag("mi23_send_once")) {Text("Enviar esta vez")} },
            dismissButton={TextButton(onClick={previewNetwork=false}){Text("Cancelar")}},
        )
    }
    // MI-23 4.1: the relaxed search is a SEPARATE request without `release`.
    // It is only reachable after a strict zero-match result and its own confirmation.
    relaxedIntent?.let { relaxed ->
        if (relaxedPreview) AlertDialog(
            onDismissRequest = { relaxedPreview = false },
            title = { Text("¿Buscar sin álbum?") },
            text = {
                Column {
                    Text("Segunda consulta independiente. Se enviarán solo estos campos públicos:")
                    relaxed.outgoingFields().forEach { (key, value) -> Text("$key: $value") }
                    Text("No se enviará el álbum ni ningún otro dato. MusicBrainz puede " +
                        "registrar la IP y los parámetros. No se modifica ningún archivo.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        relaxedPreview = false
                        relaxedSearchOffered = false
                        dispatchSearch(relaxed)
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("mi26_send_relaxed"),
                ) { Text("Enviar sin álbum") }
            },
            dismissButton = {
                TextButton(onClick = { relaxedPreview = false }) { Text("Cancelar") }
            },
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
                        dispatchRecordingLookup(chosen)
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
                            message=when(val result=writer.write(ticket,genrePreview.proposed.toList(),true,true,true,::stillOwns)) {
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

/**
 * K47-NORM-02: exact, path-free diagnostic for a cold read that could not prove
 * the physical tags. Never says the file has no genre.
 */
internal fun evidenceUnavailableLabel(reason: ExtendedTagUnavailableReason?): String = when (reason) {
    ExtendedTagUnavailableReason.NOT_CONTENT_URI ->
        "La pista no apunta a un documento content://, así que no hay archivo local que leer."
    ExtendedTagUnavailableReason.INVALID_TRACK_ID ->
        "La identificación de la pista está incompleta."
    ExtendedTagUnavailableReason.INVALID_SIZE ->
        "El tamaño del archivo no está disponible."
    ExtendedTagUnavailableReason.MISSING_STAMP ->
        "El proveedor no devolvió tamaño y fecha del documento."
    ExtendedTagUnavailableReason.SIZE_MISMATCH ->
        "El tamaño actual no coincide con la evidencia registrada en la base local."
    ExtendedTagUnavailableReason.MODIFIED_AT_ZERO ->
        "El proveedor no informa una fecha de modificación utilizable."
    ExtendedTagUnavailableReason.NO_CURRENT_EVIDENCE ->
        "No hay evidencia actual de etiquetas para esta ruta en la base local."
    ExtendedTagUnavailableReason.TAG_READER_UNSUPPORTED ->
        "El lector no pudo interpretar las etiquetas de este formato."
    ExtendedTagUnavailableReason.TAG_MISMATCH ->
        "Las etiquetas releídas no coinciden con la evidencia registrada."
    ExtendedTagUnavailableReason.PERMISSION_DENIED ->
        "Android denegó el permiso de lectura del documento."
    ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE ->
        "El proveedor de documentos no está disponible ahora mismo."
    null -> "No se registró un motivo para esta lectura."
}
