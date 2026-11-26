package org.michimusic.data.repository

import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.data.cache.MetadataAppliedTransaction

/**
 * Decides whether a committed revision may still patch the in-memory catalog.
 *
 * A marker is provenance, not a reason to drop a signal: the save writes the
 * Room marker before the in-memory catalog is patched, so an exact marker with
 * an equal durable revision still applies and the patch remains idempotent.
 * Only a strictly older revision is an out-of-order replay. A marker that
 * describes another transaction, target or candidate is never trusted.
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
            val sameTarget = marker.sourceId == change.sourceId &&
                marker.trackId == change.trackId &&
                marker.uri == change.uri
            val sameRevision = marker.originalRevision == change.previousRevision &&
                marker.committedRevision == change.committedRevision
            val sameCandidate = marker.candidateBytes == change.committedBytes &&
                marker.candidateSha256 == change.committedSha256
            if (!sameTarget || !sameRevision || !sameCandidate) {
                return Decision.Refuse("APPLIED_MARKER_MISMATCH")
            }
            return if (stale) Decision.Ignore("STALE_REVISION") else Decision.Apply
        }
        return if (stale) Decision.Ignore("STALE_REVISION") else Decision.Apply
    }
}
