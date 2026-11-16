package org.michimusic.mobile.artist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/**
 * K48-R12-06: the photo picker host.
 *
 * Everything that can reach the network, the disk or the database lives here: the artwork
 * service, the download chooser, the framing writes and the identity/latern authority. The
 * rendered [ArtistArtworkPickerContent] only receives data and emits intents, so a
 * re-composition can never start a request or persist anything.
 *
 * R12-06 also makes [initialSelections] authoritative: an empty map is a real instruction to
 * clear the visible selection after a delete or an association change, not a no-op.
 */
@Composable
fun ArtistArtworkPickerHost(
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
    modifier: Modifier = Modifier,
) {
    if (libraryId == null) return
    val context = LocalContext.current
    val coordinator = remember(context) { ArtistArtworkService.from(context) }
    val choose: suspend (ArtistIdentityAnchor, ArtistArtworkCandidate, ArtworkSlot) -> ArtworkResolutionReport =
        chooser ?: { anchor, candidate, slot -> coordinator.chooseManual(anchor, candidate, slot) }
    val anchor = remember(
        libraryId, artistCredit, albumIds, profile.mbid,
        profile.wikidataQid, associationGroupKey,
    ) {
        artistIdentityAnchor(libraryId, artistCredit, albumIds, profile, associationGroupKey)
    }
    if (anchor == null) return
    val scope = rememberCoroutineScope()
    val currentAnchor by rememberUpdatedState(anchor)
    var selections by remember(anchor) { mutableStateOf(initialSelections) }
    // K48-R12-06: the parameter is authoritative even when it is empty.
    LaunchedEffect(initialSelections) { selections = initialSelections }
    var busy by remember(anchor) { mutableStateOf(false) }
    var confirmedIdentity by remember(anchor, profile.mbid) { mutableStateOf(false) }
    var message by remember(anchor) { mutableStateOf<String?>(null) }
    var consentOpen by remember(anchor, profile.mbid) { mutableStateOf(false) }

    val uiState = ArtistArtworkPickerUiState(
        artistCredit = artistCredit,
        mbid = profile.mbid.orEmpty(),
        consentOpen = consentOpen,
        searching = searching,
        busy = busy,
        canSearch = onSearch != null,
        confirmedIdentity = confirmedIdentity,
        message = message,
        savedSlots = selections.values
            .sortedBy { it.slot.ordinal }
            .mapNotNull { selection ->
                val path = selection.localImagePath?.takeIf { it.isNotBlank() }
                if (path == null || selection.status != ArtworkLoadStatus.READY) return@mapNotNull null
                ArtistArtworkPickerSavedSlotUi(
                    slot = selection.slot,
                    label = if (selection.slot == ArtworkSlot.ARTIST_THUMB) "Mosaico" else "Ficha",
                    imagePath = path,
                    focusX = selection.focus.x,
                    focusY = selection.focus.y,
                    attribution = selection.attribution,
                    licenseId = selection.licenseId,
                    sourcePageUrl = selection.sourcePageUrl,
                )
            },
        candidates = pendingCandidates.take(4).mapIndexed { index, candidate ->
            ArtistArtworkPickerCandidateUi(
                index = index,
                stableKey = candidateStableKey(candidate),
                candidate = candidate,
            )
        },
    )

    fun save(candidateId: String, slots: List<ArtworkSlot>) {
        if (busy) return
        val candidate = uiState.candidate(candidateId)?.candidate ?: return
        busy = true
        message = null
        val startedAt = anchor
        scope.launch {
            try {
                // K48-R12-05: each destination owns its outcome, so a throw on the second one
                // never discards the selection the first one already persisted.
                val outcomes = slots.map { slot -> saveToDestination(choose, startedAt, candidate, slot) }
                if (currentAnchor != startedAt) return@launch
                val merged = mergeSavedSelections(selections, outcomes)
                selections = merged
                if (outcomes.any { it.saved }) onSelectionsChanged(merged)
                message = if (slots.size == 1) {
                    singleDestinationMessage(
                        status = outcomes.first().status,
                        destination = if (slots.first() == ArtworkSlot.ARTIST_THUMB) "mosaico" else "ficha",
                    )
                } else {
                    bothDestinationsMessage(
                        mosaic = outcomes.first { it.slot == ArtworkSlot.ARTIST_THUMB },
                        card = outcomes.first { it.slot == ArtworkSlot.ARTIST_HERO },
                    )
                }
            } finally {
                busy = false
            }
        }
    }

    fun commitFocus(slot: ArtworkSlot) {
        val current = selections[slot] ?: return
        val startedAt = anchor
        scope.launch {
            try {
                if (coordinator.updateFocus(startedAt, slot, current.focus)) {
                    if (currentAnchor == startedAt) onSelectionsChanged(selections)
                } else {
                    val persisted = coordinator.loadSelections(startedAt)
                        .filter { it.status == ArtworkLoadStatus.READY }
                        .associateBy { it.slot }
                    if (currentAnchor != startedAt) return@launch
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
    }

    ArtistArtworkPickerContent(
        uiState = uiState,
        onIntent = { intent ->
            when (intent) {
                ArtistArtworkPickerIntent.RequestSearch -> consentOpen = true
                ArtistArtworkPickerIntent.CancelSearch -> consentOpen = false
                ArtistArtworkPickerIntent.ConfirmSearch -> {
                    consentOpen = false
                    onSearch?.invoke()
                }
                is ArtistArtworkPickerIntent.ChooseForMosaic ->
                    save(intent.candidateId, listOf(ArtworkSlot.ARTIST_THUMB))
                is ArtistArtworkPickerIntent.ChooseForCard ->
                    save(intent.candidateId, listOf(ArtworkSlot.ARTIST_HERO))
                is ArtistArtworkPickerIntent.ChooseForBoth ->
                    save(intent.candidateId, listOf(ArtworkSlot.ARTIST_THUMB, ArtworkSlot.ARTIST_HERO))
                ArtistArtworkPickerIntent.ToggleIdentityConfirmation ->
                    confirmedIdentity = !confirmedIdentity
                is ArtistArtworkPickerIntent.AdjustFocus -> {
                    val current = selections[intent.slot] ?: return@ArtistArtworkPickerContent
                    selections = selections + (
                        intent.slot to current.copy(focus = current.focus.copy(x = intent.x, y = intent.y))
                        )
                }
                is ArtistArtworkPickerIntent.CommitFocus -> commitFocus(intent.slot)
                ArtistArtworkPickerIntent.Close -> consentOpen = false
            }
        },
        modifier = modifier,
    )
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
 * One destination, one outcome. A throw becomes a reported failure instead of escaping, so the
 * caller can still publish whatever the other destination managed to save.
 */
internal suspend fun saveToDestination(
    chooser: suspend (ArtistIdentityAnchor, ArtistArtworkCandidate, ArtworkSlot) -> ArtworkResolutionReport,
    anchor: ArtistIdentityAnchor,
    candidate: ArtistArtworkCandidate,
    slot: ArtworkSlot,
): SlotSaveOutcome {
    val report = try {
        chooser(anchor, candidate, slot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return SlotSaveOutcome(slot, ArtworkLoadStatus.PROVIDER_ERROR, null, SlotSaveFailure.THREW)
    }
    val saved = report.selections[slot]?.takeIf { it.status == ArtworkLoadStatus.READY }
    return SlotSaveOutcome(
        slot = slot,
        status = report.status,
        persistedSelection = saved,
        failure = if (saved != null) null else failureFor(report.status),
    )
}

/** Two destinations, two independent outcomes. The second cannot invalidate the first. */
internal suspend fun saveToBothDestinations(
    chooser: suspend (ArtistIdentityAnchor, ArtistArtworkCandidate, ArtworkSlot) -> ArtworkResolutionReport,
    anchor: ArtistIdentityAnchor,
    candidate: ArtistArtworkCandidate,
): List<SlotSaveOutcome> = listOf(ArtworkSlot.ARTIST_THUMB, ArtworkSlot.ARTIST_HERO).map { slot ->
    saveToDestination(chooser, anchor, candidate, slot)
}

/** Folds only the selections that were really persisted; a failure never erases the other slot. */
internal fun mergeSavedSelections(
    current: Map<ArtworkSlot, ArtistArtworkSelection>,
    outcomes: List<SlotSaveOutcome>,
): Map<ArtworkSlot, ArtistArtworkSelection> {
    var merged = current
    outcomes.forEach { outcome ->
        outcome.persistedSelection?.let { merged = merged + (outcome.slot to it) }
    }
    return merged
}

/** K48-R07: one saved destination is reported as one saved destination. */
internal fun singleDestinationMessage(status: ArtworkLoadStatus, destination: String): String =
    when (status) {
        ArtworkLoadStatus.READY -> "Fotografía guardada para $destination."
        ArtworkLoadStatus.REJECTED -> "El origen no confirma una licencia admitida."
        else -> "No fue posible guardar la fotografía (${status.name.lowercase()})."
    }

/** K48-R12-05: a partial save is reported as partial, naming the half that survived. */
internal fun bothDestinationsMessage(mosaic: SlotSaveOutcome, card: SlotSaveOutcome): String =
    when {
        mosaic.saved && card.saved -> "Fotografía guardada en mosaico y ficha."
        mosaic.saved -> "Fotografía guardada en mosaico. No se pudo completar la ficha."
        card.saved -> "Fotografía guardada en ficha. No se pudo completar el mosaico."
        else -> "No se pudo guardar la fotografía en ninguno de los destinos."
    }
