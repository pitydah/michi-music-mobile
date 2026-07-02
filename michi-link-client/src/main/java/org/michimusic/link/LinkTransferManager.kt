package org.michimusic.link

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.michimusic.core.models.DownloadItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class LinkTransferManager(
    private val context: Context,
) {
    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val musicDir: File
        get() = File(context.filesDir, "synced_music").also { it.mkdirs() }

    suspend fun downloadTrack(
        client: LinkClient,
        item: DownloadItem,
    ): Result<File> = withContext(Dispatchers.IO) {
        val ext = item.format.lowercase().takeIf { it.isNotEmpty() } ?: "mp3"
        val file = File(musicDir, "${safeFileName(item.trackId)}.$ext")

        if (file.exists() && file.length() > 0) {
            if (item.checksum.isNotEmpty() && !verifyChecksum(file, item.checksum)) {
                file.delete()
            } else {
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Completed(file.length()))
                return@withContext Result.success(file)
            }
        }

        _downloads.value += (item.trackId to DownloadProgress.Downloading(0L))

        val result = FileOutputStream(file).use { outputStream ->
            client.streamTrack(
                trackId = item.trackId,
                outputStream = outputStream,
                startBytes = 0,
            )
        }

        result.onSuccess { bytes ->
            if (item.checksum.isNotEmpty()) {
                if (verifyChecksum(file, item.checksum)) {
                    _downloads.value += (item.trackId to DownloadProgress.Completed(bytes))
                } else {
                    file.delete()
                    _downloads.value += (item.trackId to DownloadProgress.Failed("Checksum mismatch"))
                    return@withContext Result.failure(ChecksumException(item.trackId))
                }
            } else {
                _downloads.value += (item.trackId to DownloadProgress.Completed(bytes))
            }
        }.onFailure { e ->
            file.delete()
            _downloads.value += (item.trackId to DownloadProgress.Failed(e.message ?: "Error"))
        }

        result.map { file }
    }

    suspend fun downloadTracks(
        client: LinkClient,
        items: List<DownloadItem>,
        maxConcurrent: Int = 4,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Map<String, Result<File>> = withContext(Dispatchers.IO) {
        val total = items.size
        val mutex = Any()
        val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))
        var completed = 0
        val results = mutableMapOf<String, Result<File>>()

        coroutineScope {
            items.map { item ->
                async {
                    val result = semaphore.withPermit {
                        downloadTrack(client, item)
                    }
                    synchronized(mutex) {
                        results[item.trackId] = result
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
        val file = File(musicDir, "${safeFileName(trackId)}.$ext")
        return file.takeIf { it.exists() }
    }

    fun clearDownloads() {
        musicDir.listFiles()?.forEach { it.delete() }
        _downloads.value = emptyMap()
    }

    private fun verifyChecksum(file: File, expected: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            hex == expected.lowercase()
        } catch (_: Exception) {
            false
        }
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "track" }
}

sealed class DownloadProgress {
    data object Queued : DownloadProgress()
    data class Downloading(val bytesDownloaded: Long) : DownloadProgress()
    data class Completed(val totalBytes: Long) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
}

class ChecksumException(trackId: String) : Exception("Checksum falló para $trackId")
