package org.michimusic.mobile.library

/** Last observable membership and candidate set, distinct from the earlier tap snapshot. */
internal data class ArtistTrackChoiceLiveReview(
    val claim: ArtistExactOccurrenceClaim,
    val offeredMbids: List<String>,
)

/** A changed track, credit, URI, catalogue candidates or membership aborts the commit. */
internal object ArtistTrackChoiceLiveGate {
    fun matches(
        request: ArtistTrackChoiceRequest,
        tapped: ArtistExactOccurrenceClaim,
        live: ArtistTrackChoiceLiveReview?,
    ): Boolean = live != null && live.claim == tapped &&
        live.claim.libraryId == request.libraryId &&
        live.claim.albumId == request.albumId &&
        live.claim.trackId == request.trackId &&
        live.claim.literalCredit == request.literalCredit &&
        live.claim.trackIdentity == request.trackIdentity &&
        live.claim.localContentSignature.isNotBlank() &&
        live.offeredMbids == request.offeredMbids &&
        request.chosenMbid in live.offeredMbids
}
