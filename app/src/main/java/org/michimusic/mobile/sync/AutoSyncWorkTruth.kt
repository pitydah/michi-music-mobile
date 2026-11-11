package org.michimusic.mobile.sync

/** Explicit distinction between persistent preference and WorkManager's observed jobs. */
enum class AutoSyncWorkTruth {
    UNKNOWN, NOT_SCHEDULED, QUEUED, BLOCKED, RUNNING,
}

/** Includes stale CANCELLED/FAILED/SUCCEEDED records, which are NOT scheduled work. */
fun autoSyncWorkTruth(states: List<String>): AutoSyncWorkTruth = when {
    "RUNNING" in states -> AutoSyncWorkTruth.RUNNING
    "ENQUEUED" in states -> AutoSyncWorkTruth.QUEUED
    "BLOCKED" in states -> AutoSyncWorkTruth.BLOCKED
    else -> AutoSyncWorkTruth.NOT_SCHEDULED
}

fun autoSyncWorkStatusLabel(enabled: Boolean?, truth: AutoSyncWorkTruth): String = when {
    enabled == null -> "Consultando preferencia…"
    !enabled -> "Sincronización automática desactivada"
    truth == AutoSyncWorkTruth.RUNNING -> "Sincronización en curso (WorkManager)"
    truth == AutoSyncWorkTruth.QUEUED -> "Trabajo periódico programado por Android"
    truth == AutoSyncWorkTruth.BLOCKED -> "Trabajo en espera de dependencias"
    truth == AutoSyncWorkTruth.NOT_SCHEDULED ->
        "Preferencia activada, sin tarea programada. Revisa el vínculo, URL e identidad."
    else -> "Preferencia activada. Consultando programación…"
}

/** Stable owner tag for a periodic sync. Deliberately separate from generic tag. */
internal fun periodicSyncOwnerTag(deviceId: String): String =
    "michi_periodic_sync_owner_$deviceId"

/** Conservatively reject unowned legacy jobs and jobs from revoked peers. */
internal fun shouldPrunePeriodicSync(tags: Set<String>, finished: Boolean, selectedDeviceId: String): Boolean =
    !finished && periodicSyncOwnerTag(selectedDeviceId) !in tags

/** Ensure async identity resolution did not outlive the selected peer's authority. */
internal fun samePeriodicSyncPeer(
    expectedServerId: String, expectedUrl: String,
    candidateServerId: String, candidateUrl: String, revoked: Boolean,
): Boolean = !revoked && expectedServerId.isNotBlank() && expectedUrl.isNotBlank() &&
    candidateServerId == expectedServerId && candidateUrl == expectedUrl
