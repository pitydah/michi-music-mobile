package org.michimusic.sync

import android.content.Context
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SyncTransferManager(
    private val context: Context,
) {
    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val musicDir: File
        get() = File(context.filesDir, "synced_music").also { it.mkdirs() }

    suspend fun downloadTrack(
        client: MichiSyncClient,
        trackId: String,
        title: String,
        format: String,
    ): Result<File> = withContext(Dispatchers.IO) {
        val ext = format.lowercase().takeIf { it.isNotEmpty() } ?: "mp3"
        val file = File(musicDir, "${trackId}.$ext")

        if (file.exists() && file.length() > 0) {
            _downloads.value = _downloads.value + (trackId to DownloadProgress.Completed(file.length()))
            return@withContext Result.success(file)
        }

        _downloads.value = _downloads.value + (trackId to DownloadProgress.Downloading(0L))
        var downloadedBytes = 0L

        val result = client.streamTrack(
            trackId = trackId,
            outputStream = FileOutputStream(file),
            startBytes = 0,
        )

        result.onSuccess { bytes ->
            downloadedBytes = bytes
            _downloads.value = _downloads.value + (trackId to DownloadProgress.Completed(bytes))
        }.onFailure { e ->
            file.delete()
            _downloads.value = _downloads.value + (trackId to DownloadProgress.Failed(e.message ?: "Error"))
        }

        result.map { file }
    }

    suspend fun downloadTracks(
        client: MichiSyncClient,
        trackIds: List<Pair<String, String>>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Map<String, Result<File>> = withContext(Dispatchers.IO) {
        val total = trackIds.size
        val mutex = Any()
        var completed = 0
        val results = mutableMapOf<String, Result<File>>()

        coroutineScope {
            trackIds.map { (id, title) ->
                async {
                    val result = downloadTrack(client, id, title, "")
                    synchronized(mutex) {
                        results[id] = result
                        completed++
                        onProgress(completed, total)
                    }
                    result
                }
            }.awaitAll()
        }

        results
    }

    fun getTrackFile(trackId: String, format: String): File? {
        val ext = format.lowercase().takeIf { it.isNotEmpty() } ?: "mp3"
        val file = File(musicDir, "${trackId}.$ext")
        return file.takeIf { it.exists() }
    }

    fun clearDownloads() {
        musicDir.listFiles()?.forEach { it.delete() }
        _downloads.value = emptyMap()
    }
}

sealed class DownloadProgress {
    data object Queued : DownloadProgress()
    data class Downloading(val bytesDownloaded: Long) : DownloadProgress()
    data class Completed(val totalBytes: Long) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
}
