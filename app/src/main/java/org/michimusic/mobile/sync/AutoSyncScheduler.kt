package org.michimusic.mobile.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val refreshMutex = Mutex()

    /**
     * App-owned scope: completing a pairing and leaving its screen must not
     * cancel the refresh that schedules or cancels the periodic mirror.
     * The mutex serialises concurrent requests; no polling is added.
     */
    private val appScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun isEnabled(): Boolean =
        appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SYNC, false)

    /** WorkManager database truth, observed only while Settings > Ecosistema is visible.
     * An enabled preference does NOT guarantee any task was enqueued.
     */
    fun observeWorkTruth(): Flow<AutoSyncWorkTruth> =
        WorkManager.getInstance(appContext).getWorkInfosByTagFlow(PERIODIC_SYNC_TAG)
            .map { infos -> autoSyncWorkTruth(infos.map { it.state.name }) }
            .distinctUntilChanged()

    /**
     * Persists the preference and immediately applies it: enabling schedules the job,
     * disabling cancels both the tagged jobs and the untagged ones enqueued before
     * [PERIODIC_SYNC_TAG] existed.
     */
    suspend fun setEnabled(enabled: Boolean) {
        withContext(ioDispatcher) {
            refreshMutex.withLock {
                check(appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_AUTO_SYNC, enabled).commit()) {
                    "No se pudo conservar la preferencia de sincronización automática"
                }
                refreshBlocking()
            }
        }
    }

    /** Re-reads the preference and the pairings, then schedules or cancels. */
    suspend fun refresh() {
        withContext(ioDispatcher) { refreshMutex.withLock { refreshBlocking() } }
    }

    /**
     * Fire-and-forget refresh for callers tied to a screen lifecycle (pairing,
     * forget, settings). The work belongs to this app-owned scope so popBackStack
     * or a completed pairing cannot cancel it.
     */
    fun requestRefresh() {
        appScope.launch { refresh() }
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

        val device = paired.firstOrNull { it.effectiveServerId.isNotBlank() && it.lastUrl.isNotBlank() }
        if (device == null) {
            cancelAll(devices) // No usable peer: revoke stale periodic work.
            return
        }
        val deviceId = device.effectiveServerId
        val baseUrl = device.lastUrl
        // Never schedule a mirror without a real identity: the worker would sign
        // with an empty client id and fail every twelve hours.
        val identity = identityAuthority.await()
        if (identity == null || identity.michiId.isBlank()) {
            cancelAll(devices) // No signing identity: prior jobs must not survive.
            return
        }
        // Re-read only during an explicit refresh: the user may have revoked or
        // re-paired this peer while identityAuthority.await() was suspended.
        val currentDevices = registry.getAllDevices()
        val refreshedDevice = currentDevices.firstOrNull { peer ->
            samePeriodicSyncPeer(deviceId, baseUrl, peer.effectiveServerId,
                peer.lastUrl, peer.isRevoked)
        }
        if (refreshedDevice == null || !isEnabled()) {
            cancelAll(currentDevices)
            return
        }
        // Use fresh registry credentials: the device may have re-paired during await.
        val clientDeviceId = refreshedDevice.effectiveClientDeviceId.ifEmpty { identity.michiId }
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
            .addTag(periodicSyncOwnerTag(deviceId))
            .build()

        // An old periodic job may belong to an unpaired/revoked peer. The owner tag
        // also upgrades legacy jobs lacking this tag by cancelling them once.
        // No polling: WorkManager's current DB snapshot is read only on refresh.
        // Await cancellations on the IO dispatcher before enqueuing replacements.
        val workManager = WorkManager.getInstance(appContext)
        workManager.getWorkInfosByTagFlow(PERIODIC_SYNC_TAG).first().forEach { work ->
            if (shouldPrunePeriodicSync(work.tags, work.state.isFinished, deviceId)) {
                workManager.cancelWorkById(work.id).result.get()
            }
        }
        // Cancel legacy untagged jobs for other still-known paired devices.
        devices.filter { it.effectiveServerId != deviceId }.forEach { other ->
            workManager.cancelUniqueWork(periodicWorkName(other.effectiveServerId)).result.get()
        }
        // UPDATE, not KEEP: a re-pairing or a token refresh changes the input data, and
        // KEEP would keep the stale credentials for up to twelve hours.
        workManager.enqueueUniquePeriodicWork(
                periodicWorkName(deviceId),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            ).result.get()
    }

    private fun cancelAll(devices: List<org.michimusic.link.PairedDevice>) {
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelAllWorkByTag(PERIODIC_SYNC_TAG).result.get()
        // Jobs enqueued before the tag existed are only reachable by their unique name.
        devices.forEach { workManager.cancelUniqueWork(periodicWorkName(it.effectiveServerId)).result.get() }
    }

    companion object {
        const val SETTINGS_PREFS: String = "michi_settings"
        const val KEY_AUTO_SYNC: String = "auto_sync"
        private const val PERIOD_IN_HOURS: Long = 12L
    }
}
