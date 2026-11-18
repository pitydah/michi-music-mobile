package org.michimusic.mobile.devices.aircat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Serializes user-triggered resumes with the same process + file lease used by
 * explicit transfers. No background polling; one short-lived job per request.
 * A busy gate never reports a transfer as running.
 */
internal class AirCatResumeScheduler(
    private val scope: CoroutineScope,
    private val lease: AirCatLeaseCoordinator?,
) {
    private val pending = ConcurrentHashMap<String, Job>()

    fun request(
        transferId: String,
        previous: Job?,
        canRun: () -> Boolean,
        onBusy: () -> Unit,
        onStarted: (Job) -> Unit,
        onError: (Exception) -> Unit,
        // Invoked after the resume Job settles and its registry entry is removed.
        // Used to release owned HTTP connections after a successful resume.
        onSettled: (Job) -> Unit = {},
        action: suspend () -> Unit,
    ): Boolean {
        if (transferId.isBlank() || !canRun()) return false
        val gate = lease ?: run { onBusy(); return false }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // A paused transfer may still be running its cancellation finally.
                // Wait before acquiring the same cross-process lease and resuming I/O.
                previous?.join()
                coroutineContext.ensureActive()
                if (!canRun()) return@launch
                when (gate.runExclusive {
                    coroutineContext.ensureActive()
                    if (canRun()) {
                        onStarted(coroutineContext[Job] ?: error("AIRC_RESUME_JOB_MISSING"))
                        coroutineContext.ensureActive()
                        action()
                    }
                }) {
                    is AirCatLeaseRun.Busy -> if (canRun()) onBusy()
                    is AirCatLeaseRun.Completed -> Unit
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (canRun()) onError(error)
            }
        }
        val existing = pending.putIfAbsent(transferId, job)
        if (existing != null) {
            job.cancel() // Lazy loser never starts network or reserves a lease.
            return false
        }
        // A cancelled LAZY child may never enter its coroutine body. Register a
        // completion hook rather than relying on `finally` inside the body.
        job.invokeOnCompletion {
            pending.remove(transferId, job)
            // This callback does not own the transfer result. Never permit a
            // cleanup callback to destabilize the coroutine completion handler.
            runCatching { onSettled(job) }
        }
        if (!job.start()) {
            pending.remove(transferId, job)
            return false
        }
        return true
    }

    /** Cancel without releasing the reservation until the job has unwound. */
    fun cancel(transferId: String): Job? = pending[transferId]?.also { it.cancel() }

    /** Allows the owner to join even if the job is already cancelling. */
    internal fun pendingJob(transferId: String): Job? = pending[transferId]

    internal fun isScheduled(transferId: String): Boolean = pending.containsKey(transferId)
}
