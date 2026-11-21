package org.michimusic.mobile.ui.components

import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.intelligence.MichiGenreAccessPolicy
import org.michimusic.core.intelligence.MichiGenreLookupAuthorization
import org.michimusic.core.intelligence.MichiGenrePreviewPolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.intelligence.MichiGenreTagWriteGateway
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

/**
 * ME-11S: genre normalization is not a second editor. This action opens the ONE
 * fullscreen metadata editor directly at its integrated Géneros tool, so the
 * same draft, undo/redo history and review are shared with the rest of the file.
 * Nothing here searches on composition or while scrolling.
 */
@Composable
internal fun MichiGenreNormalizationAction(
    track: Track,
    embeddedGenres: List<String>,
    tagsVerified: Boolean,
    origin: MichiGenreEditorOrigin = MichiGenreEditorOrigin.NOW_PLAYING,
) {
    if (track.source != TrackSource.LOCAL) return
    val editorLauncher = org.michimusic.mobile.ui.metadata.LocalMetadataEditorLauncher.current
    val rootAuthority: MusicRootAuthority = koinInject()
    TextButton(
        onClick = {
            editorLauncher(
                org.michimusic.mobile.ui.metadata.MetadataEditorLaunchRequest(
                    sourceId = track.sourceId,
                    trackId = track.id,
                    scopeGeneration = rootAuthority.revision.value,
                    origin = org.michimusic.mobile.ui.metadata.MetadataEditorOrigin.FILE_INFO,
                    focusGenre = true,
                )
            )
        },
        modifier = Modifier.testTag("mi23_open_genre_normalization"),
    ) {
        Text("Normalizar géneros ›")
    }
}

/**
 * K48-R06 host for the one genre editor.
 *
 * It owns every platform dependency — Koin graph, session state, Android launchers and
 * the MediaStore/SAF grants — and nothing else: the state machine lives in
 * [GenreEditorCoordinator] and every visible pixel in [GenreEditorContent]. Both entry
 * points (Genres detail and Now Playing) reach this exact host, so they share one
 * receipt, one coordinator, one consent policy and one draft operation set.
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
    // Observed once for the whole editor: the honest mode badge must follow a handoff.
    val sessionState by session.sessionState.collectAsStateWithLifecycle()
    val albumsViewModel: AlbumsViewModel = koinViewModel()
    val trackDao: TrackDao = koinInject()
    val coroutineScope = rememberCoroutineScope()
    // A new source/URI/metadata receipt cannot inherit another song's remote results.
    // Including the observed tag list also invalidates stale proposals after a refresh.
    val trackReceipt = remember(
        track.sourceId, track.id, track.filepath, track.title, track.artist,
        track.album, track.size, embeddedGenres,
    ) { Any() }
    // New metadata receipts own their permits; a previously opened song cannot reuse one.
    val authorization = remember(trackReceipt) { MichiGenreLookupAuthorization() }
    val gateway = remember(authorization) {
        MichiGenreMusicBrainzGateway(authorization = authorization)
    }
    val writer = remember(context) { MichiGenreTagWriteGateway(context.applicationContext) }
    val reconciler = remember(context, trackDao) {
        MichiGenrePostWriteReconciler(context.applicationContext, trackDao)
    }
    // `:data` owns the real gateway and reconciler; these adapters are the only bridge
    // into the coordinator seams, which keeps the state machine fake-friendly.
    val gatewayAdapter = remember(gateway) {
        object : GenreEditorGateway {
            override suspend fun search(
                intent: org.michimusic.core.intelligence.MichiGenreNormalizationPolicy.SearchIntent,
                permit: MichiGenreLookupAuthorization.Permit?,
                durationMs: Long?,
            ) = gateway.search(intent, permit, durationMs)

            override suspend fun genresForSelectedRecording(
                selected: org.michimusic.core.intelligence.MichiGenreNormalizationPolicy.RecordingCandidate,
                permit: MichiGenreLookupAuthorization.Permit?,
            ) = gateway.genresForSelectedRecording(selected, permit)
        }
    }
    val writerAdapter = remember(writer) {
        object : GenreEditorWriter {
            override suspend fun pendingRecovery() = writer.pendingRecovery()

            override suspend fun write(
                ticket: MichiGenreTagWriteGateway.Ticket,
                proposed: List<String>,
                userConfirmed: Boolean,
                replacementConfirmed: Boolean,
                writeGrantConfirmed: Boolean,
                stillOwns: () -> Boolean,
            ) = writer.write(
                ticket, proposed, userConfirmed, replacementConfirmed, writeGrantConfirmed,
                stillOwns,
            )

            override suspend fun restorePending(
                ticket: MichiGenreTagWriteGateway.Ticket,
                userConfirmed: Boolean,
                writeGrantConfirmed: Boolean,
                stillOwns: () -> Boolean,
                unknownBytesOverwriteConfirmed: Boolean,
                expectedUnknownBytes: Long?,
                expectedUnknownSha256: String?,
            ) = writer.restorePending(
                ticket, userConfirmed, writeGrantConfirmed, stillOwns,
                unknownBytesOverwriteConfirmed, expectedUnknownBytes, expectedUnknownSha256,
            )
        }
    }
    val reconcilerAdapter = remember(reconciler) {
        GenreEditorReconciler { ticket, verified, stillOwns ->
            reconciler.reconcile(ticket, verified, stillOwns)
        }
    }
    // Authority is captured once, exactly like the receipt: a later library switch or
    // root revision must invalidate this editor instead of silently re-targeting it.
    val capturedScope = remember(trackReceipt) { scopeStore.activeScope.value }
    val capturedRoot = remember(trackReceipt) { rootAuthority.revision.value }
    val uri = remember(track.filepath) { Uri.parse(track.filepath) }
    val authority = remember(trackReceipt, capturedScope, capturedRoot) {
        object : GenreEditorAuthority {
            private fun accessContext() = MichiGenreAccessPolicy.Context(
                trackLocal = track.source == TrackSource.LOCAL,
                receiptMatches = true,
                uriMatches = track.filepath == uri.toString(),
                scopeUnchanged = scopeStore.activeScope.value == capturedScope,
                revisionUnchanged = rootAuthority.revision.value == capturedRoot,
                dialogOpen = true,
                trackSelectedInPlayer =
                    session.sessionState.value.currentTrack?.filepath == track.filepath,
                remoteSessionActive = session.sessionState.value.isRemoteActive,
                handoffActive =
                    session.sessionState.value.handoffState !=
                        org.michimusic.mobile.playback.HandoffState.IDLE,
                localEndpoint = session.sessionState.value.activeEndpoint.isLocal,
            )

            /** Strict contract for a physical GENRE mutation (kept fail-closed). */
            override fun stillOwns(): Boolean =
                MichiGenreAccessPolicy.canMutateAudioFile(accessContext())

            /** Read-only contract: playback of the same stable local file never blocks it. */
            override fun canLookup(): Boolean =
                MichiGenreAccessPolicy.canLookup(accessContext())
        }
    }
    val initial = remember(trackReceipt) {
        GenreEditorUiState(
            target = MichiGenreEditorContract.target(
                track = track,
                embeddedGenres = embeddedGenres,
                tagsVerified = tagsVerified,
                origin = origin,
            ),
            evidenceReason = evidenceReason,
            libraryClassification = track.genre,
            classificationHint =
                "Clasificación del índice local, no prueba de la etiqueta física.",
            preview = MichiGenrePreviewPolicy.of(embeddedGenres),
            descriptorOpenInPlayer = sessionState.currentTrack?.filepath == track.filepath,
        )
    }
    val coordinator = remember(trackReceipt) {
        GenreEditorCoordinator(
            initial = initial,
            scope = coroutineScope,
            authority = authority,
            gateway = gatewayAdapter,
            writer = writerAdapter,
            reconciler = reconcilerAdapter,
            onLibraryRefresh = { albumsViewModel.loadMedia(forceRefresh = true) },
            onClose = onClose,
            authorization = authorization,
        )
    }
    val uiState by coordinator.state.collectAsStateWithLifecycle()
    // The mode badge follows the honest session truth without rebuilding the machine.
    val descriptorOpen = sessionState.currentTrack?.filepath == track.filepath
    LaunchedEffect(descriptorOpen) { coordinator.refreshAuthorityFacts(descriptorOpen) }
    // A cold editor reads one local receipt only; nothing runs in Home/scroll/idle.
    LaunchedEffect(trackReceipt) { coordinator.loadRecovery() }
    // Neither a dismissed editor nor a replaced track may receive late HTTP results.
    // A cancelled coroutine does not revoke an already completed network disclosure.
    DisposableEffect(trackReceipt) {
        onDispose { coordinator.unbind() }
    }
    val writeGrantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        coordinator.dispatch(
            GenreEditorIntent.ResolveSystemRequest(result.resultCode == Activity.RESULT_OK),
        )
    }
    val recoveryGrantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        coordinator.dispatch(
            GenreEditorIntent.ResolveSystemRequest(result.resultCode == Activity.RESULT_OK),
        )
    }
    // The Android grant is the only work the coordinator cannot do itself.
    LaunchedEffect(uiState.systemRequest) {
        val request = uiState.systemRequest ?: return@LaunchedEffect
        try {
            val grant = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
            val payload = IntentSenderRequest.Builder(grant.intentSender).build()
            when (request) {
                GenreEditorSystemRequest.WRITE_PERMISSION -> writeGrantLauncher.launch(payload)
                GenreEditorSystemRequest.RECOVERY_PERMISSION,
                GenreEditorSystemRequest.UNKNOWN_BYTES_RECOVERY_PERMISSION,
                -> recoveryGrantLauncher.launch(payload)
            }
        } catch (_: Exception) {
            coordinator.dispatch(GenreEditorIntent.SystemRequestUnavailable)
        }
    }
    GenreEditorContent(uiState = uiState, onIntent = coordinator::dispatch)
    GenreEditorPlatformDialogs(
        uiState = uiState,
        coordinator = coordinator,
        context = context,
        uri = uri,
    )
}

/**
 * Platform-bound confirmations. They are NOT a second editor: each one only asks for
 * the Android grant and then hands the exact same receipt back to the coordinator.
 */
@Composable
private fun GenreEditorPlatformDialogs(
    uiState: GenreEditorUiState,
    coordinator: GenreEditorCoordinator,
    context: android.content.Context,
    uri: Uri,
) {
    fun platformGrantRequired(): Boolean =
        uri.authority == MediaStore.AUTHORITY ||
            context.checkUriPermission(
                uri,
                Process.myPid(),
                Process.myUid(),
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            ) != PackageManager.PERMISSION_GRANTED

    if (uiState.confirmWrite && uiState.writesCertified && uiState.tagsVerified) {
        AlertDialog(
            onDismissRequest = { coordinator.dispatch(GenreEditorIntent.DismissWrite) },
            title = { Text("Confirmar edición de género") },
            text = {
                Text(
                    "Se editará solo el campo GENRE de esta canción. El género previo será " +
                        "sustituido si existía. Se realizará una copia temporal de seguridad y " +
                        "verificación posterior.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (platformGrantRequired()) coordinator.confirmWriteRequest()
                        else coordinator.writeWithHeldPermission()
                    },
                    modifier = Modifier.testTag("mi23_write_confirm"),
                ) { Text("Autorizar cambio") }
            },
            dismissButton = {
                TextButton(onClick = { coordinator.dispatch(GenreEditorIntent.DismissWrite) }) {
                    Text("Cancelar")
                }
            },
        )
    }
    if (uiState.confirmRecovery && uiState.writesCertified) {
        AlertDialog(
            onDismissRequest = { coordinator.dispatch(GenreEditorIntent.DismissRecovery) },
            title = { Text("Restaurar el archivo original") },
            text = {
                Text(
                    "Se reemplazará el contenido actual de esta canción por la copia local " +
                        "preservada antes de la operación. No recupera ediciones externas " +
                        "posteriores. Requiere el permiso de Android y se verificará el SHA-256 " +
                        "del original.",
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("mi24_confirm_explicit_recovery"),
                    onClick = {
                        coordinator.confirmRecoveryRequest(requirePermission = platformGrantRequired())
                    },
                ) { Text("Restaurar con verificación") }
            },
            dismissButton = {
                TextButton(onClick = { coordinator.dispatch(GenreEditorIntent.DismissRecovery) }) {
                    Text("Cancelar")
                }
            },
        )
    }
    // KILLCRITIC MI-25: NEVER treat an unknown current file as ordinary rollback.
    // This second, conspicuous confirmation is shown only when the provider bytes
    // differ from BOTH the original SHA-256 and the expected staged SHA-256.
    val unknown = uiState.unknownRecovery
    if (uiState.confirmUnknownRecovery && uiState.writesCertified && unknown != null) {
        AlertDialog(
            onDismissRequest = { coordinator.dispatch(GenreEditorIntent.DismissUnknownRecovery) },
            title = { Text("Advertencia: el archivo cambió") },
            text = {
                Text(
                    "Los bytes actuales no coinciden con el original ni con la versión editada " +
                        "prevista. Podría ser una escritura interrumpida O una modificación de " +
                        "otra aplicación. Restaurar la copia BORRARÁ esos bytes actuales, " +
                        "incluidos posibles cambios posteriores. No continúes si desconoces " +
                        "el origen de la modificación.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !uiState.busy,
                    modifier = Modifier.testTag("mi25_override_unknown_file_confirm"),
                    onClick = {
                        coordinator.confirmUnknownRecoveryRequest(
                            requirePermission = platformGrantRequired(),
                        )
                    },
                ) { Text("Sobrescribir bytes actuales con la copia original") }
            },
            dismissButton = {
                TextButton(
                    onClick = { coordinator.dispatch(GenreEditorIntent.DismissUnknownRecovery) },
                ) { Text("Conservar archivo actual") }
            },
        )
    }
}

/**
 * K47-NORM-02: exact, path-free diagnostic for a cold read that could not prove the
 * physical tags. Never says the file has no genre.
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
    ExtendedTagUnavailableReason.DOCUMENT_CHANGED_DURING_READ ->
        "El archivo cambió mientras se leían sus etiquetas. Vuelve a intentarlo."
    null -> "No se registró un motivo para esta lectura."
}
