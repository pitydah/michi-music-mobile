package org.michimusic.core.intelligence

import java.util.concurrent.atomic.AtomicReference

/**
 * MI-15: immediate, zero-IO revocation for a Michi Control handoff INTENT.
 * PlayerDependencies.outputAuthority may be published asynchronously. Listening
 * evidence and recommendation queue commands must not borrow its old local
 * endpoint while a transfer is being negotiated.
 *
 * This fence is intentionally separate from global audio/DSP authority. It
 * never starts a service, listens to media events, scans, or writes to disk.
 */
object MichiPlaybackIntentFence {
    data class Stamp(val generation: Long, val localAllowed: Boolean, val pending: Boolean = false)

    private val state = AtomicReference(Stamp(0L, true))

    fun snapshot(): Stamp = state.get()

    fun permits(): Boolean = state.get().localAllowed

    /** A callback from a superseded transfer cannot restore permission. */
    fun permits(expectedGeneration: Long): Boolean {
        val current = state.get()
        return current.localAllowed && current.generation == expectedGeneration
    }

    /** Called on the initiating thread, BEFORE network requests or suspensions. */
    fun beginTransfer(): Long = state.updateAndGet { previous ->
        Stamp(previous.generation + 1L, false, true)
    }.generation

    /**
     * Called by explicit, completed switch-to-phone paths after sessionState
     * has been published. Increments the generation so late remote callbacks
     * and old listening evidence cannot revive an earlier session.
     */
    fun confirmedLocalEndpoint(): Long = state.updateAndGet { previous ->
        Stamp(previous.generation + 1L, true, false)
    }.generation

    /**
     * An operation may restore local intelligence ONLY after the manager has
     * completed and confirmed the local endpoint. Newer tickets always win.
     */
    fun completeTransfer(ticket: Long, confirmedLocal: Boolean): Boolean {
        while (true) {
            val previous = state.get()
            if (previous.generation != ticket || !previous.pending) return false
            if (state.compareAndSet(previous, previous.copy(localAllowed = confirmedLocal, pending = false))) return true
        }
    }
}
