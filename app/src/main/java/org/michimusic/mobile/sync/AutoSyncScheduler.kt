package org.michimusic.mobile.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.identity.MichiIdentityAuthority
import java.util.concurrent.TimeUnit

/**
 * Single owner of the periodic library mirror.
 *
 * Before this class the scheduling policy existed but nothing called it, so the
 * preference could not enable or disable background sync. It also had to run off the
 * main thread: reading the paired-device registry touches EncryptedSharedPreferences,
 * which StrictMode reported as a disk read on the main thread at every cold start.
 */
class AutoSyncScheduler(
    private val context: Context,
    private val registry: PairedDeviceRegistry,
    private val identityAuthority: MichiIdentityAuthority,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext

    fun isEnabled(): Boolean =
        appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SYNC, false)

    /**
     * Persists the preference and immediately applies it: enabling schedules the job,
     * disabling cancels both the tagged jobs and the untagged ones enqueued before
     * [PERIODIC_SYNC_TAG] existed.
     */
    suspend fun setEnabled(enabled: Boolean) {
        withContext(ioDispatcher) {
            appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_SYNC, enabled)
                .apply()
            refreshBlocking()
        }
    }

    /** Re-reads the preference and the pairings, then schedules or cancels. */
    suspend fun refresh() {
        withContext(ioDispatcher) { refreshBlocking() }
    }

    private suspend fun refreshBlocking() {
        val devices = registry.getAllDevices()
        val paired = devices.filter { !it.isRevoked }
        val enabled = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SYNC, false)

        if (periodicSyncDecision(enabled, paired.isNotEmpty()) == PeriodicSyncDecision.Cancel) {
            cancelAll(devices)
            return
        }

        val device = paired.first()
        val deviceId = device.effectiveServerId
        val baseUrl = device.lastUrl.ifEmpty { return }
        // Never schedule a mirror without a real identity: the worker would sign
        // with an empty client id and fail every twelve hours.
        val identity = identityAuthority.await() ?: return
        val clientDeviceId = device.effectiveClientDeviceId.ifEmpty { identity.michiId }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_IN_HOURS, TimeUnit.HOURS)
            .setInputData(
                SyncWorker.buildInputData(
                    baseUrl = baseUrl,
                    deviceId = deviceId,
                    alias = android.os.Build.MODEL,
                    clientDeviceId = clientDeviceId,
                )
            )
            .setConstraints(periodicSyncConstraints())
            .addTag(PERIODIC_SYNC_TAG)
            .build()

        // UPDATE, not KEEP: a re-pairing or a token refresh changes the input data, and
        // KEEP would keep the stale credentials for up to twelve hours.
        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork(
                periodicWorkName(deviceId),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    private fun cancelAll(devices: List<org.michimusic.link.PairedDevice>) {
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelAllWorkByTag(PERIODIC_SYNC_TAG)
        // Jobs enqueued before the tag existed are only reachable by their unique name.
        devices.forEach { workManager.cancelUniqueWork(periodicWorkName(it.effectiveServerId)) }
    }

    companion object {
        const val SETTINGS_PREFS: String = "michi_settings"
        const val KEY_AUTO_SYNC: String = "auto_sync"
        private const val PERIOD_IN_HOURS: Long = 12L
    }
}
