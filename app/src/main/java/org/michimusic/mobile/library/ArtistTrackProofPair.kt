package org.michimusic.mobile.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * D47 — two bounded content reads outside Room's write transaction.
 * Readers must enforce MAX_BYTES and close streams. This is an admission proof,
 * NOT a lock against external file replacement after the last read.
 */
internal object ArtistTrackProofPair {
    suspend fun read(
        claim: ArtistExactOccurrenceClaim,
        source: suspend (ArtistExactOccurrenceClaim) -> ArtistTrackByteProof?,
    ): ArtistTrackByteProof? {
        currentCoroutineContext().ensureActive()
        val first = source(claim) ?: return null
        currentCoroutineContext().ensureActive()
        if (!valid(claim, first)) return null
        val second = source(claim) ?: return null
        currentCoroutineContext().ensureActive()
        return second.takeIf { second == first && valid(claim, second) }
    }

    private fun valid(claim: ArtistExactOccurrenceClaim, proof: ArtistTrackByteProof): Boolean =
        Regex("[a-f0-9]{64}").matches(proof.sha256) &&
            proof.byteCount in 1L..ArtistTrackSourceProof.MAX_BYTES &&
            ArtistTrackSourceProof.consistentWithCatalog(proof, claim.localContentSignature) &&
            catalogSourceSizeMatches(proof.byteCount, claim.localContentSignature)
}
