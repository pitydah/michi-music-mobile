package org.michimusic.mobile.artist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * K48-R12-06: the photo picker, rendered from [ArtistArtworkPickerUiState] alone.
 *
 * There is no service, coordinator, scope launch, download or write in this file. Every
 * decision leaves as an [ArtistArtworkPickerIntent]; the host that owns the services decides
 * what happens next. That is what makes "opening the picker never queries the provider" a
 * structural fact instead of a promise.
 */
@Composable
internal fun ArtistArtworkPickerContent(
    uiState: ArtistArtworkPickerUiState,
    onIntent: (ArtistArtworkPickerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondaryColors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryPurple)
    val secondaryBorder = BorderStroke(0.8.dp, SecondaryPurple.copy(alpha = 0.5f))
    val editor = uiState.focusDraft
    ArtistS3PickerStatus(uiState)
    if (editor != null) {
        ArtistArtworkFocusEditor(uiState, editor, onIntent)
        return
    }
    val previewAtTop = uiState.previewCandidate
    if (previewAtTop != null) {
        Text("Vista previa de fotografía", color = PureWhite, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, modifier = modifier)
        ArtistArtworkCandidatePreview(
            uiState, previewAtTop, onIntent,
            secondaryColors, secondaryBorder,
        )
        return
    }
    if (uiState.consentOpen) {
        Text("Permiso de búsqueda", color = PureWhite, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, modifier = modifier)
        ArtistArtworkSearchConsent(uiState, onIntent, secondaryColors, secondaryBorder)
        return
    }
    Text(
        if (uiState.previewCandidate != null) "Vista previa de fotografía" else "Elegir fotografía",
        color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
    TextButton(
        onClick = { onIntent(ArtistArtworkPickerIntent.Close) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("artist_artwork_picker_close"),
    ) { Text("Cerrar selector", color = TertiaryCyan) }
    Text(
        "Selecciona una miniatura para verla antes de elegir dónde guardarla.",
        color = TextSecondary, fontSize = 12.sp,
    )
    Text(
        "Solo se consultan cuando tú lo pides. La imagen elegida se descarga y se guarda para este artista.",
        color = TextSecondary, fontSize = 11.sp,
    )
    uiState.savedSlots.forEach { saved ->
        ArtistArtworkSavedSlot(saved = saved, busy = uiState.busy, onIntent = onIntent)
    }
    if (uiState.canSearch) {
        OutlinedButton(
            onClick = { onIntent(ArtistArtworkPickerIntent.RequestSearch) },
            enabled = !uiState.searching && !uiState.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("artist_artwork_openverse_search"),
            colors = secondaryColors,
            border = secondaryBorder,
        ) { Text(uiState.searchLabel) }
    }
    uiState.message?.let { text ->
        Text(
            text,
            color = TextSecondary, fontSize = 11.sp,
            modifier = Modifier.testTag("artist_artwork_openverse_status"),
        )
    }
    if (uiState.totalCandidates > 0) {
        Text("Fotografías ${uiState.galleryWindow.firstNumber}–${uiState.galleryWindow.lastNumber} de ${uiState.totalCandidates}",
            color = TextSecondary, fontSize = 12.sp,
            modifier = Modifier.testTag("artist_artwork_candidate_count"))
    }
    uiState.candidates.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { candidate ->
                ArtistArtworkGalleryTile(uiState, candidate, onIntent, Modifier.weight(1f))
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (uiState.hasPreviousCandidates) {
        OutlinedButton(
            onClick = { onIntent(ArtistArtworkPickerIntent.ShowPreviousCandidates) },
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("artist_artwork_show_previous"),
            colors = secondaryColors,
            border = secondaryBorder,
        ) { Text("Fotografías anteriores") }
    }
    if (uiState.hasMoreCandidates) {
        OutlinedButton(
            onClick = { onIntent(ArtistArtworkPickerIntent.ShowMoreCandidates) },
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("artist_artwork_show_more"),
            colors = secondaryColors,
            border = secondaryBorder,
        ) { Text("Siguientes fotografías") }
    }
}

/** A destination that is already stored, with the framing that was stored with it. */
@Composable
private fun ArtistArtworkSavedSlot(
    saved: ArtistArtworkPickerSavedSlotUi,
    busy: Boolean,
    onIntent: (ArtistArtworkPickerIntent) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var unreadable by remember(saved.imagePath, saved.assetId, saved.revision) {
        mutableStateOf(false)
    }
    if (unreadable) {
        Text("La fotografía guardada no se puede mostrar. No se ha borrado.",
            color = TextSecondary, fontSize = 12.sp,
            modifier = (if (saved.slot == ArtworkSlot.ARTIST_THUMB)
                Modifier.size(144.dp).clip(CircleShape)
                else Modifier.fillMaxWidth().height(248.dp).clip(RoundedCornerShape(24.dp)))
                .testTag("artist_artwork_saved_unreadable_${saved.slot.name}"))
    } else {
    AsyncImage(
        model = File(saved.imagePath),
        contentDescription = if (saved.slot == ArtworkSlot.ARTIST_THUMB) {
            "Retrato alternativo para el mosaico"
        } else {
            "Retrato alternativo para la ficha"
        },
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(saved.focusX * 2f - 1f, saved.focusY * 2f - 1f),
        onState = { state -> if (state is AsyncImagePainter.State.Error) unreadable = true },
        modifier = if (saved.slot == ArtworkSlot.ARTIST_THUMB) {
            Modifier.size(144.dp).clip(CircleShape)
        } else {
            Modifier.fillMaxWidth().height(248.dp).clip(RoundedCornerShape(24.dp))
        }.testTag("artist_artwork_saved_${saved.slot.name}"),
    )
    }
    Text(
        "${saved.label}: ${saved.attribution.orEmpty()} · ${saved.licenseId.orEmpty()}",
        color = TextSecondary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    // The regular artist page stays clean; source and licence remain accessible here.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        saved.sourcePageUrl?.let { url ->
            TextButton(
                onClick = { uriHandler.openUri(url) },
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("artist_artwork_source_${saved.slot.name}"),
            ) { Text("Origen de la foto", color = TertiaryCyan, fontSize = 11.sp) }
        }
    }
    TextButton(
        onClick = { onIntent(ArtistArtworkPickerIntent.OpenFocusEditor(saved.slot)) },
        enabled = !busy && !unreadable && saved.assetId.isNotBlank() && saved.revision >= 0,
        modifier = Modifier.heightIn(min = 48.dp)
            .testTag("artist_artwork_focus_edit_${saved.slot.name}"),
    ) { Text("Editar encuadre · ${saved.label}", color = TertiaryCyan) }

}

/** D14. Draft editor never reaches the database until SaveFocusDraft is explicitly emitted. */
@Composable
private fun ArtistArtworkFocusEditor(
    uiState: ArtistArtworkPickerUiState,
    draft: ArtistArtworkFocusDraft,
    onIntent: (ArtistArtworkPickerIntent) -> Unit,
) {
    val label = if (draft.slot == ArtworkSlot.ARTIST_THUMB) "Mosaico" else "Ficha"
    Text("Editar encuadre · $label", color = PureWhite, fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("artist_artwork_focus_editor"))
    Text("Los cambios se previsualizan aquí. Solo Guardar modifica la imagen seleccionada.",
        color = TextSecondary, fontSize = 12.sp)
    AsyncImage(
        model = File(draft.imagePath),
        contentDescription = "Previsualización del recorte para $label",
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(draft.x * 2f - 1f, draft.y * 2f - 1f),
        modifier = (if (draft.slot == ArtworkSlot.ARTIST_THUMB) {
            Modifier.size(232.dp).clip(CircleShape)
        } else {
            Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(18.dp))
        }).testTag("artist_artwork_focus_preview"),
    )
    Text("Horizontal", color = TextSecondary, fontSize = 13.sp)
    Slider(
        value = draft.x, enabled = !uiState.busy,
        onValueChange = { onIntent(ArtistArtworkPickerIntent.AdjustFocusDraft(it, draft.y)) },
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth().testTag("artist_artwork_focus_x_${draft.slot.name}"),
    )
    Text("Vertical", color = TextSecondary, fontSize = 13.sp)
    Slider(
        value = draft.y, enabled = !uiState.busy,
        onValueChange = { onIntent(ArtistArtworkPickerIntent.AdjustFocusDraft(draft.x, it)) },
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth().testTag("artist_artwork_focus_y_${draft.slot.name}"),
    )
    Text("Fuente: ${draft.attribution.orEmpty().ifBlank { "Sin atribución declarada" }} · " +
        (draft.licenseId ?: "Licencia no declarada"),
        color = TextSecondary, fontSize = 11.sp)
    TextButton(
        onClick = { onIntent(ArtistArtworkPickerIntent.ResetFocusDraft) },
        enabled = !uiState.busy,
        modifier = Modifier.heightIn(min = 48.dp).testTag("artist_artwork_focus_reset"),
    ) { Text("Restablecer centro", color = TertiaryCyan) }
    TextButton(
        onClick = { onIntent(ArtistArtworkPickerIntent.CancelFocusDraft) },
        enabled = !uiState.busy,
        modifier = Modifier.heightIn(min = 48.dp).testTag("artist_artwork_focus_cancel"),
    ) { Text("Cancelar sin guardar", color = TertiaryCyan) }
    Button(
        onClick = { onIntent(ArtistArtworkPickerIntent.SaveFocusDraft) },
        enabled = uiState.canCommitFocus,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .testTag("artist_artwork_focus_save"),
    ) { Text(if (uiState.busy) "Guardando encuadre…" else "Guardar encuadre") }
}

/** The disclosure. Nothing leaves the device until the user confirms it. */
@Composable
private fun ArtistArtworkSearchConsent(
    uiState: ArtistArtworkPickerUiState,
    onIntent: (ArtistArtworkPickerIntent) -> Unit,
    secondaryColors: androidx.compose.material3.ButtonColors,
    secondaryBorder: BorderStroke,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("artist_artwork_openverse_consent"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(uiState.consentText, color = TextSecondary, fontSize = 11.sp)
        TextButton(
            onClick = { onIntent(ArtistArtworkPickerIntent.CancelSearch) },
            modifier = Modifier.heightIn(min = 48.dp)
                .testTag("artist_artwork_openverse_cancel"),
        ) { Text("Cancelar", color = SecondaryPurple) }
        OutlinedButton(
            onClick = { onIntent(ArtistArtworkPickerIntent.ConfirmSearch) },
            enabled = !uiState.searching && uiState.canSearch,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("artist_artwork_openverse_confirm"),
            colors = secondaryColors,
            border = secondaryBorder,
        ) { Text("Buscar esta vez") }
    }
}

@Composable
private fun ArtistArtworkGalleryTile(
    uiState: ArtistArtworkPickerUiState,
    candidate: ArtistArtworkPickerCandidateUi,
    onIntent: (ArtistArtworkPickerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(
            enabled = !uiState.busy, role = Role.Button,
        ) { onIntent(ArtistArtworkPickerIntent.OpenPreview(candidate.stableKey)) }
            .testTag("artist_artwork_gallery_${candidate.index}")
            .heightIn(min = 164.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AsyncImage(
            model = candidate.candidate.imageUrl,
            contentDescription = "Ver foto ${candidate.index + 1} de ${uiState.artistCredit}; no guardar",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(116.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(artistS3ProviderLabel(candidate.candidate.source.name), color = PureWhite, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(candidate.licenseLine, color = TextSecondary, fontSize = 10.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One candidate, one destination, one explicit identity check, one save action. */
@Composable
private fun ArtistArtworkCandidatePreview(
    uiState: ArtistArtworkPickerUiState,
    candidate: ArtistArtworkPickerCandidateUi,
    onIntent: (ArtistArtworkPickerIntent) -> Unit,
    secondaryColors: androidx.compose.material3.ButtonColors,
    secondaryBorder: BorderStroke,
) {
    TextButton(
        onClick = { onIntent(ArtistArtworkPickerIntent.BackToGallery) },
        enabled = !uiState.busy,
        modifier = Modifier.heightIn(min = 48.dp).testTag("artist_artwork_preview_back"),
    ) { Text("Volver a fotografías", color = TertiaryCyan) }
    AsyncImage(
        model = candidate.candidate.imageUrl,
        contentDescription = "Vista previa de la fotografía ${candidate.index + 1} para ${uiState.artistCredit}",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().height(272.dp)
            .clip(RoundedCornerShape(16.dp)).testTag("artist_artwork_preview_image"),
    )
    Text("Proveedor: ${artistS3ProviderLabel(candidate.candidate.source.name)}", color = PureWhite, fontSize = 12.sp)
    Text("Atribución: ${candidate.attributionLine.ifBlank { "No declarada" }}",
        color = TextSecondary, fontSize = 12.sp)
    Text(candidate.licenseLine, color = TextSecondary, fontSize = 12.sp,
        modifier = Modifier.testTag("artist_artwork_preview_license"))
    Text("La procedencia y la licencia corresponden únicamente a esta candidata. " +
        "No se aplicarán a otras fotos.", color = TextSecondary, fontSize = 12.sp,
        modifier = Modifier.testTag("artist_s3_preview_provenance_notice"))
    Text("¿Dónde quieres guardar esta fotografía?", color = PureWhite,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    // Each target has a full-width 48dp hit area, including at fontScale 2.0.
    ArtistArtworkPickerDestination.entries.forEach { target ->
        OutlinedButton(
            onClick = { onIntent(ArtistArtworkPickerIntent.SelectPreviewDestination(target)) },
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("artist_artwork_preview_target_${target.name}"),
            colors = secondaryColors, border = secondaryBorder,
        ) {
            Text(if (uiState.previewDestination == target) "✓ ${target.uiLabel}" else target.uiLabel)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(
            checked = uiState.previewIdentityConfirmed,
            enabled = !uiState.busy,
            onCheckedChange = { onIntent(ArtistArtworkPickerIntent.TogglePreviewIdentityConfirmation) },
            modifier = Modifier.testTag("artist_artwork_preview_confirm_identity"),
        )
        Text(uiState.identityText, color = TextSecondary, fontSize = 12.sp)
    }
    if (uiState.previewReplacementRequired) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = uiState.previewReplacementAcknowledged,
                enabled = !uiState.busy,
                onCheckedChange = {
                    onIntent(ArtistArtworkPickerIntent.TogglePreviewReplacementAcknowledgment)
                },
                modifier = Modifier.testTag("artist_artwork_preview_replace_confirm"),
            )
            Text("Este destino ya tiene una foto elegida manualmente. Autorizo sustituirla " +
                "solo en los destinos indicados.", color = TextSecondary, fontSize = 12.sp)
        }
    }
    ArtistS3PreviewRequirementsContent(uiState)
    Button(
        onClick = { onIntent(ArtistArtworkPickerIntent.SavePreview) },
        enabled = uiState.canCommitPreview,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .testTag("artist_artwork_preview_save"),
    ) { Text(if (uiState.busy) "Guardando fotografía…" else "Guardar fotografía") }
}
