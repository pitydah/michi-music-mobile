package org.michimusic.player

/**
 * Monotonic generation guard for metadata that arrives after a Media3 transition.
 * The service calls every method on the Android main thread. Equality by mediaId
 * is insufficient for a queue containing the same track more than once.
 */
internal class PlaybackTransitionEpoch {
    private var generation: Long = 0L

    fun advance(): Long = ++generation

    fun isCurrent(candidate: Long): Boolean = candidate == generation
}
