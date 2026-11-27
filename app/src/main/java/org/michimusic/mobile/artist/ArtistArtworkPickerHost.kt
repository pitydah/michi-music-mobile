package org.michimusic.mobile.artist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import org.michimusic.mobile.artist.artwork.ArtistManualArtworkSnapshot
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkResolutionReport
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor
import org.michimusic.data.repository.MusicRootPreferences

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
internal fun ArtistArtworkPickerHost(
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
    onClose: () -> Unit = {},
    preferredSlot: ArtworkSlot? = null,
    onViewportChanged: (ArtistS3ViewportKey) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (libraryId == null) return
    val context = LocalContext.current
    val coordinator = remember(context) { ArtistArtworkService.from(context) }
    val rootPreferences = remember(context) { MusicRootPreferences(context.applicationContext) }
    val anchor = remember(
        libraryId, artistCredit, albumIds, profile.mbid,
        profile.wikidataQid, associationGroupKey,
    ) {
        artistIdentityAnchor(libraryId, artistCredit, albumIds, profile, associationGroupKey)
    }
    if (anchor == null) return
    // D17: the picker keeps the root generation observed when this identity was shown.
    // Reading it only after the download would authorize a stale artist on a new root.
    val rootAtPickerOpen = remember(anchor) { rootPreferences.currentRootStamp() }
    val scope = rememberCoroutineScope()
    val currentAnchor by rememberUpdatedState(anchor)
    var selections by remember(anchor) { mutableStateOf(initialSelections) }
    var busy by remember(anchor) { mutableStateOf(false) }
    var focusDraft by remember(anchor) { mutableStateOf<ArtistArtworkFocusDraft?>(null) }
    // An external choice/delete invalidates a draft by ASSET and REVISION.
    var previewCandidateId by remember(anchor) { mutableStateOf<String?>(null) }
    var previewDestination by remember(anchor) { mutableStateOf<ArtistArtworkPickerDestination?>(null) }
    var previewIdentityConfirmed by remember(anchor) { mutableStateOf(false) }
    var previewReplacementAcknowledged by remember(anchor) { mutableStateOf(false) }
    var message by remember(anchor) { mutableStateOf<String?>(null) }
    // An external selection update invalidates preview replacement approval and stale crop drafts.
    LaunchedEffect(initialSelections) {
        selections = initialSelections
        previewReplacementAcknowledged = false
        val draft = focusDraft
        if (draft != null) {
            val current = initialSelections[draft.slot]
            if (current == null || current.assetId != draft.assetId || current.revision != draft.revision ||
                current.localImagePath != draft.imagePath
            ) {
                focusDraft = null
                message = "La fotografía cambió; se descartó el encuadre sin guardar."
            }
        }
    }
    var consentOpen by remember(anchor, profile.mbid) { mutableStateOf(false) }
    var candidateOffset by remember(anchor) { mutableIntStateOf(0) }
    // Search updates invalidate the preview and its consent, even if a previous image
    // appears again later; authorization is for ONE candidate in ONE result snapshot.
    LaunchedEffect(pendingCandidates) {
        candidateOffset = 0
        previewCandidateId = null
        previewDestination = null
        previewIdentityConfirmed = false
        previewReplacementAcknowledged = false
    }

    val replacementRequired = previewDestination?.slots.orEmpty()
        .any { selections[it]?.manuallyChosen == true }
    val uiState = ArtistArtworkPickerUiState(
        artistCredit = artistCredit,
        mbid = profile.mbid.orEmpty(),
        consentOpen = consentOpen,
        searching = searching,
        busy = busy,
        canSearch = onSearch != null,
        previewCandidateId = previewCandidateId,
        previewDestination = previewDestination,
        previewIdentityConfirmed = previewIdentityConfirmed,
        previewReplacementRequired = replacementRequired,
        previewReplacementAcknowledged = previewReplacementAcknowledged,
        focusDraft = focusDraft,
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
                    assetId = selection.assetId,
                    revision = selection.revision,
                )
            },
        candidates = pendingCandidates.subList(
            artistS3GalleryWindow(pendingCandidates.size, candidateOffset).offset,
            artistS3GalleryWindow(pendingCandidates.size, candidateOffset).let { it.offset + it.size },
        ).mapIndexed { localIndex, candidate ->
            ArtistArtworkPickerCandidateUi(
                index = artistS3GalleryWindow(pendingCandidates.size, candidateOffset).offset + localIndex,
                stableKey = candidateStableKey(candidate),
                candidate = candidate,
            )
        },
        totalCandidates = pendingCandidates.size,
        candidateOffset = candidateOffset,
    )

    LaunchedEffect(uiState.viewportKey) {
        onViewportChanged(uiState.viewportKey)
    }

    fun save(candidateId: String, slots: List<ArtworkSlot>) {
        if (!uiState.canCommitPreview || previewCandidateId != candidateId ||
            previewDestination?.slots != slots
        ) return
        val candidate = uiState.candidate(candidateId)?.candidate ?: return
        val expectedSlots = slots.associateWith { slot ->
            ArtistManualArtworkSnapshot.from(selections[slot])
        }
        val replaceManualAuthorized = replacementRequired
        // Without the explicit acknowledgment, no manual image can be replaced.
        val replacementAccepted = previewReplacementAcknowledged && replaceManualAuthorized
        busy = true
        // Consume the per-candidate confirmation before starting I/O. Double taps and
        // retries cannot borrow it, even if saving one of two destinations fails.
        previewIdentityConfirmed = false
        previewReplacementAcknowledged = false
        message = null
        val startedAt = anchor
        scope.launch {
            try {
                // K48-R12-05: each destination owns its outcome, so a throw on the second one
                // never discards the selection the first one already persisted.
                val outcomes = slots.map { slot ->
                    saveToDestination(
                        chooser = { chosenAnchor, chosenCandidate, chosenSlot ->
                            // Injected chooser is a test-only seam; production enforces Room CAS.
                            chooser?.invoke(chosenAnchor, chosenCandidate, chosenSlot)
                                ?: coordinator.chooseManualGuarded(
                                    chosenAnchor, chosenCandidate, chosenSlot,
                                    expected = expectedSlots.getValue(chosenSlot),
                                    replaceManualAuthorized = replacementAccepted,
                                    expectedRootStamp = rootAtPickerOpen,
                                )
                        },
                        anchor = startedAt, candidate = candidate, slot = slot,
                    )
                }
                if (currentAnchor != startedAt) return@launch
                val merged = if (chooser == null) {
                    // A rejected stale receipt must restore current Room ownership, not a ghost preview.
                    coordinator.loadSelections(startedAt)
                        .filter { it.status == ArtworkLoadStatus.READY }
                        .associateBy { it.slot }
                } else mergeSavedSelections(selections, outcomes)
                val changedByConcurrentWrite = merged != selections
                selections = merged
                if (outcomes.any { it.saved } || (chooser == null && changedByConcurrentWrite)) {
                    onSelectionsChanged(merged)
                }
                if (outcomes.any { it.saved }) {
                    previewCandidateId = null
                    previewDestination = null
                }
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentAnchor == startedAt) {
                    message = "No se pudo comprobar el guardado. Vuelve a abrir el selector para leer Room."
                }
            } finally {
                busy = false
            }
        }
    }

    fun saveFocusDraft() {
        val draft = focusDraft ?: return
        val current = uiState.savedSlots.firstOrNull { it.slot == draft.slot }
        if (!mayCommitArtworkFocus(draft, current, busy)) return
        // Single flight protects this slot and the normal candidate save at the same time.
        busy = true
        val startedAt = anchor
        scope.launch {
            try {
                val updated = coordinator.updateFocusGuarded(
                    startedAt, draft.slot, ArtworkFocus(draft.x, draft.y),
                    draft.assetId, draft.revision,
                    expectedRootStamp = rootAtPickerOpen,
                )
                if (currentAnchor != startedAt) return@launch
                // Always publish the real Room result, never the optimistic slider draft.
                val persisted = coordinator.loadSelections(startedAt)
                    .filter { it.status == ArtworkLoadStatus.READY }
                    .associateBy { it.slot }
                focusDraft = null
                selections = persisted
                onSelectionsChanged(persisted)
                val currentSaved = persisted[draft.slot]
                message = if (updated && currentSaved != null && currentSaved.assetId == draft.assetId &&
                    currentSaved.focus.x == draft.x && currentSaved.focus.y == draft.y
                ) "Encuadre de ${if (draft.slot == ArtworkSlot.ARTIST_THUMB) "mosaico" else "ficha"} guardado."
                else "La imagen, su revisión o la raíz musical cambiaron; se restauró el encuadre guardado."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentAnchor == startedAt) {
                    focusDraft = null
                    message = "No fue posible verificar el encuadre guardado; vuelve a abrir la fotografía."
                }
            } finally {
                busy = false
            }
        }
    }

    ArtistArtworkPickerContent(
        uiState = uiState,
        onIntent = { intent ->
            when (intent) {
                ArtistArtworkPickerIntent.RequestSearch -> if (
                    !busy && !searching && onSearch != null && focusDraft == null &&
                    previewCandidateId == null && !consentOpen
                ) consentOpen = true
                ArtistArtworkPickerIntent.CancelSearch -> consentOpen = false
                ArtistArtworkPickerIntent.ConfirmSearch -> {
                    if (consentOpen && !busy && !searching && onSearch != null &&
                        focusDraft == null && previewCandidateId == null
                    ) {
                        consentOpen = false
                        onSearch.invoke()
                    }
                }
                is ArtistArtworkPickerIntent.OpenPreview -> {
                    // A tap opens preview only after the consent/search phase is closed.
                    if (busy || searching || consentOpen || focusDraft != null) return@ArtistArtworkPickerContent
                    previewCandidateId = intent.candidateId.takeIf {
                        uiState.candidate(it) != null
                    }
                    previewDestination = when (preferredSlot) {
                        ArtworkSlot.ARTIST_THUMB -> ArtistArtworkPickerDestination.MOSAIC
                        ArtworkSlot.ARTIST_HERO -> ArtistArtworkPickerDestination.CARD
                        null -> null
                    }
                    previewIdentityConfirmed = false
                    previewReplacementAcknowledged = false
                }
                is ArtistArtworkPickerIntent.SelectPreviewDestination -> {
                    if (previewCandidateId != null && !busy) {
                        previewDestination = intent.target
                        previewIdentityConfirmed = false
                        previewReplacementAcknowledged = false
                    }
                }
                ArtistArtworkPickerIntent.TogglePreviewIdentityConfirmation -> {
                    if (previewCandidateId != null && !busy) {
                        previewIdentityConfirmed = !previewIdentityConfirmed
                    }
                }
                ArtistArtworkPickerIntent.TogglePreviewReplacementAcknowledgment -> {
                    if (previewCandidateId != null && replacementRequired && !busy) {
                        previewReplacementAcknowledged = !previewReplacementAcknowledged
                    }
                }
                ArtistArtworkPickerIntent.BackToGallery -> {
                    if (!busy) {
                        previewCandidateId = null
                        previewDestination = null
                        previewIdentityConfirmed = false
                        previewReplacementAcknowledged = false
                    }
                }
                ArtistArtworkPickerIntent.SavePreview -> {
                    val chosenId = previewCandidateId
                    val chosenSlots = previewDestination?.slots
                    if (chosenId != null && chosenSlots != null) save(chosenId, chosenSlots)
                }
                ArtistArtworkPickerIntent.ShowMoreCandidates -> if (!busy && !consentOpen &&
                    previewCandidateId == null && focusDraft == null) {
                    if (uiState.hasMoreCandidates) candidateOffset = uiState.galleryWindow.nextOffset
                }
                ArtistArtworkPickerIntent.ShowPreviousCandidates -> if (!busy && !searching && !consentOpen &&
                    previewCandidateId == null && focusDraft == null && uiState.hasPreviousCandidates) {
                    candidateOffset = uiState.galleryWindow.previousOffset
                }
                is ArtistArtworkPickerIntent.OpenFocusEditor -> {
                    if (!busy && !searching && !consentOpen && previewCandidateId == null) {
                        val saved = uiState.savedSlots.firstOrNull { it.slot == intent.slot }
                        if (saved != null && saved.assetId.isNotBlank() && saved.revision >= 0) {
                            focusDraft = newArtworkFocusDraft(saved)
                            message = null
                        }
                    }
                }
                is ArtistArtworkPickerIntent.AdjustFocusDraft -> {
                    val draft = focusDraft
                    if (!busy && draft != null && intent.x.isFinite() && intent.y.isFinite()) {
                        focusDraft = draft.copy(
                            x = intent.x.coerceIn(0f, 1f), y = intent.y.coerceIn(0f, 1f),
                        )
                    }
                }
                ArtistArtworkPickerIntent.ResetFocusDraft -> {
                    if (!busy) focusDraft = focusDraft?.copy(x = 0.5f, y = 0.5f)
                }
                ArtistArtworkPickerIntent.CancelFocusDraft -> {
                    if (!busy) focusDraft = null // No database operation and no optimistic mutation.
                }
                ArtistArtworkPickerIntent.SaveFocusDraft -> saveFocusDraft()
                ArtistArtworkPickerIntent.Close -> {
                    if (!busy) {
                        consentOpen = false
                        previewCandidateId = null
                        previewDestination = null
                        previewIdentityConfirmed = false
                        previewReplacementAcknowledged = false
                        focusDraft = null
                        onClose()
                    }
                }
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
