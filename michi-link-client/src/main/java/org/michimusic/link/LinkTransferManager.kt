package org.michimusic.link

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
        val fileName = "${safeFileName(item.trackId)}.$ext"
        val file = File(musicDir, fileName)
        val tempFile = File(musicDir, "$fileName.part")

        if (file.exists() && file.length() > 0) {
            if (item.checksum.isNotEmpty() && !verifyChecksum(file, item.checksum)) {
                file.delete()
            } else {
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Completed(file.length()))
                return@withContext Result.success(file)
            }
        }

        _downloads.value += (item.trackId to DownloadProgress.Downloading(0L))
        tempFile.delete()

        val result = FileOutputStream(tempFile).use { outputStream ->
            client.streamTrack(
                trackId = item.trackId,
                outputStream = outputStream,
                startBytes = 0,
            )
        }

        result.onSuccess { bytes ->
            if (item.size > 0 && bytes != item.size) {
                tempFile.delete()
                _downloads.value += (item.trackId to DownloadProgress.Failed("Size mismatch"))
                return@withContext Result.failure(SizeMismatchException(item.trackId, item.size, bytes))
            }
            if (item.checksum.isNotEmpty()) {
                if (verifyChecksum(tempFile, item.checksum)) {
                    if (!promoteDownload(tempFile, file)) {
                        _downloads.value += (item.trackId to DownloadProgress.Failed("Unable to finalize download"))
                        return@withContext Result.failure(DownloadFinalizeException(item.trackId))
                    }
                    _downloads.value += (item.trackId to DownloadProgress.Completed(file.length()))
                } else {
                    tempFile.delete()
                    _downloads.value += (item.trackId to DownloadProgress.Failed("Checksum mismatch"))
                    return@withContext Result.failure(ChecksumException(item.trackId))
                }
            } else {
                if (!promoteDownload(tempFile, file)) {
                    _downloads.value += (item.trackId to DownloadProgress.Failed("Unable to finalize download"))
                    return@withContext Result.failure(DownloadFinalizeException(item.trackId))
                }
                _downloads.value += (item.trackId to DownloadProgress.Completed(file.length()))
            }
        }.onFailure { e ->
            tempFile.delete()
            _downloads.value += (item.trackId to DownloadProgress.Failed(e.message ?: "Error"))
        }

        result.map { file }
    }

    suspend fun downloadTracks(
        client: LinkClient,
        items: List<DownloadItem>,
        maxConcurrent: Int = 4,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): Map<String, Result<File>> = withContext(Dispatchers.IO) {
        val total = items.size
        val mutex = Mutex()
        val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))
        var completed = 0
        val results = mutableMapOf<String, Result<File>>()

        coroutineScope {
            items.map { item ->
                async {
                    val result = semaphore.withPermit {
                        downloadTrack(client, item)
                    }
                    mutex.withLock {
                        results[item.trackId] = result
                        completed++
                    }
                    onProgress(completed, total)
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

    private fun promoteDownload(tempFile: File, finalFile: File): Boolean {
        if (!tempFile.exists() || tempFile.length() <= 0L) return false
        if (finalFile.exists() && !finalFile.delete()) return false
        return tempFile.renameTo(finalFile)
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
class SizeMismatchException(trackId: String, expected: Long, actual: Long) :
    Exception("Tamaño inválido para $trackId: esperado $expected, recibido $actual")

class DownloadFinalizeException(trackId: String) : Exception("No se pudo finalizar descarga de $trackId")
