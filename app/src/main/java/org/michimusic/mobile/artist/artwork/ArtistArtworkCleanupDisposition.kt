package org.michimusic.mobile.artist.artwork

/**
 * Terminal outcome for local artwork cleanup. The journal is the authority:
 * a retry count is not evidence that pending work was completed.
 *
 * WorkManager applies its system-managed backoff. A permanent integrity anomaly
 * is terminalized separately as REVIEW_REQUIRED, and therefore does not appear as
 * pending; this policy never authorizes deleting uncertain bytes.
 */
internal enum class ArtistArtworkCleanupDisposition { SUCCESS, RETRY }

internal fun artistArtworkCleanupDisposition(
    stopped: Boolean,
    pending: Boolean,
    failed: Boolean,
): ArtistArtworkCleanupDisposition = if (stopped || pending || failed) {
    ArtistArtworkCleanupDisposition.RETRY
} else {
    ArtistArtworkCleanupDisposition.SUCCESS
}
