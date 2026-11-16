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
import androidx.compose.runtime.rememberUpdatedState
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
    // K48-R12-05: a late save must never publish into another artist's picker.
    val currentAnchor by rememberUpdatedState(anchor)
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
                            val startedAt = anchor
                            scope.launch {
                                try {
                                    // K48-R12-05: a real persisted selection is published on its
                                    // own; a failed destination never erases the other slot.
                                    val outcome = saveToDestination(choose, startedAt, candidate, slot)
                                    if (currentAnchor != startedAt) return@launch
                                    val merged = mergeSavedSelections(selections, listOf(outcome))
                                    selections = merged
                                    if (outcome.saved) onSelectionsChanged(merged)
                                    message = singleDestinationMessage(outcome.status, label)
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
                    val startedAt = anchor
                    scope.launch {
                        try {
                            // K48-R12-05: two writes can half-succeed. Both outcomes survive a
                            // throw on the second destination, and the partial result is published.
                            val outcomes = saveToBothDestinations(choose, startedAt, candidate)
                            if (currentAnchor != startedAt) return@launch
                            val merged = mergeSavedSelections(selections, outcomes)
                            selections = merged
                            if (outcomes.any { it.saved }) onSelectionsChanged(merged)
                            message = bothDestinationsMessage(
                                mosaic = outcomes.first { it.slot == ArtworkSlot.ARTIST_THUMB },
                                card = outcomes.first { it.slot == ArtworkSlot.ARTIST_HERO },
                            )
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
        ArtworkLoadStatus.READY -> "Fotografía guardada para $destination."
        ArtworkLoadStatus.REJECTED -> "El origen no confirma una licencia admitida."
        else -> "No fue posible guardar la fotografía (${status.name.lowercase()})."
    }

/**
 * K48-R12-05: what actually happened to one destination.
 *
 * A save either produced a persisted selection or a reason. Keeping the two apart is what
 * lets a partial result be published instead of swallowed: the slot that succeeded still
 * carries its selection after the other slot throws.
 */
internal data class SlotSaveOutcome(
    val slot: ArtworkSlot,
    val status: ArtworkLoadStatus,
    val persistedSelection: ArtistArtworkSelection?,
    val failure: SlotSaveFailure?,
) {
    val saved: Boolean get() = persistedSelection != null && status == ArtworkLoadStatus.READY
}

internal enum class SlotSaveFailure { PROVIDER_REJECTED, DOWNLOAD_FAILED, STORAGE_FAILED, THREW }

private fun failureFor(status: ArtworkLoadStatus): SlotSaveFailure? = when (status) {
    ArtworkLoadStatus.READY -> null
    ArtworkLoadStatus.REJECTED, ArtworkLoadStatus.NO_IMAGE, ArtworkLoadStatus.NOT_REQUESTED ->
        SlotSaveFailure.PROVIDER_REJECTED
    ArtworkLoadStatus.CACHE_ERROR, ArtworkLoadStatus.PARTIAL -> SlotSaveFailure.STORAGE_FAILED
    ArtworkLoadStatus.PROVIDER_ERROR, ArtworkLoadStatus.FETCHING, ArtworkLoadStatus.IDENTITY_MISMATCH ->
        SlotSaveFailure.DOWNLOAD_FAILED
}

/**
 * Saves one candidate into one destination and reports the outcome without ever throwing a
 * provider failure at the caller. Cancellation is not a failure and is propagated untouched.
 */
internal suspend fun saveToDestination(
    chooser: suspend (ArtistIdentityAnchor, ArtistArtworkCandidate, ArtworkSlot) -> ArtworkResolutionReport,
    anchor: ArtistIdentityAnchor,
    candidate: ArtistArtworkCandidate,
    slot: ArtworkSlot,
): SlotSaveOutcome =
    try {
        val report = chooser(anchor, candidate, slot)
        val persisted = report.selections[slot]
        SlotSaveOutcome(
            slot = slot,
            status = report.status,
            persistedSelection = persisted?.takeIf { it.status == ArtworkLoadStatus.READY },
            failure = failureFor(report.status),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SlotSaveOutcome(slot, ArtworkLoadStatus.PROVIDER_ERROR, null, SlotSaveFailure.THREW)
    }

/**
 * K48-R12-05: "Usar en ambos" is two writes, so it can half-succeed. Each destination is
 * attempted independently and both outcomes survive; a throw on the second one must not
 * discard the first one that is already persisted.
 */
internal suspend fun saveToBothDestinations(
    chooser: suspend (ArtistIdentityAnchor, ArtistArtworkCandidate, ArtworkSlot) -> ArtworkResolutionReport,
    anchor: ArtistIdentityAnchor,
    candidate: ArtistArtworkCandidate,
): List<SlotSaveOutcome> = listOf(ArtworkSlot.ARTIST_THUMB, ArtworkSlot.ARTIST_HERO)
    .map { slot -> saveToDestination(chooser, anchor, candidate, slot) }

/**
 * Folds the outcomes into the visible map. Only real persisted selections are added, so a
 * failed destination never erases the one that already worked.
 */
internal fun mergeSavedSelections(
    current: Map<ArtworkSlot, ArtistArtworkSelection>,
    outcomes: List<SlotSaveOutcome>,
): Map<ArtworkSlot, ArtistArtworkSelection> =
    outcomes.fold(current) { acc, outcome ->
        outcome.persistedSelection?.let { acc + (outcome.slot to it) } ?: acc
    }

/**
 * K48-R12-05 §9.7: a partial save is reported as partial, naming the destination that worked
 * and the one that did not. The wording is fixed by the sprint contract.
 */
internal fun bothDestinationsMessage(mosaic: SlotSaveOutcome, card: SlotSaveOutcome): String =
    when {
        mosaic.saved && card.saved -> "Fotografía guardada en mosaico y ficha."
        mosaic.saved -> "Fotografía guardada en mosaico. No se pudo completar la ficha."
        card.saved -> "Fotografía guardada en ficha. No se pudo completar el mosaico."
        else -> "No se pudo guardar la fotografía en ninguno de los destinos."
    }
