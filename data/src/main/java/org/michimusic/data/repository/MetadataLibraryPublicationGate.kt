package org.michimusic.data.repository

import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.data.cache.MetadataAppliedTransaction

/**
 * Decides whether a committed revision may still patch the in-memory catalog.
 *
 * The Room transaction marker is the only commit authority. A notification is
 * never a substitute for it: without the exact marker, no in-memory patch is
 * allowed, even when the committed revision looks plausible. This prevents a
 * save that never committed, a deleted row from being resurrected and a
 * fabricated identity from replacing live metadata.
 *
 * A marker is provenance, not a reason to drop a signal: the save writes the
 * Room marker before the in-memory catalog is patched, so an exact marker with
 * an equal durable revision still applies and the patch remains idempotent.
 * Only a strictly older revision is an out-of-order replay. A marker that
 * describes another transaction, target or candidate is never trusted, and a
 * marker whose revision is ahead of the durable row is an inconsistent commit.
 */
object MetadataLibraryPublicationGate {
    sealed interface Decision {
        data object Apply : Decision
        data class Ignore(val reason: String) : Decision
        data class Refuse(val reason: String) : Decision
    }

    fun evaluate(
        change: MetadataCommittedLibraryChange,
        marker: MetadataAppliedTransaction?,
        durableRevision: Long?,
    ): Decision {
        val stale = durableRevision != null &&
            change.committedRevision < durableRevision
        if (marker != null) {
            val sameTransaction = marker.transactionId == change.transactionId
            val sameTarget = marker.sourceId == change.sourceId &&
                marker.trackId == change.trackId &&
                marker.uri == change.uri
            val sameRevision = marker.originalRevision == change.previousRevision &&
                marker.committedRevision == change.committedRevision
            val sameCandidate = marker.candidateBytes == change.committedBytes &&
                marker.candidateSha256 == change.committedSha256
            if (!sameTransaction || !sameTarget || !sameRevision || !sameCandidate) {
                return Decision.Refuse("APPLIED_MARKER_MISMATCH")
            }
            if (stale) return Decision.Ignore("STALE_REVISION")
            // A marker without its durable row is a deleted track, not a
            // resurrection target. A row still behind the marker is not a
            // committed revision either.
            if (durableRevision == null)
                return Decision.Refuse("DURABLE_REVISION_UNAVAILABLE")
            if (durableRevision < change.committedRevision)
                return Decision.Refuse("DURABLE_REVISION_NOT_COMMITTED")
            return Decision.Apply
        }
        return if (stale) {
            Decision.Ignore("STALE_REVISION")
        } else {
            Decision.Refuse("COMMITTED_EVIDENCE_REQUIRED")
        }
    }
}
