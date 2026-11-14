package org.michimusic.data.intelligence

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.core.intelligence.MichiSharingConsentPolicy
import java.io.File
import java.io.IOException

/**
 * Stores ONLY the user's selected privacy tier, not a provider permission.
 * Remains local in noBackupFilesDir. No connection, SDK or background work.
 * Loss/corruption of this file always falls back to MAXIMUM.
 */
class MichiIntelligencePrivacyPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.noBackupFilesDir, FILE_NAME)

    companion object {
        internal const val FILE_NAME = "michi-intelligence-privacy-mode-v1"
        private val mutex = Mutex()
    }

    suspend fun readMode(): MichiSharingConsentPolicy.PrivacyMode = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked() }
    }

    suspend fun setMode(mode: MichiSharingConsentPolicy.PrivacyMode): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val atomic = AtomicFile(file)
                if (mode == MichiSharingConsentPolicy.PrivacyMode.MAXIMUM) {
                    // No persisted data required for the safest option.
                    atomic.delete()
                } else {
                    val dir = appContext.noBackupFilesDir
                    if ((!dir.exists() && !dir.mkdirs()) || !dir.isDirectory) {
                        throw IOException("Missing private privacy settings directory")
                    }
                    val stream = atomic.startWrite()
                    try {
                        stream.write(mode.name.toByteArray(Charsets.US_ASCII))
                        atomic.finishWrite(stream)
                    } catch (error: Exception) {
                        atomic.failWrite(stream)
                        throw error
                    }
                }
            }
        }

    private fun readLocked(): MichiSharingConsentPolicy.PrivacyMode {
        if (!file.exists() && !File(file.path + ".bak").exists())
            return MichiSharingConsentPolicy.PrivacyMode.MAXIMUM
        return try {
            val raw = AtomicFile(file).openRead().bufferedReader(Charsets.US_ASCII).use { it.readText() }
            MichiSharingConsentPolicy.PrivacyMode.entries.firstOrNull { it.name == raw }
                ?: MichiSharingConsentPolicy.PrivacyMode.MAXIMUM
        } catch (_: Exception) {
            MichiSharingConsentPolicy.PrivacyMode.MAXIMUM
        }
    }
}
