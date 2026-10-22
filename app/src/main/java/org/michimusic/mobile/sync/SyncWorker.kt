package org.michimusic.mobile.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
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

        fun buildInputData(
            baseUrl: String,
            deviceId: String,
            alias: String,
            clientDeviceId: String = "",
        ) = workDataOf(
            "baseUrl" to baseUrl,
            "deviceId" to deviceId,
            "alias" to alias,
            "clientDeviceId" to clientDeviceId,
        )
    }

    override suspend fun doWork(): Result {
        val baseUrl = inputData.getString("baseUrl") ?: return Result.failure()
        val deviceId = inputData.getString("deviceId") ?: ""
        val alias = inputData.getString("alias") ?: ""
        val clientDeviceId = inputData.getString("clientDeviceId") ?: ""

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

        // Check available storage
        val storage = StatFs(applicationContext.filesDir.absolutePath)
        val freeBytes = storage.availableBytes
        val minFree = 50L * 1024 * 1024 // 50MB minimum
        if (freeBytes < minFree) {
            try { wakeLock?.release() } catch (_: Exception) {}
            return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
        }

        setForeground(createForegroundInfo(0, 0, "Iniciando sincronización..."))

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

            // Recover incomplete/paused AirCat checkpoints first
            val aircatClient = org.michimusic.mobile.di.airCatTransferClient
            if (aircatClient != null) {
                runCatching {
                    aircatClient.recoverIncompleteTransfers(deviceId, client)
                }
            }

            val downloadedIds = repository.getDownloadedIds()
            val destLibId = "lib_synced_${deviceId.ifEmpty { "node" }}"

            val itemsToDownload = manifestTracks
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
                setForeground(createForegroundInfo(0, 0, "Todo sincronizado"))
                setProgress(workDataOf(PROGRESS_TOTAL to 0, PROGRESS_CURRENT to 0))
                client.close()
                try { wakeLock?.release() } catch (_: Exception) {}
                return Result.success(workDataOf(RESULT_DOWNLOADED to 0))
            }

            setProgress(workDataOf(PROGRESS_TOTAL to total, PROGRESS_CURRENT to 0))

            val libraryRepo = org.michimusic.mobile.di.musicLibraryRepository
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
                setProgress(workDataOf(
                    PROGRESS_TOTAL to total,
                    PROGRESS_CURRENT to completed,
                ))
                val msg = "Sincronizando $completed de $total"
                setForeground(createForegroundInfo(completed, total, msg))
            }

            var downloaded = 0
            var errors = 0

            results.forEach { (id, result) ->
                val transferKey = "$deviceId:$destLibId:$id"
                result.fold(
                    onSuccess = { file ->
                        val regResult = runCatching {
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
                                libraryRepo?.registerTrackWithLocation(
                                    identity = identity,
                                    location = location,
                                    libraryId = destLibId,
                                    origin = "SYNCED",
                                )
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
                            checkpointRepo?.updateProgress(
                                transferKey = transferKey,
                                receivedBytes = file.length(),
                                verifiedBytes = file.length(),
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
                Result.failure(workDataOf(RESULT_ERROR to errors, RESULT_DOWNLOADED to 0))
            } else {
                Result.success(workDataOf(
                    RESULT_DOWNLOADED to downloaded,
                    RESULT_ERROR to errors,
                ))
            }
        } catch (e: LinkException) {
            client.close()
            try { wakeLock?.release() } catch (_: Exception) {}
            Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
        } catch (e: Exception) {
            client.close()
            try { wakeLock?.release() } catch (_: Exception) {}
            Result.retry()
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
