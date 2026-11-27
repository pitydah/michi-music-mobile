package org.michimusic.mobile.artist

import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource

/**
 * K48-R12-06: everything the photo picker can ask for.
 *
 * The content only emits these. Searching the provider, downloading a candidate and writing a
 * selection all live behind the host, so a render can never start a request or a write.
 */
internal sealed interface ArtistArtworkPickerIntent {
    data object RequestSearch : ArtistArtworkPickerIntent
    data object ConfirmSearch : ArtistArtworkPickerIntent
    data object CancelSearch : ArtistArtworkPickerIntent
    data class OpenPreview(val candidateId: String) : ArtistArtworkPickerIntent
    data class SelectPreviewDestination(val target: ArtistArtworkPickerDestination) : ArtistArtworkPickerIntent
    data object TogglePreviewIdentityConfirmation : ArtistArtworkPickerIntent
    data object SavePreview : ArtistArtworkPickerIntent
    data object TogglePreviewReplacementAcknowledgment : ArtistArtworkPickerIntent
    data class OpenFocusEditor(val slot: ArtworkSlot) : ArtistArtworkPickerIntent
    data class AdjustFocusDraft(val x: Float, val y: Float) : ArtistArtworkPickerIntent
    data object ResetFocusDraft : ArtistArtworkPickerIntent
    data object CancelFocusDraft : ArtistArtworkPickerIntent
    data object SaveFocusDraft : ArtistArtworkPickerIntent
    data object BackToGallery : ArtistArtworkPickerIntent
    data object ShowMoreCandidates : ArtistArtworkPickerIntent
    data object ShowPreviousCandidates : ArtistArtworkPickerIntent
    data object Close : ArtistArtworkPickerIntent
}

/** Each preview explicitly selects its destination; none is silently persisted. */
internal enum class ArtistArtworkPickerDestination(val uiLabel: String) {
    MOSAIC("Mosaico"), CARD("Ficha"), BOTH("Ambos");

    val slots: List<ArtworkSlot>
        get() = when (this) {
            MOSAIC -> listOf(ArtworkSlot.ARTIST_THUMB)
            CARD -> listOf(ArtworkSlot.ARTIST_HERO)
            BOTH -> listOf(ArtworkSlot.ARTIST_THUMB, ArtworkSlot.ARTIST_HERO)
        }
}

/**
 * One destination the user already has stored, with the framing it was saved with.
 * The path is a verified local file; the content never fetches it from anywhere else.
 */
internal data class ArtistArtworkPickerSavedSlotUi(
    val slot: ArtworkSlot,
    val label: String,
    val imagePath: String,
    val focusX: Float,
    val focusY: Float,
    val attribution: String?,
    val licenseId: String?,
    val sourcePageUrl: String?,
    /** These are receipt fields, not UI-derived guesses. */
    val assetId: String = "",
    val revision: Long = -1L,
)

/** Draft is immutable: changing a slider never changes Room or a saved selection. */
internal data class ArtistArtworkFocusDraft(
    val slot: ArtworkSlot,
    val assetId: String,
    val revision: Long,
    val imagePath: String,
    val originalX: Float,
    val originalY: Float,
    val x: Float,
    val y: Float,
    val attribution: String?,
    val licenseId: String?,
    val sourcePageUrl: String?,
)

internal fun newArtworkFocusDraft(saved: ArtistArtworkPickerSavedSlotUi) = ArtistArtworkFocusDraft(
    slot = saved.slot, assetId = saved.assetId, revision = saved.revision,
    imagePath = saved.imagePath, originalX = saved.focusX, originalY = saved.focusY,
    x = saved.focusX, y = saved.focusY, attribution = saved.attribution,
    licenseId = saved.licenseId, sourcePageUrl = saved.sourcePageUrl,
)

/** Validates stale assets, revisions, non-finite coordinates, and no-op drafts. */
internal fun mayCommitArtworkFocus(
    draft: ArtistArtworkFocusDraft?, current: ArtistArtworkPickerSavedSlotUi?, busy: Boolean,
): Boolean = draft != null && current != null && !busy &&
    draft.assetId.isNotBlank() && draft.revision >= 0 &&
    draft.slot == current.slot && draft.assetId == current.assetId &&
    draft.revision == current.revision && draft.imagePath == current.imagePath &&
    draft.x.isFinite() && draft.y.isFinite() && draft.x in 0f..1f && draft.y in 0f..1f &&
    (draft.x != draft.originalX || draft.y != draft.originalY)

/**
 * One provider candidate, carrying a stable key so an intent names the image rather than a
 * position in a list that can be reordered between the tap and the write.
 */
internal data class ArtistArtworkPickerCandidateUi(
    val index: Int,
    val stableKey: String,
    val candidate: ArtistArtworkCandidate,
) {
    val attributionLine: String get() = candidate.attribution.orEmpty()
    /**
     * TheAudioDB and fanart.tv state no licence and no author for any of their
     * photographs. The copy says exactly that instead of borrowing the wording used for
     * a verified Flickr CC.
     */
    val licenseLine: String
        get() = when (candidate.source) {
            ArtworkSource.THEAUDIODB,
            ArtworkSource.FANART_TV,
            -> "Licencia no declarada por el proveedor · revisar antes de usar"

            // Openverse aggregates Commons, museums and Flickr alike, so naming one
            // source here would send the reader to a provenance the photo may not have.
            else -> "${candidate.licenseId.orEmpty()} · revisar procedencia"
        }
}

/** K48-R12-06: the picker rendered from data alone. */
internal data class ArtistArtworkPickerUiState(
    val artistCredit: String,
    val mbid: String,
    val consentOpen: Boolean = false,
    val searching: Boolean = false,
    val busy: Boolean = false,
    val canSearch: Boolean = false,
    /** These three values are cleared on every preview change and results refresh. */
    val previewCandidateId: String? = null,
    val previewDestination: ArtistArtworkPickerDestination? = null,
    val previewIdentityConfirmed: Boolean = false,
    val previewReplacementRequired: Boolean = false,
    val previewReplacementAcknowledged: Boolean = false,
    val focusDraft: ArtistArtworkFocusDraft? = null,
    val message: String? = null,
    val savedSlots: List<ArtistArtworkPickerSavedSlotUi> = emptyList(),
    val candidates: List<ArtistArtworkPickerCandidateUi> = emptyList(),
    val totalCandidates: Int = candidates.size,
    val candidateOffset: Int = 0,
) {
    val galleryWindow: ArtistS3GalleryWindow get() = artistS3GalleryWindow(totalCandidates, candidateOffset)
    val hasMoreCandidates: Boolean get() = galleryWindow.hasNext
    val hasPreviousCandidates: Boolean get() = galleryWindow.hasPrevious
    val viewportKey: ArtistS3ViewportKey get() = when {
        focusDraft != null -> ArtistS3ViewportKey(ArtistS3PickerScene.CROP, selectedItem = focusDraft.slot.name)
        previewCandidate != null -> ArtistS3ViewportKey(ArtistS3PickerScene.PREVIEW, selectedItem = previewCandidateId)
        consentOpen -> ArtistS3ViewportKey(ArtistS3PickerScene.CONSENT)
        else -> ArtistS3ViewportKey(ArtistS3PickerScene.GALLERY, pageOffset = galleryWindow.offset)
    }
    val canCommitFocus: Boolean get() = mayCommitArtworkFocus(
        focusDraft, savedSlots.firstOrNull { it.slot == focusDraft?.slot }, busy,
    )
    val previewCandidate: ArtistArtworkPickerCandidateUi?
        get() = previewCandidateId?.let(::candidate)
    val canCommitPreview: Boolean
        get() = mayCommitArtworkPreview(
            previewCandidateId, candidates.map { it.stableKey }.toSet(),
            previewDestination, previewIdentityConfirmed, busy, searching, consentOpen,
            previewReplacementRequired, previewReplacementAcknowledged,
        )
    /** The disclosure the user has to read before any query leaves the device. */
    val consentText: String
        get() = "Se enviará el nombre público de $artistCredit a Openverse y a TheAudioDB, y su " +
            "identificador musical a fanart.tv, para buscar fotografías. Los tres proveedores " +
            "podrán recibir los datos habituales de conexión. Openverse declara la licencia de " +
            "cada imagen; las fotografías procedentes de fanart.tv y del catálogo de TheAudioDB " +
            "no declaran ninguna, así que sólo pueden guardarse tras tu elección explícita y " +
            "quedarán marcadas como licencia no declarada. fanart.tv pone a disposición sus " +
            "imágenes en fanart.tv y puedes usar tu propia clave personal en los ajustes."

    val identityText: String
        get() = "Confirmo que la foto elegida corresponde a $artistCredit (MBID $mbid). " +
            "La identidad no se deduce por nombre."

    val searchLabel: String
        get() = when {
            searching -> "Buscando fotografías…"
            // "otras" only makes sense once there is a first one.
            savedSlots.isEmpty() -> "Buscar fotografías"
            else -> "Buscar otras fotografías"
        }

    fun candidate(candidateId: String): ArtistArtworkPickerCandidateUi? =
        candidates.firstOrNull { it.stableKey == candidateId }
}

/**
 * A candidate is identified by where it came from and which asset it is, never by its position.
 * Two refreshes that return the same photo keep the same key; a filtered list keeps it too.
 */
internal fun candidateStableKey(candidate: ArtistArtworkCandidate): String =
    "${candidate.source.name.lowercase()}:${candidate.stableAssetId}"

/** Pure pagination over an already-returned list; this does not authorize HTTP. */
internal fun nextArtworkCandidateLimit(total: Int, visible: Int, step: Int = 4): Int {
    require(total >= 0 && step > 0)
    return (visible.coerceAtLeast(0) + step).coerceAtMost(total)
}

/** Pure authorization gate: the candidate must still be in the received results. */
internal fun mayCommitArtworkPreview(
    candidateId: String?, currentKeys: Set<String>,
    destination: ArtistArtworkPickerDestination?, identityConfirmed: Boolean,
    busy: Boolean, searching: Boolean, consentOpen: Boolean,
    replacementRequired: Boolean = false, replacementAcknowledged: Boolean = false,
): Boolean = candidateId != null && candidateId in currentKeys &&
    destination != null && identityConfirmed && !busy && !searching && !consentOpen &&
    (!replacementRequired || replacementAcknowledged)
