package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.data.intelligence.MichiGenreTagWriteGateway
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * K48-R06 pure editor content.
 *
 * Renders exactly one [GenreEditorUiState] and emits [GenreEditorIntent]. It has no
 * Koin, Room, Media3 or network dependency, so every visible branch is reachable from
 * a plain state literal in a test.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenreEditorContent(
    uiState: GenreEditorUiState,
    onIntent: (GenreEditorIntent) -> Unit,
) {
    val manualFieldFocus = remember { FocusRequester() }
    val manualBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val consent = uiState.pendingConsent
    MichiGenreEditorSheet(
        onDismissRequest = {
            // A lookup in flight may be dismissed (that cancels it). A destructive
            // operation may not: it has no safe partial state.
            if (!uiState.busy || uiState.lookupInFlight) onIntent(GenreEditorIntent.Close)
        },
        trackTitle = uiState.trackTitle,
        trackArtist = uiState.trackArtist,
        state = uiState.contractState,
        writesCertified = uiState.writesCertified,
        scrollResetKey = consent,
        footerAction = { GenreEditorFooter(uiState = uiState, onIntent = onIntent) },
        body = {
            if (consent != null) {
                // K47-NORM-05: every HTTP request gets its own inline consent stage in
                // this same modal. No nested dialog, no request on composition.
                GenreNetworkConsentPanel(
                    consent = consent,
                    busy = uiState.busy,
                    onCancel = { onIntent(GenreEditorIntent.CancelNetworkRequest) },
                    onConfirm = { onIntent(GenreEditorIntent.ConfirmNetworkRequest) },
                )
            } else {
                GenreEditorStatusSection(uiState)
                GenreEditorEvidenceSection(uiState = uiState, onIntent = onIntent)
                GenreEditorSuggestionSearchSection(
                    uiState = uiState,
                    onIntent = onIntent,
                    onManualEntryRequested = {
                        scope.launch { manualBringIntoView.bringIntoView() }
                        manualFieldFocus.requestFocus()
                    },
                )
                if (uiState.busy) CircularProgressIndicator()
                if (uiState.lookupInFlight) {
                    TextButton(
                        onClick = { onIntent(GenreEditorIntent.CancelLookup) },
                        modifier = Modifier.testTag("mi27_cancel_lookup"),
                    ) { Text("Cancelar consulta") }
                }
                GenreEditorRelaxedSearchSection(uiState = uiState, onIntent = onIntent)
                GenreEditorRecordingResultsSection(uiState = uiState, onIntent = onIntent)
                GenreEditorSuggestionsSection(uiState = uiState, onIntent = onIntent)
                GenreEditorPreviewSection(
                    uiState = uiState,
                    onIntent = onIntent,
                    manualFieldFocus = manualFieldFocus,
                    manualBringIntoView = manualBringIntoView,
                )
                uiState.message?.let { Text(it) }
            }
        },
    )
}

@Composable
private fun GenreEditorFooter(
    uiState: GenreEditorUiState,
    onIntent: (GenreEditorIntent) -> Unit,
) {
    // K47-NORM-03: while WRITES_CERTIFIED is false there is no inert save button at
    // all. The preview is functional, it just never pretends tags will be written.
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
                onClick = { onIntent(GenreEditorIntent.Close) },
                enabled = !uiState.busy || uiState.lookupInFlight,
                modifier = Modifier.testTag("mi26_genre_editor_close"),
            ) { Text("Cerrar") }
            Spacer(Modifier.weight(1f))
            if (uiState.preview.isModified) {
                TextButton(
                    onClick = { onIntent(GenreEditorIntent.TogglePreview) },
                    modifier = Modifier.testTag("mi26_genre_editor_preview_toggle"),
                ) { Text("Ver previsualización") }
            }
        }
        if (uiState.draftPreviewOpen) {
            Text(
                MichiGenreEditorContract.proposedLabel(
                    uiState.tagsVerified,
                    uiState.preview.proposed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = PureWhite,
                modifier = Modifier.testTag("mi26_genre_preview_summary"),
            )
        }
    }
}

/** Pending local recovery, its blocking reasons and the explicit restore entry. */
@Composable
private fun GenreEditorStatusSection(uiState: GenreEditorUiState) {
    when (val recovery = uiState.recovery) {
        is MichiGenreTagWriteGateway.RecoveryStatus.Pending -> Text(
            "Recuperación local pendiente (${recovery.phase}). No se podrá guardar otra etiqueta " +
                "hasta restaurar o verificar la copia original. El proceso no se reanuda solo.",
        )
        is MichiGenreTagWriteGateway.RecoveryStatus.Blocked -> Text(
            "El diario de recuperación no es verificable (${recovery.code}). Edición bloqueada.",
        )
        else -> Unit
    }
}

@Composable
private fun GenreEditorEvidenceSection(
    uiState: GenreEditorUiState,
    onIntent: (GenreEditorIntent) -> Unit,
) {
    if (uiState.pendingRecoveryForThisTrack != null) {
        if (uiState.writesCertified) {
            TextButton(
                onClick = { onIntent(GenreEditorIntent.RequestRecovery) },
                enabled = !uiState.busy,
                modifier = Modifier.testTag("mi24_begin_explicit_recovery"),
            ) { Text("Restaurar copia original") }
        } else {
            Text("La copia original está protegida. La restauración requiere certificación Android.")
        }
    }
    if (uiState.tagsVerified) {
        GenreEditorSectionLabel("Género en biblioteca")
        Text(
            MichiGenreEditorContract.evidenceLabel(true, uiState.embeddedGenres),
            modifier = Modifier.testTag("mi26_genre_evidence"),
        )
    } else {
        // K47-NORM-02: the local index classification and the physical tag are two
        // independent pieces of evidence. An unavailable read is never an empty tag.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            GenreEditorSectionLabel("Género en biblioteca")
            Text(
                "En Biblioteca: " + (
                    uiState.libraryClassification?.takeIf(String::isNotBlank)
                        ?: "Sin clasificación"
                    ),
                color = PureWhite,
                modifier = Modifier.testTag("mi26_genre_library_classification"),
            )
            Text(
                uiState.classificationHint,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            GenreEditorSectionLabel(
                "Etiqueta del archivo",
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Aún no verificable",
                color = PureWhite,
                modifier = Modifier.testTag("mi26_genre_evidence"),
            )
            Text("Conservar la clasificación actual. No inventar ausencia de metadatos.")
            TextButton(
                onClick = { onIntent(GenreEditorIntent.ToggleEvidenceReason) },
                modifier = Modifier.testTag("mi26_genre_evidence_reason"),
            ) { Text("¿Por qué no se verificó?") }
            if (uiState.evidenceReasonOpen) {
                Text(
                    evidenceUnavailableLabel(uiState.evidenceReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.testTag("mi26_genre_evidence_reason_text"),
                )
            }
        }
    }
}

@Composable
private fun GenreEditorSuggestionSearchSection(
    uiState: GenreEditorUiState,
    onIntent: (GenreEditorIntent) -> Unit,
    onManualEntryRequested: () -> Unit,
) {
    val intent = uiState.searchIntent
    GenreEditorSectionLabel(
        "Encontrar sugerencias",
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        "Solo se buscará por título, artista y, si existe, álbum. Nunca se enviarán rutas, " +
            "archivos, biblioteca, historial ni gustos.",
    )
    if (intent == null) {
        Text("No hay título y artista válidos para consultar MusicBrainz.")
    } else {
        TextButton(
            onClick = { onIntent(GenreEditorIntent.SearchSuggestions) },
            enabled = !uiState.busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("mi23_preview_external_search"),
        ) { Text("Buscar en MusicBrainz") }
        // Technical detail stays folded unless the person asks for it.
        TextButton(
            onClick = { onIntent(GenreEditorIntent.ToggleTechnicalDetails) },
            modifier = Modifier.testTag("mi26_toggle_exact_fields"),
        ) {
            Text(
                if (uiState.technicalDetailsOpen) "Ocultar datos exactos"
                else "Ver datos exactos",
            )
        }
        if (uiState.technicalDetailsOpen) {
            Text("Datos exactos de búsqueda:")
            intent.outgoingFields().forEach { (key, value) -> Text("$key: $value") }
        }
    }
    TextButton(
        onClick = onManualEntryRequested,
        modifier = Modifier.testTag("mi26_open_manual_entry"),
    ) { Text("Añadir género manualmente") }
}

@Composable
private fun GenreEditorRelaxedSearchSection(
    uiState: GenreEditorUiState,
    onIntent: (GenreEditorIntent) -> Unit,
) {
    if (!uiState.relaxedSearchOffered) return
    if (uiState.searchIntent?.album?.isNotEmpty() != true) return
    Text("La búsqueda estricta (título, artista y álbum) no encontró coincidencias.")
    TextButton(
        onClick = { onIntent(GenreEditorIntent.OfferRelaxedSearch) },
        enabled = !uiState.busy,
        modifier = Modifier.testTag("mi26_open_relaxed_search"),
    ) { Text("Buscar sin álbum (segunda consulta)") }
}

@Composable
private fun GenreEditorRecordingResultsSection(
    uiState: GenreEditorUiState,
    onIntent: (GenreEditorIntent) -> Unit,
) {
    uiState.matches.forEach { choice ->
        TextButton(
            enabled = !uiState.busy,
            onClick = { onIntent(GenreEditorIntent.SelectRecording(choice.recording.mbid)) },
            modifier = Modifier.fillMaxWidth().testTag("mi23_choose_recording"),
        ) {
            Text(
                "${choice.recording.title} · ${choice.recording.artist}" +
                    if (choice.plausible) " · Coincidencia revisable" else " · Identidad incierta",
            )
        }
    }
    val selected = uiState.selectedRecording
    if (selected != null) {
        Text("Grabación elegida: ${selected.mbid}")
        Text(
            "Procedencia: " + MichiGenreNormalizationPolicy.provenanceLabel(
                MichiGenreNormalizationPolicy.provenanceOf(
                    MichiGenreNormalizationPolicy.Entity.RECORDING,
                ),
            ),
        )
    }
}

@Composable
private fun GenreEditorSuggestionsSection(
    uiState: GenreEditorUiState,
    onIntent: (GenreEditorIntent) -> Unit,
) {
    if (uiState.proposals.isEmpty()) return
    GenreEditorSectionLabel(
        "Sugerencias",
        modifier = Modifier.padding(top = 12.dp),
    )
    uiState.proposals.forEach { proposal ->
        val selected = uiState.selectedRecording
        val provenanceVerified = selected != null &&
            MichiGenreNormalizationPolicy.approvedProposal(selected, proposal, selected.mbid)
        val provenance = MichiGenreNormalizationPolicy.provenanceOf(proposal.entity)
        // One row per suggestion: the name is the action, the provenance is the
        // explanation. Votes are never the headline.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    onIntent(
                        GenreEditorIntent.AddGenre(
                            MichiGenreDisambiguationPolicy.suggestedTag(proposal.name),
                        ),
                    )
                },
                enabled = !uiState.busy && provenanceVerified,
                modifier = Modifier.weight(1f).testTag("mi23_proposed_genre"),
            ) { Text(proposal.name) }
            Text(
                if (provenanceVerified) {
                    "Procedencia: " + MichiGenreNormalizationPolicy.provenanceLabel(provenance)
                } else {
                    "Solo contexto"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.testTag("mi23_proposed_genre_origin"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreEditorPreviewSection(
    uiState: GenreEditorUiState,
    onIntent: (GenreEditorIntent) -> Unit,
    manualFieldFocus: FocusRequester,
    manualBringIntoView: BringIntoViewRequester,
) {
    val preview = uiState.preview
    GenreEditorSectionLabel(
        "Previsualización",
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        "Previsualización: no modifica el archivo.",
        modifier = Modifier.testTag("mi26_preview_only_notice"),
    )
    Text(
        MichiGenreEditorContract.originalLabel(uiState.tagsVerified, preview.original),
        modifier = Modifier.testTag("mi26_preview_original"),
    )
    Text(
        MichiGenreEditorContract.proposedLabel(uiState.tagsVerified, preview.proposed),
        modifier = Modifier.testTag("mi26_preview_proposed"),
    )
    val diff = preview.diff()
    if (diff.added.isNotEmpty()) Text("Añadido: ${diff.added.joinToString(" · ")}")
    if (diff.removed.isNotEmpty()) Text("Quitado: ${diff.removed.joinToString(" · ")}")
    // One explicit operation per value; the remaining values are preserved. Positions
    // are used because two identical labels would make a name-based edit ambiguous.
    preview.proposed.forEachIndexed { index, value ->
        Row {
            TextButton(
                onClick = { onIntent(GenreEditorIntent.RemoveGenre(index)) },
                enabled = !uiState.busy,
                modifier = Modifier.testTag("mi26_preview_remove"),
            ) { Text("Quitar") }
            TextButton(
                onClick = { onIntent(GenreEditorIntent.SelectReplaceTarget(index)) },
                enabled = !uiState.busy,
                modifier = Modifier.testTag("mi26_preview_select_replace"),
            ) { Text("Sustituir") }
            Text(value + if (uiState.replaceIndex == index) " · seleccionado" else "")
        }
    }
    OutlinedTextField(
        value = uiState.draft,
        onValueChange = { onIntent(GenreEditorIntent.EditDraft(it)) },
        singleLine = true,
        label = { Text("Género para añadir o sustituir") },
        modifier = Modifier.fillMaxWidth()
            .bringIntoViewRequester(manualBringIntoView)
            .focusRequester(manualFieldFocus)
            .testTag("mi23_manual_genre"),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { onIntent(GenreEditorIntent.AddGenre(uiState.draft)) },
            enabled = !uiState.busy && uiState.draft.isNotBlank(),
            modifier = Modifier.testTag("mi26_preview_add"),
        ) { Text("Añadir") }
        TextButton(
            onClick = {
                uiState.replaceIndex?.let {
                    onIntent(GenreEditorIntent.ReplaceGenre(it, uiState.draft))
                }
            },
            enabled = !uiState.busy && uiState.replaceIndex != null && uiState.draft.isNotBlank(),
            modifier = Modifier.testTag("mi26_preview_replace"),
        ) { Text("Sustituir seleccionado") }
        TextButton(
            onClick = { onIntent(GenreEditorIntent.ResetPreview) },
            enabled = !uiState.busy,
            modifier = Modifier.testTag("mi26_preview_keep_all"),
        ) { Text("Mantener todo") }
    }
    preview.structuralError?.let {
        Text("Previsualización no válida ($it). No se puede guardar.")
    }
    if (uiState.writesCertified && uiState.tagsVerified &&
        preview.structuralError == null && preview.isModified
    ) {
        TextButton(
            onClick = { onIntent(GenreEditorIntent.RequestWrite) },
            enabled = !uiState.busy,
            modifier = Modifier.testTag("mi23_request_write"),
        ) {
            Text(
                if (uiState.embeddedGenres.isEmpty()) "Guardar GENRE en archivo"
                else "Reemplazar GENRE existente",
            )
        }
    } else {
        Text(
            "Escritura de etiquetas deshabilitada hasta certificar MediaStore/SAF y los " +
                "formatos MP3/FLAC en Android. Esta vista solo previsualiza.",
        )
    }
    // The manual entry affordance lives in the search section but needs the field's
    // focus handles, so the caller wires it there.
}

@Composable
internal fun GenreEditorSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        letterSpacing = 1.2.sp,
        modifier = modifier,
    )
}
