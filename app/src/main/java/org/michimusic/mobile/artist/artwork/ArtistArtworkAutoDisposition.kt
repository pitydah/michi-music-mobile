package org.michimusic.mobile.artist.artwork

/** Disposition after an attempted automatic artwork recovery; no I/O or side effects. */
internal enum class AutoArtworkNextAction {
    COMPLETE,
    RETRY_WITH_WORK_MANAGER,
    DEFER_NO_IMAGE,
    DEFER_PROVIDER_FAILURE,
    NEEDS_IDENTITY,
}

internal object ArtistArtworkAutoDisposition {
    /** PARTIAL means only one slot succeeded, NOT that the other has no legal image.
     * Provider/transport/cache failures on the missing slot receive bounded retries
     * and 24h failure cooldown, never the 7-day confirmed-no-image cooldown.
     */
    fun decide(
        status: ArtworkLoadStatus,
        runAttemptCount: Int,
        transientFailure: Boolean = false,
    ): AutoArtworkNextAction = when (status) {
        ArtworkLoadStatus.READY -> AutoArtworkNextAction.COMPLETE
        ArtworkLoadStatus.PARTIAL, ArtworkLoadStatus.NO_IMAGE ->
            if (transientFailure) providerFailure(runAttemptCount)
            else AutoArtworkNextAction.DEFER_NO_IMAGE
        ArtworkLoadStatus.REJECTED -> AutoArtworkNextAction.DEFER_NO_IMAGE
        ArtworkLoadStatus.PROVIDER_ERROR, ArtworkLoadStatus.CACHE_ERROR,
        ArtworkLoadStatus.FETCHING, ArtworkLoadStatus.NOT_REQUESTED -> providerFailure(runAttemptCount)
        ArtworkLoadStatus.IDENTITY_MISMATCH -> AutoArtworkNextAction.NEEDS_IDENTITY
    }

    private fun providerFailure(runAttemptCount: Int): AutoArtworkNextAction =
        if (runAttemptCount < 2) AutoArtworkNextAction.RETRY_WITH_WORK_MANAGER
        else AutoArtworkNextAction.DEFER_PROVIDER_FAILURE
}
