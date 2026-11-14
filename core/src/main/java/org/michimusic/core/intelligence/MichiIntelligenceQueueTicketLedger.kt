package org.michimusic.core.intelligence

import java.util.concurrent.atomic.AtomicReference

/**
 * MI-16: lock-free, one-shot queue intent ledger. No scheduler, scope, state flow,
 * worker or allocation while idle. A ticket is only an ephemeral command receipt,
 * never a consent token or permission to read a music file.
 *
 * All operations are linearizable: older delayed availability checks cannot
 * reclaim authority after a newer click or explicit generic queue action.
 */
class MichiIntelligenceQueueTicketLedger {
    private data class State(val issued: Long, val active: Long)
    private val state = AtomicReference(State(0L, 0L))

    fun issue(): Long = state.updateAndGet { old ->
        val next = old.issued + 1L
        require(next > 0L) { "Queue ticket sequence exhausted" }
        State(next, next)
    }.active

    /** A normal playback action supersedes any pending Intelligence UI IO. */
    fun cancel() {
        state.updateAndGet { it.copy(active = 0L) }
    }

    /** Only one successful dispatch can consume the latest receipt. */
    fun consume(ticket: Long): Boolean {
        if (ticket <= 0L) return false
        while (true) {
            val current = state.get()
            if (current.active != ticket) return false
            if (state.compareAndSet(current, current.copy(active = 0L))) return true
        }
    }
}
