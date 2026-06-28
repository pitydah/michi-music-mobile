package org.michimusic.mobile.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.sync.MichiSyncClient
import org.michimusic.sync.SyncTransferManager

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
        ) = workDataOf(
            "baseUrl" to baseUrl,
            "sessionToken" to sessionToken,
            "deviceId" to deviceId,
            "alias" to alias,
        )
    }

    override suspend fun doWork(): Result {
        val baseUrl = inputData.getString("baseUrl") ?: return Result.failure()
        val sessionToken = inputData.getString("sessionToken") ?: return Result.failure()
        val deviceId = inputData.getString("deviceId") ?: ""
        val alias = inputData.getString("alias") ?: ""

        setForeground(createForegroundInfo(0, 0, "Iniciando sincronización..."))

        val client = MichiSyncClient(baseUrl = baseUrl, sessionToken = sessionToken)
        val db = MichiDatabase.buildForSync(applicationContext)
        val trackDao = db.trackDao()
        val repository = SyncedTrackRepository(trackDao)
        val transferManager = SyncTransferManager(applicationContext)

        return try {
            val libraryResult = client.fetchLibrary()
            if (libraryResult.isFailure) return Result.retry()

            val library = libraryResult.getOrThrow()
            repository.saveLibrary(library.tracks)

            val tracksToDownload = if (deviceId.isNotEmpty()) {
                val manifestResult = client.fetchSyncManifest(deviceId)
                if (manifestResult.isSuccess) {
                    val manifest = manifestResult.getOrThrow()
                    manifest.tracks.mapNotNull { manifestTrack ->
                        val cached = repository.getById(manifestTrack.trackId)
                        if (cached == null || !cached.downloaded) {
                            manifestTrack.trackId to manifestTrack.title
                        } else null
                    }
                } else {
                    library.tracks.map { it.id to it.title }
                }
            } else {
                library.tracks.map { it.id to it.title }
            }

            val total = tracksToDownload.size
            if (total == 0) {
                setForeground(createForegroundInfo(total, total, "Todo sincronizado"))
                return Result.success(workDataOf(RESULT_DOWNLOADED to 0))
            }

            val results = transferManager.downloadTracks(
                client = client,
                trackIds = tracksToDownload,
                onProgress = { completed, _ ->
                    val msg = "Sincronizando $completed de $total"
                    kotlinx.coroutines.runBlocking {
                        setForeground(createForegroundInfo(completed, total, msg))
                    }
                },
            )

            var downloaded = 0
            var errors = 0
            results.forEach { (id, result) ->
                result.onSuccess { file ->
                    trackDao.markDownloadedWithPath(id, file.absolutePath)
                    downloaded++
                }.onFailure {
                    errors++
                }
            }

            client.close()
            db.close()

            if (errors > 0 && downloaded == 0) {
                Result.failure(workDataOf(RESULT_ERROR to errors, RESULT_DOWNLOADED to 0))
            } else {
                Result.success(workDataOf(
                    RESULT_DOWNLOADED to downloaded,
                    RESULT_ERROR to errors,
                ))
            }
        } catch (e: Exception) {
            client.close()
            db.close()
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
