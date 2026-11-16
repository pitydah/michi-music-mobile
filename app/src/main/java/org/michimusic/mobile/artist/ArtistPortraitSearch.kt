package org.michimusic.mobile.artist

import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate

/**
 * Immutable identity of one explicitly requested portrait search.
 *
 * Two libraries can hold the same artist and MBID, and the same library can hold two
 * spellings of the credit, so the receipt carries the whole anchor that produced the
 * request. A response is publishable only while its receipt is still the current one.
 */
internal data class ArtistPortraitSearchReceipt(
    val libraryId: String?,
    val artistCredit: String,
    val albumIds: List<String>,
    val associationGroupKey: String?,
    val mbid: String,
    val requestSequence: Long,
) {
    companion object {
        fun of(
            libraryId: String?,
            artistCredit: String,
            albumIds: List<String>,
            associationGroupKey: String?,
            mbid: String,
            requestSequence: Long,
        ): ArtistPortraitSearchReceipt = ArtistPortraitSearchReceipt(
            libraryId = libraryId,
            artistCredit = artistCredit,
            albumIds = albumIds.toSortedSet().toList(),
            associationGroupKey = associationGroupKey,
            mbid = mbid.lowercase(),
            requestSequence = requestSequence,
        )
    }
}

/**
 * Typed outcome of an explicit portrait search. A cancellation is never reported as a
 * failure, and an empty provider answer is a real "no match" only after a verified
 * identity was able to ask the provider.
 */
sealed interface ArtistPortraitSearchResult {
    data class Candidates(val items: List<ArtistArtworkCandidate>) : ArtistPortraitSearchResult
    data object NoMatch : ArtistPortraitSearchResult
    data object IdentityUnverified : ArtistPortraitSearchResult
    data class Failed(val reason: ArtistPortraitFailure) : ArtistPortraitSearchResult
}

/**
 * K48-R04: a closed set of causes. NoMatch means the provider answered and had nothing;
 * every other cause is a failure the user can act on, and none of them is reported as
 * "no match". Reasons never carry URLs, paths or provider exception text.
 */
enum class ArtistPortraitFailure {
    NETWORK_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    RATE_LIMITED,
    INVALID_RESPONSE,
    LICENSE_UNVERIFIED,
    UNKNOWN_FAILURE,
}

/** User-facing copy for a portrait failure. It never names a URL, path or exception. */
fun ArtistPortraitFailure.userMessage(): String = when (this) {
    ArtistPortraitFailure.NETWORK_UNAVAILABLE -> "No se pudo conectar con el proveedor."
    ArtistPortraitFailure.PROVIDER_UNAVAILABLE -> "El proveedor no respondió correctamente."
    ArtistPortraitFailure.RATE_LIMITED -> "El proveedor limitó temporalmente las consultas."
    ArtistPortraitFailure.INVALID_RESPONSE -> "El proveedor devolvió una respuesta no válida."
    ArtistPortraitFailure.LICENSE_UNVERIFIED -> "No se pudo verificar la licencia."
    ArtistPortraitFailure.UNKNOWN_FAILURE ->
        "No fue posible consultar Openverse. No se modificó ninguna foto guardada."
}
