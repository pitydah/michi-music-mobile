package org.michimusic.mobile.ui.components

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
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.fieldReviewSummary
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
        if (artLyrics.artwork != null) 1 else 0
    val canRevert = certifiedDirty || effectiveEditCount > 0 ||
        provisionalCount > 0 || artLyrics.isDirty
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun requestExit() {
        if (certifiedDirty || provisionalCount > 0) confirmExit = true else onDismiss()
    }
    fun revertChanges() {
        draft = MetadataEditorDraftSession.start(snapshot)
        provisional = emptyMap()
        artLyrics = artLyrics.reset()
        genreFirst = false
        onDirtyChanged(false)
        confirmRevert = false
    }
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
                                onClick = { fieldPickerVisible = true },
                                modifier = Modifier.sizeIn(minHeight = 48.dp)
                                    .testTag("metadata_add_field"),
                            ) { Text("+ Añadir campo", color = accent) }
                            DropdownMenu(
                                expanded = fieldPickerVisible,
                                onDismissRequest = { fieldPickerVisible = false },
                            ) {
                                pickerFields.forEach { field ->
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
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp)
                            .testTag("metadata_save_file"),
                    ) { Text("Guardar") }
                }
                Text(
                    "Guardado no disponible para este perfil todavía",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                        .testTag("metadata_save_unavailable"),
                )
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
    val focused = focusedRows.isNotEmpty()
    val rows = when {
        field.multiple && values.isEmpty() -> listOf("")
        field.multiple -> values
        else -> listOf(values.firstOrNull().orEmpty())
    }
    val statusLabel = when {
        provisional -> "Propuesta provisional"
        cleared -> "Eliminación pendiente"
        field in draft.editedFields -> "Modificado"
        observation == MetadataFieldObservation.VerifiedAbsent -> "Ausente comprobado"
        !editable -> "Sin verificar"
        else -> "Verificado"
    }
    val statusColor = when {
        provisional -> Color(0xFFFFB3B5)
        cleared -> MaterialTheme.colorScheme.error
        observation is MetadataFieldObservation.Conflicted -> MaterialTheme.colorScheme.error
        !editable -> TextMuted
        field in draft.editedFields -> accent
        observation == MetadataFieldObservation.VerifiedAbsent -> TextMuted
        else -> TertiaryCyan
    }
    fun emitCoalesced(next: List<String>) {
        if (provisional) onProvisionalChange(next)
        else onDraftChange(draft.type(field, next, coalesceKey = "field:${field.name}"))
    }
    fun emitImmediate(next: List<String>) {
        if (provisional) onProvisionalChange(next) else onDraftChange(draft.type(field, next))
    }
    fun focusBack() { focusRequestKey++ }
    val hasActions = focused || menuOpen || cleared ||
        field in draft.editedFields ||
        (provisional && values.any(String::isNotBlank))
    val canAddValue = field.multiple && (editable || provisional) && rows.size < 32 &&
        values.any(String::isNotBlank)

    MetadataFieldShell(
        field = field,
        statusLabel = statusLabel,
        statusColor = statusColor,
        focused = focused,
        modified = field in draft.editedFields,
        errorText = error?.let(::metadataDraftErrorLabel),
        modifier = modifier,
        hasActions = hasActions,
        statusDetail = null,
        showStatusText = false,
        actions = {
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
        },
    ) {
        when {
            !editable && !canPropose -> {
                Text(
                    "Sin verificar · no disponible para edición",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            cleared -> {
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
            }
            else -> {
                rows.forEachIndexed { index, value ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MichiSpacing.xs),
                    ) {
                        MetadataInlineValueField(
                            field = field,
                            value = value,
                            index = index,
                            tag = if (provisional) {
                                "metadata_provisional_${field.name.lowercase()}_$index"
                            } else {
                                "metadata_input_${field.name.lowercase()}_$index"
                            },
                            modifier = Modifier.weight(1f),
                            enabled = editable || provisional,
                            autoFocus = (autoFocusField || focusGenre) && index == 0,
                            focusRequestKey = if (index == rows.lastIndex) focusRequestKey else 0,
                            placeholder = if (field in setOf(
                                MetadataField.RELEASE_DATE,
                                MetadataField.ORIGINAL_RELEASE_DATE,
                                MetadataField.RECORDING_DATE,
                            )) "AAAA-MM-DD" else metadataFieldPlaceholder(field),
                            onFocusChanged = { isFocused ->
                                focusedRows = if (isFocused) focusedRows + index
                                else focusedRows - index
                            },
                            onValueChange = { changed ->
                                emitCoalesced(rows.toMutableList().also { it[index] = changed })
                            },
                        )
                        if (field.multiple && (focusedRows.contains(index) || valueMenuIndex == index)) {
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
                                DropdownMenuItem(
                                    text = { Text("Quitar este valor") },
                                    modifier = Modifier.testTag("metadata_remove_${field.name.lowercase()}_$index"),
                                    onClick = {
                                        valueMenuIndex = null
                                        emitImmediate(rows.filterIndexed { i, _ -> i != index })
                                        focusBack()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Subir") },
                                    enabled = index > 0,
                                    onClick = {
                                        val next = rows.toMutableList()
                                        val previous = next[index - 1]
                                        next[index - 1] = next[index]
                                        next[index] = previous
                                        valueMenuIndex = null
                                        emitImmediate(next)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Bajar") },
                                    enabled = index < rows.lastIndex,
                                    onClick = {
                                        val next = rows.toMutableList()
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
    }
    if (canAddValue) {
        TextButton(
            onClick = {
                emitImmediate(rows + "")
                focusBack()
            },
            modifier = modifier.padding(start = 4.dp).sizeIn(minHeight = 48.dp)
                .testTag("metadata_add_value_${field.name.lowercase()}"),
        ) { Text("+ Añadir", color = accent) }
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
            lines = listOfNotNull(
                "Estado: $statusLabel",
                reason,
            ),
            tag = "metadata_field_info_${field.name.lowercase()}",
            onDismiss = { infoVisible = false; focusBack() },
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
