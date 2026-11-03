package org.michimusic.mobile.sync

import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * What the periodic library mirror should do on the current call.
 *
 * The periodic worker is not free: every run wakes the radio, takes a wake lock and
 * compares the whole manifest. That is only defensible while the user asked for it
 * and there is still a paired device to talk to.
 */
enum class PeriodicSyncDecision { Schedule, Cancel }

/**
 * Tag shared by every periodic mirror job, so turning the preference off can cancel
 * the ones already enqueued instead of only refusing to enqueue new ones. Work that
 * was scheduled yesterday keeps firing today otherwise, and `auto_sync = false` would
 * be a lie.
 */
const val PERIODIC_SYNC_TAG: String = "michi_sync_periodic"

/**
 * Decides whether the periodic mirror must be scheduled or cancelled.
 *
 * A disabled preference cancels; a revoked or removed device cancels as well, because
 * the work would only retry against a pairing that no longer exists.
 */
fun periodicSyncDecision(autoSyncEnabled: Boolean, hasPairedDevice: Boolean): PeriodicSyncDecision =
    if (autoSyncEnabled && hasPairedDevice) PeriodicSyncDecision.Schedule else PeriodicSyncDecision.Cancel

/**
 * Constraints for the periodic mirror.
 *
 * Without them the job fired on a dead network, on a battery at 3 % and on a full disk:
 * it spent a wake lock and a foreground notification to fail. The manual run stays
 * unconstrained, because the user pressing "sincronizar ahora" is an explicit request.
 */
fun periodicSyncConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .setRequiresBatteryNotLow(true)
    .setRequiresStorageNotLow(true)
    .build()

/**
 * Unique work name for one device's periodic mirror.
 *
 * The name is derived from the device id, so cancelling the preference can reach the
 * jobs that were enqueued before this policy existed: those carried no tag, and only
 * their unique name identifies them. Slashes and colons are legal in ids but not in
 * work names, so they are normalised consistently in both places.
 */
fun periodicWorkName(deviceId: String): String =
    "michi_sync_periodic_${deviceId.replace(Regex("[^A-Za-z0-9_]"), "_")}"
