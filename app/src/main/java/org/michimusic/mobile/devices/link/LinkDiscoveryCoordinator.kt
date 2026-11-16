package org.michimusic.mobile.devices.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.michimusic.link.LinkDiscovery

/** One foreground Devices listening window. A known peer is never a trusted grant. */
internal class LinkDiscoveryCoordinator(
    private val linkDiscovery: LinkDiscovery,
    private val scope: CoroutineScope,
    private val onWindowFinished: () -> Unit,
) {
    private companion object { const val DISCOVERY_WINDOW_MS = 35_000L }

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    private val closeMutex = Mutex()
    private var scanJob: Job? = null
    private var stopJob: Job? = null
    private var epoch = 0L
    private var retainResultsOnStop = false
    private var closed = false

    /** Non-reentrant window; waits until any previous listener releases its socket. */
    @Synchronized
    fun start(): Boolean {
        if (closed || _isActive.value || scanJob?.isActive == true) return false
        val generation = ++epoch
        val pendingStop = stopJob
        retainResultsOnStop = false
        _isActive.value = true
        scanJob = scope.launch {
            try {
                pendingStop?.join()
                if (generation != epoch || closed) return@launch
                withTimeoutOrNull(DISCOVERY_WINDOW_MS) { linkDiscovery.start() }
            } finally {
                // A62: a timeout, screen close, ViewModel cancellation or fast
                // stop/start must all release the multicast socket/lock. The
                // cancelled screen scope cannot be relied on to launch cleanup.
                withContext(NonCancellable) {
                    closeMutex.withLock {
                        linkDiscovery.stop(preservePeers = retainResultsOnStop && generation != epoch)
                    }
                }
                if (generation == epoch) {
                    _isActive.value = false
                    scanJob = null
                    onWindowFinished()
                }
            }
        }
        return true
    }

    /** Stop the visible spinner immediately; teardown continues without new scan. */
    @Synchronized
    fun stop(preserveResults: Boolean = false) {
        ++epoch
        retainResultsOnStop = preserveResults
        _isActive.value = false
        val running = scanJob
        val previousStop = stopJob
        running?.cancel()
        stopJob = scope.launch {
            try {
                previousStop?.join()
                running?.join()
            } finally {
                // This is a second idempotent guard for a scan that never
                // started; an active scan also cleans itself in its finally.
                withContext(NonCancellable) {
                    closeMutex.withLock { linkDiscovery.stop(preservePeers = preserveResults) }
                }
            }
        }
    }

    /** No cancellation of stopJob: that would discard an in-flight teardown. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        stop(preserveResults = false)
    }
}
