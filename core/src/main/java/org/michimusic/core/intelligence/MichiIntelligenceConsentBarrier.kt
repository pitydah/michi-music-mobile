package org.michimusic.core.intelligence

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MI-05: process-local revocation fence for already queued playback events.
 * This does NOT store consent: the private repository remains the durable authority.
 * A fresh process starts permissive only to consult that durable authority.
 * No observers, workers, timers or allocations in the playback event path.
 */
object MichiIntelligenceConsentBarrier {
    private val generation = AtomicLong(0L)
    // Serialize consent writes and StateFlow publication. Hot-path reads stay lock-free.
    private val mutationLock = Any()
    @Volatile private var revoked = false
    // Hot, passive state: only emits when the user changes consent. Home collects
    // it while visible; no worker, timer, polling or background observer.
    private val changesMutable = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changesMutable

    fun epoch(): Long = generation.get()

    fun permits(eventEpoch: Long): Boolean =
        !revoked && eventEpoch == generation.get()

    /** Call synchronously from a user opt-out before scheduling any IO. */
    fun revoke() {
        synchronized(mutationLock) {
            revoked = true
            changesMutable.value = generation.incrementAndGet()
        }
    }

    /**
     * Activates a finished opt-in only if no intervening revoke occurred.
     * The store checks this AFTER its SQLite commit; a revoked request must
     * instead erase its newly-written `enabled=true` before returning.
     */
    fun acceptIfUnchanged(requestEpoch: Long): Boolean = synchronized(mutationLock) {
        if (generation.get() != requestEpoch) return@synchronized false
        generation.incrementAndGet()
        revoked = false
        changesMutable.value = generation.get()
        true
    }

    /** Test/support shortcut; never use in production consent activation. */
    fun acceptedExplicitConsent() {
        synchronized(mutationLock) {
            // Never publish a permissive revision before both state fields agree.
            generation.incrementAndGet()
            revoked = false
            changesMutable.value = generation.get()
        }
    }
}
