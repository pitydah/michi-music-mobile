package org.michimusic.link

import android.content.Context
import kotlinx.coroutines.CancellationException
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
import org.michimusic.link.transfer.ResumeMode
import org.michimusic.link.transfer.TransferCheckpoint
import org.michimusic.link.transfer.TransferChecksumMismatchException
import org.michimusic.link.transfer.TransferETagMismatchException
import org.michimusic.link.transfer.TransferProgress
import org.michimusic.link.transfer.TransferPromotionException
import org.michimusic.link.transfer.TransferSizeMismatchException
import org.michimusic.link.transfer.TransferState
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class LinkTransferManager(
    private val context: Context,
    private val checkpointRepository: org.michimusic.core.models.TransferCheckpointRepository? = null,
) {
    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val _checkpoints = MutableStateFlow<Map<String, TransferCheckpoint>>(emptyMap())
    val checkpoints: StateFlow<Map<String, TransferCheckpoint>> = _checkpoints.asStateFlow()

    val musicDir: File
        get() = File(context.filesDir, "synced_music").also { it.mkdirs() }

    suspend fun downloadTrack(
        client: LinkClient,
        item: DownloadItem,
    ): Result<File> = withContext(Dispatchers.IO) {
        val ext = item.format.lowercase().takeIf { it.isNotEmpty() } ?: "mp3"
        val fileName = if (item.remoteDeviceId.isNotEmpty() && item.destinationLibraryId.isNotEmpty()) {
            "${safeFileName(item.remoteDeviceId)}_${safeFileName(item.destinationLibraryId)}_${safeFileName(item.trackId)}.$ext"
        } else {
            "${safeFileName(item.trackId)}.$ext"
        }
        val file = File(musicDir, fileName)
        val tempFile = File(musicDir, "$fileName.part")

        // 1. If file already exists and is intact, return immediately
        if (file.exists() && file.length() > 0) {
            val len = file.length()
            if (item.checksum.isEmpty() || verifyChecksum(file, item.checksum)) {
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Completed(len))
                updateCheckpoint(item.effectiveTransferKey) {
                    TransferCheckpoint(
                        trackId = item.trackId,
                        tempPath = tempFile.absolutePath,
                        finalPath = file.absolutePath,
                        bytesVerified = len,
                        expectedSize = if (item.size > 0) item.size else len,
                        sha256Expected = item.checksum,
                        resumeMode = ResumeMode.NOT_APPLICABLE,
                        state = TransferState.COMPLETED,
                    )
                }
                return@withContext Result.success(file)
            }
            // If checksum differs (e.g. updating track or re-downloading), do NOT delete existing file yet!
            // It will be atomically replaced by promoteDownload only once the new .part download is fully verified.
        }


        // 2. Preflight checkpoint inspection: check in-memory or recover from persistent repository
        val transferKey = item.effectiveTransferKey
        var existingPartBytes = if (tempFile.exists()) tempFile.length() else 0L
        var savedCheckpoint = _checkpoints.value[transferKey] ?: if (item.remoteDeviceId.isEmpty() || item.destinationLibraryId.isEmpty()) _checkpoints.value[item.trackId] else null
        if (savedCheckpoint == null && checkpointRepository != null) {
            val persisted = checkpointRepository.getCheckpoint(transferKey)
                ?: if (item.remoteDeviceId.isEmpty() || item.destinationLibraryId.isEmpty()) {
                    checkpointRepository.getLatestCheckpointForTrack(item.trackId)
                } else null
            if (persisted != null) {
                savedCheckpoint = TransferCheckpoint(
                    trackId = persisted.trackId,
                    tempPath = persisted.tempFilePath,
                    finalPath = persisted.finalFilePath,
                    bytesVerified = persisted.verifiedBytes,
                    expectedSize = persisted.expectedSize,
                    etag = persisted.etag,
                    sha256Expected = persisted.expectedSha256,
                    resumeMode = if (persisted.verifiedBytes > 0) ResumeMode.RESUMED_FROM_OFFSET else ResumeMode.NOT_APPLICABLE,
                    state = when (persisted.transferState) {
                        org.michimusic.core.models.TransferStatus.DOWNLOADING -> TransferState.DOWNLOADING
                        org.michimusic.core.models.TransferStatus.COMPLETED -> TransferState.COMPLETED
                        org.michimusic.core.models.TransferStatus.PAUSED -> TransferState.PAUSED
                        org.michimusic.core.models.TransferStatus.VERIFYING -> TransferState.VERIFYING
                        org.michimusic.core.models.TransferStatus.COMMITTING -> TransferState.COMMITTING
                        else -> TransferState.FAILED
                    },
                    updatedAt = persisted.updatedAt,
                )
                _checkpoints.value = _checkpoints.value + (transferKey to savedCheckpoint)
            }
        }
        val expectedEtag = savedCheckpoint?.etag?.ifEmpty { null }

        var startOffset = 0L
        var shouldAppend = false

        if (existingPartBytes > 0) {
            if (item.size > 0 && existingPartBytes >= item.size) {
                // .part is larger or equal to full size; reset to prevent corruption
                tempFile.delete()
                existingPartBytes = 0L
            } else {
                startOffset = existingPartBytes
                shouldAppend = true
            }
        }

        _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Downloading(startOffset))
        updateCheckpoint(transferKey) {
            TransferCheckpoint(
                trackId = item.trackId,
                tempPath = tempFile.absolutePath,
                finalPath = file.absolutePath,
                bytesVerified = startOffset,
                expectedSize = item.size,
                etag = expectedEtag ?: "",
                sha256Expected = item.checksum,
                resumeMode = if (startOffset > 0) ResumeMode.RESUMED_FROM_OFFSET else ResumeMode.NOT_APPLICABLE,
                state = TransferState.DOWNLOADING,
            )
        }
        checkpointRepository?.updateProgress(
            transferKey = transferKey,
            receivedBytes = startOffset,
            verifiedBytes = startOffset,
            state = org.michimusic.core.models.TransferStatus.DOWNLOADING
        )

        try {
            var actualResumeMode = if (startOffset > 0) ResumeMode.RESUMED_FROM_OFFSET else ResumeMode.NOT_APPLICABLE
            var responseEtag = expectedEtag ?: ""

            // Stream track with Range support
            var streamResult = try {
                // If append mode, write to existing file; otherwise create new
                FileOutputStream(tempFile, shouldAppend).use { outputStream ->
                    client.streamTrackRange(
                        trackId = item.trackId,
                        outputStream = outputStream,
                        startBytes = startOffset,
                        expectedEtag = expectedEtag,
                    ) { bytesRead, _ ->
                        val currentBytes = if (shouldAppend) startOffset + bytesRead else bytesRead
                        _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Downloading(currentBytes))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                Result.failure(ex)
            }

            // If 416 Range Not Satisfiable (e.g. file size changed or corrupt local offset >= server size),
            // reset and restart honestly from 0
            if (streamResult.isFailure && startOffset > 0 && streamResult.exceptionOrNull() is org.michimusic.link.errors.LinkException.RangeNotSatisfiable) {
                tempFile.delete()
                startOffset = 0L
                shouldAppend = false
                actualResumeMode = ResumeMode.RESTARTED_FROM_ZERO
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Downloading(0L))

                streamResult = try {
                    FileOutputStream(tempFile, false).use { outputStream ->
                        client.streamTrackRange(
                            trackId = item.trackId,
                            outputStream = outputStream,
                            startBytes = 0L,
                        ) { bytesRead, _ ->
                            _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Downloading(bytesRead))
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Exception) {
                    Result.failure(ex)
                }
            }

            if (streamResult.isFailure) {
                val error = streamResult.exceptionOrNull() ?: Exception("Unknown error")
                // On failure, if it was an ETag mismatch or partial content error, we can handle it
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed(error.message ?: "Download failed"))
                updateCheckpoint(transferKey) {
                    it?.copy(state = TransferState.FAILED) ?: TransferCheckpoint(
                        trackId = item.trackId,
                        tempPath = tempFile.absolutePath,
                        finalPath = file.absolutePath,
                        bytesVerified = if (tempFile.exists()) tempFile.length() else 0L,
                        expectedSize = item.size,
                        state = TransferState.FAILED,
                    )
                }
                return@withContext Result.failure(error)
            }

            val rangeInfo = streamResult.getOrThrow()
            responseEtag = rangeInfo.etag

            // Check if server refused Range and sent 200 OK from scratch
            if (startOffset > 0 && !rangeInfo.isPartialContent) {
                // Server ignored range and sent the entire file!
                // But since we opened with append=true, tempFile contains startOffset + fullFile!
                // We MUST restart honestly from zero to prevent corrupted audio file.
                actualResumeMode = ResumeMode.RESTARTED_FROM_ZERO
                tempFile.delete()
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Downloading(0L))
                
                val restartedResult = FileOutputStream(tempFile, false).use { outputStream ->
                    client.streamTrackRange(
                        trackId = item.trackId,
                        outputStream = outputStream,
                        startBytes = 0L,
                    ) { bytesRead, _ ->
                        _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Downloading(bytesRead))
                    }
                }
                if (restartedResult.isFailure) {
                    val err = restartedResult.exceptionOrNull() ?: Exception("Restarted download failed")
                    _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed(err.message ?: "Failed"))
                    return@withContext Result.failure(err)
                }
            }

            // 3. Verifying stage
            val downloadedLength = tempFile.length()
            updateCheckpoint(transferKey) {
                it?.copy(
                    bytesVerified = downloadedLength,
                    etag = responseEtag,
                    resumeMode = actualResumeMode,
                    state = TransferState.VERIFYING,
                )
            }

            // Size check
            if (item.size > 0 && downloadedLength != item.size) {
                tempFile.delete()
                val err = TransferSizeMismatchException(item.trackId, item.size, downloadedLength)
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed("Size mismatch"))
                updateCheckpoint(transferKey) { it?.copy(state = TransferState.FAILED) }
                return@withContext Result.failure(err)
            }

            // SHA-256 Checksum check
            if (item.checksum.isNotEmpty()) {
                val matches = verifyChecksum(tempFile, item.checksum)
                if (!matches) {
                    tempFile.delete()
                    val actualHex = calculateSha256(tempFile)
                    val err = TransferChecksumMismatchException(item.trackId, item.checksum, actualHex)
                    _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed("Checksum mismatch"))
                    updateCheckpoint(transferKey) { it?.copy(state = TransferState.FAILED) }
                    return@withContext Result.failure(err)
                }
            }

            // 4. Committing stage: Atomic promotion
            updateCheckpoint(transferKey) { it?.copy(state = TransferState.COMMITTING) }
            val promoted = promoteDownload(tempFile, file)
            if (!promoted) {
                val err = TransferPromotionException(item.trackId, "Atomic rename/replace failed")
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed("Unable to finalize download"))
                updateCheckpoint(transferKey) { it?.copy(state = TransferState.FAILED) }
                return@withContext Result.failure(err)
            }

            // 5. Completed
            _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Completed(file.length()))
            updateCheckpoint(transferKey) {
                it?.copy(
                    bytesVerified = file.length(),
                    state = TransferState.COMPLETED,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            Result.success(file)

        } catch (ce: CancellationException) {
            // Cancel preserves safe .part for later resume
            val currentBytes = if (tempFile.exists()) tempFile.length() else 0L
            _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed("Cancelled"))
            updateCheckpoint(transferKey) {
                it?.copy(
                    bytesVerified = currentBytes,
                    state = TransferState.PAUSED,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            checkpointRepository?.updateProgress(
                transferKey = transferKey,
                receivedBytes = currentBytes,
                verifiedBytes = currentBytes,
                state = org.michimusic.core.models.TransferStatus.PAUSED
            )
            throw ce
        } catch (e: Exception) {
            val currentBytes = if (tempFile.exists()) tempFile.length() else 0L
            _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed(e.message ?: "Error"))
            updateCheckpoint(transferKey) {
                it?.copy(
                    bytesVerified = currentBytes,
                    state = TransferState.FAILED,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            checkpointRepository?.updateProgress(
                transferKey = transferKey,
                receivedBytes = currentBytes,
                verifiedBytes = currentBytes,
                state = org.michimusic.core.models.TransferStatus.FAILED
            )
            Result.failure(e)
        }
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

    fun getTrackFile(
        trackId: String,
        format: String,
        remoteDeviceId: String = "",
        destinationLibraryId: String = "",
    ): File? {
        val ext = format.lowercase().takeIf { it.isNotEmpty() } ?: "mp3"
        val fileName = if (remoteDeviceId.isNotEmpty() && destinationLibraryId.isNotEmpty()) {
            "${safeFileName(remoteDeviceId)}_${safeFileName(destinationLibraryId)}_${safeFileName(trackId)}.$ext"
        } else {
            "${safeFileName(trackId)}.$ext"
        }
        val file = File(musicDir, fileName)
        if (file.exists()) return file

        return null
    }

    fun clearDownloads() {
        musicDir.listFiles()?.forEach { it.delete() }
        _downloads.value = emptyMap()
        _checkpoints.value = emptyMap()
    }

    private fun updateCheckpoint(trackId: String, transform: (TransferCheckpoint?) -> TransferCheckpoint?) {
        val current = _checkpoints.value
        val updated = transform(current[trackId])
        _checkpoints.value = if (updated != null) current + (trackId to updated) else current - trackId
    }

    private fun verifyChecksum(file: File, expected: String): Boolean {
        if (!file.exists()) return false
        val hex = calculateSha256(file)
        return hex.equals(expected.trim(), ignoreCase = true)
    }

    private fun calculateSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Promotes .part to final file atomically.
     * If finalFile already exists and is valid, preserves it until promotion succeeds.
     */
    private fun promoteDownload(tempFile: File, finalFile: File): Boolean {
        if (!tempFile.exists() || tempFile.length() <= 0L) return false

        val backupFile = if (finalFile.exists()) {
            File(finalFile.parentFile, "${finalFile.name}.bak").also {
                if (it.exists()) it.delete()
                finalFile.renameTo(it)
            }
        } else null

        val renamed = tempFile.renameTo(finalFile)
        if (renamed) {
            backupFile?.delete()
            return true
        } else {
            // Restore previous good file if rename failed
            backupFile?.renameTo(finalFile)
            return false
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
class SizeMismatchException(trackId: String, expected: Long, actual: Long) :
    Exception("Tamaño inválido para $trackId: esperado $expected, recibido $actual")

class DownloadFinalizeException(trackId: String) : Exception("No se pudo finalizar descarga de $trackId")
