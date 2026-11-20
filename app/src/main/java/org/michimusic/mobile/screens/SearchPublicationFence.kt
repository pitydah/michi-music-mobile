package org.michimusic.mobile.screens

/**
 * UI8-DEV-09: Serialize UI state changes across IO and Compose dispatchers.
 * MutableStateFlow is individually thread-safe, but checking a generation and
 * then publishing multiple flows is not an atomic transaction by itself.
 *
 * All query/filter changes, root revocations and final result emissions must
 * use the SAME instance. Reentrancy permits a successful load to reschedule
 * the current query without another dispatcher hop. No background worker is created.
 */
internal class SearchPublicationFence {
    private val monitor = Any()

    fun <T> write(block: () -> T): T = synchronized(monitor) { block() }
}
