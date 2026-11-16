package org.michimusic.mobile.artist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkService
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkResolutionReport
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * K48-R07 picker: optional, explicitly requested Openverse discovery. It never runs on
 * composition: the provider query starts only from [artist_artwork_openverse_search], and a
 * chosen candidate must pass the Flickr origin-licence check before bytes are downloaded.
 *
 * Koin, persistence and download live here so the summary content stays pure. A tap on a
 * thumbnail never saves anything: the destination buttons are the only write path, and the
 * dual action verifies each slot independently and reports both results.
 */
@Composable
fun ArtistArtworkPickerContent(
    profile: ArtistExternalProfile,
    artistCredit: String,
    libraryId: String?,
    albumIds: List<String>,
    initialSelections: Map<ArtworkSlot, ArtistArtworkSelection>,
    pendingCandidates: List<ArtistArtworkCandidate>,
    associationGroupKey: String?,
    searching: Boolean = false,
    onSearch: (() -> Unit)? = null,
    onSelectionsChanged: (Map<ArtworkSlot, ArtistArtworkSelection>) -> Unit,
    chooser: (suspend (ArtistIdentityAnchor, ArtistArtworkCandidate, ArtworkSlot) -> ArtworkResolutionReport)? = null,
) {
    if (libraryId == null) return
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coordinator = remember(context) { ArtistArtworkService.from(context) }
    val choose: suspend (ArtistIdentityAnchor, ArtistArtworkCandidate, ArtworkSlot) -> ArtworkResolutionReport =
        chooser ?: { anchor, candidate, slot -> coordinator.chooseManual(anchor, candidate, slot) }
    val anchor = remember(libraryId, artistCredit, albumIds, profile.mbid,
        profile.wikidataQid, associationGroupKey) {
        artistIdentityAnchor(libraryId, artistCredit, albumIds, profile, associationGroupKey)
    }
    if (anchor == null) return
    val scope = rememberCoroutineScope()
    val secondaryColors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryPurple)
    val secondaryBorder = BorderStroke(0.8.dp, SecondaryPurple.copy(alpha = 0.5f))
    var selections by remember(anchor) { mutableStateOf(initialSelections) }
    LaunchedEffect(initialSelections) {
        if (initialSelections.isNotEmpty()) selections = initialSelections
    }
    var busy by remember(anchor) { mutableStateOf(false) }
    var confirmedIdentity by remember(anchor, profile.mbid) { mutableStateOf(false) }
    var message by remember(anchor) { mutableStateOf<String?>(null) }
    val candidates = pendingCandidates
    // K48-S7: the picker names its job and its destinations before showing candidates.
    Text("Elegir fotografía", color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Destino: elige mosaico o ficha en cada candidata. Tocar una miniatura no guarda nada.",
        color = TextSecondary, fontSize = 12.sp,
    )
    Text("Solo se consultan cuando tú lo pides. La imagen elegida se descarga y se guarda para este artista.",
        color = TextSecondary, fontSize = 11.sp)
    selections.values.sortedBy { it.slot.ordinal }.forEach { selection ->
        val file = selection.localImagePath?.takeIf { it.isNotBlank() }?.let(::File)
        if (file != null && selection.status == ArtworkLoadStatus.READY) {
            val live = selections[selection.slot] ?: selection
            AsyncImage(
                model = file,
                contentDescription = if (selection.slot == ArtworkSlot.ARTIST_THUMB)
                    "Retrato alternativo para el mosaico de $artistCredit"
                else "Retrato alternativo para la ficha de $artistCredit",
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(live.focus.x * 2f - 1f, live.focus.y * 2f - 1f),
                modifier = if (selection.slot == ArtworkSlot.ARTIST_THUMB) {
                    Modifier.size(144.dp).clip(CircleShape)
                } else {
                    Modifier.fillMaxWidth().height(248.dp)
                        .clip(RoundedCornerShape(24.dp))
                }.testTag("artist_artwork_saved_${selection.slot.name}"),
            )
            Text("${if (selection.slot == ArtworkSlot.ARTIST_THUMB) "Mosaico" else "Ficha"}: " +
                "${selection.attribution.orEmpty()} · ${selection.licenseId.orEmpty()}",
                color = TextSecondary, fontSize = 10.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            // The regular artist page stays clean; source and licence remain accessible here.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                live.sourcePageUrl?.let { url ->
                    TextButton(
                        onClick = { uriHandler.openUri(url) },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("artist_artwork_source_${selection.slot.name}"),
                    ) { Text("Origen de la foto", color = TertiaryCyan, fontSize = 11.sp) }
                }
                live.licenseUrl?.let { url ->
                    TextButton(
                        onClick = { uriHandler.openUri(url) },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("artist_artwork_license_${selection.slot.name}"),
                    ) { Text("Licencia", color = TertiaryCyan, fontSize = 11.sp) }
                }
            }
            Text("Encuadre ${selection.slot.name.lowercase().replace('_', ' ')}: " +
                "${(live.focus.x * 100).toInt()}% / ${(live.focus.y * 100).toInt()}%",
                color = TextSecondary, fontSize = 10.sp)
            Slider(
                value = live.focus.x,
                onValueChange = { value ->
                    val current = selections[selection.slot] ?: selection
                    selections = selections + (selection.slot to
                        current.copy(focus = current.focus.copy(x = value)))
                },
                onValueChangeFinished = {
                    val current = selections[selection.slot] ?: selection
                    scope.launch {
                        try {
                            if (coordinator.updateFocus(anchor, selection.slot, current.focus)) {
                                onSelectionsChanged(selections)
                            } else {
                                val persisted = coordinator.loadSelections(anchor)
                                    .filter { it.status == ArtworkLoadStatus.READY }
                                    .associateBy { it.slot }
                                selections = persisted
                                onSelectionsChanged(persisted)
                                message = "No fue posible guardar el encuadre; se restauró el valor persistido."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = "No fue posible guardar el encuadre: ${error.message ?: "error"}"
                        }
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
                    .testTag("artist_artwork_focus_x_${selection.slot.name}"),
            )
            Slider(
                value = live.focus.y,
                onValueChange = { value ->
                    val current = selections[selection.slot] ?: selection
                    selections = selections + (selection.slot to
                        current.copy(focus = current.focus.copy(y = value)))
                },
                onValueChangeFinished = {
                    val current = selections[selection.slot] ?: selection
                    scope.launch {
                        try {
                            if (coordinator.updateFocus(anchor, selection.slot, current.focus)) {
                                onSelectionsChanged(selections)
                            } else {
                                val persisted = coordinator.loadSelections(anchor)
                                    .filter { it.status == ArtworkLoadStatus.READY }
                                    .associateBy { it.slot }
                                selections = persisted
                                onSelectionsChanged(persisted)
                                message = "No fue posible guardar el encuadre; se restauró el valor persistido."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = "No fue posible guardar el encuadre: ${error.message ?: "error"}"
                        }
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
                    .testTag("artist_artwork_focus_y_${selection.slot.name}"),
            )
        }
    }
    var consentOpen by remember(anchor, profile.mbid) { mutableStateOf(false) }
    if (consentOpen) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .testTag("artist_artwork_openverse_consent"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // K48-R12-01: the disclosure names the provider, what leaves the device and what
            // the provider learns by being contacted. Consent is worthless without it.
            Text(
                "Se enviará el nombre público de $artistCredit a Openverse para buscar fotografías. " +
                    "El proveedor podrá recibir los datos habituales de conexión. " +
                    "Las imágenes deberán verificarse antes de guardarse.",
                color = TextSecondary, fontSize = 11.sp,
            )
            TextButton(
                onClick = { consentOpen = false },
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("artist_artwork_openverse_cancel"),
            ) { Text("Cancelar", color = SecondaryPurple) }
            OutlinedButton(
                onClick = {
                    consentOpen = false
                    onSearch?.invoke()
                },
                enabled = !searching && onSearch != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("artist_artwork_openverse_confirm"),
                colors = secondaryColors,
                border = secondaryBorder,
            ) { Text("Buscar esta vez") }
        }
    } else if (onSearch != null) {
        OutlinedButton(
            onClick = { consentOpen = true },
            enabled = !searching,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("artist_artwork_openverse_search"),
            colors = secondaryColors,
            border = secondaryBorder,
        ) { Text(if (searching) "Buscando retratos…" else "Buscar otras fotografías") }
    }
    message?.let { Text(it, color = TextSecondary, fontSize = 11.sp,
        modifier = Modifier.testTag("artist_artwork_openverse_status")) }
    if (candidates.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                confirmedIdentity = !confirmedIdentity
            },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = confirmedIdentity,
                onCheckedChange = { confirmedIdentity = it },
                modifier = Modifier.testTag("artist_artwork_identity_confirm"),
            )
            Text(
                "Confirmo que la foto elegida corresponde a $artistCredit " +
                    "(MBID ${profile.mbid}). La identidad no se deduce por nombre.",
                color = TextSecondary, fontSize = 11.sp,
            )
        }
    }
    candidates.take(4).forEachIndexed { index, candidate ->
        AsyncImage(
            model = candidate.imageUrl,
            contentDescription = "Candidato de retrato ${index + 1} para $artistCredit",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(candidate.attribution.orEmpty(), color = PureWhite, fontSize = 12.sp)
        Text("${candidate.licenseId.orEmpty()} · revisar origen Flickr",
            color = TextSecondary, fontSize = 10.sp)
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
                            if (busy) return@OutlinedButton
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    val report = choose(anchor, candidate, slot)
                                    selections = report.selections
                                    onSelectionsChanged(report.selections)
                                    message = singleDestinationMessage(report.status, label)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    message = "No fue posible descargar el retrato: ${error.message ?: "error"}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && confirmedIdentity,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_artwork_choose_${slot.name}_$index"),
                        colors = secondaryColors,
                        border = secondaryBorder,
                    ) { Text("Usar en $label") }
                }
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            val forMosaic = choose(anchor, candidate, ArtworkSlot.ARTIST_THUMB)
                            val forCard = choose(anchor, candidate, ArtworkSlot.ARTIST_HERO)
                            val updated = forMosaic.selections + forCard.selections
                            selections = updated
                            onSelectionsChanged(updated)
                            message = bothDestinationsMessage(forMosaic.status, forCard.status)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = "No fue posible descargar el retrato: ${error.message ?: "error"}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && confirmedIdentity,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("artist_artwork_choose_both_$index"),
                colors = secondaryColors,
                border = secondaryBorder,
            ) { Text("Usar en ambos") }
        }
    }
}

/** K48-R07: one saved destination is reported as one saved destination. */
internal fun singleDestinationMessage(status: ArtworkLoadStatus, destination: String): String =
    when (status) {
        ArtworkLoadStatus.READY -> "Retrato guardado para $destination."
        ArtworkLoadStatus.REJECTED -> "El origen no confirma una licencia admitida."
        else -> "No fue posible guardar el retrato (${status.name.lowercase()})."
    }

/**
 * K48-R07 §9.7: "Usar en ambos" verifies each slot on its own, so a slot that failed keeps its
 * real reason and a partial result is never reported as a complete one.
 */
internal fun bothDestinationsMessage(mosaic: ArtworkLoadStatus, card: ArtworkLoadStatus): String {
    val mosaicSaved = mosaic == ArtworkLoadStatus.READY
    val cardSaved = card == ArtworkLoadStatus.READY
    return when {
        mosaicSaved && cardSaved -> "Retrato guardado en mosaico y ficha."
        mosaicSaved ->
            "Retrato guardado en mosaico. La ficha no se pudo guardar (${card.name.lowercase()})."
        cardSaved ->
            "Retrato guardado en ficha. El mosaico no se pudo guardar (${mosaic.name.lowercase()})."
        else ->
            "No fue posible guardar el retrato en ningún destino " +
                "(${mosaic.name.lowercase()} / ${card.name.lowercase()})."
    }
}
