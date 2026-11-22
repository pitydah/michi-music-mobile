package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import org.michimusic.core.intelligence.MichiGenreAccessPolicy
import org.michimusic.core.metadata.MetadataEditorSection
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.cache.toTrack
import org.michimusic.data.metadata.MetadataSaveService
import org.michimusic.data.metadata.MetadataPrivateBundleCoordinator
import org.michimusic.data.repository.ExtendedTagUnavailableReason
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.IndexedMetadataReference
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.MetadataEditorColdRead
import org.michimusic.data.repository.MetadataReadPhase
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.playback.HandoffState
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.ui.components.MetadataEditorTopBar
import org.michimusic.mobile.ui.components.MichiMetadataAlbumBatchEditor
import org.michimusic.mobile.ui.components.MichiMetadataEditorPreviewContent
import org.michimusic.mobile.ui.components.MichiMetadataRecoveryPanel
import org.michimusic.mobile.ui.metadata.MetadataEditorLaunchHolder
import org.michimusic.mobile.ui.metadata.MetadataEditorLaunchRequest
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * ME-11S: the single, fullscreen metadata editor destination.
 *
 * It re-resolves the track and its document on entry, reads tags physically (cold
 * read) when the provider allows it, and never invents physical evidence from the
 * Room index. A cold-read failure is a first-class state: it offers a retry, an
 * indexed reference explicitly labelled as non-physical, and an optional
 * diagnostic; it does not open the recovery flow.
 */
@Composable
internal fun MetadataEditorScreen(onClose: () -> Unit) {
    val request = remember { MetadataEditorLaunchHolder.peek() }
    if (request == null) {
        MetadataEditorShell {
            SafeEditorMessage(
                title = "El editor se cerró",
                body = "La solicitud de edición ya no está disponible. Vuelve a abrir la canción desde su menú.",
                onClose = onClose,
            )
        }
        return
    }
    val trackDao: TrackDao = koinInject()
    val localMedia: LocalMediaRepository = koinInject()
    val repository: ExtendedTrackMetadataRepository = koinInject()
    val rootAuthority: MusicRootAuthority = koinInject()
    var attempt by remember(request) { mutableIntStateOf(0) }
    var state by remember(request) { mutableStateOf<EditorState>(EditorState.Loading) }

    LaunchedEffect(request, attempt) {
        state = EditorState.Loading
        state = loadEditorState(request, trackDao, localMedia, repository, rootAuthority)
    }

    when (val current = state) {
        EditorState.Loading -> MetadataEditorShell {
            Column(
                Modifier.fillMaxSize().testTag("metadata_editor_loading"),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = PrimaryPinkContainer)
                Spacer(Modifier.height(MichiSpacing.md))
                Text("Leyendo las etiquetas del archivo…", color = TextSecondary)
                Spacer(Modifier.height(MichiSpacing.sm))
                Text(
                    "La lectura es física y de solo lectura. No se escribe nada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }

        is EditorState.Batch -> MetadataEditorShell {
            if (current.tracks.isEmpty()) {
                SafeEditorMessage(
                    title = "La selección ya no está disponible",
                    body = "Ninguna de las pistas seleccionadas sigue en la biblioteca activa.",
                    onClose = onClose,
                )
            } else {
                MichiMetadataAlbumBatchEditor(tracks = current.tracks, onDismiss = onClose)
            }
        }

        is EditorState.Ready -> MetadataEditorShell {
            MetadataEditorReadyContent(
                request = request,
                track = current.track,
                snapshot = current.snapshot,
                partial = current.partial,
                onClose = onClose,
                trackDao = trackDao,
            )
        }

        is EditorState.Failed -> MetadataEditorShell {
            MetadataReadError(
                track = current.track,
                reason = current.reason,
                indexed = current.indexed,
                detail = current.detail,
                phase = current.phase,
                onRetry = { attempt++ },
                onClose = onClose,
            )
        }

        is EditorState.MissingTrack -> MetadataEditorShell {
            SafeEditorMessage(
                title = "La canción ya no está en la biblioteca",
                body = "El archivo cambió de ámbito o se eliminó del índice. Vuelve a abrirla desde su menú actualizado.",
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun MetadataEditorReadyContent(
    request: MetadataEditorLaunchRequest,
    track: Track,
    snapshot: MetadataSnapshot,
    partial: Boolean,
    onClose: () -> Unit,
    trackDao: TrackDao,
) {
    val context = LocalContext.current
    val repository: ExtendedTrackMetadataRepository = koinInject()
    val scopeStore: LibraryScopeStore = koinInject()
    val rootAuthority: MusicRootAuthority = koinInject()
    val session: PlaybackSessionManager = koinInject()
    val currentSession by session.sessionState.collectAsStateWithLifecycle()
    val currentSessionAllowsPreparation = currentSession.currentTrack?.filepath != track.filepath &&
        !currentSession.isRemoteActive && currentSession.handoffState == HandoffState.IDLE &&
        currentSession.activeEndpoint.isLocal
    val token = remember(track.sourceId, track.id, track.filepath, track.size) { Any() }
    val capturedScope = remember(token) { scopeStore.activeScope.value }
    val capturedRevision = remember(token) { rootAuthority.revision.value }
    val open = remember(token) { AtomicBoolean(true) }
    DisposableEffect(token) {
        open.set(true)
        onDispose { open.set(false) }
    }
    // Private preparation reuses ME-11R's stricter mutation preflight; it never
    // certifies physical ownership and never writes the original file.
    val bundle = remember(context, repository) {
        MetadataPrivateBundleCoordinator(context.applicationContext, repository)
    }
    val pilotWriter = remember(context, trackDao) {
        MetadataSaveService(context.applicationContext, trackDao)
    }
    fun authority() = MichiGenreAccessPolicy.Context(
        trackLocal = track.source == TrackSource.LOCAL,
        receiptMatches = open.get() && track.id.isNotBlank(),
        uriMatches = track.filepath.startsWith("content://"),
        scopeUnchanged = scopeStore.activeScope.value == capturedScope,
        revisionUnchanged = rootAuthority.revision.value == capturedRevision,
        dialogOpen = open.get(),
        trackSelectedInPlayer = session.sessionState.value.currentTrack?.filepath == track.filepath,
        remoteSessionActive = session.sessionState.value.isRemoteActive,
        handoffActive = session.sessionState.value.handoffState != HandoffState.IDLE,
        localEndpoint = session.sessionState.value.activeEndpoint.isLocal,
    )
    Column(Modifier.fillMaxSize()) {
        MichiMetadataEditorPreviewContent(
            snapshot = snapshot,
            onDismiss = onClose,
            initialSection = request.initialSection,
            focusGenre = request.focusGenre,
            partial = partial,
            artworkModel = artworkModelFor(track.coverId),
            recoveryPanel = if (track.source == TrackSource.LOCAL &&
                track.filepath.startsWith("content://")) {
                { MichiMetadataRecoveryPanel(track.sourceId, track.id, track.filepath) }
            } else null,
            privateBundleCoordinator = bundle,
            expectedFileBytes = track.size,
            stillOwnsPrivateSource = { open.get() && MichiGenreAccessPolicy.canLookup(authority()) },
            playbackDescriptorClosed = {
                open.get() && MichiGenreAccessPolicy.canMutateAudioFile(authority())
            },
            currentSessionAllowsPreparation = currentSessionAllowsPreparation,
            pilotWriter = pilotWriter,
            indexSourceId = track.sourceId.ifBlank {
                MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
            },
        )
    }
}

private sealed interface EditorState {
    data object Loading : EditorState
    data class Ready(
        val track: Track,
        val snapshot: org.michimusic.core.metadata.MetadataSnapshot,
        val partial: Boolean,
        val indexed: IndexedMetadataReference?,
    ) : EditorState
    data class Batch(val tracks: List<Track>) : EditorState
    data class Failed(
        val track: Track?,
        val indexed: IndexedMetadataReference?,
        val reason: ExtendedTagUnavailableReason,
        val detail: String?,
        val phase: MetadataReadPhase? = null,
    ) : EditorState
    data object MissingTrack : EditorState
}

/**
 * The room cache may not contain on-device library rows, and the UI track may
 * carry a blank sourceId. Resolve the stable media id through Room first and
 * fall back to the authoritative MediaStore-backed local repository.
 */
/** MediaStore tracks carry a bare album id; synced tracks may carry a full URI. */
private fun artworkModelFor(coverId: String): Any? {
    val id = coverId.trim()
    if (id.isEmpty()) return null
    return if (id.startsWith("content://") || id.startsWith("file:") ||
        id.startsWith("http://") || id.startsWith("https://")) id
    else "content://media/external/audio/albumart/$id"
}

private suspend fun resolveTrack(
    trackDao: TrackDao,
    localMedia: LocalMediaRepository,
    sourceId: String,
    trackId: String,
    expectedUri: String = "",
    expectedSize: Long = 0L,
): Track? {
    if (trackId.isBlank()) return null
    fun matches(track: Track): Boolean =
        (expectedUri.isBlank() || track.filepath == expectedUri) &&
            (expectedSize <= 0L || track.size == expectedSize)
    trackDao.getTrackBySource(sourceId, trackId)?.toTrack()?.takeIf(::matches)?.let { return it }
    trackDao.getTracksById(trackId)
        .firstOrNull { it.sourceId == MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID }
        ?.toTrack()?.takeIf(::matches)?.let { return it }
    val local = localMedia.loadTracks()
    return local.firstOrNull { it.id == trackId && matches(it) }
}

private suspend fun loadEditorState(
    request: MetadataEditorLaunchRequest,
    trackDao: TrackDao,
    localMedia: LocalMediaRepository,
    repository: ExtendedTrackMetadataRepository,
    rootAuthority: MusicRootAuthority,
): EditorState = try {
    val expectedGeneration = if (request.scopeGeneration > 0L) {
        request.scopeGeneration
    } else {
        rootAuthority.revision.value
    }
    if (rootAuthority.revision.value != expectedGeneration) {
        EditorState.Failed(
            track = null,
            indexed = null,
            reason = ExtendedTagUnavailableReason.LIBRARY_SCOPE_CHANGED,
            detail = "El ámbito de la biblioteca cambió mientras se abría el editor.",
            phase = MetadataReadPhase.CHECK_ROOT_OWNERSHIP,
        )
    } else if (request.isBatch) {
        val tracks = request.selectionIds.mapNotNull { id ->
            resolveTrack(trackDao, localMedia, request.sourceId, id)
        }
        EditorState.Batch(tracks)
    } else {
        val track = resolveTrack(
            trackDao, localMedia, request.sourceId, request.trackId,
            expectedUri = request.trackUri, expectedSize = request.expectedSize,
        )
        if (track == null) {
            EditorState.MissingTrack
        } else if (request.trackUri.isNotBlank() && track.filepath != request.trackUri) {
            EditorState.Failed(
                track = track,
                indexed = null,
                reason = ExtendedTagUnavailableReason.SOURCE_IDENTITY_MISMATCH,
                detail = "La identidad solicitada no coincide con el documento resuelto.",
                phase = MetadataReadPhase.RESOLVE_CANONICAL_TRACK,
            )
        } else {
            when (val read = repository.loadEditorColdRead(
                sourceId = track.sourceId,
                trackId = track.id,
                filepath = track.filepath,
                expectedSize = track.size,
            )) {
                is MetadataEditorColdRead.Verified -> EditorState.Ready(
                    track = track,
                    snapshot = read.snapshot,
                    partial = false,
                    indexed = read.indexed,
                )
                is MetadataEditorColdRead.Partial -> EditorState.Ready(
                    track = track,
                    snapshot = read.snapshot,
                    partial = true,
                    indexed = read.indexed,
                )
                is MetadataEditorColdRead.IndexedOnly -> EditorState.Failed(
                    track = track,
                    indexed = read.indexed,
                    reason = read.reason,
                    detail = null,
                    phase = read.phase,
                )
                is MetadataEditorColdRead.Unavailable -> EditorState.Failed(
                    track = track,
                    indexed = read.indexed,
                    reason = read.reason,
                    detail = null,
                    phase = read.phase,
                )
            }
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    EditorState.Failed(
        track = null,
        indexed = null,
        reason = ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE,
        detail = error.message,
        phase = MetadataReadPhase.COLD_TAG_READ,
    )
}

@Composable
private fun MetadataEditorShell(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(SurfaceObsidian)
            .statusBarsPadding()
            .testTag("metadata_editor_screen"),
    ) { content() }
}

@Composable
private fun MetadataReadError(
    track: Track?,
    reason: ExtendedTagUnavailableReason,
    indexed: IndexedMetadataReference?,
    detail: String?,
    phase: MetadataReadPhase?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    var indexExpanded by remember { mutableStateOf(false) }
    var diagnosticExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(SurfaceObsidian)) {
        MetadataEditorTopBar(
            title = "Editar metadatos",
            subtitle = "Lectura del archivo",
            onBack = onClose,
            backDescription = "Cerrar y volver a la canción",
        )
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = MichiSpacing.xl).padding(top = MichiSpacing.lg)
                .testTag("metadata_read_error"),
            verticalArrangement = Arrangement.spacedBy(MichiSpacing.lg),
        ) {
        if (track != null) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                SubcomposeAsyncImage(
                    model = artworkModelFor(track.coverId),
                    contentDescription = "Carátula de ${track.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(62.dp).clip(MichiShapes.sm),
                    loading = { ErrorArtworkFallback() },
                    error = { ErrorArtworkFallback() },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("CANCION · BIBLIOTECA LOCAL", style = MichiTypography.microLabel,
                        color = PrimaryPinkContainer)
                    Text(track.title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = PureWhite,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.album.takeIf { it.isNotBlank() })
                            .joinToString(" · "),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).background(Color(0xFFA1A5B2),
                            androidx.compose.foundation.shape.CircleShape))
                        Text("Etiquetas sin verificar", style = MichiTypography.microLabel,
                            color = TextSecondary)
                    }
                }
            }
            HorizontalDivider(color = AeroHairline, thickness = 1.dp)
        }
        Column(
            Modifier.fillMaxWidth().background(AeroGraphite, MichiShapes.md)
                .border(1.dp, AeroHairline, MichiShapes.md)
                .padding(MichiSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                Icon(Icons.Filled.Warning, contentDescription = null,
                    tint = PrimaryPinkContainer, modifier = Modifier.size(20.dp))
                Text("LECTURA NO COMPLETADA", style = MichiTypography.screenEyebrow,
                    color = PrimaryPinkContainer)
            }
            Text("No pudimos verificar las etiquetas", style = MichiTypography.sectionTitle,
                color = PureWhite)
            Text(
                "La biblioteca conoce esta canción, pero falta confirmar sus etiquetas " +
                    "directamente en el archivo. Esto no significa que estén vacías ni dañadas.",
                color = TextSecondary,
            )
            Text(readErrorMessage(reason), color = TextSecondary,
                style = MaterialTheme.typography.bodySmall)
            val retryLabel = readRetryLabel(reason)
            if (retryLabel != null) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("metadata_read_retry"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(MichiSpacing.sm))
                    Text(retryLabel, color = Color.Black, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    "Reintentar no cambiaría el resultado.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                )
            }
        }
        if (indexed != null && indexed.rows.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(AeroGraphite, MichiShapes.md)
                    .border(1.dp, AeroHairline, MichiShapes.md),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { indexExpanded = !indexExpanded }
                        .padding(horizontal = MichiSpacing.lg, vertical = MichiSpacing.md)
                        .heightIn(min = 48.dp)
                        .testTag("metadata_index_reference_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Referencia del índice", color = PureWhite,
                            style = MaterialTheme.typography.titleSmall)
                        Text("No son etiquetas físicas verificadas", color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (indexExpanded) "Ocultar" else "Ver", color = PrimaryPinkContainer,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (indexExpanded) {
                    Column(Modifier.padding(horizontal = MichiSpacing.lg)
                        .padding(bottom = MichiSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        indexed.rows.forEach { row ->
                            Text("${row.label}: ${row.value}", color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().background(AeroGraphite, MichiShapes.md)
                .border(1.dp, AeroHairline, MichiShapes.md)
                .clickable { diagnosticExpanded = !diagnosticExpanded }
                .padding(horizontal = MichiSpacing.lg, vertical = MichiSpacing.md)
                .testTag("metadata_diagnostic_toggle"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Detalles del diagnóstico", color = PureWhite,
                style = MaterialTheme.typography.titleSmall)
            Text(if (diagnosticExpanded) "Ocultar el motivo técnico" else
                "Motivo técnico disponible bajo demanda", color = PrimaryPinkContainer,
                style = MaterialTheme.typography.bodySmall)
            if (diagnosticExpanded) {
                Text(
                    buildString {
                        append(reason.name)
                        phase?.let { append(" · fase ").append(it.name) }
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!detail.isNullOrBlank()) {
                    Text(detail, color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            "La recuperación privada solo se abre cuando existe un diario pendiente real; " +
                "un fallo de lectura o la falta de caché no la activan.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        }
        HorizontalDivider(color = AeroHairline, thickness = 1.dp)
        Box(
            Modifier.fillMaxWidth().padding(horizontal = MichiSpacing.xl,
                vertical = MichiSpacing.md),
        ) {
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(AeroGraphite, MichiShapes.md)
                    .border(1.dp, AeroHairline, MichiShapes.md),
            ) {
                Text("Cerrar y volver a la canción", color = PureWhite)
            }
        }
    }
}

@Composable
private fun SafeEditorMessage(title: String, body: String, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(MichiSpacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MichiTypography.sectionTitle, color = PureWhite)
        Spacer(Modifier.height(MichiSpacing.sm))
        Text(body, color = TextSecondary)
        Spacer(Modifier.height(MichiSpacing.lg))
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Volver", color = PrimaryPinkContainer)
        }
    }
}

internal fun readRetryLabel(reason: ExtendedTagUnavailableReason): String? = when (reason) {
    ExtendedTagUnavailableReason.TAG_READER_UNSUPPORTED,
    ExtendedTagUnavailableReason.NOT_CONTENT_URI,
    ExtendedTagUnavailableReason.INVALID_TRACK_ID,
    ExtendedTagUnavailableReason.INVALID_SIZE,
    ExtendedTagUnavailableReason.SOURCE_IDENTITY_MISMATCH,
    ExtendedTagUnavailableReason.LIBRARY_SCOPE_CHANGED -> null
    ExtendedTagUnavailableReason.PERMISSION_DENIED -> "Reintentar con permiso"
    else -> "Volver a intentar"
}

private fun readErrorMessage(reason: ExtendedTagUnavailableReason): String = when (reason) {
    ExtendedTagUnavailableReason.PERMISSION_DENIED ->
        "No hay permiso para leer este archivo. Vuelve a conceder el acceso a la biblioteca."
    ExtendedTagUnavailableReason.TAG_READER_UNSUPPORTED ->
        "El formato del archivo no tiene un lector de etiquetas compatible."
    ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE ->
        "El proveedor de documentos no respondió. Puede ser temporal."
    ExtendedTagUnavailableReason.DOCUMENT_CHANGED_DURING_READ ->
        "El archivo cambió mientras se leían sus etiquetas. Inténtalo de nuevo."
    ExtendedTagUnavailableReason.SOURCE_IDENTITY_MISMATCH ->
        "La canción solicitada ya no corresponde al mismo archivo. Vuelve a abrirla desde la biblioteca."
    ExtendedTagUnavailableReason.LIBRARY_SCOPE_CHANGED ->
        "La biblioteca cambió de ámbito mientras se abría el editor. Vuelve a abrir la canción."
    ExtendedTagUnavailableReason.SIZE_MISMATCH,
    ExtendedTagUnavailableReason.MISSING_STAMP,
    ExtendedTagUnavailableReason.MODIFIED_AT_ZERO ->
        "No se pudo confirmar el estado del archivo antes y después de la lectura."
    ExtendedTagUnavailableReason.NOT_CONTENT_URI ->
        "Este origen no expone un documento Android legible."
    else -> "La lectura física no pudo completarse. No se modificó ningún archivo."
}

@Composable
private fun ErrorArtworkFallback() {
    Box(
        Modifier.size(62.dp).background(AeroGraphiteRaised, MichiShapes.sm),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Album,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(28.dp),
        )
    }
}
