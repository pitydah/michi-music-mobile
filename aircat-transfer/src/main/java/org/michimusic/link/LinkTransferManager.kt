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
import org.michimusic.link.port.RemoteFileSource
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

    // Link legacy audio/sync keeps /stream. Managed AirCat must fetch immutable
    // source bytes from /download; NEVER silently fall back to transcodable stream.
    // Both ports share LinkClient authentication and its HTTP engine.
    suspend fun downloadTrack(client: LinkClient, item: DownloadItem): Result<File> =
        if (item.requiresLibraryCommit) Result.failure(IllegalStateException(
            "AIRC_AUTHENTICATED_SOURCE_REQUIRED: use a pinned raw source with Home grant"))
        else downloadTrack(source = client, item = item)

    suspend fun downloadTrack(
        source: RemoteFileSource,
        item: DownloadItem,
        beforeWrite: ((Int) -> Unit)? = null,
        beforeCommit: (() -> Unit)? = null,
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

        // A cached file is never accredited by size or filename alone.
        // Hashless legacy AirCat downloads remain allowed but UNVERIFIED, without cached reuse.
        val checksum = item.checksum.trim()
        // A40: managed transfers cannot bypass ongoing Home/cancel/expiry
        // checks by calling the transfer manager directly. Initial zero-byte
        // preflight applies even to a cached file reuse (no HTTP stream).
        if (item.requiresLibraryCommit) {
            if (beforeWrite == null || beforeCommit == null) return@withContext Result.failure(
                IllegalStateException("AIRC_RUNTIME_CONTINUITY_REQUIRED"))
            val initialGuard = runCatching { beforeWrite(0) }
            if (initialGuard.isFailure) return@withContext Result.failure(
                initialGuard.exceptionOrNull() ?: IllegalStateException("AIRC_RUNTIME_CONTINUITY_REFUSED"))
        }
        if (item.requiresLibraryCommit && (!source.servesOriginalBytes || !source.verifiedTransport)) {
            return@withContext Result.failure(IllegalArgumentException(
                "AIRC_AUTHENTICATED_SOURCE_REQUIRED: pinned original bytes only"))
        }
        // A24: a direct caller cannot bypass AirCat's strict manifest preflight
        // by invoking LinkTransferManager with requiresLibraryCommit=true.
        // Reject before any cached reuse, network request, or journal creation.
        if (item.requiresLibraryCommit &&
            (item.remoteTrackId.isBlank() || item.size <= 0L ||
                !TransferIntegrityPolicy.validSha256(checksum))) {
            return@withContext Result.failure(IllegalArgumentException(
                "AIRC_STRICT_MANAGED_SOURCE_REQUIRED: remote ID, size and SHA-256"))
        }
        if (checksum.isNotEmpty() && !TransferIntegrityPolicy.validSha256(checksum)) {
            return@withContext Result.failure(
                IllegalArgumentException("INVALID_CHECKSUM: SHA-256 must be 64 hexadecimal characters")
            )
        }
        // A20: block unresolved journal before cached reuse or network activity.
        if (AirCatRecoveryJournal(File(file.parentFile, ".aircat-journal")).hasJournal(file)) {
            return@withContext Result.failure(IllegalStateException("AIRC_RECOVERY_REQUIRED"))
        }

        // 1. Reuse only if hash is provided AND the expected size matches (when known).
        if (file.exists() && file.length() > 0) {
            val len = file.length()
            if (!AirCatRecoveryJournal(File(file.parentFile, ".aircat-journal")).hasJournal(file) &&
                TransferIntegrityPolicy.mayReuse(len, item.size, checksum) && verifyChecksum(file, checksum)
            ) {
                // A41: even a cached SHA match must not revive a revoked Home grant.
                if (item.requiresLibraryCommit) {
                    val permit = runCatching { beforeCommit!!.invoke() }
                    if (permit.isFailure) return@withContext Result.failure(
                        permit.exceptionOrNull() ?: IllegalStateException("AIRC_PRECOMMIT_REFUSED"))
                }
                _downloads.value = _downloads.value + (item.trackId to
                    if (item.requiresLibraryCommit) DownloadProgress.Downloading(len)
                    else DownloadProgress.Completed(len))
                updateCheckpoint(item.effectiveTransferKey) {
                    TransferCheckpoint(
                        trackId = item.trackId,
                        tempPath = tempFile.absolutePath,
                        finalPath = file.absolutePath,
                        bytesVerified = len,
                        expectedSize = if (item.size > 0) item.size else len,
                        sha256Expected = item.checksum,
                        resumeMode = ResumeMode.NOT_APPLICABLE,
                        state = if (item.requiresLibraryCommit) TransferState.COMMITTING
                            else TransferState.COMPLETED,
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
                // A25: the Room key lookup is NOT a receipt for the requested item.
                // A stale/replaced row must not lend its offset to another file,
                // remote ID, plan, digest, or managed destination.
                if (persisted.transferKey != transferKey ||
                    persisted.trackId != item.trackId ||
                    persisted.remoteTrackId != item.remoteTrackId ||
                    persisted.tempFilePath != tempFile.absolutePath ||
                    persisted.finalFilePath != file.absolutePath ||
                    persisted.expectedSize != item.size ||
                    !persisted.expectedSha256.equals(checksum, ignoreCase = true) ||
                    (item.requiresLibraryCommit &&
                        (persisted.remoteDeviceId != item.remoteDeviceId ||
                            persisted.destinationLibraryId != item.destinationLibraryId ||
                            persisted.transferId != item.planId))) {
                    return@withContext Result.failure(IllegalStateException(
                        "AIRC_CHECKPOINT_IDENTITY_MISMATCH: existing partial preserved"))
                }
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
        // The server's strong revision validator and a persisted verified offset
        // are BOTH required before a .part may be appended to. Micro's current
        // /download supports Range without ETag: restart such files from zero.
        val expectedEtag = savedCheckpoint?.etag?.takeIf(TransferResumeBoundaryPolicy::strongEtag)

        var existingPartBytes = if (tempFile.exists()) tempFile.length() else 0L
        var startOffset = 0L
        var shouldAppend = false

        if (existingPartBytes > 0) {
            // A68-10: only an AirCat managed transfer can enforce the strong
            // validator; the legacy Link /download protocol has none, and demanding
            // one there turned every interrupted library sync into a full re-download.
            // The legacy branch still requires the persisted checkpoint to describe
            // this exact file, and the final SHA-256 still gates the commit.
            val safeResume = if (item.requiresLibraryCommit) {
                TransferResumeBoundaryPolicy.mayAppend(
                    checkpoint = savedCheckpoint, partPath = tempFile.absolutePath,
                    finalPath = file.absolutePath, expectedSize = item.size,
                    expectedSha256 = checksum, actualPartBytes = existingPartBytes,
                )
            } else {
                TransferResumeBoundaryPolicy.mayAppendWithoutValidator(
                    checkpoint = savedCheckpoint, partPath = tempFile.absolutePath,
                    finalPath = file.absolutePath, expectedSize = item.size,
                    expectedSha256 = checksum, actualPartBytes = existingPartBytes,
                )
            }
            // Missing checkpoint/strong ETag or mismatched file/identity means
            // the partial is untrusted; never infer verified bytes from its size.
            // A managed transfer resumes exactly what the server attested. The
            // legacy /download protocol attests nothing per byte, so a checkpoint
            // that recorded verified bytes still truncates to them, while a
            // checkpoint that recorded none lets the physical .part length be the
            // resume point - the final SHA-256 is what authorises the file.
            val recordedBytes = when {
                !safeResume -> 0L
                item.requiresLibraryCommit -> savedCheckpoint!!.bytesVerified
                else -> savedCheckpoint?.bytesVerified?.takeIf { it > 0L } ?: existingPartBytes
            }
            val sizeOverflow = item.size > 0 && existingPartBytes >= item.size
            if (!safeResume || sizeOverflow || existingPartBytes < recordedBytes) {
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
                AirCatPartOutput.open(tempFile, append = shouldAppend, expectedOffset = startOffset).use { outputStream ->
                    // A38: a compromised sender cannot write past manifest.size,
                    // even with chunked HTTP and no Content-Length.
                    val boundedOutput = if (item.requiresLibraryCommit)
                        AirCatBoundedOutputStream(outputStream, item.size - startOffset, beforeWrite)
                    else outputStream
                    source.streamTrackRange(
                        trackId = item.remoteTrackId,
                        outputStream = boundedOutput,
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
                    AirCatPartOutput.open(tempFile, append = false, expectedOffset = 0L).use { outputStream ->
                        val boundedOutput = if (item.requiresLibraryCommit)
                            AirCatBoundedOutputStream(outputStream, item.size, beforeWrite)
                        else outputStream
                        source.streamTrackRange(
                            trackId = item.remoteTrackId,
                            outputStream = boundedOutput,
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
                
                val restartedResult = AirCatPartOutput.open(tempFile, append = false, expectedOffset = 0L).use { outputStream ->
                    val boundedOutput = if (item.requiresLibraryCommit)
                        AirCatBoundedOutputStream(outputStream, item.size, beforeWrite)
                    else outputStream
                    source.streamTrackRange(
                        trackId = item.remoteTrackId,
                        outputStream = boundedOutput,
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
                // The successful zero-offset retry may return a different revision.
                // Persist its ETag, not the ignored 200 response's ETag.
                responseEtag = restartedResult.getOrThrow().etag
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

            // SHA-256 Checksum check: absent hash is an explicit UNVERIFIED legacy case.
            if (checksum.isNotEmpty()) {
                val actualHex = calculateSha256(tempFile)
                val matches = actualHex.equals(checksum, ignoreCase = true)
                if (!matches) {
                    tempFile.delete()
                    val err = TransferChecksumMismatchException(item.trackId, checksum, actualHex)
                    _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed("Checksum mismatch"))
                    updateCheckpoint(transferKey) { it?.copy(state = TransferState.FAILED) }
                    return@withContext Result.failure(err)
                }
            }

            // 4. Committing stage: Atomic promotion
            // A41: close the gap after the last HTTP chunk and before the journal/rename.
            // This also refuses paused/cancelled/expired sessions without changing final.
            if (item.requiresLibraryCommit) {
                val permit = runCatching { beforeCommit!!.invoke() }
                if (permit.isFailure) return@withContext Result.failure(
                    permit.exceptionOrNull() ?: IllegalStateException("AIRC_PRECOMMIT_REFUSED"))
            }
            updateCheckpoint(transferKey) { it?.copy(state = TransferState.COMMITTING) }
            val promoted = promoteDownload(tempFile, file, transferKey, checksum, item.requiresLibraryCommit)
            if (!promoted) {
                val err = TransferPromotionException(item.trackId, "Atomic rename/replace failed")
                _downloads.value = _downloads.value + (item.trackId to DownloadProgress.Failed("Unable to finalize download"))
                updateCheckpoint(transferKey) { it?.copy(state = TransferState.FAILED) }
                return@withContext Result.failure(err)
            }

            // The successful rename is NOT a Room/checkpoint commit. AirCat remains
            // COMMITTING until its caller reads back the durable checkpoint receipt.
            _downloads.value = _downloads.value + (item.trackId to
                if (item.requiresLibraryCommit) DownloadProgress.Downloading(file.length())
                else DownloadProgress.Completed(file.length()))
            updateCheckpoint(transferKey) {
                it?.copy(
                    bytesVerified = file.length(),
                    state = if (item.requiresLibraryCommit) TransferState.COMMITTING
                        else TransferState.COMPLETED,
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
        // A22: no public file lookup may expose bytes awaiting Room/checkpoint commit.
        if (file.isFile && !hasPendingRecovery(file)) return file

        return null
    }

    /** Legacy reset of transient transfer state; NEVER deletes downloaded music. */
    fun clearDownloads() {
        // Complete copies remain intact even when hidden from Michi's catalog.
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
        // A50: secure NOFOLLOW hash also protects cached reuse and the final .part verification.
        return AirCatSafeFileHash.sha256(file)
    }

    /**
     * Fail-closed .part promotion; A19 does not overwrite any unresolved .bak.
     * The caller verifies SHA-256 before this step, commits Room afterwards,
     * and discards the backup only after checkpoint+library success.
     */
    private fun promoteDownload(
        tempFile: File,
        finalFile: File,
        transferKey: String,
        checksum: String,
        requiresLibraryCommit: Boolean,
    ): Boolean = if (requiresLibraryCommit) {
        // Managed AirCat owns the durable Room + checkpoint journal lifecycle.
        if (checksum.isBlank()) false else
            AirCatRecoveryJournal(File(finalFile.parentFile, ".aircat-journal"))
                .promote(tempFile, finalFile, transferKey, checksum)
    } else {
        // SyncWorker/legacy Link must never leave an AirCat journal that only
        // a managed AirCat caller can finalize. Keep existing promotion semantics.
        AirCatFilePromotion.promote(tempFile, finalFile)
    }

    /** Atomically replaces the uncommitted final without deleting it first. */
    fun restoreBackup(finalFile: File): Boolean =
        !AirCatRecoveryJournal(File(finalFile.parentFile, ".aircat-journal")).hasJournal(finalFile) &&
            AirCatFilePromotion.restore(finalFile)

    /** A20: legacy discard MUST NOT bypass a pending recovery journal. */
    fun discardBackup(finalFile: File): Boolean =
        !AirCatRecoveryJournal(File(finalFile.parentFile, ".aircat-journal")).hasJournal(finalFile) &&
            AirCatFilePromotion.discardAfterCommit(finalFile)

    /**
     * Caller must prove that Room registration and persistent checkpoint writes succeeded.
     * The journal re-hashes the bytes before releasing the previous valid file.
     * An interrupted finalization remains visible to explicit repair, without polling.
     */
    fun finalizeAfterCommit(
        finalFile: File,
        transferKey: String,
        expectedSha256: String,
        libraryCommitted: Boolean,
        checkpointCommitted: Boolean,
    ): Boolean {
        val committed = AirCatRecoveryJournal(File(finalFile.parentFile, ".aircat-journal"))
            .finishCommitted(finalFile, transferKey, expectedSha256, libraryCommitted, checkpointCommitted)
        if (committed) {
            val trackId = _checkpoints.value[transferKey]?.trackId
            updateCheckpoint(transferKey) {
                it?.copy(state = TransferState.COMPLETED, updatedAt = System.currentTimeMillis())
            }
            if (trackId != null) {
                _downloads.value = _downloads.value + (trackId to DownloadProgress.Completed(finalFile.length()))
            }
        }
        return committed
    }

    /** Constant-time pending guard for trusted cached-file reuse. */
    fun hasPendingRecovery(finalFile: File): Boolean =
        AirCatRecoveryJournal(File(finalFile.parentFile, ".aircat-journal")).hasJournal(finalFile)

    /** A24: corroborate exact journal ownership BEFORE changing any Room row.
     * A matching digest alone is not enough when different plans share a path.
     */
    fun hasMatchingPendingJournal(finalFile: File, transferKey: String, expectedSha256: String): Boolean {
        if (transferKey.isBlank() || !TransferIntegrityPolicy.validSha256(expectedSha256)) return false
        val entry = AirCatRecoveryJournal(File(finalFile.parentFile, ".aircat-journal"))
            .pending(finalFile) ?: return false
        return entry.transferKey == transferKey &&
            entry.expectedSha256.equals(expectedSha256, ignoreCase = true) &&
            entry.phase != AirCatRecoveryJournal.Phase.PREPARED
    }

    /** Read-only diagnosis for the on-demand repair UI; never mutates or scans. */
    fun diagnoseRecovery(finalFile: File, roomRegistered: Boolean?, checkpointCompleted: Boolean?): String =
        AirCatRecoveryJournal(File(finalFile.parentFile, ".aircat-journal"))
            .assess(finalFile, roomRegistered, checkpointCompleted).name

    /**
     * Restore an orphan only if it is missing/empty or the Room path is unregistered.
     * Room registration by itself is not SHA-256 evidence: ambiguous backups
     * and committed-looking files are preserved until explicit verification.
     */
    suspend fun reconcileOrphanBackups(
        targetDir: File = musicDir,
        isRegisteredInDatabase: (suspend (File) -> Boolean)? = null,
    ): Int = withContext(Dispatchers.IO) {
        AirCatFilePromotion.reconcile(targetDir, isRegisteredInDatabase)
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
