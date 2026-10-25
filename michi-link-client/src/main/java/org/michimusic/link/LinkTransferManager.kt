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
        val (file, tempFile) = org.michimusic.core.models.TransferFileIdentity.resolveFiles(
            musicDir = musicDir,
            remoteDeviceId = item.remoteDeviceId,
            destinationLibraryId = item.destinationLibraryId,
            trackId = item.trackId,
            format = item.format,
            planId = item.planId,
            representationId = item.representationId,
        )

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

        var existingPartBytes = if (tempFile.exists()) tempFile.length() else 0L
        var startOffset = 0L
        var shouldAppend = false

        if (existingPartBytes > 0) {
            val recordedBytes = savedCheckpoint?.bytesVerified?.takeIf { it > 0L } ?: existingPartBytes
            val sizeOverflow = item.size > 0 && existingPartBytes >= item.size
            if (sizeOverflow || (recordedBytes > 0 && existingPartBytes < recordedBytes)) {
                // Inconsistent or overflowing partial file: purge to prevent corruption
                tempFile.delete()
                existingPartBytes = 0L
                startOffset = 0L
                shouldAppend = false
            } else if (recordedBytes > 0) {
                // Align physical file size with recordedBytes to guarantee that
                // FileOutputStream(tempFile, append=true) starts writing at EXACTLY the same byte position
                // requested in the HTTP Range: header (startBytes = recordedBytes)
                if (existingPartBytes > recordedBytes) {
                    try {
                        java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                            raf.setLength(recordedBytes)
                        }
                        existingPartBytes = tempFile.length()
                    } catch (_: Exception) {
                        tempFile.delete()
                        existingPartBytes = 0L
                    }
                }
                if (existingPartBytes == recordedBytes && existingPartBytes > 0) {
                    startOffset = recordedBytes
                    shouldAppend = true
                } else {
                    startOffset = 0L
                    shouldAppend = false
                }
            } else {
                // If there's no accredited verifiedBytes in checkpoint, do not blindly append unverified bytes
                tempFile.delete()
                existingPartBytes = 0L
                startOffset = 0L
                shouldAppend = false
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
                val currentBytes = if (tempFile.exists()) tempFile.length() else 0L
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed(error.message ?: "Download failed"))
                updateCheckpoint(transferKey) {
                    it?.copy(
                        bytesVerified = startOffset,
                        state = TransferState.FAILED,
                    ) ?: TransferCheckpoint(
                        trackId = item.trackId,
                        tempPath = tempFile.absolutePath,
                        finalPath = file.absolutePath,
                        bytesVerified = startOffset,
                        expectedSize = item.size,
                        state = TransferState.FAILED,
                    )
                }
                checkpointRepository?.updateProgress(
                    transferKey = transferKey,
                    receivedBytes = currentBytes,
                    verifiedBytes = startOffset,
                    state = org.michimusic.core.models.TransferStatus.FAILED
                )
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
                verifiedBytes = startOffset,
                state = org.michimusic.core.models.TransferStatus.PAUSED
            )
            throw ce
        } catch (e: Exception) {
            val currentBytes = if (tempFile.exists()) tempFile.length() else 0L
            _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed(e.message ?: "Error"))
            updateCheckpoint(transferKey) {
                it?.copy(
                    bytesVerified = startOffset,
                    state = TransferState.FAILED,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            checkpointRepository?.updateProgress(
                transferKey = transferKey,
                receivedBytes = currentBytes,
                verifiedBytes = startOffset,
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
        representationId: String = "",
    ): File? {
        val fileName = org.michimusic.core.models.TransferFileIdentity.buildFinalFileName(
            remoteDeviceId = remoteDeviceId,
            destinationLibraryId = destinationLibraryId,
            trackId = trackId,
            format = format,
            representationId = representationId,
        )
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

    fun verifyChecksum(file: File, expected: String): Boolean {
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
     * The backup file (.bak) is preserved so callers can roll back if Room registration or post-transfer integrity fails.
     * If a .bak already exists from an earlier interrupted cycle, it is safely preserved (never deleted blindly).
     */
    private fun promoteDownload(tempFile: File, finalFile: File): Boolean {
        if (!tempFile.exists() || tempFile.length() <= 0L) return false

        val bak = File(finalFile.parentFile, "${finalFile.name}.bak")
        val backupFile = if (finalFile.exists()) {
            if (bak.exists()) {
                // If a backup already exists (e.g. from an interrupted earlier attempt),
                // verify if finalFile is identical or if we need to preserve both.
                // In all cases, never delete an existing .bak before finalFile is successfully replaced.
                val stagedBak = File(finalFile.parentFile, "${finalFile.name}.bak.staged")
                if (stagedBak.exists()) stagedBak.delete()
                val movedToStaged = finalFile.renameTo(stagedBak)
                if (!movedToStaged) return false
                stagedBak
            } else {
                val moved = finalFile.renameTo(bak)
                if (!moved) {
                    // Moving existing file to backup failed: abort immediately without touching finalFile
                    return false
                }
                bak
            }
        } else null

        val renamed = tempFile.renameTo(finalFile)
        if (renamed) {
            // If we used a staged backup and finalFile was promoted, atomically consolidate to .bak
            if (backupFile != null && backupFile.name.endsWith(".bak.staged")) {
                if (bak.exists()) bak.delete()
                backupFile.renameTo(bak)
            }
            // Keep backupFile on disk so callers can roll back via restoreBackup(finalFile)
            // Callers that succeed in registration should call discardBackup(finalFile)
            return true
        } else {
            // Restore previous good file if rename failed
            if (backupFile != null) {
                if (backupFile.name.endsWith(".bak.staged")) {
                    backupFile.renameTo(finalFile)
                } else {
                    backupFile.renameTo(finalFile)
                }
            }
            return false
        }
    }

    fun restoreBackup(finalFile: File): Boolean {
        val backupFile = File(finalFile.parentFile, "${finalFile.name}.bak")
        val stagedBackup = File(finalFile.parentFile, "${finalFile.name}.bak.staged")
        val effectiveBackup = when {
            backupFile.exists() && backupFile.length() > 0L -> backupFile
            stagedBackup.exists() && stagedBackup.length() > 0L -> stagedBackup
            backupFile.exists() -> backupFile
            stagedBackup.exists() -> stagedBackup
            else -> null
        } ?: return false

        if (finalFile.exists()) finalFile.delete()
        val restored = effectiveBackup.renameTo(finalFile)
        if (stagedBackup.exists()) stagedBackup.delete()
        return restored
    }

    fun discardBackup(finalFile: File): Boolean {
        val backupFile = File(finalFile.parentFile, "${finalFile.name}.bak")
        val stagedBackup = File(finalFile.parentFile, "${finalFile.name}.bak.staged")
        var deleted = false
        if (backupFile.exists()) {
            deleted = backupFile.delete() || deleted
        }
        if (stagedBackup.exists()) {
            deleted = stagedBackup.delete() || deleted
        }
        return deleted
    }

    /**
     * Reconciles orphan .bak files remaining in musicDir after an unexpected process crash.
     * If a target file is missing or corrupted/empty while its .bak exists, restores the .bak.
     * If a target file is already present and non-empty, checks Room registration via [isRegisteredInDatabase]:
     *   - If targetFile is confirmed registered in Room, the redundant .bak is safely purged.
     *   - If targetFile is NOT registered in Room, targetFile is considered an uncommitted orphaned promotion
     *     and the valid .bak is restored over it to prevent loss of the previously registered audio copy.
     * Returns the count of restored files.
     */
    suspend fun reconcileOrphanBackups(
        targetDir: File = musicDir,
        isRegisteredInDatabase: (suspend (File) -> Boolean)? = null,
    ): Int = withContext(Dispatchers.IO) {
        if (!targetDir.exists() || !targetDir.isDirectory) return@withContext 0
        var reconciledCount = 0
        val backupFiles = targetDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".bak") || file.name.endsWith(".bak.staged"))
        } ?: return@withContext 0

        for (backup in backupFiles) {
            val targetName = backup.name.removeSuffix(".bak.staged").removeSuffix(".bak")
            val targetFile = File(targetDir, targetName)

            if (!targetFile.exists() || targetFile.length() == 0L) {
                if (targetFile.exists()) targetFile.delete()
                val restored = backup.renameTo(targetFile)
                if (restored) reconciledCount++
            } else if (targetFile.length() > 0L && backup.length() > 0L) {
                val isRegistered = isRegisteredInDatabase?.invoke(targetFile)
                if (isRegistered == true) {
                    // Confirmed: target file is valid and registered in Room. Safe to purge backup.
                    backup.delete()
                } else if (isRegistered == false) {
                    // Unconfirmed: target file is not registered in Room (interrupted before registration).
                    // Roll back to previous good copy to preserve user's valid audio data.
                    targetFile.delete()
                    val restored = backup.renameTo(targetFile)
                    if (restored) reconciledCount++
                } else {
                    // No verifier provided: if target exists and is non-empty, do not delete backup blindly;
                    // preserve backup until caller explicitly validates registration.
                }
            }
        }
        reconciledCount
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
