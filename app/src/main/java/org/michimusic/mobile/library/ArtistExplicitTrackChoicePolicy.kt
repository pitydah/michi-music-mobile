package org.michimusic.mobile.library

import java.security.MessageDigest
import java.util.Locale
import org.michimusic.data.artist.ArtistExplicitTrackIdentityRecord
import org.michimusic.mobile.artist.canonicalArtistMbid

/** Only a deliberate, exact, one-track action may produce a durable confirmation. */
internal data class ArtistTrackChoiceRequest(
    val libraryId: String,
    val albumId: String,
    val trackId: String,
    val literalCredit: String,
    val trackIdentity: String,
    val chosenMbid: String,
    val offeredMbids: List<String>,
    val rootSourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
    val confirmedByUser: Boolean,
)

internal object ArtistExplicitTrackChoicePolicy {
    const val EVIDENCE = "USER_EXPLICIT_TRACK_CONFIRMATION"

    /** A stable digest only; never store a raw URI/filepath in Room or a Nav route. */
    fun trackDigest(claim: ArtistExactOccurrenceClaim): String {
        val sha = MessageDigest.getInstance("SHA-256")
        fun feed(s: String) {
            val b=s.toByteArray(Charsets.UTF_8)
            sha.update(byteArrayOf((b.size ushr 24).toByte(),(b.size ushr 16).toByte(),
                (b.size ushr 8).toByte(),b.size.toByte()))
            sha.update(b)
        }
        feed("michi.track-explicit.v1")
        feed(claim.libraryId);feed(claim.albumId);feed(claim.trackId.orEmpty())
        feed(claim.literalCredit.trim().lowercase(Locale.ROOT));feed(claim.trackIdentity)
        feed(claim.localContentSignature)
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun prepare(
        request: ArtistTrackChoiceRequest,
        exactClaim: ArtistExactOccurrenceClaim,
        currentRootSourceId: String,
        currentRootEpoch: Long,
        currentCatalogRevision: Long,
        now: Long,
        sourceProof: ArtistTrackByteProof? = null,
    ): ArtistExplicitTrackIdentityRecord? {
        // Never persist an evidence receipt without observing the source bytes.
        val proof = sourceProof ?: return null
        if (!Regex("[a-f0-9]{64}").matches(proof.sha256) ||
            proof.byteCount !in 1L..ArtistTrackSourceProof.MAX_BYTES ||
            !ArtistTrackSourceProof.consistentWithCatalog(proof, exactClaim.localContentSignature) ||
            !catalogSourceSizeMatches(proof.byteCount, exactClaim.localContentSignature))
            return null
        if (!request.confirmedByUser || request.libraryId.isBlank() || request.libraryId == "SCOPE_ALL" ||
            request.rootSourceId.isBlank() || request.rootEpoch < 0 || request.catalogRevision < 0 ||
            now <= 0 || request.albumId.isBlank() || request.trackId.isBlank() ||
            request.literalCredit != request.literalCredit.trim() || request.literalCredit.isBlank() ||
            request.trackIdentity.isBlank() || request.trackIdentity.length > 2048 ||
            request.trackId.length > 160 || request.albumId.length > 160 ||
            request.libraryId.length > 160 || request.literalCredit.length > 160 ||
            request.rootSourceId.length > 240 || request.offeredMbids.size !in 2..16 ||
            request.offeredMbids.distinct().size != request.offeredMbids.size ||
            request.offeredMbids.any { canonicalArtistMbid(it) != it } ||
            canonicalArtistMbid(request.chosenMbid) != request.chosenMbid ||
            request.chosenMbid !in request.offeredMbids
        ) return null
        if (request.rootSourceId != currentRootSourceId || request.rootEpoch != currentRootEpoch ||
            request.catalogRevision != currentCatalogRevision ||
            exactClaim.libraryId != request.libraryId || exactClaim.albumId != request.albumId ||
            exactClaim.trackId != request.trackId || exactClaim.trackIdentity != request.trackIdentity ||
            exactClaim.literalCredit.trim() != request.literalCredit ||
            exactClaim.localContentSignature.isBlank()
        ) return null
        return ArtistExplicitTrackIdentityRecord(
            libraryId=request.libraryId, albumKey=request.albumId, trackId=request.trackId,
            creditKey=request.literalCredit.lowercase(Locale.ROOT),
            trackKeySha256=trackDigest(exactClaim), confirmedMbid=request.chosenMbid,
            rootSourceId=request.rootSourceId, rootEpoch=request.rootEpoch,
            catalogRevision=request.catalogRevision, evidence=EVIDENCE,
            revision=1L, confirmedAtEpochMs=now,
            sourceContentSha256=proof.sha256, sourceContentBytes=proof.byteCount,
        )
    }

    fun matches(
        row: ArtistExplicitTrackIdentityRecord, claim: ArtistExactOccurrenceClaim,
        sourceId: String, epoch: Long, catalogRevision: Long,
    ): Boolean = row.libraryId == claim.libraryId && row.albumKey == claim.albumId &&
        row.trackId == claim.trackId && row.creditKey == claim.literalCredit.trim().lowercase(Locale.ROOT) &&
        row.trackKeySha256 == trackDigest(claim) && row.rootSourceId == sourceId &&
        // D46: a stored metadata fingerprint is NOT the byte proof. After a restart
        // passive presentation requires a strong SHA-256 carried by the authoritative
        // current catalogue; absent it, the choice stays stored but unverified.
        Regex("[a-f0-9]{64}").matches(row.sourceContentSha256) &&
        row.sourceContentBytes in 1L..ArtistTrackSourceProof.MAX_BYTES &&
        strongCatalogProofMatches(row.sourceContentSha256, row.sourceContentBytes,
            claim.localContentSignature) &&
        // Root epoch and file fingerprint survive process restart. The in-memory
        // catalog revision is an admission CAS only, not a persistent version source.
        row.rootEpoch == epoch && row.catalogRevision >= 0L && catalogRevision >= 0L &&
        row.evidence == EVIDENCE && row.revision > 0L && row.confirmedAtEpochMs > 0L &&
        canonicalArtistMbid(row.confirmedMbid) == row.confirmedMbid
}

/** Verify declared size when present even if catalogue lacks a strong hash. */
internal fun catalogSourceSizeMatches(actual: Long, signature: String): Boolean {
    val declared = signature.split('\u0000', limit = 3).getOrNull(1)?.toLongOrNull()
    return declared == null || declared <= 0 || declared == actual
}

/** A local selector may report durable verification only with current strong evidence. */
internal fun strongCatalogHashIsPresent(signature: String): Boolean {
    val parts = signature.split('\u0000', limit = 3)
    return Regex("[a-f0-9]{64}").matches(parts.firstOrNull().orEmpty().trim().lowercase(java.util.Locale.ROOT)) &&
        (parts.getOrNull(1)?.toLongOrNull() ?: 0L) > 0L
}

/** Passive reads must fail closed where the current catalogue cannot vouch for bytes. */
internal fun strongCatalogProofMatches(sha: String, byteCount: Long, signature: String): Boolean {
    val parts = signature.split('\u0000', limit = 3)
    val trusted = parts.firstOrNull()?.trim()?.lowercase(java.util.Locale.ROOT)
    val size = parts.getOrNull(1)?.toLongOrNull()
    return trusted != null && Regex("[a-f0-9]{64}").matches(trusted) &&
        trusted == sha && size != null && size > 0 && size == byteCount
}
