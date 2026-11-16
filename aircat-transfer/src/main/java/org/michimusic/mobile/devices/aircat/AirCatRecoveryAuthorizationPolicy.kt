package org.michimusic.mobile.devices.aircat

import org.michimusic.core.models.PersistentCheckpoint

/**
 * A24: the caller's explicit subset also governs already-COMPLETED journals.
 * This is a local selection fence, NOT Link Home cryptographic authorization.
 * No network, polling or background worker.
 */
internal object AirCatRecoveryAuthorizationPolicy {
    fun permits(
        cp: PersistentCheckpoint,
        remoteDeviceId: String,
        authorizedTrackIds: Set<String>?,
        authorizedCriteria: List<AuthorizedTransferCriteria>?,
    ): Boolean {
        if (remoteDeviceId.isBlank() || cp.remoteDeviceId != remoteDeviceId) return false
        // Explicit composite criteria prevail, even if a track-only set is also present.
        if (authorizedCriteria != null) return authorizedCriteria.any { criteria ->
            criteria.trackId == cp.trackId &&
                (criteria.destinationLibraryId == null || criteria.destinationLibraryId == cp.destinationLibraryId) &&
                (criteria.expectedSize == null || criteria.expectedSize == cp.expectedSize) &&
                (criteria.expectedSha256.isNullOrEmpty() ||
                    criteria.expectedSha256.equals(cp.expectedSha256, ignoreCase = true)) &&
                (criteria.planId.isNullOrEmpty() || criteria.planId == cp.transferId)
        }
        return authorizedTrackIds == null || cp.trackId in authorizedTrackIds
    }
}
