package org.michimusic.player

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small, bounded, asynchronous boundary between Media3's PCM renderer and a
 * potentially blocking RTP/network sender. offer() NEVER waits for network I/O.
 *
 * A saturated queue is a transport fault, not an invitation to silently drop
 * arbitrary PCM frames and corrupt timestamps. The associated tap stays muted
 * until the user explicitly starts a new sender or stops remote playback.
 */
internal class BoundedPcmSender(
    private val send: (ByteArray) -> Unit,
    private val onFailure: (Exception) -> Unit,
    capacity: Int = 8,
    private val maxChunkBytes: Int = 1_048_576,
) {
    init {
        require(capacity in 1..64) { "PCM delivery capacity must be bounded" }
        require(maxChunkBytes in 1..2_097_152) { "PCM chunk size must be bounded" }
    }

    private val pending = ArrayBlockingQueue<ByteArray>(capacity)
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val worker = Thread({
        while (!closed.get()) {
            val payload = try {
                pending.take()
            } catch (_: InterruptedException) {
                if (closed.get()) break
                continue
            }
            if (closed.get()) break
            try {
                send(payload)
            } catch (e: Exception) {
                fail(e)
                break
            }
        }
    }, "Michi-PCM-Network").apply { isDaemon = true }

    fun start() { worker.start() }

    /** Called synchronously from Media3's audio renderer; never blocks on send(). */
    fun offer(bytes: ByteArray) {
        if (closed.get()) return
        if (bytes.size > maxChunkBytes) {
            fail(IllegalStateException("PCM chunk exceeds configured sender memory bound"))
            return
        }
        if (!pending.offer(bytes)) {
            fail(IllegalStateException("PCM sender queue saturated; remote audio requires reconnection"))
        }
    }

    /** Drop queued pre-pause/pre-format samples; does not block an in-flight send. */
    fun discardPending() { pending.clear() }

    /** Non-blocking shutdown, even if the network callback does not respond to interrupts. */
    fun close() {
        closed.set(true)
        pending.clear()
        worker.interrupt()
    }

    private fun fail(reason: Exception) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        closed.set(true)
        pending.clear()
        worker.interrupt()
        try { onFailure(reason) } catch (_: Exception) { /* keep render thread safe */ }
    }
}
