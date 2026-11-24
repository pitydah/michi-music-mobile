package org.michimusic.mobile.ui.components

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.core.metadata.MetadataDescribedTextWorkbench
import org.michimusic.core.metadata.MetadataBlockReason
import org.michimusic.core.metadata.MetadataEditorDraftSession
import org.michimusic.core.metadata.MetadataEditorSection
import org.michimusic.mobile.ui.metadata.MetadataLibraryRefreshSignal
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.fieldReviewSummary
import org.michimusic.data.metadata.MetadataSaveService
import org.michimusic.data.metadata.MetadataPrivateBundleCoordinator
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.components.MetadataArtLyricsOnDemandReader
import kotlinx.coroutines.launch

/**
 * ME-10. One workbench, one modal, no writer. Consumes verified embedded evidence
 * and keeps temporarily invalid IME input separate from explicit Clear/Replace.
 * Dynamic color and contrast remain controlled by Michi's existing theme.
 */
@Composable
internal fun MichiMetadataEditorPreviewContent(
    snapshot: MetadataSnapshot,
    onDismiss: () -> Unit,
    onDirtyChanged: (Boolean) -> Unit = {},
    recoveryPanel: (@Composable () -> Unit)? = null,
    privateBundleCoordinator: MetadataPrivateBundleCoordinator? = null,
    expectedFileBytes: Long = 0L,
    stillOwnsPrivateSource: () -> Boolean = { false },
    playbackDescriptorClosed: () -> Boolean = { false },
    currentSessionAllowsPreparation: Boolean = false,
    initialSection: MetadataEditorSection = MetadataEditorSection.BASICS,
    focusGenre: Boolean = false,
    partial: Boolean = false,
    artworkModel: Any? = null,
    pilotWriter: MetadataSaveService? = null,
    indexSourceId: String = "",
    pilotEligibilityOverride: MetadataSaveService.Eligibility? = null,
) {
    val target = snapshot.target
    val narrowTopBar = LocalConfiguration.current.screenWidthDp < 360 ||
        (LocalDensity.current.fontScale > 1.5f &&
            LocalConfiguration.current.screenWidthDp < 480)
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    val outline = if (highContrast) MaterialTheme.colorScheme.outline else AeroHairline
    var draft by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(MetadataEditorDraftSession.start(snapshot))
    }
    var section by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(if (focusGenre) MetadataEditorSection.BASICS else initialSection)
    }
    var showMusicBrainz by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var showSectionMenu by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var confirmExit by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var confirmRevert by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var showStatusInfo by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var showSaveDetail by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var pilotEligibility by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf<MetadataSaveService.Eligibility?>(null)
    }
    val pilotEligible = pilotEligibility is MetadataSaveService.Eligibility.Eligible
    val recoveryPending = pilotEligibility is MetadataSaveService.Eligibility.RecoveryPending
    var saving by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var saveMessage by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf<String?>(null)
    }
    var pendingReview by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf<MetadataReview.Ready?>(null)
    }
    var genreFirst by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(focusGenre)
    }
    var artLyrics by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(MetadataArtLyricsUiState())
    }
    // Local scratch values for fields the file itself cannot verify. They never enter
    // the certified draft, the effective count or any save path.
    var provisional by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf<Map<MetadataField, List<String>>>(emptyMap())
    }
    var extraFields by remember(section, target.evidenceToken) {
        mutableStateOf<Set<MetadataField>>(emptySet())
    }
    var fieldPickerVisible by remember(section, target.evidenceToken) {
        mutableStateOf(false)
    }
    var fieldPickerQuery by remember(section, target.evidenceToken) {
        mutableStateOf("")
    }
    var focusedExtraField by remember(section, target.evidenceToken) {
        mutableStateOf<MetadataField?>(null)
    }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun inspectArtworkLyrics() {
        if (artLyrics.load is MetadataArtLyricsUiState.Load.Loading ||
            artLyrics.load is MetadataArtLyricsUiState.Load.Ready) return
        artLyrics = artLyrics.copy(load = MetadataArtLyricsUiState.Load.Loading)
        scope.launch {
            val result = try {
                MetadataArtLyricsOnDemandReader.inspect(context.contentResolver, target.uri)
            } catch (_: Exception) {
                MetadataArtLyricsOnDemandReader.Outcome.Blocked("No se pudo inspeccionar")
            }
            artLyrics = when (result) {
                is MetadataArtLyricsOnDemandReader.Outcome.Ready ->
                    artLyrics.copy(load = MetadataArtLyricsUiState.Load.Ready(result.snapshot))
                is MetadataArtLyricsOnDemandReader.Outcome.Blocked ->
                    artLyrics.copy(load = MetadataArtLyricsUiState.Load.Blocked(result.label))
            }
        }
    }
    LaunchedEffect(section) {
        if (section == MetadataEditorSection.ART_LYRICS &&
            artLyrics.load is MetadataArtLyricsUiState.Load.NotRequested) {
            inspectArtworkLyrics()
        }
    }
    val fieldsState = rememberLazyListState()
    LaunchedEffect(section) { fieldsState.scrollToItem(0) }
    val compactHeader by remember {
        derivedStateOf {
            fieldsState.firstVisibleItemIndex > 0 || fieldsState.firstVisibleItemScrollOffset > 32
        }
    }
    val certifiedDirty = draft.isDirty || artLyrics.isDirty
    val provisionalCount = provisional.values.count { values -> values.any(String::isNotBlank) }
    val fieldSummary = fieldReviewSummary(draft)
    val textEffectiveCount = (artLyrics.textResult
        as? MetadataDescribedTextWorkbench.Result.Ready)?.differences?.size ?: 0
    val effectiveEditCount = fieldSummary.effectiveFields + textEffectiveCount +
        if (artLyrics.artwork != null || artLyrics.artworkEdit != null) 1 else 0
    val canRevert = certifiedDirty || effectiveEditCount > 0 ||
        provisionalCount > 0 || artLyrics.isDirty
    val saveableReview = draft.review as? MetadataReview.Ready
    val pilotArtworkBlocked = artLyrics.artwork != null || artLyrics.artworkEdit != null
    val canSave = pilotEligible && !saving && saveableReview != null &&
        fieldSummary.effectiveFields > 0 && !pilotArtworkBlocked &&
        textEffectiveCount == 0 && provisionalCount == 0 &&
        stillOwnsPrivateSource() && playbackDescriptorClosed()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun requestExit() {
        if (certifiedDirty || provisionalCount > 0) confirmExit = true else onDismiss()
    }
    fun revertChanges() {
        draft = MetadataEditorDraftSession.start(snapshot)
        provisional = emptyMap()
        artLyrics = artLyrics.reset()
        genreFirst = false
        saveMessage = null
        onDirtyChanged(false)
        confirmRevert = false
    }
    LaunchedEffect(
        pilotWriter, target.sourceId, target.trackId, target.uri,
        expectedFileBytes, pilotEligibilityOverride,
    ) {
        pilotEligibility = pilotEligibilityOverride ?: pilotWriter?.eligibility(target.uri)
    }
    var retryPilotSave: (MetadataReview.Ready) -> Unit = {}
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val review = pendingReview
        if (result.resultCode == Activity.RESULT_OK && review != null) {
            pendingReview = null
            retryPilotSave(review)
        } else {
            pendingReview = null
            saveMessage = "Permiso de escritura no concedido."
        }
    }
    fun performPilotSave(review: MetadataReview.Ready) {
        val writer = pilotWriter ?: return
        if (saving) return
        if (!stillOwnsPrivateSource() || !playbackDescriptorClosed()) {
            saveMessage = "El archivo está en reproducción o cambió de propietario."
            return
        }
        saving = true
        saveMessage = null
        scope.launch {
            when (val outcome = writer.save(
                sourceId = indexSourceId.ifBlank { target.sourceId },
                trackId = target.trackId,
                uriString = target.uri,
                expectedBytes = expectedFileBytes,
                reviewInput = review,
                ownerCurrent = stillOwnsPrivateSource,
                playbackClosed = playbackDescriptorClosed,
            )) {
                is MetadataSaveService.Outcome.Saved -> {
                    saving = false
                    pendingReview = null
                    draft = MetadataEditorDraftSession.start(outcome.snapshot)
                    artLyrics = MetadataArtLyricsUiState()
                    provisional = emptyMap()
                    saveMessage = if (outcome.indexPublished) "Cambios guardados"
                    else "Archivo verificado; biblioteca pendiente"
                    MetadataLibraryRefreshSignal.request()
                    onDirtyChanged(false)
                }
                is MetadataSaveService.Outcome.ConsentRequired -> {
                    saving = false
                    pendingReview = review
                    consentLauncher.launch(
                        IntentSenderRequest.Builder(outcome.intent.intentSender).build(),
                    )
                }
                is MetadataSaveService.Outcome.Blocked -> {
                    saving = false
                    saveMessage = "No se puede guardar ahora: ${outcome.reason}."
                }
                is MetadataSaveService.Outcome.RecoveryRequired -> {
                    saving = false
                    android.util.Log.d("MichiSave", "recovery=${outcome.reason}")
                    saveMessage = "Recuperación necesaria. No se declara guardado."
                }
                is MetadataSaveService.Outcome.Failed -> {
                    saving = false
                    saveMessage = if (outcome.needsRecovery)
                        "Recuperación necesaria. No se declara guardado."
                    else "No se pudo guardar: ${outcome.reason}."
                }
            }
        }
    }
    retryPilotSave = ::performPilotSave
    BackHandler(enabled = true) { requestExit() }
    LaunchedEffect(certifiedDirty) { onDirtyChanged(certifiedDirty) }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("¿Descartar cambios?") },
            text = { Text("Los cambios de este formulario se perderán.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onDirtyChanged(false)
                    onDismiss()
                }, modifier = Modifier.testTag("metadata_confirm_discard")) {
                    Text("Descartar cambios", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmExit = false },
                    modifier = Modifier.testTag("metadata_keep_editing"),
                ) { Text("Seguir editando") }
            },
        )
    }
    if (confirmRevert) {
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text("¿Revertir los cambios?") },
            text = { Text("El formulario volverá a los valores leídos del archivo. El archivo no se modifica.") },
            confirmButton = {
                TextButton(
                    onClick = ::revertChanges,
                    modifier = Modifier.testTag("metadata_confirm_revert"),
                ) { Text("Revertir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmRevert = false },
                    modifier = Modifier.testTag("metadata_cancel_revert"),
                ) { Text("Cancelar") }
            },
        )
    }
    if (showStatusInfo) {
        val unverified = MetadataField.entries.filterNot { draft.canEdit(it) }
        val lines = buildList {
            add(if (partial) "Algunas etiquetas no tienen evidencia física completa." else
                "Las etiquetas leídas están verificadas.")
            if (unverified.isNotEmpty()) {
                add("${unverified.size} campos no verificados se muestran como propuesta local.")
                unverified.take(8).forEach { add("${metadataFieldLabel(it)}: sin verificar.") }
            } else {
                add("No hay campos pendientes de verificación en esta lectura.")
            }
        }
        MetadataEvidenceDialog(
            title = "Estado de las etiquetas",
            lines = lines,
            tag = "metadata_status_dialog",
            onDismiss = { showStatusInfo = false },
        )
    }

    if (showSaveDetail) {
        AlertDialog(
            onDismissRequest = { showSaveDetail = false },
            title = { Text("Guardar en el archivo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pilotEligible) {
                        Text(
                            "Perfil MP3 de texto: MediaStore + ID3v2.3/2.4 para " +
                                "TITLE, ARTIST, ALBUM y GENRE.",
                            color = TertiaryCyan,
                        )
                        Text(
                            "Guardar solicita permiso exacto para este archivo, crea una copia " +
                                "privada con journal, escribe el original, lo verifica y publica " +
                                "la biblioteca. Si la biblioteca no puede publicarse, el archivo " +
                                "queda verificado y la operación se reintenta al abrirla.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Carátulas, letras, varios valores por campo, FLAC y SAF quedan " +
                                "fuera de este perfil.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (recoveryPending) {
                        Text(
                            "Hay una escritura pendiente de recuperación.",
                            color = PrimaryPinkContainer,
                        )
                        Text(
                            "No se permite iniciar otra edición sobre bytes físicos inciertos. " +
                                "Comprueba la operación pendiente en el panel de recuperación y " +
                                "restaura el original si corresponde.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "Este tipo de archivo todavía no tiene una ruta de escritura certificada.",
                            color = TextSecondary,
                        )
                        Text(
                            "El perfil certificado solo admite archivos MP3 de MediaStore con " +
                                "etiquetas ID3v2.3/2.4. No se ha modificado ningún archivo.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showSaveDetail = false },
                    modifier = Modifier.testTag("metadata_save_detail_dialog"),
                ) { Text("Entendido") }
            },
        )
    }

    val tabs = listOf(
        MetadataEditorSection.BASICS,
        MetadataEditorSection.CREDITS,
        MetadataEditorSection.DISCOGRAPHY,
        MetadataEditorSection.ART_LYRICS,
    )
    val sectionTitle = when (section) {
        MetadataEditorSection.BASICS -> "Información principal"
        MetadataEditorSection.CREDITS -> "Créditos"
        MetadataEditorSection.DISCOGRAPHY -> "Discografía"
        MetadataEditorSection.ART_LYRICS -> "Arte y letras"
        else -> metadataSectionLabel(section)
    }
    fun hasContent(field: MetadataField): Boolean =
        draft.values(field).any(String::isNotBlank) ||
            field in draft.editedFields ||
            provisional[field].orEmpty().any(String::isNotBlank)
    val basicsShown = setOf(
        MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM,
        MetadataField.ALBUM_ARTIST, MetadataField.GENRE, MetadataField.RELEASE_DATE,
        MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
        MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL,
        MetadataField.COMPOSER, MetadataField.BPM,
    )
    val basicsHidden = MetadataEditorSection.BASICS.fields.filterNot { it in basicsShown }
    val creditsCommon = listOf(
        MetadataField.PERFORMER, MetadataField.LYRICIST,
        MetadataField.PRODUCER, MetadataField.ENGINEER,
    )
    val creditsVisible = (creditsCommon + MetadataEditorSection.CREDITS.fields
        .filter { it != MetadataField.COMPOSER && (hasContent(it) || it in extraFields) })
        .distinct()
    val creditsHidden = MetadataEditorSection.CREDITS.fields
        .filter { it != MetadataField.COMPOSER && it !in creditsVisible }
    val discographyCommon = listOf(
        MetadataField.LABEL, MetadataField.CATALOG_NUMBER, MetadataField.ISRC,
        MetadataField.ORIGINAL_RELEASE_DATE, MetadataField.RECORDING_DATE,
        MetadataField.PUBLISHER, MetadataField.BARCODE,
    )
    val discographyVisible = (discographyCommon + MetadataEditorSection.DISCOGRAPHY.fields
        .filter { hasContent(it) || it in extraFields }).distinct()
    val discographyHidden = MetadataEditorSection.DISCOGRAPHY.fields
        .filterNot { it in discographyVisible }
    val pickerFields = when (section) {
        MetadataEditorSection.BASICS -> basicsHidden.filterNot { it in extraFields }
        MetadataEditorSection.CREDITS -> creditsHidden
        MetadataEditorSection.DISCOGRAPHY -> discographyHidden
        else -> emptyList()
    }

    Column(
        Modifier.fillMaxWidth().fillMaxSize().imePadding()
            .testTag("metadata_workbench"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = MichiSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
        ) {
            MetadataEditorTopBar(
                title = "Editar metadatos",
                onBack = { requestExit() },
                backDescription = "Cerrar editor",
                backTestTag = "metadata_editor_exit",
                actions = {
                    if (!narrowTopBar) {
                        IconButton(
                            onClick = {
                                val next = draft.undo()
                                draft = next
                                onDirtyChanged(next.isDirty || artLyrics.isDirty)
                            },
                            enabled = draft.canUndo,
                            modifier = Modifier.size(48.dp).testTag("metadata_editor_undo"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Deshacer",
                                tint = if (draft.canUndo) PureWhite else TextSecondary.copy(alpha = 0.4f))
                        }
                        IconButton(
                            onClick = {
                                val next = draft.redo()
                                draft = next
                                onDirtyChanged(next.isDirty || artLyrics.isDirty)
                            },
                            enabled = draft.canRedo,
                            modifier = Modifier.size(48.dp).testTag("metadata_editor_redo"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Rehacer",
                                tint = if (draft.canRedo) PureWhite else TextSecondary.copy(alpha = 0.4f))
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { showSectionMenu = true },
                            modifier = Modifier.size(48.dp).testTag("metadata_section_menu"),
                        ) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Acciones de la sección",
                                tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = showSectionMenu,
                            onDismissRequest = { showSectionMenu = false },
                        ) {
                            if (narrowTopBar) {
                                DropdownMenuItem(
                                    text = { Text("Deshacer") },
                                    enabled = draft.canUndo,
                                    modifier = Modifier.testTag("metadata_menu_undo"),
                                    onClick = {
                                        val next = draft.undo()
                                        draft = next
                                        showSectionMenu = false
                                        onDirtyChanged(next.isDirty || artLyrics.isDirty)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rehacer") },
                                    enabled = draft.canRedo,
                                    modifier = Modifier.testTag("metadata_menu_redo"),
                                    onClick = {
                                        val next = draft.redo()
                                        draft = next
                                        showSectionMenu = false
                                        onDirtyChanged(next.isDirty || artLyrics.isDirty)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Sugerencias de MusicBrainz") },
                                modifier = Modifier.testTag("metadata_genre_musicbrainz"),
                                onClick = {
                                    showSectionMenu = false
                                    showMusicBrainz = true
                                },
                            )
                        }
                    }
                },
            )
            MetadataTrackSummary(
                snapshot,
                effectiveEditCount,
                partial,
                artworkModel,
                compact = compactHeader,
                onArtworkClick = {
                    section = MetadataEditorSection.ART_LYRICS
                    showMusicBrainz = false
                },
                onStatusInfo = { showStatusInfo = true },
            )
        }
        if (showMusicBrainz) {
            MichiMetadataMusicBrainzPanel(
                draft = draft,
                onApply = { selected ->
                    var next = draft
                    selected.forEach { (field, values) ->
                        if (next.canEdit(field)) next = next.type(field, values)
                    }
                    draft = next
                    onDirtyChanged(next.isDirty || artLyrics.isDirty)
                    showMusicBrainz = false
                },
                onBack = { showMusicBrainz = false },
                modifier = Modifier.weight(1f),
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactTabs = maxWidth < 360.dp &&
                    LocalDensity.current.fontScale <= 1.5f
                if (compactTabs) {
                    Column(
                        Modifier.fillMaxWidth().padding(
                            horizontal = MichiSpacing.xl,
                            vertical = MichiSpacing.sm,
                        ).testTag("metadata_section_chips"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tabs.take(2).forEach { candidate ->
                                MetadataSectionTab(
                                    candidate = candidate,
                                    selected = candidate == section,
                                    accent = accent,
                                    onClick = {
                                        section = candidate
                                        showMusicBrainz = false
                                        if (candidate != MetadataEditorSection.BASICS) {
                                            genreFirst = false
                                        }
                                    },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tabs.drop(2).forEach { candidate ->
                                MetadataSectionTab(
                                    candidate = candidate,
                                    selected = candidate == section,
                                    accent = accent,
                                    onClick = {
                                        section = candidate
                                        showMusicBrainz = false
                                        if (candidate != MetadataEditorSection.BASICS) {
                                            genreFirst = false
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().testTag("metadata_section_chips"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = MichiSpacing.xl, vertical = MichiSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
                    ) {
                        items(tabs, key = { it.name }) { candidate ->
                            MetadataSectionTab(
                                candidate = candidate,
                                selected = candidate == section,
                                accent = accent,
                                onClick = {
                                    section = candidate
                                    showMusicBrainz = false
                                    if (candidate != MetadataEditorSection.BASICS) {
                                        genreFirst = false
                                    }
                                },
                            )
                        }
                    }
                }
            }
            val onDraft: (MetadataEditorDraftSession) -> Unit = { next ->
                draft = next
                onDirtyChanged(next.isDirty || artLyrics.isDirty)
            }
            val renderField: @Composable (MetadataField, Boolean, Modifier) -> Unit =
                { field, autoFocus, fieldModifier ->
                    MetadataInlineField(
                        field = field,
                        draft = draft,
                        provisionalValues = provisional[field],
                        focusGenre = false,
                        onDraftChange = onDraft,
                        onProvisionalChange = { values ->
                            provisional = if (values.isEmpty()) provisional - field
                            else provisional + (field to values)
                        },
                        modifier = fieldModifier,
                        autoFocusField = field == focusedExtraField || autoFocus,
                    )
                }
            LazyColumn(
                state = fieldsState,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("metadata_fields_list"),
                verticalArrangement = Arrangement.spacedBy(MetadataFormTokens.fieldGap),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = MichiSpacing.xxl),
            ) {
                item(key = "intro_${section.name}") {
                    Text(
                        sectionTitle,
                        style = MichiTypography.sectionTitle,
                        color = PureWhite,
                        modifier = Modifier.padding(
                            start = MichiSpacing.xl, end = MichiSpacing.xl,
                            top = 14.dp, bottom = 10.dp,
                        ),
                    )
                }
                if (section == MetadataEditorSection.ART_LYRICS) {
                    item(key = "artwork_lyrics") {
                        MichiMetadataArtworkLyricsSection(
                            sourceUri = target.uri,
                            state = artLyrics,
                            onChange = { next ->
                                artLyrics = next
                                onDirtyChanged(draft.isDirty || next.isDirty)
                            },
                            canInspect = true,
                            onInspect = ::inspectArtworkLyrics,
                            modifier = Modifier.padding(horizontal = MichiSpacing.xl),
                        )
                    }
                    items(MetadataEditorSection.ART_LYRICS.fields, key = { it.name }) { field ->
                        renderField(
                            field, false,
                            Modifier.padding(horizontal = MichiSpacing.xl),
                        )
                    }
                } else if (section == MetadataEditorSection.BASICS) {
                    if (genreFirst) {
                        item(key = "field_genre_first") {
                            renderField(MetadataField.GENRE, false,
                                Modifier.padding(horizontal = MichiSpacing.xl))
                        }
                    }
                    item(key = "field_title") {
                        renderField(MetadataField.TITLE, false,
                            Modifier.padding(horizontal = MichiSpacing.xl))
                    }
                    item(key = "field_artist") {
                        renderField(MetadataField.ARTIST, false,
                            Modifier.padding(horizontal = MichiSpacing.xl))
                    }
                    item(key = "field_album") {
                        renderField(MetadataField.ALBUM, false,
                            Modifier.padding(horizontal = MichiSpacing.xl))
                    }
                    item(key = "field_album_artist") {
                        renderField(MetadataField.ALBUM_ARTIST, false,
                            Modifier.padding(horizontal = MichiSpacing.xl))
                    }
                    if (!genreFirst) {
                        item(key = "field_genre") {
                            renderField(MetadataField.GENRE, false,
                                Modifier.padding(horizontal = MichiSpacing.xl))
                        }
                    }
                    item(key = "field_release_date") {
                        Box(
                            Modifier.padding(horizontal = MichiSpacing.xl).width(160.dp),
                        ) {
                            renderField(MetadataField.RELEASE_DATE, false,
                                Modifier.fillMaxWidth())
                        }
                    }
                    item(key = "field_track_pair") {
                        MetadataNumberPair(
                            title = "Pista",
                            numberField = MetadataField.TRACK_NUMBER,
                            totalField = MetadataField.TRACK_TOTAL,
                            draft = draft,
                            onDraftChange = onDraft,
                            modifier = Modifier.padding(horizontal = MichiSpacing.xl),
                        )
                    }
                    item(key = "field_disc_pair") {
                        MetadataNumberPair(
                            title = "Disco",
                            numberField = MetadataField.DISC_NUMBER,
                            totalField = MetadataField.DISC_TOTAL,
                            draft = draft,
                            onDraftChange = onDraft,
                            modifier = Modifier.padding(horizontal = MichiSpacing.xl),
                        )
                    }
                    item(key = "field_composer") {
                        renderField(MetadataField.COMPOSER, false,
                            Modifier.padding(horizontal = MichiSpacing.xl))
                    }
                    item(key = "field_bpm") {
                        renderField(MetadataField.BPM, false,
                            Modifier.padding(horizontal = MichiSpacing.xl))
                    }
                    items(basicsHidden.filter { it in extraFields }, key = { it.name }) { field ->
                        renderField(field, field == focusedExtraField,
                            Modifier.padding(horizontal = MichiSpacing.xl))
                    }
                } else {
                    val visible = if (section == MetadataEditorSection.CREDITS)
                        creditsVisible else discographyVisible
                    val dateFields = setOf(
                        MetadataField.ORIGINAL_RELEASE_DATE,
                        MetadataField.RECORDING_DATE,
                    )
                    items(visible, key = { it.name }) { field ->
                        if (field in dateFields) {
                            Box(
                                Modifier.padding(horizontal = MichiSpacing.xl).width(160.dp),
                            ) {
                                renderField(field, field == focusedExtraField,
                                    Modifier.fillMaxWidth())
                            }
                        } else {
                            renderField(field, field == focusedExtraField,
                                Modifier.padding(horizontal = MichiSpacing.xl))
                        }
                    }
                }
                val hiddenCount = when (section) {
                    MetadataEditorSection.BASICS -> basicsHidden.count { it !in extraFields }
                    MetadataEditorSection.CREDITS -> creditsHidden.size
                    MetadataEditorSection.DISCOGRAPHY -> discographyHidden.size
                    else -> 0
                }
                if (hiddenCount > 0) {
                    item(key = "add_field_${section.name}") {
                        Box(Modifier.padding(horizontal = MichiSpacing.xl)) {
                            TextButton(
                                onClick = {
                                    fieldPickerQuery = ""
                                    fieldPickerVisible = true
                                },
                                modifier = Modifier.sizeIn(minHeight = 48.dp)
                                    .testTag("metadata_add_field"),
                            ) { Text("+ Añadir campo", color = accent) }
                            DropdownMenu(
                                expanded = fieldPickerVisible,
                                onDismissRequest = { fieldPickerVisible = false },
                            ) {
                                BasicTextField(
                                    value = fieldPickerQuery,
                                    onValueChange = { fieldPickerQuery = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = PureWhite),
                                    cursorBrush = SolidColor(accent),
                                    modifier = Modifier.width(280.dp).heightIn(min = 48.dp)
                                        .padding(horizontal = 14.dp)
                                        .testTag("metadata_field_search"),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (fieldPickerQuery.isEmpty()) {
                                                Text("Buscar etiqueta", color = TextMuted)
                                            }
                                            inner()
                                        }
                                    },
                                )
                                val query = fieldPickerQuery.trim()
                                pickerFields.filter { field ->
                                    query.isEmpty() || metadataFieldLabel(field)
                                        .contains(query, ignoreCase = true)
                                }.forEach { field ->
                                    DropdownMenuItem(
                                        text = { Text(metadataFieldLabel(field)) },
                                        modifier = Modifier.testTag(
                                            "metadata_add_field_${field.name.lowercase()}",
                                        ),
                                        onClick = {
                                            extraFields = extraFields + field
                                            focusedExtraField = field
                                            fieldPickerVisible = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "end_${section.name}") {
                    Spacer(Modifier.height(MichiSpacing.xl))
                }
            }
        }
        if (!imeVisible) {
            Column(
                Modifier.fillMaxWidth().background(SurfaceObsidian)
                    .padding(horizontal = MichiSpacing.xl, vertical = MichiSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
            ) {
                HorizontalDivider(color = outline)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { confirmRevert = true },
                        enabled = canRevert,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp)
                            .testTag("metadata_revert_changes"),
                    ) {
                        Text(
                            "Revertir cambios",
                            color = if (canRevert) PrimaryPinkContainer else TextSecondary.copy(alpha = 0.5f),
                        )
                    }
                    Button(
                        onClick = { saveableReview?.let(::performPilotSave) },
                        enabled = canSave,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp)
                            .testTag("metadata_save_file"),
                    ) { Text(if (saving) "Guardando…" else "Guardar") }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val caption = when {
                        saving -> "Guardando…"
                        saveMessage != null -> saveMessage!!
                        recoveryPending -> "Recuperación necesaria antes de editar"
                        pilotEligible && pilotArtworkBlocked ->
                            "La carátula no se puede guardar en este perfil todavía"
                        pilotEligible && effectiveEditCount == 0 -> "Sin cambios"
                        pilotEligible && saveableReview != null -> "Listo para guardar en el archivo"
                        else -> "No se puede guardar este tipo de archivo todavía"
                    }
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (saveMessage == "Cambios guardados") TertiaryCyan else TextSecondary,
                        modifier = Modifier.weight(1f)
                            .testTag("metadata_save_unavailable"),
                    )
                    TextButton(
                        onClick = { showSaveDetail = true },
                        modifier = Modifier.sizeIn(minHeight = 48.dp)
                            .testTag("metadata_save_detail"),
                    ) { Text("Detalle") }
                }
            }
        }
    }
}

@Composable
private fun MetadataSectionTab(
    candidate: MetadataEditorSection,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(
                if (selected) Color(0xFF38242C) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .border(
                1.dp,
                if (selected) Color(0xFF7A3847) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .heightIn(min = 48.dp)
            .testTag("metadata_section_${candidate.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (selected) {
            Box(Modifier.size(5.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
        }
        Text(
            metadataSectionLabel(candidate),
            color = if (selected) PureWhite else TextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun MetadataNumberPair(
    title: String,
    numberField: MetadataField,
    totalField: MetadataField,
    draft: MetadataEditorDraftSession,
    onDraftChange: (MetadataEditorDraftSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(MetadataFormTokens.inputRadius)
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(MetadataFormTokens.labelGap))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(numberField to "número", totalField to "total").forEachIndexed { index, pair ->
                if (index > 0) {
                    Text("de", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
                val (field, part) = pair
                Box(
                    Modifier.width(76.dp).heightIn(min = 48.dp)
                        .background(AeroGraphite, shape)
                        .border(1.dp, AeroHairline, shape)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    MetadataInlineValueField(
                        field = field,
                        value = draft.values(field).firstOrNull().orEmpty(),
                        index = 0,
                        tag = "metadata_input_${field.name.lowercase()}_0",
                        enabled = draft.canEdit(field),
                        placeholder = "—",
                        onValueChange = { changed ->
                            onDraftChange(
                                draft.type(
                                    field, listOf(changed),
                                    coalesceKey = "pair:${field.name}",
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactEditorAction(text: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
            .padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("›", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
    }
}

@Composable
private fun ArtworkFallback(
    accent: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp = 56.dp,
) {
    Box(Modifier.size(size).background(AeroGraphiteRaised, MichiShapes.sm),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Album, contentDescription = null, tint = accent,
            modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun MetadataTrackSummary(
    snapshot: MetadataSnapshot,
    edited: Int,
    partial: Boolean,
    artworkModel: Any? = null,
    compact: Boolean = false,
    onArtworkClick: () -> Unit = {},
    onStatusInfo: () -> Unit = {},
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val edge = if (highContrast) MaterialTheme.colorScheme.outline else AeroHairline
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    val title = if (MetadataField.TITLE in snapshot.verifiedFields)
        snapshot.known[MetadataField.TITLE]?.firstOrNull()?.takeIf(String::isNotBlank) else null
    val artist = if (MetadataField.ARTIST in snapshot.verifiedFields)
        snapshot.known[MetadataField.ARTIST]?.firstOrNull() else null
    val album = if (MetadataField.ALBUM in snapshot.verifiedFields)
        snapshot.known[MetadataField.ALBUM]?.firstOrNull()?.takeIf(String::isNotBlank) else null
    val coverSize by animateDpAsState(if (compact) 44.dp else MetadataFormTokens.summaryArt, label = "cover")
    val highFontScale = LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxWidth().padding(horizontal = MichiSpacing.xl)
        .padding(top = if (compact) 2.dp else 4.dp, bottom = if (compact) 8.dp else 15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(
                Modifier.size(coverSize).clip(MichiShapes.sm)
                    .clickable(onClickLabel = "Abrir arte y letras", onClick = onArtworkClick)
                    .testTag("metadata_cover"),
            ) {
                if (artworkModel != null) {
                    SubcomposeAsyncImage(
                        model = artworkModel,
                        contentDescription = "Carátula de ${title ?: "la canción"}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        loading = { ArtworkFallback(accent, coverSize) },
                        error = { ArtworkFallback(accent, coverSize) },
                    )
                } else {
                    ArtworkFallback(accent, size = coverSize)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (!compact && !highFontScale) {
                    Text("CANCION · BIBLIOTECA LOCAL", style = MichiTypography.microLabel,
                        color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    title ?: "Archivo seleccionado",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                    ),
                    fontWeight = FontWeight.Bold,
                    maxLines = if (highFontScale) 2 else 1,
                    overflow = TextOverflow.Ellipsis, color = PureWhite,
                )
                if (!compact) {
                    Text(
                        listOfNotNull(artist, album).joinToString(" · ")
                            .ifBlank { "Artista no verificado" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                        ),
                        color = TextSecondary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(
                if (partial) Color(0xFFA1A5B2) else Color(0xFF79DEB6),
                androidx.compose.foundation.shape.CircleShape))
            Text(
                if (partial) "Etiquetas parcialmente verificadas"
                else "Etiquetas físicas verificadas",
                style = MaterialTheme.typography.labelSmall,
                color = if (partial) TextSecondary else Color(0xFFC0D9D2),
                modifier = Modifier.weight(1f).then(
                    if (partial) Modifier.testTag("metadata_partial_verification") else Modifier,
                ),
            )
            if (edited > 0) Text(if (edited == 1) "1 modificado" else "$edited modificados", color = accent,
                style = MaterialTheme.typography.labelSmall)
            IconButton(
                onClick = onStatusInfo,
                modifier = Modifier.size(48.dp).testTag("metadata_status_info"),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = "Detalle del estado de las etiquetas",
                    tint = TextSecondary)
            }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 15.dp))
        HorizontalDivider(color = edge, thickness = 1.dp)
    }
}

@Composable
private fun MetadataInlineField(
    field: MetadataField,
    draft: MetadataEditorDraftSession,
    provisionalValues: List<String>?,
    focusGenre: Boolean,
    onDraftChange: (MetadataEditorDraftSession) -> Unit,
    onProvisionalChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    autoFocusField: Boolean = false,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    val editable = draft.canEdit(field)
    val canPropose = !editable && draft.original.tagsVerified
    val provisional = canPropose
    val values = if (provisional) provisionalValues.orEmpty() else draft.values(field)
    val cleared = editable && draft.isCleared(field)
    val error = if (editable) draft.fieldError(field) else null
    val observation = draft.original.fieldObservations[field]
    val token = draft.original.target.evidenceToken
    var focusedRows by remember(field, token) { mutableStateOf<Set<Int>>(emptySet()) }
    var menuOpen by remember(field, token) { mutableStateOf(false) }
    var valueMenuIndex by remember(field, token) { mutableStateOf<Int?>(null) }
    var confirmDelete by remember(field, token) { mutableStateOf(false) }
    var infoVisible by remember(field, token) { mutableStateOf(false) }
    var focusRequestKey by remember(field, token) { mutableIntStateOf(0) }
    // A new multivalue row is UI-only until it has a non-blank value. Cancelling it
    // never creates a draft operation, an empty MetadataChange or an effective diff.
    var pendingRow by remember(field, token) { mutableStateOf<String?>(null) }
    val focused = focusedRows.isNotEmpty()
    val realRows = when {
        field.multiple && values.isEmpty() -> listOf("")
        field.multiple -> values
        else -> listOf(values.firstOrNull().orEmpty())
    }
    val pendingIndex = if (field.multiple && pendingRow != null) realRows.size else -1
    val rows = if (pendingIndex >= 0) realRows + pendingRow.orEmpty() else realRows
    val statusLabel = when {
        provisional -> "Propuesta provisional"
        cleared -> "Eliminación pendiente"
        field in draft.editedFields -> "Modificado"
        observation == MetadataFieldObservation.VerifiedAbsent -> "Ausente comprobado"
        !editable -> "Sin verificar"
        else -> "Verificado"
    }
    fun emitCoalesced(next: List<String>) {
        if (provisional) onProvisionalChange(next)
        else onDraftChange(draft.type(field, next, coalesceKey = "field:${field.name}"))
    }
    fun emitImmediate(next: List<String>) {
        if (provisional) onProvisionalChange(next) else onDraftChange(draft.type(field, next))
    }
    fun focusBack() { focusRequestKey++ }
    fun changeRow(index: Int, changed: String) {
        if (index == pendingIndex) {
            if (changed.isBlank()) {
                pendingRow = changed
            } else {
                pendingRow = null
                emitCoalesced(realRows + changed)
            }
        } else {
            emitCoalesced(realRows.toMutableList().also { it[index] = changed })
        }
    }
    val hasActions = focused || menuOpen || cleared ||
        field in draft.editedFields ||
        (provisional && values.any(String::isNotBlank))
    val canAddValue = field.multiple && (editable || provisional) && pendingRow == null &&
        realRows.size < 32 && values.any(String::isNotBlank)

    Column(modifier.testTag("metadata_field_${field.name.lowercase()}")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 24.dp)
                .testTag("metadata_field_label_${field.name.lowercase()}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                metadataFieldLabel(field),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                fontWeight = FontWeight.Medium,
                color = if (focused) PureWhite else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasActions) {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(48.dp)
                        .testTag("metadata_field_menu_${field.name.lowercase()}"),
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "Acciones de ${metadataFieldLabel(field)}",
                        tint = TextSecondary,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!editable || provisional ||
                        observation is MetadataFieldObservation.Conflicted ||
                        observation is MetadataFieldObservation.Unverifiable) {
                        DropdownMenuItem(
                            text = { Text("Información del campo") },
                            modifier = Modifier.testTag("metadata_menu_info_${field.name.lowercase()}"),
                            onClick = { menuOpen = false; infoVisible = true },
                        )
                    }
                    if (editable && field in draft.editedFields) {
                        DropdownMenuItem(
                            text = { Text("Restaurar original") },
                            modifier = Modifier.testTag("metadata_menu_restore_${field.name.lowercase()}"),
                            onClick = {
                                menuOpen = false
                                onDraftChange(draft.keep(field))
                                focusBack()
                            },
                        )
                    }
                    if (editable && !cleared && values.any(String::isNotBlank)) {
                        DropdownMenuItem(
                            text = { Text("Eliminar etiqueta") },
                            modifier = Modifier.testTag("metadata_menu_delete_${field.name.lowercase()}"),
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                    if (provisional && values.any(String::isNotBlank)) {
                        DropdownMenuItem(
                            text = { Text("Descartar propuesta") },
                            modifier = Modifier.testTag("metadata_menu_discard_${field.name.lowercase()}"),
                            onClick = {
                                menuOpen = false
                                onProvisionalChange(emptyList())
                                focusBack()
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(MetadataFormTokens.labelGap))
        if (!editable && !canPropose) {
            Text(
                "Sin verificar · no disponible para edición",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else if (cleared) {
            Text(
                "Se eliminará esta etiqueta cuando guardes.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MichiSpacing.xs)) {
                TextButton(onClick = {
                    onDraftChange(draft.keep(field))
                    focusBack()
                }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Conservar original") }
                TextButton(onClick = {
                    onDraftChange(draft.type(field, listOf("")))
                    focusBack()
                }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Escribir otro valor") }
            }
        } else {
            rows.forEachIndexed { index, value ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.xs),
                ) {
                    MetadataValueCapsule(
                        field = field,
                        value = value,
                        index = index,
                        tag = when {
                            index == pendingIndex ->
                                "metadata_pending_${field.name.lowercase()}_0"
                            provisional -> "metadata_provisional_${field.name.lowercase()}_$index"
                            else -> "metadata_input_${field.name.lowercase()}_$index"
                        },
                        modifier = Modifier.weight(1f),
                        enabled = editable || provisional,
                        autoFocus = (autoFocusField || focusGenre) && index == 0,
                        focusRequestKey = if (index == (if (pendingIndex >= 0) pendingIndex else rows.lastIndex)) {
                            focusRequestKey
                        } else 0,
                        placeholder = if (field in setOf(
                            MetadataField.RELEASE_DATE,
                            MetadataField.ORIGINAL_RELEASE_DATE,
                            MetadataField.RECORDING_DATE,
                        )) "AAAA-MM-DD" else metadataFieldPlaceholder(field),
                        focused = focusedRows.contains(index),
                        modified = field in draft.editedFields,
                        onFocusChanged = { isFocused ->
                            focusedRows = if (isFocused) focusedRows + index
                            else focusedRows - index
                        },
                        onValueChange = { changed -> changeRow(index, changed) },
                    )
                    if (focusedRows.contains(index) || valueMenuIndex == index) {
                        IconButton(
                            onClick = { valueMenuIndex = index },
                            modifier = Modifier.size(48.dp)
                                .testTag("metadata_value_menu_${field.name.lowercase()}_$index"),
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = "Acciones del valor ${index + 1} de ${metadataFieldLabel(field)}",
                                tint = TextSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded = valueMenuIndex == index,
                            onDismissRequest = { valueMenuIndex = null },
                        ) {
                            if (index == pendingIndex) {
                                DropdownMenuItem(
                                    text = { Text("Descartar fila nueva") },
                                    modifier = Modifier.testTag("metadata_discard_pending_${field.name.lowercase()}"),
                                    onClick = {
                                        valueMenuIndex = null
                                        pendingRow = null
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Quitar este valor") },
                                    modifier = Modifier.testTag("metadata_remove_${field.name.lowercase()}_$index"),
                                    onClick = {
                                        valueMenuIndex = null
                                        emitImmediate(rows.filterIndexed { i, _ -> i != index })
                                        focusBack()
                                    },
                                )
                                if (field.multiple) {
                                    DropdownMenuItem(
                                        text = { Text("Subir") },
                                        enabled = index > 0,
                                        onClick = {
                                            val next = realRows.toMutableList()
                                            val previous = next[index - 1]
                                            next[index - 1] = next[index]
                                            next[index] = previous
                                            valueMenuIndex = null
                                            emitImmediate(next)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Bajar") },
                                        enabled = index < realRows.lastIndex,
                                        onClick = {
                                            val next = realRows.toMutableList()
                                            val following = next[index + 1]
                                            next[index + 1] = next[index]
                                            next[index] = following
                                            valueMenuIndex = null
                                            emitImmediate(next)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (canAddValue) {
                TextButton(
                    onClick = {
                        pendingRow = ""
                        focusBack()
                    },
                    modifier = Modifier.padding(start = 4.dp).sizeIn(minHeight = 48.dp)
                        .testTag("metadata_add_value_${field.name.lowercase()}"),
                ) { Text("+ Otro valor", color = accent) }
            }
        }
        if (error != null) {
            Text(
                metadataDraftErrorLabel(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
                    .testTag("metadata_error_${field.name.lowercase()}"),
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false; focusBack() },
            title = {
                Text(
                    if (field.multiple) "¿Eliminar todos los valores de ${metadataFieldLabel(field)}?"
                    else "¿Eliminar ${metadataFieldLabel(field)}?",
                )
            },
            text = { Text("La etiqueta se quitará del formulario. Todavía no se ha escrito nada.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDraftChange(draft.clear(field))
                        focusBack()
                    },
                    modifier = Modifier.testTag("metadata_confirm_clear_${field.name.lowercase()}"),
                ) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDelete = false; focusBack() },
                    modifier = Modifier.testTag("metadata_cancel_clear_${field.name.lowercase()}"),
                ) { Text("Cancelar") }
            },
        )
    }
    if (infoVisible) {
        val reason = when (observation) {
            is MetadataFieldObservation.Conflicted ->
                "El archivo contiene datos contradictorios para este campo."
            is MetadataFieldObservation.Unverifiable ->
                "El lector no puede verificar este campo con seguridad."
            null -> if (field in draft.original.verifiedFields)
                "El archivo confirma este campo." else
                "El archivo no confirma este campo."
            else -> "El archivo no confirma este campo."
        }
        MetadataEvidenceDialog(
            title = metadataFieldLabel(field),
            lines = listOfNotNull("Estado: $statusLabel", reason),
            tag = "metadata_field_info_${field.name.lowercase()}",
            onDismiss = { infoVisible = false; focusBack() },
        )
    }
}

@Composable
private fun MetadataValueCapsule(
    field: MetadataField,
    value: String,
    index: Int,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    autoFocus: Boolean,
    focusRequestKey: Int,
    placeholder: String,
    focused: Boolean,
    modified: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val shape = RoundedCornerShape(MetadataFormTokens.inputRadius)
    val fill = if (highContrast) MaterialTheme.colorScheme.surfaceContainerHigh else AeroGraphite
    val neutralBorder = if (highContrast) MaterialTheme.colorScheme.outline
    else AeroHairline
    val borderColor = when {
        focused -> PrimaryPinkContainer
        modified -> PrimaryPinkContainer.copy(alpha = 0.34f)
        else -> neutralBorder
    }
    Box(
        modifier.heightIn(min = MetadataFormTokens.inputMinHeight)
            .background(fill, shape)
            .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
            .testTag(
                if (field.multiple) "metadata_capsule_${field.name.lowercase()}_$index"
                else "metadata_capsule_${field.name.lowercase()}",
            )
            .padding(horizontal = MetadataFormTokens.inputHPad),
        contentAlignment = Alignment.CenterStart,
    ) {
        MetadataInlineValueField(
            field = field,
            value = value,
            index = index,
            tag = tag,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            autoFocus = autoFocus,
            focusRequestKey = focusRequestKey,
            placeholder = placeholder,
            onFocusChanged = onFocusChanged,
            onValueChange = onValueChange,
        )
    }
}

@Composable
private fun MetadataEvidenceDialog(
    title: String,
    lines: List<String>,
    tag: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                lines.forEach { Text(it, color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(tag)) {
                Text("Entendido")
            }
        },
    )
}

private fun metadataDraftErrorLabel(reason: MetadataBlockReason): String = when (reason) {
    MetadataBlockReason.EMPTY_VALUE -> "Introduce un valor o utiliza «Eliminar» para proponer un borrado."
    MetadataBlockReason.EMPTY_REPLACEMENT -> "La sustitución necesita un valor explícito."
    MetadataBlockReason.FIELD_NOT_VERIFIED -> "Este campo no tiene una lectura física comprobada."
    MetadataBlockReason.NUL_CHARACTER -> "El texto contiene un carácter de control incompatible."
    MetadataBlockReason.FIELD_TOO_LONG -> "El valor supera la longitud permitida para esta etiqueta."
    MetadataBlockReason.TOO_MANY_VALUES -> "Máximo de 32 valores por campo."
    MetadataBlockReason.MULTIPLE_VALUES_UNSUPPORTED -> "Este campo solo admite un valor."
    MetadataBlockReason.UNVERIFIED_EMBEDDED_TAGS -> "No se ha verificado la lectura de etiquetas."
    else -> "La propuesta no supera la validación: ${reason.name}."
}
