package org.michimusic.mobile.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.koin.java.KoinJavaComponent
import org.michimusic.core.models.DownloadItem
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.mobile.di.trackDao
import org.michimusic.mobile.di.playlistDao
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.errors.LinkException

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_CURRENT = "progress_current"
        const val RESULT_DOWNLOADED = "result_downloaded"
        const val RESULT_ERROR = "result_error"
        const val CHANNEL_ID = "michi_sync"
        const val NOTIFICATION_ID = 1001
        private const val SYNC_WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private val planJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun buildInputData(
            baseUrl: String,
            deviceId: String,
            alias: String,
            clientDeviceId: String = "",
            selectedTrackIds: String = "",
            profile: String = "ORIGINAL",
            scope: String = "FULL_LIBRARY",
            planId: String = "",
            expectedBytes: Long = 0L,
        ) = workDataOf(
            "baseUrl" to baseUrl,
            "deviceId" to deviceId,
            "alias" to alias,
            "clientDeviceId" to clientDeviceId,
            "selectedTrackIds" to selectedTrackIds,
            "profile" to profile,
            "scope" to scope,
            "planId" to planId,
            "expectedBytes" to expectedBytes,
        )
    }

    override suspend fun doWork(): Result {
        val baseUrl = inputData.getString("baseUrl") ?: return Result.failure()
        val deviceId = inputData.getString("deviceId") ?: ""
        val alias = inputData.getString("alias") ?: ""
        val clientDeviceId = inputData.getString("clientDeviceId") ?: ""
        val scope = inputData.getString("scope") ?: if (inputData.getString("selectedTrackIds").isNullOrEmpty()) "FULL_LIBRARY" else "TRACKS"
        val expectedBytes = inputData.getLong("expectedBytes", 0L)

        // Security: resolve credentials strictly for target device from encrypted registry
        val pairedDevice = org.michimusic.mobile.di.pairedDeviceRegistry?.getDevice(deviceId)
        if (pairedDevice == null || pairedDevice.isRevoked) {
            // Unregistered or explicitly revoked device: reject immediately
            return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
        }

        // Endpoint validation: Ensure target host belongs to authorized peer
        if (pairedDevice.lastUrl.isNotEmpty()) {
            val registeredHost = java.net.URI(pairedDevice.lastUrl).host
            val targetHost = java.net.URI(baseUrl).host
            if (registeredHost != null && targetHost != null && registeredHost != targetHost && targetHost != "127.0.0.1" && targetHost != "localhost") {
                // Host altered/mismatched from authorized registration
                return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
            }
        }

        val resolvedDeviceToken = pairedDevice.deviceToken.ifEmpty { "" }
        if (resolvedDeviceToken.isEmpty()) {
            // Unauthenticated: do not proceed without valid scoped token
            return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
        }

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "michi:sync")
        wakeLock?.acquire(SYNC_WAKELOCK_TIMEOUT_MS)

        // Check available storage (strictly enforcing 100 MB safety reserve + expected payload)
        val storage = StatFs(applicationContext.filesDir.absolutePath)
        val freeBytes = storage.availableBytes
        val minFree = org.michimusic.core.sync.StoragePreflightEvaluator.SAFETY_BUFFER_BYTES // 100 MB minimum
        if (freeBytes < minFree || (expectedBytes > 0 && freeBytes < minFree + expectedBytes)) {
            try { wakeLock?.release() } catch (_: Exception) {}
            return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
        }

        safeSetForeground(createForegroundInfo(0, 0, "Iniciando sincronización..."))

        val client = LinkClient(
            baseUrl = baseUrl,
            sessionToken = resolvedDeviceToken,
            deviceToken = resolvedDeviceToken,
            clientDeviceId = clientDeviceId.ifEmpty { deviceId },
        )
        val trackDao = trackDao
        if (trackDao == null) {
            client.close()
            try { wakeLock?.release() } catch (_: Exception) {}
            return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
        }
        val playlistDao = playlistDao
        val repository = SyncedTrackRepository(trackDao, playlistDao)
        val checkpointRepo = org.michimusic.mobile.di.transferCheckpointRepository
        val transferManager = LinkTransferManager(applicationContext, checkpointRepo)

        return try {
            val manifestResult = client.fetchSyncManifest(deviceId)
            if (manifestResult.isFailure) {
                val error = manifestResult.exceptionOrNull()
                client.close()
                try { wakeLock?.release() } catch (_: Exception) {}
                if (error is LinkException) {
                    return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
                }
                return Result.retry()
            }
            val manifest = manifestResult.getOrThrow()
            val manifestTracks = manifest.effectiveTracks

            repository.saveLibrary(
                manifestTracks.map { it.toTrackDto() }
            )

            if (manifest.playlists.isNotEmpty()) {
                repository.saveManifestPlaylists(
                    manifest.playlists.map { it.toDomainPlaylist() }
                )
            }

            val planId = inputData.getString("planId") ?: ""
            val approvedPlanRepo = org.michimusic.mobile.di.approvedSyncPlanRepository
            val approvedPlan = if (planId.isNotEmpty()) {
                val plan = approvedPlanRepo?.getPlan(planId)
                if (plan == null) {
                    client.close()
                    try { wakeLock?.release() } catch (_: Exception) {}
                    return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
                }
                if (plan.endpointId != deviceId) {
                    client.close()
                    try { wakeLock?.release() } catch (_: Exception) {}
                    return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
                }
                if (plan.executionState == org.michimusic.core.models.TransferLifecycleState.CANCELLED.name) {
                    client.close()
                    try { wakeLock?.release() } catch (_: Exception) {}
                    return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
                }
                if (plan.executionState == org.michimusic.core.models.TransferLifecycleState.COMPLETED.name) {
                    client.close()
                    try { wakeLock?.release() } catch (_: Exception) {}
                    return Result.success(workDataOf(RESULT_DOWNLOADED to 0))
                }
                plan
            } else {
                null
            }

            // Manifest version validation if plan is present
            if (approvedPlan != null && approvedPlan.manifestChecksum.isNotEmpty()) {
                val currentChecksum = manifest.manifestId.ifEmpty { manifest.createdAt }
                if (currentChecksum.isNotEmpty() && currentChecksum != approvedPlan.manifestChecksum) {
                    approvedPlanRepo?.updateExecutionState(
                        planId,
                        org.michimusic.core.models.TransferLifecycleState.FAILED,
                        errorMessage = "PLAN_STALE: El catálogo del servidor ha cambiado desde que se aprobó el plan."
                    )
                    client.close()
                    try { wakeLock?.release() } catch (_: Exception) {}
                    return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
                }
            }

            val selectedTrackIdsStr = inputData.getString("selectedTrackIds") ?: ""
            val selectedTrackIds = if (selectedTrackIdsStr.isNotEmpty()) {
                selectedTrackIdsStr.split(",").filter { it.isNotBlank() }.toSet()
            } else {
                emptySet()
            }

            // Invariant: If approvedPlan exists, it is the sole authoritative source of truth.
            // DownloadedItems(P) ⊆ AuthorizedDownloadItems(P)
            val candidateTracks = if (approvedPlan != null) {
                val approvedItems = runCatching {
                    planJson.decodeFromString<List<org.michimusic.core.models.SyncItemDecision>>(approvedPlan.itemsJson)
                }.getOrNull()

                if (approvedItems == null) {
                    approvedPlanRepo?.updateExecutionState(
                        planId,
                        org.michimusic.core.models.TransferLifecycleState.FAILED,
                        errorMessage = "Plan corrupto o no deserializable"
                    )
                    client.close()
                    try { wakeLock?.release() } catch (_: Exception) {}
                    return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
                }

                val authorizedIds = approvedItems
                    .filter { it.action == org.michimusic.core.models.SyncItemAction.DOWNLOAD || it.action == org.michimusic.core.models.SyncItemAction.RESUME_PARTIAL }
                    .map { it.trackId }
                    .toSet()
                manifestTracks.filter { it.trackId in authorizedIds }
            } else {
                // Fallback strictly for non-plan sync invocations:
                when {
                    selectedTrackIds.isNotEmpty() -> manifestTracks.filter { it.trackId in selectedTrackIds }
                    scope.equals("FULL_LIBRARY", ignoreCase = true) -> manifestTracks
                    else -> emptyList() // Strictly zero downloads for empty selection!
                }
            }

            // Recover incomplete/paused AirCat checkpoints strictly linked to authorized tracks
            val aircatClient = org.michimusic.mobile.di.airCatTransferClient
            if (aircatClient != null) {
                val authorizedIdsForRecovery = candidateTracks.map { it.trackId }.toSet()
                val recoveryResult = aircatClient.recoverIncompleteTransfers(
                    remoteDeviceId = deviceId,
                    linkClient = client,
                    authorizedTrackIds = authorizedIdsForRecovery,
                )
                if (recoveryResult.isFailure) {
                    val ex = recoveryResult.exceptionOrNull()
                    if (ex is java.util.concurrent.CancellationException || ex is kotlinx.coroutines.CancellationException) {
                        throw ex
                    }
                    if (ex is org.michimusic.mobile.devices.aircat.AirCatException.UnauthorizedPeer) {
                        client.close()
                        try { wakeLock?.release() } catch (_: Exception) {}
                        return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
                    }
                    android.util.Log.w("SyncWorker", "Error recuperando checkpoints incompletos: ${ex?.message}")
                }
            }

            if (approvedPlan != null && planId.isNotEmpty()) {
                approvedPlanRepo?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.TRANSFERRING)
            }

            val downloadedIds = repository.getDownloadedIds()
            val destLibId = "lib_synced_${deviceId.ifEmpty { "node" }}"

            val itemsToDownload = candidateTracks
                .filter { it.trackId !in downloadedIds }
                .map { track ->
                    track.toDownloadItem().copy(
                        remoteDeviceId = deviceId,
                        destinationLibraryId = destLibId,
                        transferKey = "$deviceId:$destLibId:${track.trackId}",
                    )
                }

            val total = itemsToDownload.size
            if (total == 0) {
                safeSetForeground(createForegroundInfo(0, 0, "Todo sincronizado"))
                safeSetProgress(workDataOf(PROGRESS_TOTAL to 0, PROGRESS_CURRENT to 0))
                if (planId.isNotEmpty()) {
                    approvedPlanRepo?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.COMPLETED)
                }
                client.close()
                try { wakeLock?.release() } catch (_: Exception) {}
                return Result.success(workDataOf(RESULT_DOWNLOADED to 0))
            }

            safeSetProgress(workDataOf(PROGRESS_TOTAL to total, PROGRESS_CURRENT to 0))

            val libraryRepo = org.michimusic.mobile.di.musicLibraryRepository
            if (libraryRepo == null) {
                android.util.Log.e("SyncWorker", "MusicLibraryRepository no disponible en Koin DI. Abortando sincronización.")
                if (planId.isNotEmpty()) {
                    approvedPlanRepo?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.FAILED, errorMessage = "MusicLibraryRepository no disponible")
                }
                client.close()
                try { wakeLock?.release() } catch (_: Exception) {}
                return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
            }

            val manifestTrackMap = manifestTracks.associateBy { it.trackId }

            // Save initial checkpoints for all tracks scheduled to download
            itemsToDownload.forEach { item ->
                val transferKey = "$deviceId:$destLibId:${item.trackId}"
                val ext = item.format.lowercase().takeIf { it.isNotEmpty() } ?: "mp3"
                val safeDev = deviceId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "device" }
                val safeLib = destLibId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "lib" }
                val safeTrack = item.trackId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "track" }
                val targetFileName = "${safeDev}_${safeLib}_${safeTrack}.$ext"
                val existingFile = transferManager.getTrackFile(item.trackId, ext, remoteDeviceId = deviceId, destinationLibraryId = destLibId)
                val finalPath = existingFile?.absolutePath ?: java.io.File(transferManager.musicDir, targetFileName).absolutePath

                checkpointRepo?.saveCheckpoint(
                    org.michimusic.core.models.PersistentCheckpoint(
                        transferKey = transferKey,
                        transferId = "sync_${deviceId}_${item.trackId}",
                        remoteDeviceId = deviceId,
                        sourceLibraryId = deviceId,
                        destinationLibraryId = destLibId,
                        trackId = item.trackId,
                        remoteTrackId = item.trackId,
                        tempFilePath = "$finalPath.part",
                        finalFilePath = finalPath,
                        expectedSize = item.size,
                        receivedBytes = 0L,
                        verifiedBytes = 0L,
                        expectedSha256 = item.checksum,
                        transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }

            val results = transferManager.downloadTracks(
                client = client,
                items = itemsToDownload,
            ) { completed, _ ->
                safeSetProgress(workDataOf(
                    PROGRESS_TOTAL to total,
                    PROGRESS_CURRENT to completed,
                ))
                val msg = "Sincronizando $completed de $total"
                safeSetForeground(createForegroundInfo(completed, total, msg))
            }

            var downloaded = 0
            var errors = 0

            results.forEach { (id, result) ->
                val transferKey = "$deviceId:$destLibId:$id"
                result.fold(
                    onSuccess = { file ->
                        val regResult = runCatching {
                            if (!file.exists() || file.length() == 0L) {
                                error("Archivo descargado no existe o está vacío: ${file.absolutePath}")
                            }
                            trackDao.markDownloadedWithPath(id, file.absolutePath)
                            manifestTrackMap[id]?.let { meta ->
                                val identity = org.michimusic.core.models.TrackIdentity(
                                    id = meta.trackId,
                                    title = meta.title,
                                    artist = meta.artist,
                                    album = meta.album,
                                    durationMs = meta.duration.toLong(),
                                    contentHash = meta.checksum?.ifEmpty { null },
                                )
                                val location = org.michimusic.core.models.TrackLocation(
                                    id = "loc_synced_${meta.trackId}",
                                    trackId = meta.trackId,
                                    type = org.michimusic.core.models.TrackLocationType.SYNCED_MANAGED,
                                    uri = file.absolutePath,
                                    sourceDeviceId = deviceId,
                                    remoteTrackId = meta.trackId,
                                    availability = org.michimusic.core.models.LocationAvailability.AVAILABLE,
                                )
                                val regOp = libraryRepo.registerTrackWithLocation(
                                    identity = identity,
                                    location = location,
                                    libraryId = destLibId,
                                    origin = "SYNCED",
                                )
                                if (regOp.isFailure) {
                                    throw regOp.exceptionOrNull() ?: IllegalStateException("Error registrando pista en Room")
                                }
                            }
                        }

                        if (regResult.isSuccess) {
                            checkpointRepo?.updateProgress(
                                transferKey = transferKey,
                                receivedBytes = file.length(),
                                verifiedBytes = file.length(),
                                state = org.michimusic.core.models.TransferStatus.COMPLETED
                            )
                            downloaded++
                        } else {
                            android.util.Log.e("SyncWorker", "Error al registrar pista $id en la biblioteca: ${regResult.exceptionOrNull()?.message}")
                            checkpointRepo?.updateProgress(
                                transferKey = transferKey,
                                receivedBytes = if (file.exists()) file.length() else 0L,
                                verifiedBytes = 0L,
                                state = org.michimusic.core.models.TransferStatus.FAILED
                            )
                            errors++
                        }
                    },
                    onFailure = {
                        checkpointRepo?.updateProgress(
                            transferKey = transferKey,
                            receivedBytes = 0L,
                            verifiedBytes = 0L,
                            state = org.michimusic.core.models.TransferStatus.FAILED
                        )
                        errors++
                    },
                )
            }

            client.close()
            try { wakeLock?.release() } catch (_: Exception) {}
            setProgress(workDataOf(PROGRESS_TOTAL to total, PROGRESS_CURRENT to downloaded))

            if (errors > 0 && downloaded == 0) {
                if (planId.isNotEmpty()) {
                    approvedPlanRepo?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.FAILED, errorMessage = "$errors error(es) en sincronización")
                }
                Result.failure(workDataOf(RESULT_ERROR to errors, RESULT_DOWNLOADED to 0))
            } else if (errors > 0 && downloaded > 0) {
                if (planId.isNotEmpty()) {
                    approvedPlanRepo?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.PARTIAL_SUCCESS, errorMessage = "$errors error(es) en sincronización parcial")
                }
                Result.success(workDataOf(
                    RESULT_DOWNLOADED to downloaded,
                    RESULT_ERROR to errors,
                ))
            } else {
                if (planId.isNotEmpty()) {
                    approvedPlanRepo?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.COMPLETED)
                }
                Result.success(workDataOf(
                    RESULT_DOWNLOADED to downloaded,
                    RESULT_ERROR to errors,
                ))
            }
        } catch (e: java.util.concurrent.CancellationException) {
            client.close()
            try { wakeLock?.release() } catch (_: Exception) {}
            val planId = inputData.getString("planId") ?: ""
            if (planId.isNotEmpty()) {
                org.michimusic.mobile.di.approvedSyncPlanRepository?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.CANCELLED)
            }
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            client.close()
            try { wakeLock?.release() } catch (_: Exception) {}
            val planId = inputData.getString("planId") ?: ""
            if (planId.isNotEmpty()) {
                org.michimusic.mobile.di.approvedSyncPlanRepository?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.CANCELLED)
            }
            throw e
        } catch (e: LinkException) {
            client.close()
            try { wakeLock?.release() } catch (_: Exception) {}
            val planId = inputData.getString("planId") ?: ""
            if (planId.isNotEmpty()) {
                org.michimusic.mobile.di.approvedSyncPlanRepository?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.FAILED, errorMessage = e.message)
            }
            Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
        } catch (e: Exception) {
            client.close()
            try { wakeLock?.release() } catch (_: Exception) {}
            val planId = inputData.getString("planId") ?: ""
            if (planId.isNotEmpty()) {
                org.michimusic.mobile.di.approvedSyncPlanRepository?.updateExecutionState(planId, org.michimusic.core.models.TransferLifecycleState.FAILED, errorMessage = e.message)
            }
            Result.retry()
        }
    }

    private suspend fun safeSetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Ignored in test/headless environments
        }
    }

    private suspend fun safeSetProgress(data: Data) {
        try {
            setProgress(data)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Ignored in test/headless environments
        }
    }

    private fun createForegroundInfo(current: Int, total: Int, msg: String): ForegroundInfo {
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE) }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Michi Sync")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(intent)
            .setOngoing(true)
            .setProgress(total, current, false)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}

private fun org.michimusic.core.models.ManifestTrack.toTrackDto() = org.michimusic.core.models.TrackDto(
    id = trackId,
    title = title,
    artist = artist,
    album = album,
    duration = duration,
    size = size,
    format = format,
    coverId = coverId,
    year = year,
)
