package org.michimusic.core.sync

import org.michimusic.core.models.LocalSyncTrackEvidence
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.SyncIdentityVerdict

/** Result of classifying a remote manifest entry against local sync evidence. */
data class SyncIdentityMatch(
    val verdict: SyncIdentityVerdict,
    val matchedSourceId: String = "",
    val evidence: LocalSyncTrackEvidence? = null,
)

/**
 * Decides whether a local copy can stand in for a manifest entry.
 *
 * The policy is intentionally evidence-first:
 * - a bare metadata coincidence is never proof of binary identity;
 * - a track ID is only meaningful inside its owning library;
 * - a Room row marked as downloaded is not proof that the file still exists.
 */
object SyncIdentityPolicy {

    fun classify(
        endpointId: String,
        track: ManifestTrack,
        localEvidence: List<LocalSyncTrackEvidence>,
    ): SyncIdentityMatch {
        val sameId = localEvidence.filter { it.trackId == track.trackId }
        val sameOwner = sameId.filter { it.sourceId == endpointId }

        if (sameOwner.isNotEmpty()) {
            val conflicting = sameOwner.firstOrNull { conflicts(track, it) }
            if (conflicting != null) {
                return SyncIdentityMatch(
                    verdict = SyncIdentityVerdict.CONTENT_MISMATCH,
                    matchedSourceId = conflicting.sourceId,
                    evidence = conflicting,
                )
            }
            val verified = sameOwner.firstOrNull { isVerified(track, it) }
            if (verified != null) {
                return SyncIdentityMatch(
                    verdict = SyncIdentityVerdict.IDENTICAL_VERIFIED,
                    matchedSourceId = verified.sourceId,
                    evidence = verified,
                )
            }
            val reference = sameOwner.first()
            return SyncIdentityMatch(
                verdict = SyncIdentityVerdict.SAME_OWNER_REFERENCE,
                matchedSourceId = reference.sourceId,
                evidence = reference,
            )
        }

        val foreignOwner = sameId.firstOrNull()
        if (foreignOwner != null) {
            return SyncIdentityMatch(
                verdict = SyncIdentityVerdict.DIFFERENT_SOURCE,
                matchedSourceId = foreignOwner.sourceId,
                evidence = foreignOwner,
            )
        }

        val metadataMatch = localEvidence.firstOrNull { metadataMatches(track, it) }
        if (metadataMatch != null) {
            return SyncIdentityMatch(
                verdict = SyncIdentityVerdict.POSSIBLE_DUPLICATE,
                matchedSourceId = metadataMatch.sourceId,
                evidence = metadataMatch,
            )
        }

        return SyncIdentityMatch(verdict = SyncIdentityVerdict.UNKNOWN)
    }

    private fun conflicts(track: ManifestTrack, evidence: LocalSyncTrackEvidence): Boolean {
        val sizeConflict = track.size > 0 && evidence.sizeBytes > 0 && track.size != evidence.sizeBytes
        val checksumConflict = track.checksum.isNotEmpty() &&
            evidence.checksum.isNotEmpty() &&
            !track.checksum.equals(evidence.checksum, ignoreCase = true)
        return sizeConflict || checksumConflict
    }

    /**
     * Physical availability plus one comparable integrity signal is required.
     * When sizes are unknown, a verified checksum alone is enough.
     */
    private fun isVerified(track: ManifestTrack, evidence: LocalSyncTrackEvidence): Boolean {
        if (!evidence.physicallyVerified || !evidence.complete) return false
        val checksumsKnown = track.checksum.isNotEmpty() && evidence.checksum.isNotEmpty()
        if (checksumsKnown) {
            return track.checksum.equals(evidence.checksum, ignoreCase = true)
        }
        return track.size > 0 && evidence.sizeBytes > 0 && track.size == evidence.sizeBytes
    }

    private fun metadataMatches(track: ManifestTrack, evidence: LocalSyncTrackEvidence): Boolean {
        if (track.title.isBlank() || track.artist.isBlank()) return false
        if (evidence.title.isBlank() || evidence.artist.isBlank()) return false
        if (!track.title.trim().equals(evidence.title.trim(), ignoreCase = true)) return false
        if (!track.artist.trim().equals(evidence.artist.trim(), ignoreCase = true)) return false
        return track.duration <= 0 || evidence.duration <= 0 || track.duration.toLong() == evidence.duration
    }
}
