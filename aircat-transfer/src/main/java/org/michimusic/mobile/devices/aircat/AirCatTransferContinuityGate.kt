package org.michimusic.mobile.devices.aircat

/**
 * A40: event-driven continuity guard for original-file writes.
 *
 * No timer, poller, worker or additional network connection is created.
 * Every attempted write checks cancellation/pause/expiry. The heavier Home
 * membership + enrolled TLS anchor check is invoked before the first write,
 * after <=1 MiB of accepted write attempts, or after >=2s elapsed.
 * Callers MUST also check trust at the library/journal commit boundary.
 */
internal class AirCatTransferContinuityGate(
    private val nowMillis: () -> Long,
    private val expiresAtMillis: Long,
    private val isCancelled: () -> Boolean,
    private val isPaused: () -> Boolean,
    private val verifyTrust: () -> AirCatException?,
) {
    private companion object {
        const val MAX_UNCHECKED_BYTES = 1024L * 1024L
        const val MAX_UNCHECKED_MILLIS = 2_000L
    }

    private var hasCheckedTrust = false
    private var lastCheckedAt = 0L
    private var uncheckedBytes = 0L

    private fun ensureActive(now: Long) {
        if (isCancelled()) throw AirCatException.ProtocolError("AIRC_TRANSFER_CANCELLED")
        if (isPaused()) throw AirCatException.ProtocolError("AIRC_TRANSFER_PAUSED")
        if (expiresAtMillis <= 0L || now >= expiresAtMillis)
            throw AirCatException.TransferExpired(expiresAtMillis, now)
    }

    /** An unconditional last trust read immediately before journal and file promotion. */
    fun beforeCommit() {
        val now = nowMillis()
        ensureActive(now)
        verifyTrust()?.let { throw it }
        hasCheckedTrust = true
        lastCheckedAt = now
        uncheckedBytes = 0L
    }

    /** Throws before writing the chunk. A zero-length call also forces preflight. */
    fun beforeWrite(nextBytes: Int) {
        require(nextBytes >= 0) { "AIRC_CONTINUITY_NEGATIVE_CHUNK" }
        val now = nowMillis()
        ensureActive(now)

        val mustCheck = !hasCheckedTrust || now < lastCheckedAt ||
            now - lastCheckedAt >= MAX_UNCHECKED_MILLIS ||
            nextBytes.toLong() > MAX_UNCHECKED_BYTES - uncheckedBytes
        if (mustCheck) {
            verifyTrust()?.let { throw it }
            hasCheckedTrust = true
            lastCheckedAt = now
            uncheckedBytes = 0L
        }
        // BoundedOutputStream splits a large write into <=64 KiB slices.
        uncheckedBytes += nextBytes.toLong()
    }
}
