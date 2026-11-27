package org.michimusic.mobile.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.data.artist.ArtistExplicitTrackIdentityRecord
import org.michimusic.mobile.artist.canonicalArtistMbid

/**
 * D48: a SHORT-LIVED, user-initiated proof for one current track only.
 * It is never put in Room or a route, and gives no remote/profile/artwork authority.
 * Losing the screen or changing catalogue/root invalidates it.
 */
internal data class ArtistTrackSessionProof(
    val claim: ArtistExactOccurrenceClaim,
    val confirmedMbid: String,
    val sourceContentSha256: String,
    val sourceContentBytes: Long,
    val authority: ArtistTrackChoiceAuthority,
    val rowRevision: Long,
    val confirmedAtMs: Long,
)

/** Shared in-memory limits: no silent LRU eviction of the user's explicit work.
 * Charge all variable text, including private claim fields, with a conservative UTF-16 budget.
 */
internal object ArtistTrackSessionProofBudget {
    const val MAX_PROOFS = 2_048
    private const val MAX_TEXT_UNITS = 2_097_152L

    fun fits(proofs: Collection<ArtistTrackSessionProof>): Boolean =
        within(proofs, MAX_PROOFS, MAX_TEXT_UNITS)

    fun fitsTicketPool(proofs: Collection<ArtistTrackSessionProof>): Boolean =
        within(proofs, MAX_PROOFS * 2, MAX_TEXT_UNITS * 2)

    private fun within(proofs: Collection<ArtistTrackSessionProof>, maxCount: Int, maxUnits: Long): Boolean {
        if (proofs.size > maxCount) return false
        var units = 0L
        for (proof in proofs) {
            val claim = proof.claim
            units += claim.libraryId.length.toLong() + claim.albumId.length +
                claim.albumCredit.length + claim.literalCredit.length + claim.trackIdentity.length +
                (claim.trackId?.length ?: 0) + claim.localContentSignature.length +
                proof.confirmedMbid.length + proof.sourceContentSha256.length +
                proof.authority.libraryId.length + proof.authority.sourceId.length + 64L
            if (units > maxUnits) return false
        }
        return true
    }

    fun add(
        existing: Map<ArtistExactOccurrenceKey, ArtistTrackSessionProof>,
        proof: ArtistTrackSessionProof,
        authority: ArtistTrackChoiceAuthority,
    ): Map<ArtistExactOccurrenceKey, ArtistTrackSessionProof>? {
        if (proof.authority != authority || proof.claim.libraryId != authority.libraryId) return null
        val key = ArtistExactOccurrenceKey(proof.claim.albumId, proof.claim.trackIdentity)
        val current = existing.filterValues { it.authority == authority }
        val previous = current[key]
        // Late completions cannot overwrite a newer observed Room revision.
        if (previous != null && (previous.rowRevision > proof.rowRevision ||
                (previous.rowRevision == proof.rowRevision && previous != proof))) return null
        val updated = current + (key to proof)
        return updated.takeIf { fits(it.values) }
    }
}

internal object ArtistTrackSessionProofPolicy {
    fun matches(
        row: ArtistExplicitTrackIdentityRecord,
        claim: ArtistExactOccurrenceClaim,
        authority: ArtistTrackChoiceAuthority,
    ): Boolean {
        val credit = claim.literalCredit.trim().lowercase(java.util.Locale.ROOT)
        return claim.libraryId == authority.libraryId &&
            claim.trackId != null && claim.albumId.isNotBlank() &&
            claim.localContentSignature.isNotBlank() &&
            row.libraryId == authority.libraryId && row.albumKey == claim.albumId &&
            row.trackId == claim.trackId && row.creditKey == credit &&
            row.trackKeySha256 == ArtistExplicitTrackChoicePolicy.trackDigest(claim) &&
            row.evidence == ArtistExplicitTrackChoicePolicy.EVIDENCE &&
            row.rootSourceId == authority.sourceId && row.rootEpoch == authority.rootEpoch &&
            row.catalogRevision >= 0 && authority.catalogRevision >= 0 &&
            row.revision > 0 && row.confirmedAtEpochMs > 0 &&
            canonicalArtistMbid(row.confirmedMbid) == row.confirmedMbid &&
            Regex("[a-f0-9]{64}").matches(row.sourceContentSha256) &&
            row.sourceContentBytes in 1L..ArtistTrackSourceProof.MAX_BYTES &&
            catalogSourceSizeMatches(row.sourceContentBytes, claim.localContentSignature) &&
            ArtistTrackSourceProof.consistentWithCatalog(
                ArtistTrackByteProof(row.sourceContentSha256, row.sourceContentBytes),
                claim.localContentSignature,
            )
    }

    /** Promote only an exact, previously validated receipt within this UI session. */
    fun apply(
        original: ArtistExactOccurrenceIdentityResult.Ready,
        claims: List<ArtistExactOccurrenceClaim>,
        receipts: Collection<ArtistTrackSessionProof>,
        authority: ArtistTrackChoiceAuthority,
        currentRows: List<ArtistExplicitTrackIdentityRecord>,
    ): ArtistExactOccurrenceIdentityResult.Ready {
        if (receipts.isEmpty() || !ArtistTrackSessionProofBudget.fits(receipts)) return original
        val receiptKeys = receipts.map { ArtistExactOccurrenceKey(it.claim.albumId, it.claim.trackIdentity) }
        if (receiptKeys.size != receiptKeys.distinct().size) return original
        val validClaims = claims.associateBy { ArtistExactOccurrenceKey(it.albumId, it.trackIdentity) }
        val result = original.identities.toMutableMap()
        val rowsByKey = currentRows.groupBy { Triple(it.albumKey, it.trackId, it.creditKey) }
        for (receipt in receipts) {
            if (receipt.authority != authority) continue
            val key = ArtistExactOccurrenceKey(receipt.claim.albumId, receipt.claim.trackIdentity)
            val current = rowsByKey[Triple(receipt.claim.albumId, receipt.claim.trackId,
                receipt.claim.literalCredit.trim().lowercase(java.util.Locale.ROOT))]?.singleOrNull()
                ?: continue
            if (!matches(current, receipt.claim, authority) ||
                current.revision != receipt.rowRevision ||
                current.confirmedAtEpochMs != receipt.confirmedAtMs ||
                current.confirmedMbid != receipt.confirmedMbid ||
                current.sourceContentSha256 != receipt.sourceContentSha256 ||
                current.sourceContentBytes != receipt.sourceContentBytes) continue
            if (validClaims[key] != receipt.claim || key !in result ||
                !Regex("[a-f0-9]{64}").matches(receipt.sourceContentSha256) ||
                receipt.sourceContentBytes !in 1L..ArtistTrackSourceProof.MAX_BYTES ||
                canonicalArtistMbid(receipt.confirmedMbid) != receipt.confirmedMbid ||
                !ArtistTrackSourceProof.consistentWithCatalog(
                    ArtistTrackByteProof(receipt.sourceContentSha256, receipt.sourceContentBytes),
                    receipt.claim.localContentSignature)) continue
            // Observation-only fields are preserved; never synthesize tags or alter Room.
            result[key] = result.getValue(key).copy(
                confirmedMbid = receipt.confirmedMbid,
                confirmedByExactTrackChoice = true,
            )
        }
        return ArtistExactOccurrenceIdentityResult.Ready(result)
    }
}

/** No library scan, tag read, networking, or database write. One exact receipt. */
internal class ArtistOnDemandTrackRevalidator(
    private val database: ArtistEnrichmentCacheDatabase,
    private val currentAuthority: () -> ArtistTrackChoiceAuthority?,
    private val currentClaim: () -> ArtistExactOccurrenceClaim?,
    private val currentOfferedMbids: () -> List<String>?,
    private val sourceProof: suspend (ArtistExactOccurrenceClaim) -> ArtistTrackByteProof?,
) {
    suspend fun verify(tapped: ArtistExactOccurrenceClaim): ArtistTrackSessionProof? {
        currentCoroutineContext().ensureActive()
        val before = currentAuthority() ?: return null
        if (currentClaim() != tapped || before.libraryId != tapped.libraryId) return null
        val trackId = tapped.trackId?.takeIf { it.isNotBlank() } ?: return null
        val dao = database.artistEnrichmentDao()
        val credit = tapped.literalCredit.trim().lowercase(java.util.Locale.ROOT)
        val prior = dao.explicitTrackIdentity(tapped.libraryId, tapped.albumId,
            trackId, credit) ?: return null
        if (!ArtistTrackSessionProofPolicy.matches(prior, tapped, before) ||
            prior.confirmedMbid !in (currentOfferedMbids() ?: return null)) return null
        // File I/O stays OUTSIDE Room's write transaction. Each read is bounded.
        val proof = ArtistTrackProofPair.read(tapped, sourceProof) ?: return null
        currentCoroutineContext().ensureActive()
        if (proof.sha256 != prior.sourceContentSha256 ||
            proof.byteCount != prior.sourceContentBytes ||
            currentAuthority() != before || currentClaim() != tapped) return null
        val after = dao.explicitTrackIdentity(tapped.libraryId, tapped.albumId,
            trackId, credit) ?: return null
        if (prior != after || currentAuthority() != before || currentClaim() != tapped ||
            !ArtistTrackSessionProofPolicy.matches(after, tapped, before) ||
            after.confirmedMbid !in (currentOfferedMbids() ?: return null)) return null
        return ArtistTrackSessionProof(tapped, after.confirmedMbid,
            proof.sha256, proof.byteCount, before, after.revision, after.confirmedAtEpochMs)
    }
}
