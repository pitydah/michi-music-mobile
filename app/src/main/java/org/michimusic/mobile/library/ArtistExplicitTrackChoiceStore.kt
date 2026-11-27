package org.michimusic.mobile.library

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase

/** Snapshot must be re-read after the Room insert, still inside its transaction. */
internal data class ArtistTrackChoiceAuthority(
    val libraryId: String,
    val sourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
)

/** Explicit, local-only first confirmation. Neither HTTP nor profile/artwork edits. */
internal class ArtistExplicitTrackChoiceStore(
    private val database: ArtistEnrichmentCacheDatabase,
    private val currentAuthority: () -> ArtistTrackChoiceAuthority?,
    // Mandatory live UI/catalogue view, never a copy of the request itself.
    // A stale sheet must not be able to consume a prior MBID choice.
    private val currentLiveReview: (ArtistTrackChoiceRequest) -> ArtistTrackChoiceLiveReview?,
    // D45: read actual source bytes on user action, not just MediaStore metadata.
    // Null means unreadable, oversized or untrusted; confirmation must fail closed.
    private val currentSourceProof: suspend (ArtistExactOccurrenceClaim) -> ArtistTrackByteProof?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Refused : Exception()

    suspend fun confirmFirst(
        request: ArtistTrackChoiceRequest,
        claim: ArtistExactOccurrenceClaim,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val before = currentAuthority() ?: return false
        if (before.libraryId != request.libraryId ||
            !ArtistTrackChoiceLiveGate.matches(request, claim, currentLiveReview(request))) return false
        // D47: BOTH bounded SHA-256 passes run before opening Room's write transaction.
        // A large SAF source must never hold the DB write lock while bytes are streamed.
        val byteProof = ArtistTrackProofPair.read(claim, currentSourceProof) ?: return false
        if (currentAuthority() != before ||
            !ArtistTrackChoiceLiveGate.matches(request, claim, currentLiveReview(request))) return false
        val candidate = ArtistExplicitTrackChoicePolicy.prepare(
            request, claim, before.sourceId, before.rootEpoch, before.catalogRevision, clock(), byteProof,
        ) ?: return false
        val accepted = try {
            database.withTransaction {
                // Only cheap, bounded checks and CAS writes are allowed inside Room.
                // No ContentResolver, SAF, FileInputStream or hashing in this block.
                if (currentAuthority() != before ||
                    !ArtistTrackChoiceLiveGate.matches(request, claim, currentLiveReview(request)))
                    throw Refused()
                val dao = database.artistEnrichmentDao()
                val previous = dao.explicitTrackIdentity(candidate.libraryId, candidate.albumKey,
                    candidate.trackId, candidate.creditKey)
                if (previous == null) {
                    dao.insertExplicitTrackIdentity(candidate)
                } else {
                    if (previous.confirmedMbid != candidate.confirmedMbid ||
                        previous.trackKeySha256 != candidate.trackKeySha256 ||
                        previous.rootEpoch != candidate.rootEpoch ||
                        previous.rootSourceId != candidate.rootSourceId) throw Refused()
                    if (previous.sourceContentSha256.isBlank() && previous.sourceContentBytes == 0L) {
                        if (dao.reverifyLegacyExplicitTrackIdentityProof(
                            candidate.libraryId, candidate.albumKey, candidate.trackId,
                            candidate.creditKey, candidate.trackKeySha256, candidate.confirmedMbid,
                            candidate.rootSourceId, candidate.rootEpoch,
                            candidate.sourceContentSha256, candidate.sourceContentBytes, clock(),
                        ) != 1) throw Refused()
                    } else if (previous.sourceContentSha256 != candidate.sourceContentSha256 ||
                        previous.sourceContentBytes != candidate.sourceContentBytes) {
                        // Changed bytes require a separate explicit replacement workflow.
                        throw Refused()
                    }
                }
                if (currentAuthority() != before ||
                    !ArtistTrackChoiceLiveGate.matches(request, claim, currentLiveReview(request)))
                    throw Refused()
                true
            }
        } catch (_: Refused) { false }
        currentCoroutineContext().ensureActive()
        return accepted && currentAuthority() == before &&
            ArtistTrackChoiceLiveGate.matches(request, claim, currentLiveReview(request))
    }
}
