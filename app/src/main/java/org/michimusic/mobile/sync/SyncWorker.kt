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

        fun buildInputData(
            baseUrl: String,
            sessionToken: String,
            deviceId: String,
            alias: String,
            deviceToken: String = "",
            clientDeviceId: String = "",
        ) = workDataOf(
            "baseUrl" to baseUrl,
            "sessionToken" to sessionToken,
            "deviceId" to deviceId,
            "alias" to alias,
            "deviceToken" to deviceToken,
            "clientDeviceId" to clientDeviceId,
        )
    }

    override suspend fun doWork(): Result {
        val baseUrl = inputData.getString("baseUrl") ?: return Result.failure()
        val sessionToken = inputData.getString("sessionToken") ?: ""
        val deviceToken = inputData.getString("deviceToken") ?: ""
        val deviceId = inputData.getString("deviceId") ?: ""
        val alias = inputData.getString("alias") ?: ""
        val clientDeviceId = inputData.getString("clientDeviceId") ?: ""

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "michi:sync")
        wakeLock?.acquire(10_000L) // 10s timeout base, se renueva durante descarga

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
            sessionToken = sessionToken,
            deviceToken = deviceToken,
            clientDeviceId = clientDeviceId.ifEmpty { deviceId },
        )
        val trackDao = try {
            KoinJavaComponent.get<TrackDao>(TrackDao::class.java)
        } catch (_: Exception) {
            client.close()
            try { wakeLock?.release() } catch (_: Exception) {}
            return Result.failure(workDataOf(RESULT_ERROR to 1, RESULT_DOWNLOADED to 0))
        }
        val playlistDao = try {
            KoinJavaComponent.get<PlaylistDao>(PlaylistDao::class.java)
        } catch (_: Exception) {
            null
        }
        val repository = SyncedTrackRepository(trackDao, playlistDao)
        val transferManager = LinkTransferManager(applicationContext)

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

            repository.saveLibrary(
                manifest.tracks.map { it.toTrackDto() }
            )

            if (manifest.playlists.isNotEmpty()) {
                repository.saveManifestPlaylists(
                    manifest.playlists.map { it.toDomainPlaylist() }
                )
            }

            val downloadedIds = repository.getDownloadedIds()

            val itemsToDownload = manifest.tracks
                .filter { it.trackId !in downloadedIds }
                .map { mt ->
                    DownloadItem(
                        trackId = mt.trackId,
                        title = mt.title,
                        format = mt.format,
                        checksum = mt.checksum,
                        size = mt.size,
                        downloadPath = mt.downloadPath,
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

            val results = transferManager.downloadTracks(
                client = client,
                items = itemsToDownload,
            ) { completed, _ ->
                kotlinx.coroutines.runBlocking {
                    setProgress(workDataOf(
                        PROGRESS_TOTAL to total,
                        PROGRESS_CURRENT to completed,
                    ))
                    val msg = "Sincronizando $completed de $total"
                    setForeground(createForegroundInfo(completed, total, msg))
                }
            }

            var downloaded = 0
            var errors = 0
            results.forEach { (id, result) ->
                result.fold(
                    onSuccess = { file ->
                        trackDao.markDownloadedWithPath(id, file.absolutePath)
                        downloaded++
                    },
                    onFailure = { errors++ },
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
