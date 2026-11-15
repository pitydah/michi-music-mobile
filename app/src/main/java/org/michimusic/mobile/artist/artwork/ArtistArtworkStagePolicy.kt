package org.michimusic.mobile.artist.artwork

/** Per-artist bounded staged provider exploration. No network, storage or identity mutation. */
internal object ArtistArtworkStagePolicy {
    const val MAX_ATTEMPTS_PER_SLOT: Int = 3 // legacy per-provider bound
    const val MAX_ATTEMPTS_ACROSS_PROVIDERS: Int = 9

    /** A provider that fails its 3 attempts must not starve later verified sources.
     * Distinct assets are counted globally; stage budget resets at every provider.
     * Still no more than nine downloads per slot in the complete resolution.
     */
    fun canTryAcrossStages(
        attemptedAcrossProviders: Set<String>, attemptedInStage: Set<String>, assetId: String,
    ): Boolean = assetId.isNotBlank() && assetId !in attemptedAcrossProviders &&
        attemptedInStage.size < MAX_ATTEMPTS_PER_SLOT &&
        attemptedAcrossProviders.size < MAX_ATTEMPTS_ACROSS_PROVIDERS

    /** A repeated image is free for both roles through the coordinator download memo. */
    fun canTry(attempted: Set<String>, assetId: String): Boolean =
        assetId.isNotBlank() && assetId !in attempted && attempted.size < MAX_ATTEMPTS_PER_SLOT

    /** Stop before opening a lower-priority provider when both roles are satisfied. */
    suspend fun <T> visitUntilComplete(
        providers: Iterable<T>,
        hasMissing: () -> Boolean,
        visit: suspend (T) -> Unit,
    ) {
        for (provider in providers) {
            if (!hasMissing()) return
            visit(provider)
        }
    }
}
