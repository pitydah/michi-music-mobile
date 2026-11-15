package org.michimusic.mobile.artist.artwork

/** Bounded, generation-aware dispatch without re-querying the music collection. */
internal object ArtistArtworkDispatchFairness {
    const val MAX_PAGE_SIZE = 128
    const val MAX_PAGES = 8
    const val MAX_NEW_WORK = 16
    private const val ACTIVE_TAG_PREFIX = "michi.artist.auto.unique:"

    /** Only hashed WorkManager names, never artist names, tags or album IDs. */
    fun activeTag(baseName: String, rootEpoch: Long): String? {
        if (!baseName.startsWith("michi.artist.auto.") || rootEpoch < 0L ||
            baseName.any { it.isISOControl() || it.isWhitespace() } || baseName.length > 100
        ) return null
        return "$ACTIVE_TAG_PREFIX$baseName.e$rootEpoch"
    }

    fun isActiveWorkState(state: String): Boolean =
        state == "ENQUEUED" || state == "RUNNING" || state == "BLOCKED"

    /** Skip already-enqueued rows; they must not consume new-work budget. */
    fun eligible(tag: String?, active: Set<String>, alreadySelected: Set<String>): Boolean =
        tag != null && tag !in active && tag !in alreadySelected
}
