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
    data class ChooseForMosaic(val candidateId: String) : ArtistArtworkPickerIntent
    data class ChooseForCard(val candidateId: String) : ArtistArtworkPickerIntent
    data class ChooseForBoth(val candidateId: String) : ArtistArtworkPickerIntent
    data object ToggleIdentityConfirmation : ArtistArtworkPickerIntent
    data class AdjustFocus(val slot: ArtworkSlot, val x: Float, val y: Float) : ArtistArtworkPickerIntent
    data class CommitFocus(val slot: ArtworkSlot) : ArtistArtworkPickerIntent
    data object Close : ArtistArtworkPickerIntent
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
)

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

            else -> "${candidate.licenseId.orEmpty()} · revisar origen Flickr"
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
    val confirmedIdentity: Boolean = false,
    val message: String? = null,
    val savedSlots: List<ArtistArtworkPickerSavedSlotUi> = emptyList(),
    val candidates: List<ArtistArtworkPickerCandidateUi> = emptyList(),
) {
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
        get() = if (searching) "Buscando retratos…" else "Buscar otras fotografías"

    fun candidate(candidateId: String): ArtistArtworkPickerCandidateUi? =
        candidates.firstOrNull { it.stableKey == candidateId }
}

/**
 * A candidate is identified by where it came from and which asset it is, never by its position.
 * Two refreshes that return the same photo keep the same key; a filtered list keeps it too.
 */
internal fun candidateStableKey(candidate: ArtistArtworkCandidate): String =
    "${candidate.source.name.lowercase()}:${candidate.stableAssetId}"
