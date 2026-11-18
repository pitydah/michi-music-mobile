package org.michimusic.mobile.devices.aircat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the process gate and the cross-process lock for explicit AirCat operations.
 * No network, no pairing, no background service and no new scope.
 */
/** A cross-process lease outcome; BUSY is not success or a running transfer. */
internal sealed interface AirCatLeaseRun<out T> {
    data object Busy : AirCatLeaseRun<Nothing>
    data class Completed<T>(val value: T) : AirCatLeaseRun<T>
}

class AirCatLeaseCoordinator(private val context: Context) {
    companion object {
        private val owner = AtomicReference<AirCatLeaseCoordinator?>(null)
    }

    /** The initial operation and every resume use the exact same exclusivity gate. */
    internal suspend fun <T> runExclusive(action: suspend () -> T): AirCatLeaseRun<T> {
        if (!tryEnter()) return AirCatLeaseRun.Busy
        var acquired: Pair<FileChannel, FileLock>? = null
        try {
            acquired = acquireFileLease() ?: return AirCatLeaseRun.Busy
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return AirCatLeaseRun.Completed(action())
        } finally {
            runCatching { acquired?.second?.release() }
            runCatching { acquired?.first?.close() }
            leave()
        }
    }

    // Only the instance that acquired the gate can release it. The OS file
    // lock remains the separate cross-process source of truth.
    fun tryEnter(): Boolean = owner.compareAndSet(null, this)
    fun leave() { owner.compareAndSet(this, null) }

    suspend fun acquireFileLease(): Pair<FileChannel, FileLock>? =
        withContext(NonCancellable + Dispatchers.IO) {
            val lockFile = File(context.filesDir, "michi_aircat_transfer.lock")
            val channel = RandomAccessFile(lockFile, "rw").channel
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (error: Exception) {
                channel.close()
                throw error
            }
            if (lock == null) {
                channel.close()
                null
            } else channel to lock
        }
}
