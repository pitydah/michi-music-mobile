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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataDescribedTextWorkbench
import org.michimusic.core.metadata.MetadataBlockReason
import org.michimusic.core.metadata.MetadataEditorDraftSession
import org.michimusic.core.metadata.MetadataEditorSection
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldDiff
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
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
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    val outline = if (highContrast) MaterialTheme.colorScheme.outline else AeroHairline
    var draft by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(MetadataEditorDraftSession.start(snapshot))
    }
    var section by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(if (focusGenre) MetadataEditorSection.BASICS else initialSection)
    }
    var reviewing by remember(target.sourceId, target.trackId, target.evidenceToken) {
        mutableStateOf(false)
    }
    var recoveryVisible by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var suggestionsVisible by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var confirmExit by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(false)
    }
    var preparationVisible by remember(target.sourceId, target.trackId, target.evidenceToken) {
        mutableStateOf(false)
    }
    var artLyrics by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(MetadataArtLyricsUiState())
    }
    // ME-11U: scratch proposals for fields the file itself cannot verify. They never
    // enter the certified draft, the review, private preparation or the Room index.
    var provisional by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf<Map<MetadataField, List<String>>>(emptyMap())
    }
    var sectionEvidenceVisible by remember(section, target.evidenceToken) {
        mutableStateOf(false)
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
    val anyDirty = certifiedDirty
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun requestExit() {
        if (anyDirty || provisionalCount > 0) confirmExit = true else onDismiss()
    }
    BackHandler(enabled = true) { requestExit() }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("¿Descartar los cambios?") },
            text = { Text("Hay modificaciones en el borrador o propuestas provisionales. Salir no escribe etiquetas en el archivo y descartará estas propuestas.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onDirtyChanged(false)
                    onDismiss()
                }, modifier = Modifier.testTag("metadata_confirm_discard")) {
                    Text("Descartar y salir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Seguir editando") }
            },
        )
    }
    LaunchedEffect(anyDirty) { onDirtyChanged(anyDirty) }

    Column(
        Modifier.fillMaxWidth().fillMaxSize().imePadding()
            .testTag("metadata_workbench"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = MichiSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
            MetadataEditorTopBar(
                title = "Editar metadatos",
                onBack = { requestExit() },
                backDescription = "Cerrar editor",
                backTestTag = "metadata_editor_exit",
                actions = {
                    IconButton(
                        onClick = {
                            val next = draft.undo()
                            draft = next
                            onDirtyChanged(next.isDirty || artLyrics.isDirty)
                            reviewing = false
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
                            reviewing = false
                        },
                        enabled = draft.canRedo,
                        modifier = Modifier.size(48.dp).testTag("metadata_editor_redo"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Rehacer",
                            tint = if (draft.canRedo) PureWhite else TextSecondary.copy(alpha = 0.4f))
                    }
                },
            )
            MetadataTrackSummary(
                snapshot,
                draft.editedFields.size + artLyrics.operations.size + if (artLyrics.artwork != null) 1 else 0,
                partial,
                artworkModel,
                compact = compactHeader,
                onArtworkClick = {
                    section = MetadataEditorSection.ART_LYRICS
                    reviewing = false
                    suggestionsVisible = false
                    recoveryVisible = false
                },
            )
        }
        if (!reviewing && !recoveryVisible && !suggestionsVisible) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().testTag("metadata_section_chips"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = MichiSpacing.xl, vertical = MichiSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
            ) {
                items(MetadataEditorSection.entries, key = { it.name }) { candidate ->
                    val selected = candidate == section
                    Row(
                        modifier = Modifier
                            .background(
                                if (selected) Color(0xFF38242C) else Color.Transparent,
                                androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                            )
                            .border(
                                1.dp,
                                if (selected) Color(0xFF7A3847) else Color.Transparent,
                                androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                            )
                            .clickable {
                                section = candidate
                                reviewing = false
                                suggestionsVisible = false
                                recoveryVisible = false
                            }
                            .padding(horizontal = 14.dp)
                            .heightIn(min = 48.dp)
                            .testTag("metadata_section_${candidate.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (selected) {
                            Box(Modifier.size(5.dp).background(
                                accent, androidx.compose.foundation.shape.CircleShape))
                        }
                        Text(
                            metadataSectionLabel(candidate),
                            color = if (selected) PureWhite else TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        if (suggestionsVisible) {
            MichiMetadataMusicBrainzPanel(
                draft = draft,
                onApply = { selected ->
                    var next = draft
                    selected.forEach { (field, values) ->
                        if (next.canEdit(field)) next = next.type(field, values)
                    }
                    draft = next
                    onDirtyChanged(next.isDirty || artLyrics.isDirty)
                    suggestionsVisible = false
                },
                onBack = { suggestionsVisible = false },
                modifier = Modifier.weight(1f),
            )
        } else if (recoveryVisible) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)
                .padding(horizontal = MichiSpacing.xl)) {
                item(key = "explicit_recovery") { recoveryPanel?.invoke() }
                if (privateBundleCoordinator != null) item(key = "private_bundle_sessions") {
                    MichiMetadataPrivateSessionsPanel(privateBundleCoordinator, snapshot.target)
                }
            }
        } else if (reviewing) {
            if (preparationVisible && privateBundleCoordinator != null) {
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    TextButton(
                        onClick = { preparationVisible = false },
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) { Text("Volver a la revisión") }
                    Column(
                        Modifier.fillMaxWidth().weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        MichiMetadataPrivateBundlePanel(
                            original = snapshot,
                            expectedBytes = expectedFileBytes,
                            fieldReview = draft.review,
                            artLyrics = artLyrics,
                            coordinator = privateBundleCoordinator,
                            stillOwns = stillOwnsPrivateSource,
                            playbackDescriptorClosed = playbackDescriptorClosed,
                            currentSessionAllowsPreparation = currentSessionAllowsPreparation,
                        )
                    }
                }
            } else {
                MetadataReviewScreen(draft.review, artLyrics, provisional,
                    onBack = { reviewing = false }, modifier = Modifier.weight(1f))
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val fontScale = LocalDensity.current.fontScale
                val wide = maxWidth >= 560.dp && fontScale <= 1.5f
                val twoColumns = maxWidth >= 360.dp && fontScale <= 1.3f
                val onDraft = { next: MetadataEditorDraftSession ->
                    draft = next
                    onDirtyChanged(next.isDirty || artLyrics.isDirty)
                }
                val renderField: @Composable (MetadataField, Boolean) -> Unit = { field, autoFocus ->
                    MetadataInlineField(
                        field = field,
                        draft = draft,
                        provisionalValues = provisional[field],
                        focusGenre = autoFocus,
                        onDraftChange = onDraft,
                        onProvisionalChange = { values ->
                            provisional = if (values.isEmpty()) provisional - field
                            else provisional + (field to values)
                        },
                        onOpenGenreSuggestions = {
                            suggestionsVisible = true
                            reviewing = false
                            recoveryVisible = false
                        },
                        modifier = Modifier.padding(horizontal = MichiSpacing.xl),
                    )
                }
                val describedFields = artLyrics.inspected
                    ?.takeIf { it.format != MetadataArtworkLyricsProbe.Format.FLAC }
                    ?.texts?.map { it.identity.kind.field }?.toSet().orEmpty()
                val unverifiedSectionFields = section.fields.filterNot { it in describedFields }
                    .filterNot { draft.canEdit(it) }
                fun evidenceReason(field: MetadataField): String =
                    when (val observation = draft.original.fieldObservations[field]) {
                        is MetadataFieldObservation.Conflicted ->
                            "datos contradictorios en el archivo"
                        is MetadataFieldObservation.Unverifiable ->
                            "el lector no puede confirmarlo con seguridad"
                        else -> "sin evidencia física para este campo"
                    }
                LazyColumn(
                    state = fieldsState,
                    modifier = Modifier.fillMaxSize().testTag("metadata_fields_list"),
                    verticalArrangement = Arrangement.spacedBy(MichiSpacing.xs),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = MichiSpacing.xxl),
                ) {
                    item(key = "intro_${section.name}") {
                        BoxWithConstraints(Modifier.padding(horizontal = MichiSpacing.xl)
                            .padding(top = 14.dp, bottom = 10.dp)) {
                            val statusText =
                                if (partial) "● VERIFICACIÓN PARCIAL" else "● ORIGEN VERIFICADO"
                            val statusColor = if (partial) TextSecondary else TertiaryCyan
                            val title = if (section == MetadataEditorSection.BASICS)
                                "Información principal" else metadataSectionLabel(section)
                            val highFontScale = LocalDensity.current.fontScale > 1.3f
                            val availableWidth = maxWidth
                            // ME-11V P0: a Box stacks children. The section header and
                            // its explanatory note must be laid out vertically, or the
                            // note draws on top of the title (Arte y letras).
                            Column {
                                if (availableWidth < 360.dp || highFontScale) {
                                    Column {
                                        Text("EDICIÓN INDIVIDUAL", style = MichiTypography.microLabel,
                                            color = accent, fontWeight = FontWeight.ExtraBold)
                                        Text(
                                            title,
                                            style = MichiTypography.sectionTitle,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                        Text(
                                            statusText,
                                            style = MichiTypography.microLabel,
                                            color = statusColor,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("EDICIÓN INDIVIDUAL",
                                                style = MichiTypography.microLabel,
                                                color = accent, fontWeight = FontWeight.ExtraBold)
                                            Text(
                                                title,
                                                style = MichiTypography.sectionTitle,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 4.dp),
                                            )
                                        }
                                        Text(
                                            statusText,
                                            style = MichiTypography.microLabel,
                                            color = statusColor,
                                        )
                                    }
                                }
                                if (section == MetadataEditorSection.ART_LYRICS) {
                                    Text("Inspección voluntaria disponible; todas las propuestas permanecen en memoria. La escritura física aún no está certificada.",
                                        style = MaterialTheme.typography.labelMedium, color = TertiaryCyan,
                                        modifier = Modifier.padding(top = MichiSpacing.sm))
                                }
                            }
                        }
                    }
                    if (section != MetadataEditorSection.ART_LYRICS &&
                        unverifiedSectionFields.isNotEmpty()) {
                        item(key = "section_evidence_${section.name}") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = MichiSpacing.xl, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    Modifier.size(6.dp).background(
                                        TextMuted,
                                        androidx.compose.foundation.shape.CircleShape,
                                    ),
                                )
                                Text(
                                    if (LocalDensity.current.fontScale > 1.3f)
                                        "Campos sin verificar"
                                    else "Algunos campos no están verificados",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { sectionEvidenceVisible = true },
                                    modifier = Modifier.size(48.dp)
                                        .testTag("metadata_section_info"),
                                ) {
                                    Icon(Icons.Outlined.Info,
                                        contentDescription = "Información de evidencia",
                                        tint = TertiaryCyan)
                                }
                            }
                        }
                    }
                    if (section == MetadataEditorSection.ADVANCED) {
                        item(key = "raw_tags_read_only") {
                            MichiMetadataRawTagInventory(snapshot)
                        }
                        item(key = "rename_preview_user_action") {
                            MichiMetadataRenamePreviewPanel(snapshot)
                        }
                        if (recoveryPanel != null || privateBundleCoordinator != null) {
                            item(key = "recovery_toggle") {
                                CompactEditorAction(
                                    text = if (recoveryVisible) "Volver al editor" else
                                        "Comprobar recuperación privada",
                                    tag = "metadata_recovery_toggle",
                                    onClick = {
                                        recoveryVisible = !recoveryVisible; reviewing = false
                                    },
                                )
                            }
                        }
                    }
                    if (section == MetadataEditorSection.IDENTIFIERS) {
                        item(key = "musicbrainz_suggestions_toggle") {
                            CompactEditorAction(
                                text = if (suggestionsVisible) "Volver a editar etiquetas"
                                else "Sugerencias y autocompletar · MusicBrainz",
                                tag = "metadata_musicbrainz_suggestions",
                                onClick = {
                                    suggestionsVisible = !suggestionsVisible
                                    reviewing = false
                                    recoveryVisible = false
                                },
                            )
                        }
                    }
                    if (section == MetadataEditorSection.ART_LYRICS) {
                        item(key = "artwork_lyrics_on_demand") {
                            MichiMetadataArtworkLyricsSection(
                                sourceUri = target.uri, state = artLyrics,
                                onChange = { next ->
                                    artLyrics = next
                                    onDirtyChanged(draft.isDirty || next.isDirty)
                                    reviewing = false
                                },
                                canInspect = draft.editedFields.none {
                                    it == MetadataField.COMMENT || it == MetadataField.LYRICS
                                },
                                modifier = Modifier.padding(horizontal = MichiSpacing.xl),
                            )
                        }
                    }
                    val visibleFields = section.fields.filterNot {
                        (section == MetadataEditorSection.ART_LYRICS && it in describedFields)
                    }
                    if (section == MetadataEditorSection.BASICS) {
                        // ME-11U: the frequent identity fields come first; genre is a
                        // natural multivalue field, never a separate modal tool. When the
                        // user arrives from "Normalizar géneros", the genre field stays at
                        // the top so its actions are immediately reachable.
                        if (focusGenre) {
                            item(key = "field_genre") { renderField(MetadataField.GENRE, true) }
                        }
                        item(key = "field_title") { renderField(MetadataField.TITLE, false) }
                        item(key = "field_artist") { renderField(MetadataField.ARTIST, false) }
                        if (wide) {
                            item(key = "field_album_pair") {
                                Row(Modifier.padding(horizontal = MichiSpacing.xl),
                                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.lg)) {
                                    Box(Modifier.weight(1f)) { renderField(MetadataField.ALBUM, false) }
                                    Box(Modifier.weight(1f)) {
                                        renderField(MetadataField.ALBUM_ARTIST, false)
                                    }
                                }
                            }
                        } else {
                            item(key = "field_album") { renderField(MetadataField.ALBUM, false) }
                            item(key = "field_album_artist") {
                                renderField(MetadataField.ALBUM_ARTIST, false)
                            }
                        }
                        if (!focusGenre) {
                            item(key = "field_genre") { renderField(MetadataField.GENRE, false) }
                        }
                        item(key = "field_release_date") {
                            renderField(MetadataField.RELEASE_DATE, false)
                        }
                        if (twoColumns) {
                            item(key = "field_track_pair") {
                                Row(Modifier.padding(horizontal = MichiSpacing.xl),
                                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.lg)) {
                                    Box(Modifier.weight(1f)) {
                                        renderField(MetadataField.TRACK_NUMBER, false)
                                    }
                                    Box(Modifier.weight(1f)) {
                                        renderField(MetadataField.TRACK_TOTAL, false)
                                    }
                                }
                            }
                            item(key = "field_disc_pair") {
                                Row(Modifier.padding(horizontal = MichiSpacing.xl),
                                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.lg)) {
                                    Box(Modifier.weight(1f)) {
                                        renderField(MetadataField.DISC_NUMBER, false)
                                    }
                                    Box(Modifier.weight(1f)) {
                                        renderField(MetadataField.DISC_TOTAL, false)
                                    }
                                }
                            }
                        } else {
                            item(key = "field_track_number") {
                                renderField(MetadataField.TRACK_NUMBER, false)
                            }
                            item(key = "field_track_total") {
                                renderField(MetadataField.TRACK_TOTAL, false)
                            }
                            item(key = "field_disc_number") {
                                renderField(MetadataField.DISC_NUMBER, false)
                            }
                            item(key = "field_disc_total") {
                                renderField(MetadataField.DISC_TOTAL, false)
                            }
                        }
                        val shown = setOf(MetadataField.TITLE, MetadataField.ARTIST,
                            MetadataField.ALBUM, MetadataField.ALBUM_ARTIST,
                            MetadataField.GENRE, MetadataField.RELEASE_DATE,
                            MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
                            MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL)
                        val remaining = visibleFields.filterNot { it in shown }
                        if (wide) {
                            items(remaining.chunked(2), key = { row ->
                                row.joinToString("|") { it.name }
                            }) { row ->
                                Row(Modifier.padding(horizontal = MichiSpacing.xl),
                                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.lg)) {
                                    row.forEach { field ->
                                        Box(Modifier.weight(1f)) { renderField(field, false) }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        } else {
                            items(remaining, key = { it.name }) { field ->
                                renderField(field, false)
                            }
                        }
                    } else {
                        if (wide && section != MetadataEditorSection.ART_LYRICS) {
                            items(visibleFields.chunked(2), key = { row ->
                                row.joinToString("|") { it.name }
                            }) { row ->
                                Row(Modifier.padding(horizontal = MichiSpacing.xl),
                                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.lg)) {
                                    row.forEach { field ->
                                        Box(Modifier.weight(1f)) { renderField(field, false) }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        } else {
                            items(visibleFields, key = { it.name }) { field ->
                                renderField(field, false)
                            }
                        }
                    }
                    item(key = "end_${section.name}") {
                        Spacer(Modifier.padding(bottom = MichiSpacing.xl))
                    }
                }
                if (sectionEvidenceVisible) {
                    val detailLines = buildList {
                        add("${unverifiedSectionFields.size} campo(s) sin evidencia física verificada.")
                        unverifiedSectionFields.take(12).forEach { field ->
                            add("${metadataFieldLabel(field)}: ${evidenceReason(field)}.")
                        }
                        if (unverifiedSectionFields.size > 12) {
                            add("… y ${unverifiedSectionFields.size - 12} campo(s) más.")
                        }
                        add("Sin evidencia no se puede preparar ni escribir en el archivo.")
                    }
                    MetadataEvidenceDialog(
                        title = "Evidencia de ${metadataSectionLabel(section)}",
                        lines = detailLines,
                        tag = "metadata_section_info_dialog",
                        onDismiss = { sectionEvidenceVisible = false },
                    )
                }
            }
        }
        // The preparing coroutine must NOT live inside a LazyColumn item: scrolling
        // would dispose the item and cancel a long file copy unexpectedly. The
        // preparation subflow above owns it outside the review list.
        // With the IME open the heavy footer is hidden so the focused field and the
        // keyboard own the available height. The draft is untouched and the footer
        // returns as soon as the keyboard closes.
        if (!imeVisible) {
        Column(Modifier.fillMaxWidth().background(SurfaceObsidian)
            .padding(horizontal = MichiSpacing.xl, vertical = MichiSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
            HorizontalDivider(color = outline)
            val draftStatus = if (suggestionsVisible) {
                "Sugerencias externas · no se aplican automáticamente"
            } else if (anyDirty) {
                val changes = draft.editedFields.size + artLyrics.operations.size
                "Borrador · $changes ${if (changes == 1) "cambio" else "cambios"}"
            } else if (provisionalCount > 0) {
                "Propuesta provisional · no se revisa ni se escribe"
            } else {
                "Borrador sin cambios"
            }
            if (LocalDensity.current.fontScale > 1.3f) {
                Column(Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        draftStatus,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        "Sin escribir en el archivo",
                        color = if (highContrast) PureWhite else TertiaryCyan,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(AeroGraphite, MichiShapes.sm)
                            .border(1.dp, outline, MichiShapes.sm)
                            .padding(horizontal = MichiSpacing.sm, vertical = 4.dp)
                            .testTag("metadata_no_file_write"),
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        draftStatus,
                        color = TextSecondary, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Sin escribir en el archivo",
                        color = if (highContrast) PureWhite else TertiaryCyan,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(AeroGraphite, MichiShapes.sm)
                            .border(1.dp, outline, MichiShapes.sm)
                            .padding(horizontal = MichiSpacing.sm, vertical = 4.dp)
                            .testTag("metadata_no_file_write"),
                    )
                }
            }
            if (reviewing && !preparationVisible && privateBundleCoordinator != null) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                    TextButton(
                        onClick = { reviewing = false },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag("metadata_review_changes"),
                    ) { Text("Volver a editar") }
                    Button(
                        onClick = { preparationVisible = true },
                        enabled = draft.review is MetadataReview.Ready,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag("metadata_open_private_preparation"),
                    ) { Text("Preparar copia") }
                }
            } else {
                Button(onClick = {
                    if (suggestionsVisible) suggestionsVisible = false
                    else if (recoveryVisible) recoveryVisible = false
                    else if (reviewing && preparationVisible) preparationVisible = false
                    else reviewing = !reviewing
                },
                    enabled = anyDirty || reviewing || recoveryVisible || suggestionsVisible,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                        .testTag("metadata_review_changes")) {
                    Text(if (suggestionsVisible || recoveryVisible) "Volver a editar" else
                        if (reviewing) "Volver a editar" else "Revisar cambios →")
                }
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
    val coverSize by animateDpAsState(if (compact) 44.dp else 62.dp, label = "cover")
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
                Text(title ?: "Archivo seleccionado", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = PureWhite)
                if (!compact) {
                    Text(listOfNotNull(artist, album).joinToString(" · ")
                            .ifBlank { "Artista no verificado" },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    onOpenGenreSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
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
    var confirmDelete by remember(field, token) { mutableStateOf(false) }
    var infoVisible by remember(field, token) { mutableStateOf(false) }
    var focusRequestKey by remember(field, token) { mutableIntStateOf(0) }
    var genreInput by remember(field, token) { mutableStateOf("") }
    val focused = focusedRows.isNotEmpty()
    val rows = if (field.multiple) values else listOf(values.firstOrNull().orEmpty())
    val statusLabel = when {
        provisional -> "Propuesta provisional"
        cleared -> "Eliminación propuesta"
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
    // One menu pattern per field shape: a single-value field owns its actions in the
    // label row; each multi-value row owns its own row menu. Never both at once.
    val hasActions = !field.multiple &&
        (provisional || !editable || field in draft.editedFields ||
            (editable && values.any(String::isNotBlank)))
    val shellModifier = if (field == MetadataField.GENRE) {
        modifier.testTag("metadata_genre_tool")
    } else modifier

    MetadataFieldShell(
        field = field,
        statusLabel = statusLabel,
        statusColor = statusColor,
        focused = focused,
        modified = field in draft.editedFields,
        errorText = error?.let(::metadataDraftErrorLabel),
        modifier = shellModifier,
        hasActions = hasActions,
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
                    "Se propone eliminar esta etiqueta. Todavía no se ha escrito nada.",
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
                if (rows.isEmpty() && field == MetadataField.GENRE) {
                    Text(
                        "El archivo no declara géneros. No es un campo vacío sin verificar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                rows.forEachIndexed { index, value ->
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
                            focusRequestKey = focusRequestKey,
                            placeholder = if (provisional) "Propuesta provisional"
                            else if (field.multiple) "Añadir valor"
                            else "Añadir valor",
                            onFocusChanged = { isFocused ->
                                focusedRows = if (isFocused) focusedRows + index
                                else focusedRows - index
                            },
                            onValueChange = { changed ->
                                emitCoalesced(rows.toMutableList().also { it[index] = changed })
                            },
                        )
                        if (field.multiple) {
                            var valueMenuOpen by remember(field, token, index) {
                                mutableStateOf(false)
                            }
                            IconButton(
                                onClick = { valueMenuOpen = true },
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
                                expanded = valueMenuOpen,
                                onDismissRequest = { valueMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Quitar este valor") },
                                    modifier = Modifier.testTag("metadata_remove_${field.name.lowercase()}_$index"),
                                    onClick = {
                                        valueMenuOpen = false
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
                                        valueMenuOpen = false
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
                                        valueMenuOpen = false
                                        emitImmediate(next)
                                    },
                                )
                            }
                        }
                    }
                }
                if (field.multiple && field != MetadataField.GENRE && rows.size < 32) {
                    TextButton(
                        onClick = { emitImmediate(rows + ""); focusBack() },
                        modifier = Modifier.sizeIn(minHeight = 48.dp)
                            .testTag("metadata_add_value_${field.name.lowercase()}"),
                    ) { Text("+ Añadir", color = accent) }
                }
                if (field.multiple && editable && field in draft.editedFields) {
                    TextButton(
                        onClick = { onDraftChange(draft.keep(field)); focusBack() },
                        modifier = Modifier.sizeIn(minHeight = 48.dp)
                            .testTag("metadata_restore_${field.name.lowercase()}"),
                    ) { Text("Restablecer", color = TextSecondary) }
                }
                if (field == MetadataField.GENRE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
                    ) {
                        MetadataInlineValueField(
                            field = MetadataField.GENRE,
                            value = genreInput,
                            index = 99,
                            tag = if (provisional) "metadata_provisional_genre_input"
                            else "metadata_genre_input",
                            modifier = Modifier.weight(1f),
                            enabled = editable || provisional,
                            autoFocus = focusGenre,
                            placeholder = "Nuevo género",
                            onValueChange = { genreInput = it },
                        )
                        Button(
                            onClick = {
                                val next = genreInput.trim()
                                if (next.isNotEmpty()) {
                                    emitImmediate(rows + next)
                                    genreInput = ""
                                    focusBack()
                                }
                            },
                            enabled = genreInput.trim().isNotEmpty(),
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag(if (provisional) "metadata_provisional_genre_add"
                                else "metadata_genre_add"),
                        ) { Text("Añadir") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = onOpenGenreSuggestions,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("metadata_genre_open_tool"),
                        ) { Text("Normalizar géneros ›", color = Color(0xFFFFB3B5)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onOpenGenreSuggestions,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("metadata_genre_musicbrainz"),
                        ) { Text("MusicBrainz", color = TertiaryCyan) }
                    }
                    Text(
                        "Consulta opcional: solo se comparten título y artista; nunca rutas, audio ni tu biblioteca.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
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
            text = {
                Text("Se propondrá eliminar esta etiqueta del borrador. No se escribirá en el archivo hasta que exista certificación de escritura.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDraftChange(draft.clear(field))
                        focusBack()
                    },
                    modifier = Modifier.testTag("metadata_confirm_clear_${field.name.lowercase()}"),
                ) { Text("Proponer eliminación", color = MaterialTheme.colorScheme.error) }
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
                if (provisional) "Esta propuesta es local y no se revisa, no se prepara y no se escribe." else null,
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

@Composable
private fun MetadataReviewScreen(review: MetadataReview, artLyrics: MetadataArtLyricsUiState,
    provisional: Map<MetadataField, List<String>>,
    onBack: () -> Unit, modifier: Modifier = Modifier) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    LazyColumn(modifier.fillMaxWidth().testTag("metadata_review_panel"),
        verticalArrangement = Arrangement.spacedBy(MichiSpacing.md)) {
        item(key = "review_heading") {
            Column(Modifier.padding(MichiSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                Text("PASO 2 DE 2", style = MichiTypography.screenEyebrow, color = accent)
                Text("Antes de preparar", style = MichiTypography.screenTitle)
                Text("Revise exactamente qué cambiaría. Ninguna etiqueta se ha escrito " +
                    "en el archivo.", color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        when (review) {
            is MetadataReview.Blocked -> item(key = "review_blocked") {
                Column(Modifier.padding(horizontal = MichiSpacing.xl)) {
                    Text("Hay campos por corregir", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium)
                    Text(metadataDraftErrorLabel(review.reason), color = TextSecondary)
                    TextButton(onClick = onBack) { Text("Volver al formulario") }
                }
            }
            MetadataReview.Unchanged -> item(key = "review_unchanged") {
                Text("No hay diferencias respecto del archivo leído.",
                    modifier = Modifier.padding(MichiSpacing.xl))
            }
            is MetadataReview.Ready -> {
                item(key = "review_count") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = MichiSpacing.xl),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MichiSpacing.md)) {
                        Box(Modifier.size(36.dp).background(accent, MichiShapes.sm),
                            contentAlignment = Alignment.Center) {
                            Text("${review.diffs.size}", color = SurfaceObsidian,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                        }
                        Text(
                            if (review.diffs.size == 1) "1 campo modificado en 1 canción"
                            else "${review.diffs.size} campos modificados en 1 canción",
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(review.diffs, key = { it.field.name }) { diff ->
                    MetadataDiffCard(diff, Modifier.padding(horizontal = MichiSpacing.xl))
                }
                item(key = "review_shield") {
                    Text(
                        "Preparar crea un candidato privado verificable. No guarda estos " +
                            "cambios en la canción original.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = MichiSpacing.xl)
                            .background(AeroGraphite, MichiShapes.md)
                            .border(1.dp, AeroHairline, MichiShapes.md)
                            .padding(MichiSpacing.md),
                    )
                }
            }
        }
        if (artLyrics.operations.isNotEmpty()) {
            item(key = "typed_text_header") {
                Text("Comentarios y letras por entrada", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = MichiSpacing.xl))
            }
            when (val typedReview = artLyrics.textResult) {
                is MetadataDescribedTextWorkbench.Result.Blocked -> item(key = "typed_text_error") {
                    Text("Propuesta de texto bloqueada: ${typedReview.reason.name}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = MichiSpacing.xl))
                }
                is MetadataDescribedTextWorkbench.Result.Ready -> {
                    items(typedReview.differences, key = {
                        it.identity.kind.name + "/" + it.identity.language + "/" + it.identity.description
                    }) { diff ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = MichiSpacing.xl),
                            verticalArrangement = Arrangement.spacedBy(MichiSpacing.xs)) {
                            Text("${diff.identity.kind.frameId} · ${diff.identity.language} · " +
                                diff.identity.description.ifBlank { "sin descriptor" },
                                style = MaterialTheme.typography.titleSmall)
                            Text("ANTES · " + (diff.before ?: "Ausente"), color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall)
                            Text("PROPUESTA · " + (diff.after ?: "Eliminar solo esta entrada"),
                                color = TertiaryCyan, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                null -> Unit
            }
        }
        artLyrics.artwork?.let { proposed ->
            item(key = "artwork_proposal") {
                Column(Modifier.fillMaxWidth().padding(horizontal = MichiSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(MichiSpacing.xs)) {
                    Text("Carátula · imagen ${proposed.index + 1}",
                        style = MaterialTheme.typography.titleSmall)
                    Text("ORIGINAL · ${proposed.sourceSha256.take(16)}…",color = TextSecondary)
                    Text("PROPUESTA · ${proposed.proposedSha256.take(16)}… · " +
                        "${proposed.proposedSize / 1024} KiB", color = TertiaryCyan)
                    Text("Solo selección en memoria; no existe reemplazo binario certificado.",
                        color = TextSecondary,style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val provisionalRows = provisional.filterValues { values -> values.any(String::isNotBlank) }
        if (provisionalRows.isNotEmpty()) {
            item(key = "review_provisional") {
                Column(
                    Modifier.padding(horizontal = MichiSpacing.xl)
                        .background(AeroGraphite, MichiShapes.md)
                        .border(1.dp, Color(0xFFFFC46B), MichiShapes.md)
                        .padding(MichiSpacing.md)
                        .testTag("metadata_review_provisional"),
                    verticalArrangement = Arrangement.spacedBy(MichiSpacing.xs),
                ) {
                    Text("Propuestas provisionales", color = Color(0xFFFFC46B),
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Estos campos no tienen evidencia física. No forman parte de esta " +
                            "revisión, no se preparan y no se escribirán.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    provisionalRows.forEach { (field, values) ->
                        Text(
                            "${metadataFieldLabel(field)} · ${values.filter(String::isNotBlank).joinToString(" · ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PureWhite,
                        )
                    }
                }
            }
        }
        item(key = "no_save") {
            Text("Guardar en archivo todavía no disponible: falta certificación de escritura. " +
                "Puedes seguir revisando o cerrar y descartar el borrador.",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(MichiSpacing.xl))
        }
    }
}

@Composable
private fun MetadataDiffCard(diff: MetadataFieldDiff, modifier: Modifier) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val fill = if (highContrast) MaterialTheme.colorScheme.surfaceContainerHigh else AeroGraphite
    val edge = if (highContrast) MaterialTheme.colorScheme.outline else AeroHairline
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    Column(modifier.fillMaxWidth().background(fill, MichiShapes.md)
        .border(1.dp, edge, MichiShapes.md).padding(MichiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
        Text(metadataFieldLabel(diff.field), style = MaterialTheme.typography.titleSmall,
            color = PureWhite)
        Text("ANTES", style = MichiTypography.screenEyebrowTechnical, color = TertiaryCyan)
        Text(diff.before.joinToString(" · ").ifBlank { "Sin etiqueta" },
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text("PROPUESTA", style = MichiTypography.screenEyebrow, color = accent)
        Text(diff.after.joinToString(" · ").ifBlank { "Eliminar etiqueta" },
            style = MaterialTheme.typography.bodyMedium, color = PureWhite)
    }
}

private fun metadataSectionHelp(section: MetadataEditorSection): String = when (section) {
    MetadataEditorSection.BASICS -> "Identidad musical, artistas, álbum, género y orden del disco."
    MetadataEditorSection.CREDITS -> "Composición, intérpretes, producción y repertorio clásico."
    MetadataEditorSection.DISCOGRAPHY -> "Edición, fechas, sello y datos de publicación."
    MetadataEditorSection.ART_LYRICS -> "Carátulas observadas y edición de textos por idioma y descriptor, sin modificar el archivo."
    MetadataEditorSection.IDENTIFIERS -> "IDs de catálogo y bases musicales; no se infieren de nombres."
    MetadataEditorSection.ADVANCED -> "Etiquetas adicionales y campos técnicos disponibles."
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
