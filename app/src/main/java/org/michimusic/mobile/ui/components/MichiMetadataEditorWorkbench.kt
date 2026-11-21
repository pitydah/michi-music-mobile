package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val target = snapshot.target
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    val outline = if (highContrast) MaterialTheme.colorScheme.outline else AeroHairline
    var draft by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(MetadataEditorDraftSession.start(snapshot))
    }
    var section by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(MetadataEditorSection.BASICS)
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
    var artLyrics by remember(target.sourceId, target.trackId, target.uri, target.evidenceToken) {
        mutableStateOf(MetadataArtLyricsUiState())
    }
    val anyDirty = draft.isDirty || artLyrics.isDirty
    fun requestExit() {
        if (anyDirty) confirmExit = true else onDismiss()
    }
    BackHandler(enabled = true) { requestExit() }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("¿Descartar los cambios?") },
            text = { Text("Hay modificaciones en el borrador. Salir no escribe etiquetas en el archivo y descartará estas propuestas.") },
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
        Modifier.fillMaxWidth().fillMaxHeight(0.90f).imePadding()
            .testTag("metadata_workbench"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = MichiSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BIBLIOTECA · ETIQUETAS", style = MichiTypography.screenEyebrow)
                    Text("Editor de metadatos", style = MichiTypography.sectionTitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { requestExit() }, modifier = Modifier.sizeIn(minHeight = 48.dp)
                    .testTag("metadata_editor_exit")) { Text("Cerrar") }
            }
            MetadataTrackSummary(snapshot, draft.editedFields.size + artLyrics.operations.size + if (artLyrics.artwork != null) 1 else 0)
            TextButton(onClick = {
                suggestionsVisible = !suggestionsVisible
                reviewing = false
                recoveryVisible = false
            }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                .testTag("metadata_musicbrainz_suggestions")) {
                Text(if (suggestionsVisible) "Volver a editar etiquetas" else
                    "Sugerencias y autocompletar · MusicBrainz")
            }
            if ((recoveryPanel != null || privateBundleCoordinator != null) && !suggestionsVisible) {
                TextButton(onClick = { recoveryVisible = !recoveryVisible; reviewing = false },
                    modifier = Modifier.sizeIn(minHeight = 48.dp)
                        .testTag("metadata_recovery_toggle")) {
                    Text(if (recoveryVisible) "Volver al editor" else "Comprobar recuperación privada")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                TextButton(onClick = {
                    val next = draft.undo()
                    draft = next
                    onDirtyChanged(next.isDirty || artLyrics.isDirty)
                    reviewing = false
                },
                    enabled = draft.canUndo, modifier = Modifier.sizeIn(minHeight = 48.dp)
                        .testTag("metadata_editor_undo")) { Text("Deshacer") }
                TextButton(onClick = {
                    val next = draft.redo()
                    draft = next
                    onDirtyChanged(next.isDirty || artLyrics.isDirty)
                    reviewing = false
                },
                    enabled = draft.canRedo, modifier = Modifier.sizeIn(minHeight = 48.dp)
                        .testTag("metadata_editor_redo")) { Text("Rehacer") }
                Spacer(Modifier.weight(1f))
                Text(if (anyDirty) "Borrador sin guardar" else "Sin cambios",
                    color = if (anyDirty) accent else TextSecondary,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!reviewing && !recoveryVisible && !suggestionsVisible) {
            ScrollableTabRow(
                selectedTabIndex = section.ordinal,
                containerColor = SurfaceObsidian,
                contentColor = PureWhite,
                edgePadding = MichiSpacing.sm,
                divider = { HorizontalDivider(color = outline) },
            ) {
                MetadataEditorSection.entries.forEach { candidate ->
                    Tab(
                        selected = candidate == section,
                        onClick = { section = candidate },
                        modifier = Modifier.sizeIn(minHeight = 48.dp)
                            .testTag("metadata_section_${candidate.name.lowercase()}"),
                        text = { Text(metadataSectionLabel(candidate),
                            color = if (candidate == section) accent else TextSecondary) },
                    )
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
            MetadataReviewScreen(draft.review, artLyrics, onBack = { reviewing = false },
                modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("metadata_fields_list"),
                verticalArrangement = Arrangement.spacedBy(MichiSpacing.md),
            ) {
                item(key = "intro_${section.name}") {
                    Column(Modifier.padding(horizontal = MichiSpacing.xl, vertical = MichiSpacing.md)) {
                        Text(metadataSectionLabel(section), style = MichiTypography.sectionTitle)
                        Text(metadataSectionHelp(section), style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary)
                        if (section == MetadataEditorSection.ART_LYRICS) {
                            Text("Inspección voluntaria disponible; todas las propuestas permanecen en memoria. La escritura física aún no está certificada.",
                                style = MaterialTheme.typography.labelMedium, color = TertiaryCyan,
                                modifier = Modifier.padding(top = MichiSpacing.sm))
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
                            canInspect = draft.editedFields.none { it == MetadataField.COMMENT ||
                                it == MetadataField.LYRICS },
                            modifier = Modifier.padding(horizontal = MichiSpacing.xl),
                        )
                    }
                }
                val describedFields = artLyrics.inspected?.takeIf { it.format != MetadataArtworkLyricsProbe.Format.FLAC }
                    ?.texts?.map { it.identity.kind.field }?.toSet().orEmpty()
                items(section.fields.filterNot { section == MetadataEditorSection.ART_LYRICS && it in describedFields },
                    key = { it.name }) { field ->
                    MetadataFieldCard(field = field, draft = draft,
                        onChange = { next ->
                            draft = next
                            onDirtyChanged(next.isDirty || artLyrics.isDirty) // same-gesture dirty gate for Back / swipe
                        },
                        modifier = Modifier.padding(horizontal = MichiSpacing.xl))
                }
                item(key = "end_${section.name}") { Spacer(Modifier.padding(bottom = MichiSpacing.xl)) }
            }
        }
        // The preparing coroutine must NOT live inside a LazyColumn item: scrolling
        // would dispose the item and cancel a long file copy unexpectedly.
        if (reviewing && privateBundleCoordinator != null) {
            MichiMetadataPrivateBundlePanel(
                original = snapshot, expectedBytes = expectedFileBytes,
                fieldReview = draft.review, artLyrics = artLyrics,
                coordinator = privateBundleCoordinator,
                stillOwns = stillOwnsPrivateSource,
                playbackDescriptorClosed = playbackDescriptorClosed,
                currentSessionAllowsPreparation = currentSessionAllowsPreparation,
            )
        }
        Column(Modifier.fillMaxWidth().background(SurfaceObsidian)
            .padding(horizontal = MichiSpacing.xl, vertical = MichiSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
            HorizontalDivider(color = outline)
            Text(if (suggestionsVisible) "Sugerencias externas · no se aplican automáticamente"
                else "Modo de revisión · Ningún archivo se modifica",
                color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Button(onClick = {
                if (suggestionsVisible) suggestionsVisible = false
                else if (recoveryVisible) recoveryVisible = false else reviewing = !reviewing
            },
                enabled = anyDirty || reviewing || recoveryVisible || suggestionsVisible,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .testTag("metadata_review_changes")) {
                Text(if (suggestionsVisible || recoveryVisible || reviewing) "Volver a editar" else
                    "Revisar ${draft.editedFields.size + artLyrics.operations.size + if (artLyrics.artwork != null) 1 else 0} cambio(s)")
            }
        }
    }
}

@Composable
private fun MetadataTrackSummary(snapshot: MetadataSnapshot, edited: Int) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val fill = if (highContrast) MaterialTheme.colorScheme.surfaceContainerHigh else AeroGraphite
    val edge = if (highContrast) MaterialTheme.colorScheme.outline else AeroHairline
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    val title = if (MetadataField.TITLE in snapshot.verifiedFields)
        snapshot.known[MetadataField.TITLE]?.firstOrNull()?.takeIf(String::isNotBlank) else null
    val artist = if (MetadataField.ARTIST in snapshot.verifiedFields)
        snapshot.known[MetadataField.ARTIST]?.firstOrNull() else null
    Column(Modifier.fillMaxWidth().background(fill, MichiShapes.md)
        .border(1.dp, edge, MichiShapes.md).padding(MichiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MichiSpacing.md)) {
            // Neutral glyph, NOT an invented album cover. No image fetching or network.
            Box(Modifier.size(56.dp).background(AeroGraphiteRaised, MichiShapes.sm),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Album, contentDescription = null, tint = accent,
                    modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title ?: "Archivo seleccionado", style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = PureWhite)
                Text(artist ?: "Artista no verificado", style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MichiSpacing.md)) {
            Text("ETIQUETAS · ${snapshot.verifiedFields.size} CAMPOS VERIFICADOS",
                style = MichiTypography.technicalPill, color = TertiaryCyan)
            if (edited > 0) Text("$edited modificados", color = accent,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MetadataFieldCard(
    field: MetadataField,
    draft: MetadataEditorDraftSession,
    onChange: (MetadataEditorDraftSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val fill = if (highContrast) MaterialTheme.colorScheme.surfaceContainerHigh else AeroGraphite
    val edge = if (highContrast) MaterialTheme.colorScheme.outline else AeroHairline
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    val editable = draft.canEdit(field)
    val values = draft.values(field)
    val cleared = draft.isCleared(field)
    val error = draft.fieldError(field)
    var confirmClear by remember(field, draft.original.target.evidenceToken) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(fill, MichiShapes.md)
        .border(1.dp, if (error == null) edge else MaterialTheme.colorScheme.error,
            MichiShapes.md).padding(MichiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(metadataFieldLabel(field), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(when {
                !editable -> "No verificable"
                cleared -> "Eliminar etiqueta"
                field in draft.editedFields -> "Modificado"
                draft.original.fieldObservations[field] == MetadataFieldObservation.VerifiedAbsent -> "Ausente comprobado"
                else -> "Verificado"
            }, color = if (!editable) TextMuted else if (field in draft.editedFields)
                accent else TertiaryCyan,
                style = MaterialTheme.typography.labelSmall)
        }
        if (!editable) {
            val message = when (draft.original.fieldObservations[field]) {
                is MetadataFieldObservation.Conflicted -> "El archivo contiene datos contradictorios para este campo."
                is MetadataFieldObservation.Unverifiable -> "El lector no puede verificar este campo con seguridad."
                else -> "No hay evidencia física suficiente para editarlo."
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            return@Column
        }
        if (cleared) {
            Text("La eliminación se propone explícitamente. No se ha guardado.",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onChange(draft.keep(field)); confirmClear = false },
                modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Conservar original") }
            TextButton(onClick = { onChange(draft.type(field, listOf(""))); confirmClear = false },
                modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Escribir otro valor") }
            return@Column
        }
        val inputs = if (values.isEmpty()) listOf("") else values
        inputs.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MichiSpacing.xs)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { text ->
                        val next = inputs.toMutableList().also { it[index] = text }
                        onChange(draft.type(field, next))
                    },
                    label = { Text(if (field.multiple) "Valor ${index + 1}" else metadataFieldLabel(field)) },
                    modifier = Modifier.weight(1f).testTag("metadata_input_${field.name.lowercase()}_$index"),
                    singleLine = field !in setOf(MetadataField.COMMENT, MetadataField.LYRICS,
                        MetadataField.SYNCED_LYRICS),
                    minLines = if (field in setOf(MetadataField.LYRICS, MetadataField.SYNCED_LYRICS)) 4 else 1,
                    maxLines = if (field in setOf(MetadataField.LYRICS, MetadataField.SYNCED_LYRICS)) 8 else 4,
                    isError = error != null,
                )
                if (field.multiple && inputs.size > 1) {
                    TextButton(
                        onClick = { onChange(draft.type(field, inputs.filterIndexed { i, _ -> i != index })) },
                        modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                            contentDescription = "Quitar valor ${index + 1} de ${metadataFieldLabel(field)}"
                        },
                    ) { Text("Quitar") }
                }
            }
        }
        if (error != null) Text(metadataDraftErrorLabel(error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("metadata_error_${field.name.lowercase()}"))
        Row(horizontalArrangement = Arrangement.spacedBy(MichiSpacing.xs),
            verticalAlignment = Alignment.CenterVertically) {
            if (field.multiple && inputs.size < 32) {
                TextButton(onClick = { onChange(draft.type(field, inputs + "")) },
                    modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("+ Añadir") }
            }
            if (field in draft.editedFields) TextButton(onClick = {
                onChange(draft.keep(field)); confirmClear = false
            }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Restablecer") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmClear = !confirmClear },
                modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Eliminar") }
        }
        if (confirmClear) {
            Column(Modifier.fillMaxWidth().background(AeroGraphiteRaised,
                RoundedCornerShape(12.dp)).padding(MichiSpacing.sm)) {
                Text("¿Proponer la eliminación de ${metadataFieldLabel(field)}?",
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                    TextButton(onClick = { confirmClear = false },
                        modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Cancelar") }
                    TextButton(onClick = { onChange(draft.clear(field)); confirmClear = false },
                        modifier = Modifier.sizeIn(minHeight = 48.dp)
                            .testTag("metadata_confirm_clear_${field.name.lowercase()}")) {
                        Text("Confirmar eliminación", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataReviewScreen(review: MetadataReview, artLyrics: MetadataArtLyricsUiState,
    onBack: () -> Unit, modifier: Modifier = Modifier) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val accent = if (highContrast) PureWhite else PrimaryPinkContainer
    LazyColumn(modifier.fillMaxWidth().testTag("metadata_review_panel"),
        verticalArrangement = Arrangement.spacedBy(MichiSpacing.md)) {
        item(key = "review_heading") {
            Column(Modifier.padding(MichiSpacing.xl)) {
                Text("Revisión de cambios", style = MichiTypography.sectionTitle)
                Text("Comparación con las etiquetas verificadas. No es una escritura en el archivo.",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall)
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
                    Text("${review.diffs.size} campo(s) cambiarían", color = accent,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = MichiSpacing.xl))
                }
                items(review.diffs, key = { it.field.name }) { diff ->
                    MetadataDiffCard(diff, Modifier.padding(horizontal = MichiSpacing.xl))
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
