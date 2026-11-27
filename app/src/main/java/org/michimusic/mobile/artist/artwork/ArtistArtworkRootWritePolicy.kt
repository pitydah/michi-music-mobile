package org.michimusic.mobile.artist.artwork

import org.michimusic.data.repository.MusicRootStamp

/**
 * D17 — Reject stale authorizations, including a return to an earlier SAF source.
 * Called while the same short process-local gate excludes root commits.
 * It is NOT an identity or library-membership proof; Room checks those separately.
 */
internal fun isArtistArtworkRootCurrent(
    expected: MusicRootStamp,
    actual: MusicRootStamp,
): Boolean = expected.sourceId.isNotBlank() && expected.epoch >= 0L &&
    expected.sourceId == actual.sourceId && expected.epoch == actual.epoch
