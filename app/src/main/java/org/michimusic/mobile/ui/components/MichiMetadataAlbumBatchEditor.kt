package org.michimusic.mobile.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.yield
import org.koin.compose.koinInject
import org.michimusic.core.metadata.CaseMode
import org.michimusic.core.metadata.MetadataBatchAction
import org.michimusic.core.metadata.MetadataBatchEntry
import org.michimusic.core.metadata.MetadataBatchRunPolicy
import org.michimusic.core.metadata.MetadataBatchReviewDigest
import org.michimusic.core.metadata.MetadataBatchResult
import org.michimusic.core.metadata.MetadataBatchSelectionPolicy
import org.michimusic.core.metadata.MetadataBatchToolkit
import org.michimusic.core.metadata.MetadataCsvBulkExport
import org.michimusic.core.metadata.MetadataCsvBulkImport
import org.michimusic.core.metadata.MetadataCsvPreview
import org.michimusic.core.metadata.MetadataLibraryDiagnostics
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataEditPreview
import androidx.compose.ui.text.font.FontWeight
import coil3.compose.SubcomposeAsyncImage
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSelectionItem
import org.michimusic.core.metadata.MetadataSelectionIssue
import org.michimusic.core.metadata.MetadataSelectionPlan
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.metadata.MetadataBatchSequentialPreparationCoordinator
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.ExtendedTagUnavailableReason
import org.michimusic.data.repository.MetadataEditorColdRead
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.playback.HandoffState
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

private enum class AlbumBatchMode(val label: String) {
    REPLACE("Sustituir valores"), APPEND("Añadir valores"),
    COPY("Copiar de otro campo"), CLEAR("Limpiar campo"),
    TRIM("Quitar espacios extremos"), UPPER("Pasar a mayúsculas"),
    LOWER("Pasar a minúsculas"), FIND_REPLACE("Buscar y reemplazar"),
    REMOVE_EXACT("Quitar una entrada exacta"),
    TRACK_SEQUENCE("Renumerar pistas"),
}

private data class AlbumBatchRequest(val id: Int, val selected: List<Track>)
private sealed interface AlbumBatchRead {
    data object Idle : AlbumBatchRead
    data class Loading(val completed: Int, val total: Int) : AlbumBatchRead
    data class Ready(val snapshots: List<org.michimusic.core.metadata.MetadataSnapshot>) : AlbumBatchRead
    data class Unavailable(val position: Int, val reason: ExtendedTagUnavailableReason) : AlbumBatchRead
}

/**
 * ME-02 user-initiated album workbench. Does not access files until the button
 * "Leer etiquetas seleccionadas" is clicked. Every read goes through the existing
 * owner/stamp/tag-equivalence proof. Never writes MediaStore, SAF, Room or audio.
 * The source album ordering is explicit; no filename or genre heuristics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MichiMetadataAlbumBatchEditor(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    repository: ExtendedTrackMetadataRepository = koinInject(),
) {
    val candidates = remember(tracks) { tracks.map { track ->
        MetadataSelectionItem(track.sourceId, track.id, track.filepath, track.size,
            track.source == TrackSource.LOCAL)
    } }
    val selectableIndices = remember(candidates) { candidates.indices.filter { index ->
        candidates[index].isLocal && candidates[index].uri.startsWith("content://") &&
            candidates[index].sizeBytes > 0 && candidates[index].trackId.isNotBlank()
    }.toSet() }
    var selected by remember(candidates) {
        mutableStateOf(if (selectableIndices.size <= MetadataBatchSelectionPolicy.MAX_SELECTED)
            selectableIndices else emptySet())
    }
    var request by remember(candidates) { mutableStateOf<AlbumBatchRequest?>(null) }
    var requestCounter by remember(candidates) { mutableStateOf(0) }
    var load by remember(candidates) { mutableStateOf<AlbumBatchRead>(AlbumBatchRead.Idle) }
    var issue by remember(candidates) { mutableStateOf<String?>(null) }
    var chosenField by remember(candidates) { mutableStateOf(MetadataField.ALBUM) }
    var copySource by remember(candidates) { mutableStateOf(MetadataField.ARTIST) }
    var copyOnlyMissing by remember(candidates) { mutableStateOf(true) }
    var copyMenu by remember(candidates) { mutableStateOf(false) }
    var fieldMenu by remember(candidates) { mutableStateOf(false) }
    var mode by remember(candidates) { mutableStateOf(AlbumBatchMode.REPLACE) }
    var modeMenu by remember(candidates) { mutableStateOf(false) }
    var proposedValues by remember(candidates) { mutableStateOf(listOf("")) }
    var findText by remember(candidates) { mutableStateOf("") }
    var replaceText by remember(candidates) { mutableStateOf("") }
    var firstTrack by remember(candidates) { mutableStateOf("1") }
    var confirmClear by remember(candidates) { mutableStateOf(false) }
    var confirmRemoveExact by remember(candidates) { mutableStateOf(false) }
    var removeValueMenu by remember(candidates) { mutableStateOf(false) }
    var selectedRemoveIndex by remember(candidates) { mutableStateOf(0) }
    var showPreview by remember(candidates) { mutableStateOf(false) }
    var resultPage by remember(candidates) { mutableStateOf(0) }
    var showDiagnostics by remember(candidates) { mutableStateOf(false) }
    var showTrackList by remember(candidates) {
        mutableStateOf(tracks.size <= 20 || tracks.size > MetadataBatchSelectionPolicy.MAX_SELECTED)
    }
    var queuedActions by remember(candidates) { mutableStateOf<List<MetadataBatchAction>>(emptyList()) }
    // ME-11U: direct edits on shared fields become explicit Replace actions during the
    // same per-track review. This map is local state; it is never persisted or written.
    var batchEdits by remember(candidates) {
        mutableStateOf<Map<MetadataField, List<String>>>(emptyMap())
    }
    var advancedTouched by remember(candidates) { mutableStateOf(false) }
    var privateStageConfirmed by remember(candidates) { mutableStateOf(false) }
    var privateStageBusy by remember(candidates) { mutableStateOf(false) }
    var privateStageNotice by remember(candidates) { mutableStateOf<String?>(null) }
    var csvOpen by remember(candidates) { mutableStateOf(false) }
    var pastedCsv by remember(candidates) { mutableStateOf("") }
    var csvReview by remember(candidates) {
        mutableStateOf<MetadataCsvBulkImport.Result?>(null)
    }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val scope = rememberCoroutineScope()
    val scopeStore: LibraryScopeStore = koinInject()
    val rootAuthority: MusicRootAuthority = koinInject()
    val session: PlaybackSessionManager = koinInject()
    val capturedScope = remember(candidates) { scopeStore.activeScope.value }
    val capturedRevision = remember(candidates) { rootAuthority.revision.value }
    val dialogAlive = remember(candidates) { AtomicBoolean(true) }
    DisposableEffect(candidates) {
        dialogAlive.set(true)
        onDispose { dialogAlive.set(false) }
    }
    val privateBatchCoordinator = remember(context, repository) {
        MetadataBatchSequentialPreparationCoordinator(context.applicationContext, repository)
    }
    var csvFileNotice by remember(candidates) { mutableStateOf<String?>(null) }
    var exportPayload by remember(candidates) { mutableStateOf<String?>(null) }
    // SAF pickers run ONLY on explicit taps; no scan, persistence or broad permissions.
    val importDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        val openedFor = request?.id
        if (uri != null && openedFor != null && load is AlbumBatchRead.Ready) {
            scope.launch {
                val contents = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            var emptyReads = 0
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n == 0) {
                                    if (++emptyReads > 8) error("CSV_PROVIDER_UNREADABLE")
                                    continue
                                }
                                emptyReads = 0
                                if (out.size() + n > 128 * 1024) error("CSV_TOO_LARGE")
                                out.write(buffer, 0, n)
                            }
                            Charsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(out.toByteArray())).toString()
                        }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                  catch (_: Exception) { null }
                if (request?.id == openedFor) {
                    if (contents == null) csvFileNotice = "No se pudo leer el CSV seleccionado o supera 128 KiB."
                    else {
                        pastedCsv = contents.removePrefix("\uFEFF")
                        csvReview = null
                        csvOpen = true
                        csvFileNotice = "CSV leído. Valida la propuesta antes de aplicar cualquier cambio."
                    }
                }
            }
        }
    }
    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val payload = exportPayload
        exportPayload = null
        if (uri != null && payload != null) scope.launch {
            val exported = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(payload.toByteArray(Charsets.UTF_8))
                        output.flush()
                        true
                    } ?: false
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { false }
            csvFileNotice = if (exported)
                "CSV enviado al documento elegido. No se modificó ningún audio; confirma el archivo en el proveedor."
                else "La exportación CSV no pudo completarse; revisa el documento elegido."
        }
    }

    fun invalidateSelection() {
        request = null
        load = AlbumBatchRead.Idle
        issue = null
        showPreview = false
        resultPage = 0
        showDiagnostics = false
        queuedActions = emptyList()
        batchEdits = emptyMap()
        advancedTouched = false
        csvReview = null
        csvOpen = false
        pastedCsv = ""
        exportPayload = null
        csvFileNotice = null
        confirmRemoveExact = false
        privateStageConfirmed = false
        privateStageNotice = null
        removeValueMenu = false
        selectedRemoveIndex = 0
    }

    LaunchedEffect(request, repository) {
        val current = request ?: return@LaunchedEffect
        val loaded = ArrayList<org.michimusic.core.metadata.MetadataSnapshot>(current.selected.size)
        try {
            for ((position, track) in current.selected.withIndex()) {
                yield() // responsive cancellation, bounded explicit work only
                val proof = repository.loadEditorColdRead(
                    track.sourceId, track.id, track.filepath, track.size)
                when (proof) {
                    is MetadataEditorColdRead.Verified -> {
                        val target = proof.snapshot.target
                        if (target.sourceId != track.sourceId || target.trackId != track.id ||
                            target.uri != track.filepath) {
                            load = AlbumBatchRead.Unavailable(position,
                                ExtendedTagUnavailableReason.TAG_MISMATCH)
                            return@LaunchedEffect
                        }
                        loaded += proof.snapshot
                        load = AlbumBatchRead.Loading(loaded.size, current.selected.size)
                    }
                    is MetadataEditorColdRead.Partial -> {
                        val target = proof.snapshot.target
                        if (target.sourceId != track.sourceId || target.trackId != track.id ||
                            target.uri != track.filepath) {
                            load = AlbumBatchRead.Unavailable(position,
                                ExtendedTagUnavailableReason.TAG_MISMATCH)
                            return@LaunchedEffect
                        }
                        loaded += proof.snapshot
                        load = AlbumBatchRead.Loading(loaded.size, current.selected.size)
                    }
                    is MetadataEditorColdRead.IndexedOnly -> {
                        load = AlbumBatchRead.Unavailable(position, proof.reason)
                        return@LaunchedEffect
                    }
                    is MetadataEditorColdRead.Unavailable -> {
                        load = AlbumBatchRead.Unavailable(position, proof.reason)
                        return@LaunchedEffect
                    }
                }
            }
            load = AlbumBatchRead.Ready(loaded.toList())
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            load = AlbumBatchRead.Unavailable(loaded.size,
                ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE)
        }
    }

    val ready = load as? AlbumBatchRead.Ready
    val actualField = if (mode == AlbumBatchMode.TRACK_SEQUENCE) MetadataField.TRACK_NUMBER
        else chosenField
    val selectionValue = ready?.let { MetadataEditPreview.selectionValue(it.snapshots, actualField) }
    val action: MetadataBatchAction? = if (!advancedTouched) null else when (mode) {
        AlbumBatchMode.REPLACE -> MetadataBatchAction.Set(MetadataChange.Replace(chosenField, proposedValues))
        AlbumBatchMode.APPEND -> if (chosenField.multiple && proposedValues.all { it.isNotBlank() })
            MetadataBatchAction.Append(chosenField, proposedValues) else null
        AlbumBatchMode.COPY -> if (copySource != chosenField)
            MetadataBatchAction.Copy(copySource, chosenField, copyOnlyMissing) else null
        AlbumBatchMode.CLEAR -> if (confirmClear) MetadataBatchAction.Set(MetadataChange.Clear(chosenField)) else null
        AlbumBatchMode.TRIM -> MetadataBatchAction.Trim(chosenField)
        AlbumBatchMode.UPPER -> MetadataBatchAction.ChangeCase(chosenField, CaseMode.UPPERCASE)
        AlbumBatchMode.LOWER -> MetadataBatchAction.ChangeCase(chosenField, CaseMode.LOWERCASE)
        AlbumBatchMode.REMOVE_EXACT -> {
            val common = selectionValue as? MetadataEditPreview.SelectionValue.Same
            if (confirmRemoveExact && chosenField.multiple &&
                common != null && selectedRemoveIndex in common.values.indices)
                MetadataBatchAction.RemoveExact(chosenField, selectedRemoveIndex,
                    common.values[selectedRemoveIndex])
            else null
        }
        AlbumBatchMode.FIND_REPLACE -> MetadataBatchAction.FindReplace(chosenField, findText, replaceText)
        AlbumBatchMode.TRACK_SEQUENCE -> firstTrack.toIntOrNull()?.let { MetadataBatchAction.TrackSequence(it) }
    }
    val directActions = batchEdits.map { (field, values) ->
        MetadataBatchAction.Set(MetadataChange.Replace(field, values))
    }
    val candidateActions = queuedActions + directActions + listOfNotNull(action)
    val candidateReview = remember(ready, candidateActions) {
        if (ready != null && candidateActions.isNotEmpty()) MetadataBatchToolkit.review(
            ready.snapshots.map { MetadataBatchEntry(it) }, candidateActions) else null
    }
    val previewActions = if (queuedActions.isNotEmpty()) queuedActions + directActions
        else directActions + listOfNotNull(action)
    val review = remember(ready, previewActions) {
        if (ready != null && previewActions.isNotEmpty()) MetadataBatchToolkit.review(
            ready.snapshots.map { MetadataBatchEntry(it) }, previewActions) else null
    }
    // The batch identity is keyed to the EXACT review; altering a recipe
    // creates a new receipt namespace rather than recycling an old approval.
    val preparedReviewDigest = remember(ready, review) {
        if (ready != null && review is MetadataBatchResult.Ready)
            MetadataBatchReviewDigest.digest(ready.snapshots, review.entries)
        else null
    }
    val privateBatchId = remember(ready, preparedReviewDigest) { UUID.randomUUID().toString() }
    var batchRows by remember(ready, preparedReviewDigest) {
        mutableStateOf<List<MetadataBatchRunPolicy.FileResult>?>(null)
    }
    var privateStageTransactionId by remember(ready, preparedReviewDigest) { mutableStateOf<String?>(null) }
    var privateStageIndex by remember(ready, preparedReviewDigest) { mutableStateOf(-1) }
    var confirmPrivateDiscard by remember(ready, preparedReviewDigest) { mutableStateOf(false) }
    LaunchedEffect(preparedReviewDigest) {
        privateStageConfirmed = false
        confirmPrivateDiscard = false
        privateStageNotice = null
    }
    val fieldVerified = selectionValue !is MetadataEditPreview.SelectionValue.Unverified
    val allowedPreview = ready != null && previewActions.isNotEmpty() &&
        (batchEdits.isNotEmpty() || queuedActions.isNotEmpty() || (fieldVerified && action != null))
    val loadedTracks = request?.selected.orEmpty()

    Column(
        Modifier.fillMaxSize().background(SurfaceObsidian).imePadding()
            .testTag("metadata_album_batch_screen"),
    ) {
        MetadataEditorTopBar(
            title = "Editar metadatos",
            subtitle = "${tracks.size} canciones del álbum",
            onBack = onDismiss,
            backDescription = "Volver",
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .testTag("metadata_album_batch_editor"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "heading") {
                val lead = tracks.firstOrNull()
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        BatchHeroArtwork(lead)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("ÁLBUM · EDICIÓN POR LOTES",
                                style = MichiTypography.microLabel, color = PrimaryPinkContainer,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(lead?.album?.takeIf(String::isNotBlank) ?: "Álbum seleccionado",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, color = PureWhite,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    lead?.albumArtist?.takeIf(String::isNotBlank) ?: lead?.artist,
                                    "${selected.size} pistas seleccionadas",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val verified = load is AlbumBatchRead.Ready
                                Box(Modifier.size(6.dp).background(
                                    if (verified) Color(0xFF79DEB6) else Color(0xFFA1A5B2),
                                    CircleShape))
                                Text(
                                    when {
                                        verified -> "Etiquetas verificadas en la selección"
                                        load is AlbumBatchRead.Loading -> "Leyendo evidencia de la selección…"
                                        else -> "Etiquetas sin verificar hasta leer la evidencia"
                                    },
                                    style = MichiTypography.microLabel, color = TextSecondary,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = AeroHairline)
                    Column(
                        Modifier.padding(top = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("EDICIÓN POR LOTES", style = MichiTypography.screenEyebrow,
                            color = PrimaryPinkContainer)
                        Text("Campos compartidos", style = MichiTypography.sectionTitle)
                        Text("La propuesta se revisa por canción. Ninguna pista se omite silenciosamente.",
                            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.fillMaxWidth().background(AeroGraphite, MichiShapes.md)
                            .border(1.dp, AeroHairline, MichiShapes.md)
                            .padding(MichiSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${selected.size} pistas seleccionadas",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PureWhite, modifier = Modifier.weight(1f))
                                Text("1 álbum", style = MichiTypography.microLabel,
                                    color = TertiaryCyan,
                                    modifier = Modifier.background(SurfaceObsidian, MichiShapes.lg)
                                        .padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
            item(key = "select_actions") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("01 · SELECCIONAR PISTAS", style = MichiTypography.screenEyebrowTechnical)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            selected = if (selectableIndices.size <= MetadataBatchSelectionPolicy.MAX_SELECTED)
                                selectableIndices else selectableIndices.sorted().take(
                                    MetadataBatchSelectionPolicy.MAX_SELECTED).toSet()
                            invalidateSelection()
                        }) { Text("Todas") }
                        TextButton(onClick = { selected = emptySet(); invalidateSelection() }) {
                            Text("Ninguna")
                        }
                        TextButton(onClick = { showTrackList = !showTrackList },
                            modifier = Modifier.testTag("metadata_batch_toggle_tracks")) {
                            Text(if (showTrackList) "Ocultar pistas" else "Ver pistas")
                        }
                    }
                    Text("Máximo 128 pistas por operación.", style = MichiTypography.microLabel,
                        color = TextSecondary)
                }
            }
            if (showTrackList) itemsIndexed(tracks, key = { index, _ -> "batch_album_track_$index" }) { index, track ->
                val enabled = index in selectableIndices && load !is AlbumBatchRead.Loading
                val checked = index in selected
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = checked, enabled = enabled,
                        modifier = Modifier.semantics {
                            contentDescription = "Seleccionar pista ${track.title}"
                        },
                        onCheckedChange = { marked ->
                            if (marked && selected.size >= MetadataBatchSelectionPolicy.MAX_SELECTED) {
                                issue = "Máximo de 128 pistas. Desmarca otra pista antes de continuar."
                            } else {
                                selected = if (marked) selected + index else selected - index
                                invalidateSelection()
                            }
                        })
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = PureWhite)
                        Text(if (enabled) track.artist else "No verificable o en otra biblioteca",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            color = TextSecondary)
                    }
                }
            }
            item(key = "load_action") {
                HorizontalDivider()
                Text("02 · LEER EVIDENCIA", style = MichiTypography.screenEyebrowTechnical)
                Button(
                    enabled = selected.isNotEmpty() && load !is AlbumBatchRead.Loading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryPinkContainer,
                        contentColor = SurfaceObsidian,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("metadata_batch_read_selected"),
                    onClick = {
                        when (val plan = MetadataBatchSelectionPolicy.prepare(candidates, selected)) {
                            is MetadataSelectionPlan.Rejected -> issue = selectionIssueLabel(plan.issue)
                            is MetadataSelectionPlan.Ready -> {
                                issue = null
                                requestCounter++
                                load = AlbumBatchRead.Loading(0, plan.indices.size)
                                request = AlbumBatchRequest(requestCounter, plan.indices.map(tracks::get))
                                showPreview = false
                            }
                        }
                    },
                ) { Text("Leer etiquetas seleccionadas") }
                if (selected.isEmpty()) {
                    Text("Selecciona al menos una pista para leer su evidencia.",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (val state = load) {
                    AlbumBatchRead.Idle -> Unit
                    is AlbumBatchRead.Loading -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text("Comprobando ${state.completed} de ${state.total} archivos")
                        }
                        TextButton(onClick = { invalidateSelection() }) { Text("Cancelar lectura") }
                    }
                    is AlbumBatchRead.Unavailable -> Text(
                        "Se detuvo en la pista ${state.position + 1}: " +
                            evidenceUnavailableLabel(state.reason) +
                            " Ninguna pista se ha modificado.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    is AlbumBatchRead.Ready -> Text(
                        "${state.snapshots.size} archivos leídos con evidencia vigente. " +
                            "Los campos no confirmados aparecen como no verificables.")
                }
            }
            if (ready != null) {
                item(key = "batch_shared_fields") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.testTag("metadata_batch_shared_fields"),
                    ) {
                        Text("CAMPOS COMPARTIDOS",
                            style = MichiTypography.screenEyebrow, color = PrimaryPinkContainer)
                        Text("Edita un valor y se propondrá en todas las pistas seleccionadas. " +
                            "La revisión sigue siendo por pista.",
                            style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                            modifier = Modifier.padding(bottom = 6.dp))
                        BatchSharedFieldOrder.forEach { field ->
                            val value = MetadataEditPreview.selectionValue(ready.snapshots, field)
                            BatchSharedFieldRow(
                                field = field,
                                selection = value,
                                edited = batchEdits[field],
                                onEdit = { values ->
                                    batchEdits = if (values.isEmpty()) batchEdits - field
                                    else batchEdits + (field to values)
                                    showPreview = false
                                },
                            )
                        }
                    }
                }
                item(key = "batch_diagnostics_action") {
                    TextButton(onClick = { showDiagnostics = !showDiagnostics },
                        modifier = Modifier.testTag("metadata_batch_diagnostics")) {
                        Text(if (showDiagnostics) "Ocultar diagnóstico" else "Diagnosticar etiquetas seleccionadas")
                    }
                }
                if (showDiagnostics) {
                    val findings = MetadataLibraryDiagnostics.inspect(ready.snapshots)
                    item(key = "batch_diagnostics_summary") {
                        Text("${findings.size} observaciones locales. Ningún campo se modifica automáticamente.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    itemsIndexed(findings.take(30), key = { index, _ -> "diagnostic_$index" }) { _, finding ->
                        Text("Pista seleccionada · ${finding.kind.name.replace('_',' ')}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (findings.size > 30) item(key = "batch_diag_more") {
                        Text("Se muestran 30 observaciones de ${findings.size}.")
                    }
                }
                item(key = "csv_import_preview") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        TextButton(onClick = {
                            csvOpen = !csvOpen; csvReview = null
                        }, modifier = Modifier.testTag("metadata_batch_csv_import_toggle")) {
                            Text(if (csvOpen) "Ocultar importación CSV" else "Importar CSV · revisión")
                        }
                        if (csvOpen) {
                            TextButton(onClick = {
                                csvFileNotice = null
                                importDocument.launch(arrayOf("text/csv", "text/plain", "application/octet-stream"))
                            }, modifier = Modifier.testTag("metadata_batch_open_csv_saf")) {
                                Text("Elegir archivo CSV desde Android")
                            }
                            csvFileNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("El CSV usa el orden exacto de las pistas seleccionadas: " +
                                "pista,campo,accion,indice,valor. No incluye rutas ni identificadores. " +
                                "Las operaciones omitidas conservan su valor. Solo se genera una propuesta.",
                                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            OutlinedTextField(
                                value = pastedCsv,
                                onValueChange = { value ->
                                    if (value.length <= 128 * 1024) {
                                        pastedCsv = value; csvReview = null
                                    }
                                },
                                label = { Text("Pegar contenido CSV") },
                                modifier = Modifier.fillMaxWidth().testTag("metadata_batch_csv_input"),
                                minLines = 3, maxLines = 8,
                            )
                            Button(
                                enabled = pastedCsv.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().testTag("metadata_batch_csv_review"),
                                onClick = {
                                    csvReview = MetadataCsvBulkImport.review(ready.snapshots,pastedCsv)
                                },
                            ) { Text("Validar y previsualizar CSV") }
                            when (val checked = csvReview) {
                                is MetadataCsvBulkImport.Result.Blocked -> Text(
                                    "CSV bloqueado: ${checked.reason}" +
                                        (checked.row?.let { " · fila $it" } ?: "") +
                                        (checked.track?.let { " · pista $it" } ?: ""),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                is MetadataCsvBulkImport.Result.Ready -> {
                                    Text("${checked.edits} pistas con propuestas · " +
                                        "${checked.unchanged} sin cambios",
                                        style = MaterialTheme.typography.titleMedium)
                                    checked.reviews.take(12).forEachIndexed { index, preview ->
                                        if (preview is MetadataReview.Ready) {
                                            Text("Pista ${index + 1}: " + preview.diffs.joinToString("; ") {
                                                "${it.field.name} [${it.before.joinToString(" · ")}] → " +
                                                    "[${it.after.joinToString(" · ")}]"
                                            }, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (checked.reviews.size > 12) Text(
                                        "Vista abreviada: primeras 12 pistas de ${checked.reviews.size}",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("Solo previsualización. No se guardará ninguna etiqueta.",
                                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                                null -> Unit
                            }
                        }
                    }
                }
                item(key = "fields") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("03 · PREPARAR OPERACIONES", style = MichiTypography.screenEyebrowTechnical)
                        Text("Aplicar cambios a la selección", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { fieldMenu = true; advancedTouched = true }) {
                            Text("Campo: ${metadataFieldLabel(chosenField)} ▾")
                        }
                        DropdownMenu(expanded = fieldMenu, onDismissRequest = { fieldMenu = false }) {
                            MetadataField.entries.forEach { field ->
                                DropdownMenuItem(text = { Text(metadataFieldLabel(field)) },
                                    onClick = {
                                        chosenField = field
                                        proposedValues = listOf("")
                                        mode = AlbumBatchMode.REPLACE
                                        confirmClear = false
                                        confirmRemoveExact = false
                                        selectedRemoveIndex = 0
                                        removeValueMenu = false
                                        fieldMenu = false
                                        showPreview = false
                                        resultPage = 0
                                    })
                            }
                        }
                        val stateLabel = when (selectionValue) {
                            is MetadataEditPreview.SelectionValue.Same ->
                                "Valor uniforme: ${selectionValue.values.joinToString(" · ")}"
                            MetadataEditPreview.SelectionValue.Mixed -> "Valores diferentes entre pistas"
                            else -> "Campo sin verificación completa: operación bloqueada"
                        }
                        Text(stateLabel, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { modeMenu = true; advancedTouched = true }) { Text("Acción: ${mode.label} ▾") }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            AlbumBatchMode.entries.forEach { candidate ->
                                DropdownMenuItem(text = { Text(candidate.label) }, onClick = {
                                    mode = candidate; modeMenu = false; confirmClear = false
                                    confirmRemoveExact = false; selectedRemoveIndex = 0
                                    removeValueMenu = false
                                    showPreview = false; resultPage = 0
                                })
                            }
                        }
                        when (mode) {
                            AlbumBatchMode.COPY -> {
                                Text("Origen y destino deben tener evidencia verificada; no se copiará " +
                                    "ningún valor desconocido ni se escribirán archivos.",
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { copyMenu = true }) {
                                    Text("Copiar desde: ${metadataFieldLabel(copySource)} ▾")
                                }
                                DropdownMenu(expanded = copyMenu,
                                    onDismissRequest = { copyMenu = false }) {
                                    MetadataField.entries.forEach { source ->
                                        DropdownMenuItem(
                                            text = { Text(metadataFieldLabel(source)) },
                                            onClick = {
                                                copySource = source
                                                copyMenu = false
                                                showPreview = false
                                            },
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Checkbox(checked = copyOnlyMissing, onCheckedChange = {
                                        copyOnlyMissing = it; showPreview = false
                                    })
                                    Text("Solo si el destino está vacío")
                                }
                            }
                            AlbumBatchMode.REPLACE, AlbumBatchMode.APPEND -> {
                                Text(if (mode == AlbumBatchMode.APPEND)
                                    "Los nuevos valores se añadirán al final, sin eliminar los existentes. " +
                                        "Solo campos multivalor."
                                else "Los valores indicados sustituirán todas las entradas de ese campo.",
                                    style = MaterialTheme.typography.bodySmall)
                                proposedValues.forEachIndexed { index, value ->
                                    OutlinedTextField(value = value, onValueChange = { changed ->
                                        proposedValues = proposedValues.toMutableList().apply { this[index] = changed }
                                        showPreview = false
                                    }, label = { Text("Nuevo valor ${index+1}") },
                                        modifier = Modifier.fillMaxWidth())
                                    if (proposedValues.size > 1) TextButton(onClick = {
                                        proposedValues = proposedValues.filterIndexed { i, _ -> i != index }
                                        showPreview = false
                                    }) { Text("Quitar valor") }
                                }
                                if (chosenField.multiple && proposedValues.size < 32) TextButton(onClick = {
                                    proposedValues = proposedValues + ""
                                    showPreview = false
                                }) { Text("Añadir otro valor") }
                            }
                            AlbumBatchMode.CLEAR -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Checkbox(checked = confirmClear, onCheckedChange = { confirmClear = it })
                                    Text("Confirmo que deseo previsualizar la eliminación de este campo")
                                }
                            }
                            AlbumBatchMode.REMOVE_EXACT -> {
                                val common = selectionValue as? MetadataEditPreview.SelectionValue.Same
                                Text("Solo se puede quitar una entrada cuando todas las pistas tienen la " +
                                    "misma lista verificada. Los valores duplicados se distinguen por posición.",
                                    style = MaterialTheme.typography.bodySmall)
                                if (!chosenField.multiple || common == null || common.values.isEmpty()) {
                                    Text("La lista es mixta, no está verificada o el campo no es multivalor.",
                                        color = MaterialTheme.colorScheme.error)
                                } else {
                                    TextButton(onClick = { removeValueMenu = true }) {
                                        Text("Entrada: " + (common.values.getOrNull(selectedRemoveIndex)?.let {
                                            "${selectedRemoveIndex + 1}. $it" } ?: "Selecciona una entrada") + " ▾")
                                    }
                                    DropdownMenu(expanded = removeValueMenu,
                                        onDismissRequest = { removeValueMenu = false }) {
                                        common.values.forEachIndexed { index, value ->
                                            DropdownMenuItem(text = { Text("${index + 1}. $value") },
                                                onClick = {
                                                    selectedRemoveIndex = index
                                                    confirmRemoveExact = false
                                                    removeValueMenu = false
                                                    showPreview = false
                                                })
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Checkbox(checked = confirmRemoveExact,
                                            onCheckedChange = { confirmRemoveExact = it; showPreview = false })
                                        Text("Confirmo quitar exactamente esa entrada en cada pista")
                                    }
                                }
                            }
                            AlbumBatchMode.FIND_REPLACE -> {
                                OutlinedTextField(findText, onValueChange = { findText = it; showPreview = false },
                                    label = { Text("Buscar") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(replaceText, onValueChange = { replaceText = it; showPreview = false },
                                    label = { Text("Sustituir por") }, modifier = Modifier.fillMaxWidth())
                            }
                            AlbumBatchMode.TRACK_SEQUENCE -> {
                                OutlinedTextField(firstTrack, onValueChange = { firstTrack = it; showPreview = false },
                                    label = { Text("Número inicial") }, modifier = Modifier.fillMaxWidth())
                                Text("El orden es el mostrado en el álbum. No se modifica el disco.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            else -> Unit
                        }
                        if (queuedActions.isNotEmpty()) {
                            Text("Secuencia: ${queuedActions.size} paso(s) listos para revisar",
                                color = PrimaryPinkContainer,
                                style = MaterialTheme.typography.labelMedium)
                            queuedActions.forEachIndexed { index, step ->
                                Text("${index + 1}. ${step.javaClass.simpleName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary)
                            }
                            Row {
                                TextButton(onClick = {
                                    queuedActions = queuedActions.dropLast(1); showPreview = false
                                }) { Text("Deshacer último paso") }
                                TextButton(onClick = { queuedActions = emptyList(); showPreview = false }) {
                                    Text("Vaciar secuencia")
                                }
                            }
                        }
                        TextButton(
                            enabled = queuedActions.size < 32 && action != null &&
                                candidateReview is MetadataBatchResult.Ready,
                            modifier = Modifier.fillMaxWidth().testTag("metadata_batch_queue_action"),
                            onClick = {
                                action?.let { queuedActions = queuedActions + it }
                                showPreview = false
                            },
                        ) { Text("+ Añadir esta operación a la secuencia") }
                        Button(enabled = allowedPreview, onClick = { showPreview = true },
                            modifier = Modifier.fillMaxWidth().testTag("metadata_batch_preview_action")) {
                            Text(if (queuedActions.isNotEmpty()) "Revisar secuencia de ${queuedActions.size} pasos"
                                else "Previsualizar diferencias")
                        }
                    }
                }
                if (showPreview) {
                    when (val checked = review) {
                        is MetadataBatchResult.Blocked -> item(key = "blocked") {
                            Text("Operación bloqueada: ${checked.reason}" +
                                checked.validation?.let { " · $it" }.orEmpty(),
                                color = MaterialTheme.colorScheme.error)
                        }
                        is MetadataBatchResult.Ready -> {
                            val csvReport = MetadataCsvPreview.create(
                                checked.entries,
                                listOf(MetadataField.TITLE, MetadataField.ARTIST,
                                    MetadataField.ALBUM, MetadataField.GENRE),
                                ready.snapshots)
                            val reversibleCsv = MetadataCsvBulkExport.fromReviews(
                                checked.entries, ready.snapshots)
                            item(key = "csv_report") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Informe de diferencias para revisar", style = MaterialTheme.typography.titleSmall)
                                    Text("Contiene títulos y otros datos de tu música. Solo se copia con tu autorización.",
                                        style = MaterialTheme.typography.bodySmall)
                                    TextButton(
                                        enabled = csvReport is MetadataCsvPreview.Result.Ready,
                                        modifier = Modifier.testTag("metadata_batch_copy_csv"),
                                        onClick = {
                                            if (csvReport is MetadataCsvPreview.Result.Ready)
                                                clipboard.setText(AnnotatedString(csvReport.csv))
                                        },
                                    ) { Text("Copiar informe CSV") }
                                    Text("CSV reversible seguro: la columna valor_b64 codifica los textos en Base64 UTF-8 " +
                                        "para impedir fórmulas al abrir una planilla. " +
                                        "No contiene rutas, IDs, permisos ni archivos de audio.",
                                        style = MaterialTheme.typography.bodySmall)
                                    TextButton(
                                        enabled = reversibleCsv is MetadataCsvBulkExport.Result.Ready,
                                        modifier = Modifier.testTag("metadata_batch_copy_reimport_csv"),
                                        onClick = {
                                            if (reversibleCsv is MetadataCsvBulkExport.Result.Ready)
                                                clipboard.setText(AnnotatedString(reversibleCsv.csv))
                                        },
                                    ) { Text("Copiar CSV para corregir y reimportar") }
                                    TextButton(
                                        enabled = reversibleCsv is MetadataCsvBulkExport.Result.Ready,
                                        modifier = Modifier.testTag("metadata_batch_export_csv_saf"),
                                        onClick = {
                                            if (reversibleCsv is MetadataCsvBulkExport.Result.Ready) {
                                                exportPayload = reversibleCsv.csv
                                                csvFileNotice = null
                                                exportDocument.launch("michi-metadata-propuestas.csv")
                                            }
                                        },
                                    ) { Text("Guardar CSV de propuestas como documento") }
                                    csvFileNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (reversibleCsv is MetadataCsvBulkExport.Result.Blocked)
                                        Text("CSV editable no disponible: ${reversibleCsv.reason}",
                                            color = MaterialTheme.colorScheme.error)
                                }
                            }
                            item(key = "summary") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HorizontalDivider()
                                    Text("${checked.modified} cambiarían · ${checked.unchanged} sin cambios",
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("Previsualización únicamente. La escritura en archivos sigue deshabilitada.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            // Single-journal private preparation: at most ONE
                            // explicit target per tap, never a batch source write.
                            val firstChanged = checked.entries.indices.firstOrNull { index ->
                                checked.entries[index] is MetadataReview.Ready &&
                                    (batchRows == null || batchRows?.getOrNull(index)?.state ==
                                        MetadataBatchRunPolicy.State.NOT_STARTED)
                            } ?: -1
                            item(key = "private_batch_one") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Preparación privada · una pista",style=MaterialTheme.typography.titleSmall)
                                    Text("Crea una copia privada del original y del candidato de una sola pista. " +
                                        "El journal V1 solo permite una preparación pendiente; la música original no se modifica.",
                                        style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Checkbox(checked=privateStageConfirmed,
                                            enabled=!privateStageBusy,
                                            onCheckedChange={ privateStageConfirmed=it })
                                        Text("Confirmo preparar el respaldo privado de la primera pista modificada",
                                            modifier=Modifier.weight(1f),
                                            style=MaterialTheme.typography.bodySmall)
                                    }
                                    Button(enabled=privateStageConfirmed && !privateStageBusy &&
                                            firstChanged >= 0 && preparedReviewDigest != null &&
                                            loadedTracks.size == ready.snapshots.size,
                                        modifier=Modifier.fillMaxWidth()
                                            .testTag("metadata_batch_prepare_one_private"),
                                        onClick={
                                            val requestId=request?.id
                                            val wanted=firstChanged
                                            val stagedTrack=loadedTracks.getOrNull(wanted)
                                            if (requestId != null && stagedTrack != null) {
                                                privateStageBusy=true
                                                privateStageNotice=null
                                                privateStageConfirmed=false
                                                scope.launch {
                                                    fun currentOwner():Boolean = dialogAlive.get() &&
                                                        request?.id == requestId &&
                                                        scopeStore.activeScope.value == capturedScope &&
                                                        rootAuthority.revision.value == capturedRevision &&
                                                        stagedTrack.source == TrackSource.LOCAL &&
                                                        stagedTrack.filepath == ready.snapshots[wanted].target.uri
                                                    fun playbackSafe():Boolean {
                                                        val state=session.sessionState.value
                                                        return state.currentTrack?.filepath != stagedTrack.filepath &&
                                                            !state.isRemoteActive &&
                                                            state.handoffState == HandoffState.IDLE &&
                                                            state.activeEndpoint.isLocal
                                                    }
                                                    try {
                                                        val prepared=privateBatchCoordinator.prepareOne(
                                                            privateBatchId,ready.snapshots,checked.entries,
                                                            loadedTracks.map { it.size },wanted,true,
                                                            ::currentOwner,::playbackSafe)
                                                        if (dialogAlive.get() && request?.id==requestId)
                                                            privateStageNotice=when(prepared) {
                                                                is MetadataBatchSequentialPreparationCoordinator.Result.PreparedPrivateOnly -> {
                                                                    batchRows = prepared.rows
                                                                    privateStageTransactionId = prepared.transactionId
                                                                    privateStageIndex = wanted
                                                                    "Respaldo privado preparado para una pista (${prepared.transactionId}). " +
                                                                        "Inspecciona y descarta el journal antes de preparar otra. Sin guardado físico."
                                                                }
                                                                is MetadataBatchSequentialPreparationCoordinator.Result.RefusedAndRecorded -> {
                                                                    batchRows = prepared.rows
                                                                    "Preparación rechazada: ${prepared.reason}. Sin cambios en música."
                                                                }
                                                                is MetadataBatchSequentialPreparationCoordinator.Result.Blocked ->
                                                                    "Preparación bloqueada: ${prepared.reason}. Revisa recuperación existente."
                                                                is MetadataBatchSequentialPreparationCoordinator.Result.Current ->
                                                                    "Estado consultado. No se ha escrito ninguna canción."
                                                            }
                                                    } catch (cancel: CancellationException) { throw cancel }
                                                      catch (_: Exception) {
                                                        if (dialogAlive.get()) privateStageNotice=
                                                            "No se pudo confirmar la preparación: conserva y revisa el journal."
                                                    } finally {
                                                        if (dialogAlive.get()) privateStageBusy=false
                                                    }
                                                }
                                            }
                                        }) {
                                        Text(if (privateStageBusy) "Preparando respaldo privado…"
                                            else "Preparar una pista · no guarda audio")
                                    }
                                    privateStageNotice?.let { Text(it,style=MaterialTheme.typography.bodySmall,
                                        color=TextSecondary) }
                                    val preparedId = privateStageTransactionId
                                    if (preparedId != null && privateStageIndex >= 0) {
                                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            Checkbox(checked=confirmPrivateDiscard,
                                                enabled=!privateStageBusy,
                                                onCheckedChange={ confirmPrivateDiscard=it })
                                            Text("Confirmo descartar solo el respaldo privado revisado",
                                                modifier=Modifier.weight(1f),
                                                style=MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(
                                            enabled=confirmPrivateDiscard && !privateStageBusy,
                                            modifier=Modifier.testTag("metadata_batch_private_discard_one"),
                                            onClick={
                                                val requestId=request?.id
                                                val discardIndex=privateStageIndex
                                                if (requestId != null) {
                                                    privateStageBusy=true
                                                    confirmPrivateDiscard=false
                                                    scope.launch {
                                                        try {
                                                            val result=privateBatchCoordinator.discardPrivateAndAdvance(
                                                                privateBatchId,ready.snapshots,checked.entries,
                                                                discardIndex,preparedId,true,
                                                                ownerStillCurrent={
                                                                    dialogAlive.get() && request?.id==requestId &&
                                                                        scopeStore.activeScope.value==capturedScope &&
                                                                        rootAuthority.revision.value==capturedRevision
                                                                })
                                                            if (dialogAlive.get() && request?.id==requestId)
                                                                when(result) {
                                                                    is MetadataBatchSequentialPreparationCoordinator.Result.Current -> {
                                                                        batchRows=result.rows
                                                                        privateStageTransactionId=null
                                                                        privateStageIndex=-1
                                                                        privateStageNotice="Respaldo privado descartado con confirmación. " +
                                                                            "Puedes preparar la siguiente pista; ningún audio fue escrito."
                                                                    }
                                                                    is MetadataBatchSequentialPreparationCoordinator.Result.Blocked ->
                                                                        privateStageNotice="Descarte bloqueado: ${result.reason}. Conserva el journal."
                                                                    else -> privateStageNotice="No se pudo confirmar el descarte; inspecciona el journal."
                                                                }
                                                        } catch (cancel: CancellationException) { throw cancel }
                                                          catch (_: Exception) {
                                                            if (dialogAlive.get()) privateStageNotice=
                                                                "Error de descarte. No presupongas que el journal está vacío."
                                                        } finally {
                                                            if (dialogAlive.get()) privateStageBusy=false
                                                        }
                                                    }
                                                }
                                            }) { Text("Descartar respaldo privado y continuar") }
                                    }
                                    batchRows?.let { rows ->
                                        Text("Pistas con preparación privada ya descartada: " +
                                            rows.count { it.state==MetadataBatchRunPolicy.State.PRIVATE_STAGE_DISCARDED },
                                            style=MaterialTheme.typography.labelSmall,color=TextSecondary)
                                    }
                                }
                            }
                            val pageSize = 12
                            val offset = resultPage * pageSize
                            val paged = checked.entries.drop(offset).take(pageSize)
                            itemsIndexed(paged, key = { idx, _ -> "batch_result_${offset+idx}" }) { i, entry ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(loadedTracks.getOrNull(offset+i)?.title ?: "Pista ${offset+i+1}",
                                        style = MaterialTheme.typography.labelLarge)
                                    when (entry) {
                                        is MetadataReview.Ready -> entry.diffs.forEach { diff ->
                                            Text("${diff.field.name}: " +
                                                "[${diff.before.joinToString(" · ")}] → " +
                                                "[${diff.after.joinToString(" · ")}]",
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        MetadataReview.Unchanged -> Text("Sin cambios")
                                        is MetadataReview.Blocked -> Text("Bloqueada: ${entry.reason}")
                                    }
                                }
                            }
                            item(key = "pages") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(enabled = resultPage > 0, onClick = { resultPage-- }) {
                                        Text("Anterior")
                                    }
                                    Text("Página ${resultPage+1} de " +
                                        "${(checked.entries.size + pageSize - 1)/pageSize}")
                                    TextButton(enabled = offset + pageSize < checked.entries.size,
                                        onClick = { resultPage++ }) { Text("Siguiente") }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
        if (!imeVisible) {
            HorizontalDivider(color = AeroHairline)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = MichiSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sin escribir en el archivo",
                    style = MichiTypography.microLabel,
                    color = TextSecondary,
                    modifier = Modifier.background(AeroGraphite, MichiShapes.lg)
                        .padding(horizontal = MichiSpacing.md, vertical = MichiSpacing.sm),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Cerrar editor") }
            }
            Text(
                "Todavía no se pueden guardar cambios en el archivo original.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = MichiSpacing.md),
            )
        }
    }
}

private val BatchSharedFieldOrder = listOf(
    MetadataField.TITLE,
    MetadataField.ARTIST,
    MetadataField.ALBUM,
    MetadataField.ALBUM_ARTIST,
    MetadataField.GENRE,
    MetadataField.RELEASE_DATE,
    MetadataField.TRACK_NUMBER,
    MetadataField.TRACK_TOTAL,
    MetadataField.DISC_NUMBER,
    MetadataField.DISC_TOTAL,
    MetadataField.COMMENT,
)

/** Direct shared-field editing on top of the same per-track review pipeline. */
@Composable
internal fun BatchSharedFieldRow(
    field: MetadataField,
    selection: MetadataEditPreview.SelectionValue,
    edited: List<String>?,
    onEdit: (List<String>) -> Unit,
) {
    val unverified = selection is MetadataEditPreview.SelectionValue.Unverified
    val statusLabel = when (selection) {
        is MetadataEditPreview.SelectionValue.Unverified -> "NO VERIFICABLE"
        is MetadataEditPreview.SelectionValue.Mixed -> "MIXTO"
        is MetadataEditPreview.SelectionValue.Same ->
            if (selection.values.isEmpty()) "AUSENTE" else "COMÚN"
    }
    val statusColor = when {
        unverified -> TextMuted
        edited != null -> PrimaryPinkContainer
        selection is MetadataEditPreview.SelectionValue.Mixed -> Color(0xFFFFC46B)
        selection is MetadataEditPreview.SelectionValue.Same && selection.values.isEmpty() -> TextSecondary
        else -> TertiaryCyan
    }
    val values = edited ?: when (selection) {
        is MetadataEditPreview.SelectionValue.Same -> selection.values
        else -> listOf("")
    }
    val rows = if (field.multiple) values else listOf(values.firstOrNull().orEmpty())
    var focusedRows by remember(field) { mutableStateOf<Set<Int>>(emptySet()) }
    MetadataFieldShell(
        field = field,
        statusLabel = statusLabel,
        statusColor = statusColor,
        focused = focusedRows.isNotEmpty(),
        modified = edited != null,
        errorText = null,
        showStatusText = true,
        modifier = Modifier.testTag("metadata_batch_shared_${field.name.lowercase()}"),
    ) {
        if (unverified) {
            Text(
                "No todas las pistas de la selección tienen evidencia verificada para este campo.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else {
            rows.forEachIndexed { index, value ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.xs),
                ) {
                    MetadataInlineValueField(
                        field = field,
                        value = value,
                        index = index,
                        tag = "metadata_batch_shared_input_${field.name.lowercase()}_$index",
                        modifier = Modifier.weight(1f),
                        placeholder = if (selection is MetadataEditPreview.SelectionValue.Mixed) {
                            "Valores diferentes · toca para proponer"
                        } else {
                            if (field.multiple) "Toca para añadir un valor"
                            else "Sin etiqueta · toca para escribir"
                        },
                        onFocusChanged = { focused ->
                            focusedRows = if (focused) focusedRows + index else focusedRows - index
                        },
                        onValueChange = { changed ->
                            onEdit(rows.toMutableList().also { it[index] = changed })
                        },
                    )
                    if (field.multiple) {
                        var valueMenuOpen by remember(field, index) { mutableStateOf(false) }
                        IconButton(
                            onClick = { valueMenuOpen = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription =
                                    "Acciones del valor ${index + 1} de ${metadataFieldLabel(field)}",
                                tint = TextSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded = valueMenuOpen,
                            onDismissRequest = { valueMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Quitar este valor") },
                                enabled = rows.size > 1,
                                onClick = {
                                    valueMenuOpen = false
                                    onEdit(rows.filterIndexed { i, _ -> i != index })
                                },
                            )
                        }
                    }
                }
            }
            if (field.multiple && rows.size < 32) {
                TextButton(
                    onClick = { onEdit(rows + "") },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("+ Añadir", color = PrimaryPinkContainer) }
            }
            if (edited != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Se propondrá en todas las pistas",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { onEdit(emptyList()) },
                        modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                        Text("Quitar propuesta", color = TextSecondary)
                    }
                }
            }
        }
    }
}

private fun selectionIssueLabel(issue: MetadataSelectionIssue): String = when (issue) {
    MetadataSelectionIssue.EMPTY_SELECTION -> "Selecciona al menos una pista."
    MetadataSelectionIssue.TOO_MANY_TRACKS -> "Máximo 128 pistas por operación."
    MetadataSelectionIssue.INVALID_INDEX -> "La selección ya no coincide con el álbum actual."
    MetadataSelectionIssue.REMOTE_TRACK -> "La selección contiene música remota."
    MetadataSelectionIssue.INVALID_URI -> "La selección incluye un archivo sin URI Android válido."
    MetadataSelectionIssue.INVALID_IDENTITY -> "Falta identificación de una pista."
    MetadataSelectionIssue.MISSING_SIZE -> "No se conoce el tamaño de algún archivo."
    MetadataSelectionIssue.MIXED_OWNERS -> "No es posible mezclar bibliotecas en una operación."
    MetadataSelectionIssue.DUPLICATE_TRACK -> "La selección contiene una identidad de pista repetida."
    MetadataSelectionIssue.DUPLICATE_URI -> "La selección contiene un archivo repetido."
}

@Composable
private fun BatchHeroArtwork(track: Track?) {
    val coverId = track?.coverId?.trim().orEmpty()
    val model: Any? = when {
        coverId.isEmpty() -> null
        coverId.startsWith("content://") || coverId.startsWith("file:") ||
            coverId.startsWith("http://") || coverId.startsWith("https://") -> coverId
        else -> "content://media/external/audio/albumart/$coverId"
    }
    if (model == null) {
        BatchHeroFallback()
    } else {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = "Carátula del álbum",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(62.dp).clip(MichiShapes.sm),
            loading = { BatchHeroFallback() },
            error = { BatchHeroFallback() },
        )
    }
}

@Composable
private fun BatchHeroFallback() {
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
