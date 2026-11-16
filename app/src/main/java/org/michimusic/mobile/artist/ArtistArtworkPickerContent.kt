package org.michimusic.mobile.artist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
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
    Text(
        "Elegir fotografía",
        color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
    Text(
        "Destino: elige mosaico o ficha en cada candidata. Tocar una miniatura no guarda nada.",
        color = TextSecondary, fontSize = 12.sp,
    )
    Text(
        "Solo se consultan cuando tú lo pides. La imagen elegida se descarga y se guarda para este artista.",
        color = TextSecondary, fontSize = 11.sp,
    )
    uiState.savedSlots.forEach { saved ->
        ArtistArtworkSavedSlot(saved = saved, onIntent = onIntent)
    }
    if (uiState.consentOpen) {
        ArtistArtworkSearchConsent(
            uiState = uiState,
            onIntent = onIntent,
            secondaryColors = secondaryColors,
            secondaryBorder = secondaryBorder,
        )
    } else if (uiState.canSearch) {
        OutlinedButton(
            onClick = { onIntent(ArtistArtworkPickerIntent.RequestSearch) },
            enabled = !uiState.searching,
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
    if (uiState.candidates.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { onIntent(ArtistArtworkPickerIntent.ToggleIdentityConfirmation) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = uiState.confirmedIdentity,
                onCheckedChange = { onIntent(ArtistArtworkPickerIntent.ToggleIdentityConfirmation) },
                modifier = Modifier.testTag("artist_artwork_identity_confirm"),
            )
            Text(uiState.identityText, color = TextSecondary, fontSize = 11.sp)
        }
    }
    uiState.candidates.forEach { candidate ->
        ArtistArtworkCandidateCard(
            uiState = uiState,
            candidate = candidate,
            onIntent = onIntent,
            secondaryColors = secondaryColors,
            secondaryBorder = secondaryBorder,
        )
    }
}

/** A destination that is already stored, with the framing that was stored with it. */
@Composable
private fun ArtistArtworkSavedSlot(
    saved: ArtistArtworkPickerSavedSlotUi,
    onIntent: (ArtistArtworkPickerIntent) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AsyncImage(
        model = File(saved.imagePath),
        contentDescription = if (saved.slot == ArtworkSlot.ARTIST_THUMB) {
            "Retrato alternativo para el mosaico"
        } else {
            "Retrato alternativo para la ficha"
        },
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(saved.focusX * 2f - 1f, saved.focusY * 2f - 1f),
        modifier = if (saved.slot == ArtworkSlot.ARTIST_THUMB) {
            Modifier.size(144.dp).clip(CircleShape)
        } else {
            Modifier.fillMaxWidth().height(248.dp).clip(RoundedCornerShape(24.dp))
        }.testTag("artist_artwork_saved_${saved.slot.name}"),
    )
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
    Text("Encuadre horizontal", color = TextSecondary, fontSize = 10.sp)
    Slider(
        value = saved.focusX,
        onValueChange = { onIntent(ArtistArtworkPickerIntent.AdjustFocus(saved.slot, it, saved.focusY)) },
        onValueChangeFinished = { onIntent(ArtistArtworkPickerIntent.CommitFocus(saved.slot)) },
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth().testTag("artist_artwork_focus_x_${saved.slot.name}"),
    )
    Text("Encuadre vertical", color = TextSecondary, fontSize = 10.sp)
    Slider(
        value = saved.focusY,
        onValueChange = { onIntent(ArtistArtworkPickerIntent.AdjustFocus(saved.slot, saved.focusX, it)) },
        onValueChangeFinished = { onIntent(ArtistArtworkPickerIntent.CommitFocus(saved.slot)) },
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth().testTag("artist_artwork_focus_y_${saved.slot.name}"),
    )
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
private fun ArtistArtworkCandidateCard(
    uiState: ArtistArtworkPickerUiState,
    candidate: ArtistArtworkPickerCandidateUi,
    onIntent: (ArtistArtworkPickerIntent) -> Unit,
    secondaryColors: androidx.compose.material3.ButtonColors,
    secondaryBorder: BorderStroke,
) {
    val index = candidate.index
    AsyncImage(
        model = candidate.candidate.imageUrl,
        contentDescription = "Candidato de retrato ${index + 1} para ${uiState.artistCredit}",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
    Text(candidate.attributionLine, color = PureWhite, fontSize = 12.sp)
    Text(candidate.licenseLine, color = TextSecondary, fontSize = 10.sp)
    // Each destination is an explicit decision and the only write path. Buttons stack so a
    // 320 dp width never clips a label, and no destination is preselected for the user.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(ArtworkSlot.ARTIST_THUMB to "mosaico", ArtworkSlot.ARTIST_HERO to "ficha")
            .forEach { (slot, label) ->
                OutlinedButton(
                    onClick = {
                        onIntent(
                            if (slot == ArtworkSlot.ARTIST_THUMB) {
                                ArtistArtworkPickerIntent.ChooseForMosaic(candidate.stableKey)
                            } else {
                                ArtistArtworkPickerIntent.ChooseForCard(candidate.stableKey)
                            },
                        )
                    },
                    enabled = !uiState.busy && uiState.confirmedIdentity,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("artist_artwork_choose_${slot.name}_$index"),
                    colors = secondaryColors,
                    border = secondaryBorder,
                ) { Text("Usar en $label") }
            }
        OutlinedButton(
            onClick = { onIntent(ArtistArtworkPickerIntent.ChooseForBoth(candidate.stableKey)) },
            enabled = !uiState.busy && uiState.confirmedIdentity,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("artist_artwork_choose_both_$index"),
            colors = secondaryColors,
            border = secondaryBorder,
        ) { Text("Usar en ambos") }
    }
}
