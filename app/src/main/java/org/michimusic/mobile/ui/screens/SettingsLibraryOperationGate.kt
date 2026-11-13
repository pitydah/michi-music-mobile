package org.michimusic.mobile.ui.screens

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One user-authorized library operation at a time, including before Compose recomposes. */
internal enum class SettingsLibraryOperation { RECOVER, VERIFY, RESCAN, RESTORE }

/** UI-scoped and local-only: no worker, polling, retained task or shared global lock. */
internal class SettingsLibraryOperationGate {
    private data class Running(val kind: SettingsLibraryOperation, val job: Job)
    private val running = AtomicReference<Running?>(null)

    val current: SettingsLibraryOperation? get() = running.get()?.kind
    val canCancel: Boolean get() = current == SettingsLibraryOperation.RECOVER ||
        current == SettingsLibraryOperation.VERIFY

    fun launch(scope: CoroutineScope, kind: SettingsLibraryOperation, block: suspend () -> Unit): Boolean {
        // Publish one inseparable (operation, Job) pair before any block starts.
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        val receipt = Running(kind, job)
        if (!running.compareAndSet(null, receipt)) {
            job.cancel()
            return false
        }
        job.invokeOnCompletion { running.compareAndSet(receipt, null) }
        job.start()
        return true
    }

    /** Captures an exact read job; cannot accidentally cancel a successor rescan. */
    fun cancelRead(): Boolean {
        val receipt = running.get() ?: return false
        if (receipt.kind != SettingsLibraryOperation.RECOVER &&
            receipt.kind != SettingsLibraryOperation.VERIFY) return false
        receipt.job.cancel()
        return true
    }
}
