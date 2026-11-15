package org.michimusic.mobile.artist

import java.util.Locale

/** A corroborated recording/release claim is a review hint, never an identity grant. */
internal data class ArtistCreditReviewScope(
    val libraryId: String,
    val sourceId: String,
    val creditKey: String,
    val albumKey: String,
    val trackId: String,
)

internal data class ArtistCreditReviewReceipt(
    val scope: ArtistCreditReviewScope,
    val claim: MusicBrainzCreditClaim,
    val verifiedAtMs: Long,
)

internal object ArtistCreditReviewReceiptPolicy {
    const val TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

    fun create(
        scope: ArtistCreditReviewScope,
        claim: MusicBrainzCreditClaim,
        proof: MusicBrainzCreditProofResult,
        nowMs: Long,
    ): ArtistCreditReviewReceipt? {
        if (!validScope(scope) || nowMs <= 0L) return null
        val verified = proof as? MusicBrainzCreditProofResult.CorroboratedForReview ?: return null
        val artist = canonicalArtistMbid(claim.artistMbid) ?: return null
        val recording = canonicalArtistMbid(claim.recordingMbid.orEmpty()) ?: return null
        val release = canonicalArtistMbid(claim.releaseMbid.orEmpty()) ?: return null
        if (artist != claim.artistMbid || recording != claim.recordingMbid ||
            release != claim.releaseMbid || verified.artistMbid != artist ||
            verified.role != claim.role
        ) return null
        return ArtistCreditReviewReceipt(scope, claim, nowMs)
    }

    fun isCurrent(
        receipt: ArtistCreditReviewReceipt,
        expectedScope: ArtistCreditReviewScope,
        expectedClaim: MusicBrainzCreditClaim,
        nowMs: Long,
    ): Boolean = validScope(expectedScope) && receipt.scope == expectedScope &&
        receipt.claim == expectedClaim && receipt.verifiedAtMs > 0L &&
        nowMs >= receipt.verifiedAtMs &&
        nowMs - receipt.verifiedAtMs <= TTL_MS

    private fun validScope(scope: ArtistCreditReviewScope): Boolean {
        if (scope.libraryId == "SCOPE_ALL") return false
        return listOf(scope.libraryId, scope.sourceId, scope.creditKey, scope.albumKey, scope.trackId)
            .all { it.isNotBlank() && it.length <= 180 && it.none(Char::isISOControl) } &&
            scope.creditKey == scope.creditKey.trim().lowercase(Locale.ROOT) &&
            scope.creditKey.length <= 120
    }
}
