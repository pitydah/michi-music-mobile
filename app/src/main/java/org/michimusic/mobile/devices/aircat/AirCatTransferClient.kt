package org.michimusic.mobile.devices.aircat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.core.models.DownloadItem
import org.michimusic.core.models.LocationAvailability
import org.michimusic.core.models.TrackIdentity
import org.michimusic.core.models.TrackLocation
import org.michimusic.core.models.TrackLocationType
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.PairedDeviceRegistry
import java.io.File

interface AirCatTransferClient {
    val progressEvents: StateFlow<AirCatProgressEvent?>
    val processedTransferIds: StateFlow<Set<String>>

    suspend fun executeTransfer(
        intent: TransferIntent,
        manifest: AirCatManifest,
        linkClient: LinkClient,
        availableStorageBytes: Long,
        onProgress: ((AirCatProgressEvent) -> Unit)? = null,
    ): Result<List<File>>

    fun pauseTransfer(transferId: String)
    fun resumeTransfer(transferId: String)
    fun cancelTransfer(transferId: String)
    fun isTransferPaused(transferId: String): Boolean
    fun isTransferCancelled(transferId: String): Boolean
}

class DefaultAirCatTransferClient(
    private val transferManager: LinkTransferManager,
    private val pairedDeviceRegistry: PairedDeviceRegistry,
    private val libraryRepository: MusicLibraryRepository?,
    private val checkpointRepository: org.michimusic.core.models.TransferCheckpointRepository? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AirCatTransferClient {

    private val _progressEvents = MutableStateFlow<AirCatProgressEvent?>(null)
    override val progressEvents: StateFlow<AirCatProgressEvent?> = _progressEvents.asStateFlow()

    private val _processedTransferIds = MutableStateFlow<Set<String>>(emptySet())
    override val processedTransferIds: StateFlow<Set<String>> = _processedTransferIds.asStateFlow()

    private data class ActiveTransferSession(
        val intent: TransferIntent,
        val manifest: AirCatManifest,
        val linkClient: LinkClient,
        val availableStorageBytes: Long,
        val onProgress: ((AirCatProgressEvent) -> Unit)?,
    )

    private val cancelledTransferIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pausedTransferIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, ActiveTransferSession>()
    private val clientScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    override suspend fun executeTransfer(
        intent: TransferIntent,
        manifest: AirCatManifest,
        linkClient: LinkClient,
        availableStorageBytes: Long,
        onProgress: ((AirCatProgressEvent) -> Unit)?,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        if (!cancelledTransferIds.contains(intent.transferId)) {
            cancelledTransferIds.remove(intent.transferId)
        }
        if (!pausedTransferIds.contains(intent.transferId)) {
            pausedTransferIds.remove(intent.transferId)
        }
        activeSessions[intent.transferId] = ActiveTransferSession(
            intent = intent,
            manifest = manifest,
            linkClient = linkClient,
            availableStorageBytes = availableStorageBytes,
            onProgress = onProgress,
        )
        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.let { job ->
            activeJobs[intent.transferId] = job
        }

        // 1. Check idempotency
        if (intent.transferId in _processedTransferIds.value) {
            return@withContext Result.failure(AirCatException.DuplicatedTransfer(intent.transferId))
        }

        // 2. Check Intent expiration with injectable clock
        val now = clock()
        if (intent.expiresAt in 1..now) {
            val err = AirCatException.TransferExpired(intent.expiresAt, now)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // 3. Peer trust & authorization validation
        val sourceDevice = pairedDeviceRegistry.getDevice(intent.sourceMichiId)
        if (sourceDevice == null) {
            val err = AirCatException.UnauthorizedPeer(intent.sourceMichiId)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }
        if (sourceDevice.isRevoked) {
            val err = AirCatException.RevokedPeer(intent.sourceMichiId)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // 4. Validate transferId and source consistency between intent and manifest
        if (intent.transferId != manifest.transferId) {
            val err = AirCatException.TransferIdMismatch(intent.transferId, manifest.transferId)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }
        if (intent.sourceMichiId != manifest.sourceDeviceId) {
            val err = AirCatException.SourceMismatch(intent.sourceMichiId, manifest.sourceDeviceId)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // 5. Manifest version check
        if (manifest.manifestVersion != intent.manifestVersion) {
            val err = AirCatException.ManifestVersionMismatch(intent.manifestVersion, manifest.manifestVersion)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // 6. Storage check with safety margin (100MB minimum reserved)
        val safetyMargin = 100L * 1024 * 1024
        val requiredBytes = manifest.totalBytes + safetyMargin
        if (availableStorageBytes < requiredBytes) {
            val err = AirCatException.InsufficientStorage(requiredBytes, availableStorageBytes)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // 7. Validate manifest structure (track count and byte totals consistency when items provided)
        if (manifest.items.isNotEmpty()) {
            if (manifest.items.size != manifest.totalTracks) {
                val err = AirCatException.InvalidManifestStructure("Items declarados (${manifest.items.size}) no coinciden con totalTracks (${manifest.totalTracks})")
                emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                return@withContext Result.failure(err)
            }
            val itemsSumBytes = manifest.items.sumOf { it.size }
            if (manifest.totalBytes > 0 && itemsSumBytes != manifest.totalBytes) {
                val err = AirCatException.InvalidManifestStructure("Suma de bytes (${itemsSumBytes}B) no coincide con totalBytes (${manifest.totalBytes}B)")
                emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                return@withContext Result.failure(err)
            }
        }

        // 8. Transfer execution
        val downloadedFiles = mutableListOf<File>()
        var transferredBytesAcc = 0L

        emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.TRANSFERRING, onProgress = onProgress)

        try {
            for ((index, item) in manifest.items.withIndex()) {
                if (cancelledTransferIds.contains(intent.transferId)) {
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.CANCELLED, onProgress = onProgress)
                    return@withContext Result.failure(AirCatException.ProtocolError("Transferencia cancelada por el usuario"))
                }
                if (pausedTransferIds.contains(intent.transferId)) {
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.PAUSED, onProgress = onProgress)
                    return@withContext Result.failure(AirCatException.ProtocolError("Transferencia pausada"))
                }

                emitEvent(
                    intent.transferId,
                    index + 1,
                    manifest.totalTracks,
                    transferredBytesAcc,
                    manifest.totalBytes,
                    AirCatTransferState.TRANSFERRING,
                    activeTrackTitle = item.title,
                    onProgress = onProgress,
                )

                val transferKey = "${intent.sourceMichiId}:${intent.destinationLibraryId}:${item.logicalTrackId}"
                val ext = item.format.lowercase().ifEmpty { "mp3" }
                val safeDev = intent.sourceMichiId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "device" }
                val safeLib = intent.destinationLibraryId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "lib" }
                val safeTrack = item.logicalTrackId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "track" }
                val targetFileName = "${safeDev}_${safeLib}_${safeTrack}.$ext"
                val existingFile = transferManager.getTrackFile(
                    item.logicalTrackId,
                    ext,
                    remoteDeviceId = intent.sourceMichiId,
                    destinationLibraryId = intent.destinationLibraryId,
                )
                val finalPath = existingFile?.absolutePath ?: File(transferManager.musicDir, targetFileName).absolutePath
                val finalCandidate = File(finalPath)

                // Check if track is already completed in persistent checkpoint and file exists on disk
                val existingCp = checkpointRepository?.getCheckpoint(transferKey)
                if (existingCp?.transferState == org.michimusic.core.models.TransferStatus.COMPLETED && finalCandidate.exists() && (item.size <= 0 || finalCandidate.length() == item.size)) {
                    downloadedFiles.add(finalCandidate)
                    transferredBytesAcc += finalCandidate.length()
                    continue
                }

                val downloadItem = DownloadItem(
                    trackId = item.logicalTrackId,
                    title = item.title,
                    format = item.format,
                    checksum = item.sha256,
                    size = item.size,
                    remoteDeviceId = intent.sourceMichiId,
                    destinationLibraryId = intent.destinationLibraryId,
                    transferKey = transferKey,
                )

                // Record initial DOWNLOADING checkpoint in persistent repository
                checkpointRepository?.saveCheckpoint(
                    org.michimusic.core.models.PersistentCheckpoint(
                        transferKey = transferKey,
                        transferId = intent.transferId,
                        remoteDeviceId = intent.sourceMichiId,
                        sourceLibraryId = intent.sourceLibraryId,
                        destinationLibraryId = intent.destinationLibraryId,
                        trackId = item.logicalTrackId,
                        remoteTrackId = item.remoteTrackId,
                        tempFilePath = "$finalPath.part",
                        finalFilePath = finalPath,
                        expectedSize = item.size,
                        receivedBytes = 0L,
                        verifiedBytes = 0L,
                        expectedSha256 = item.sha256,
                        transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )

                val downloadResult = transferManager.downloadTrack(linkClient, downloadItem)
                if (cancelledTransferIds.contains(intent.transferId)) {
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.CANCELLED, onProgress = onProgress)
                    return@withContext Result.failure(AirCatException.ProtocolError("Transferencia cancelada por el usuario"))
                }
                if (pausedTransferIds.contains(intent.transferId)) {
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.PAUSED, onProgress = onProgress)
                    return@withContext Result.failure(AirCatException.ProtocolError("Transferencia pausada"))
                }
                if (downloadResult.isFailure) {
                    val ex = downloadResult.exceptionOrNull() ?: Exception("Error descargando pista ${item.logicalTrackId}")
                    if (ex is CancellationException || cancelledTransferIds.contains(intent.transferId)) {
                        emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.CANCELLED, onProgress = onProgress)
                        return@withContext Result.failure(AirCatException.ProtocolError("Transferencia cancelada por el usuario"))
                    }
                    if (pausedTransferIds.contains(intent.transferId)) {
                        emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.PAUSED, onProgress = onProgress)
                        return@withContext Result.failure(AirCatException.ProtocolError("Transferencia pausada"))
                    }
                    checkpointRepository?.updateProgress(
                        transferKey = transferKey,
                        receivedBytes = 0L,
                        verifiedBytes = 0L,
                        state = org.michimusic.core.models.TransferStatus.FAILED
                    )
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = ex.message, onProgress = onProgress)
                    return@withContext Result.failure(ex)
                }

                val finalFile = downloadResult.getOrThrow()
                downloadedFiles.add(finalFile)
                transferredBytesAcc += finalFile.length()

                // Register into Multi-Library domain transactionally BEFORE setting checkpoint to COMPLETED
                val trackIdentity = TrackIdentity(
                    id = item.logicalTrackId,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    durationMs = 0L,
                    contentHash = item.sha256.ifEmpty { null },
                )
                val location = TrackLocation(
                    id = "loc_aircat_${intent.destinationLibraryId}_${item.logicalTrackId}",
                    trackId = item.logicalTrackId,
                    type = TrackLocationType.SYNCED_MANAGED,
                    uri = finalFile.absolutePath,
                    sourceDeviceId = intent.sourceMichiId,
                    remoteTrackId = item.remoteTrackId,
                    availability = LocationAvailability.AVAILABLE,
                )

                if (libraryRepository != null) {
                    try {
                        libraryRepository.registerTrackWithLocation(
                            identity = trackIdentity,
                            location = location,
                            libraryId = intent.destinationLibraryId,
                            origin = "AIRC_AT",
                        )
                    } catch (e: Exception) {
                        // Compensate: mark checkpoint as FAILED so it does not report completed inconsistently
                        checkpointRepository?.updateProgress(
                            transferKey = transferKey,
                            receivedBytes = finalFile.length(),
                            verifiedBytes = finalFile.length(),
                            state = org.michimusic.core.models.TransferStatus.FAILED
                        )
                        emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = "Fallo al registrar en biblioteca: ${e.message}", onProgress = onProgress)
                        return@withContext Result.failure(e)
                    }
                }

                // Update persistent checkpoint to COMPLETED only after verified file download AND successful library registration
                checkpointRepository?.updateProgress(
                    transferKey = transferKey,
                    receivedBytes = finalFile.length(),
                    verifiedBytes = finalFile.length(),
                    state = org.michimusic.core.models.TransferStatus.COMPLETED
                )
            }

            _processedTransferIds.value = _processedTransferIds.value + intent.transferId
            activeSessions.remove(intent.transferId)
            emitEvent(intent.transferId, manifest.totalTracks, manifest.totalTracks, manifest.totalBytes, manifest.totalBytes, AirCatTransferState.COMPLETED, onProgress = onProgress)
            Result.success(downloadedFiles)

        } catch (ce: CancellationException) {
            val state = if (cancelledTransferIds.contains(intent.transferId)) AirCatTransferState.CANCELLED else AirCatTransferState.PAUSED
            emitEvent(intent.transferId, downloadedFiles.size, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, state, onProgress = onProgress)
            throw ce
        } catch (e: Exception) {
            if (cancelledTransferIds.contains(intent.transferId)) {
                emitEvent(intent.transferId, downloadedFiles.size, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.CANCELLED, onProgress = onProgress)
                Result.failure(AirCatException.ProtocolError("Transferencia cancelada por el usuario"))
            } else if (pausedTransferIds.contains(intent.transferId)) {
                emitEvent(intent.transferId, downloadedFiles.size, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.PAUSED, onProgress = onProgress)
                Result.failure(AirCatException.ProtocolError("Transferencia pausada"))
            } else {
                emitEvent(intent.transferId, downloadedFiles.size, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = e.message, onProgress = onProgress)
                Result.failure(e)
            }
        } finally {
            activeJobs.remove(intent.transferId)
        }
    }

    override fun pauseTransfer(transferId: String) {
        pausedTransferIds.add(transferId)
        activeJobs[transferId]?.cancel()
        val current = _progressEvents.value
        if (current != null && current.transferId == transferId) {
            _progressEvents.value = current.copy(state = AirCatTransferState.PAUSED)
        } else {
            _progressEvents.value = AirCatProgressEvent(
                transferId = transferId,
                currentTrackIndex = 0,
                totalTracks = 0,
                bytesTransferred = 0L,
                totalBytes = 0L,
                state = AirCatTransferState.PAUSED,
            )
        }
    }

    override fun resumeTransfer(transferId: String) {
        pausedTransferIds.remove(transferId)
        val current = _progressEvents.value
        if (current != null && current.transferId == transferId) {
            _progressEvents.value = current.copy(state = AirCatTransferState.TRANSFERRING)
        } else {
            _progressEvents.value = AirCatProgressEvent(
                transferId = transferId,
                currentTrackIndex = 0,
                totalTracks = 0,
                bytesTransferred = 0L,
                totalBytes = 0L,
                state = AirCatTransferState.TRANSFERRING,
            )
        }

        val session = activeSessions[transferId]
        if (session != null && activeJobs[transferId]?.isActive != true) {
            val job = clientScope.launch {
                executeTransfer(
                    intent = session.intent,
                    manifest = session.manifest,
                    linkClient = session.linkClient,
                    availableStorageBytes = session.availableStorageBytes,
                    onProgress = session.onProgress,
                )
            }
            activeJobs[transferId] = job
        }
    }

    override fun cancelTransfer(transferId: String) {
        cancelledTransferIds.add(transferId)
        activeJobs[transferId]?.cancel()
        activeSessions.remove(transferId)
        val current = _progressEvents.value
        if (current != null && current.transferId == transferId) {
            _progressEvents.value = current.copy(state = AirCatTransferState.CANCELLED)
        } else {
            _progressEvents.value = AirCatProgressEvent(
                transferId = transferId,
                currentTrackIndex = 0,
                totalTracks = 0,
                bytesTransferred = 0L,
                totalBytes = 0L,
                state = AirCatTransferState.CANCELLED,
            )
        }
    }

    override fun isTransferPaused(transferId: String): Boolean = pausedTransferIds.contains(transferId)

    override fun isTransferCancelled(transferId: String): Boolean = cancelledTransferIds.contains(transferId)

    private fun emitEvent(
        transferId: String,
        currentTrackIndex: Int,
        totalTracks: Int,
        bytesTransferred: Long,
        totalBytes: Long,
        state: AirCatTransferState,
        activeTrackTitle: String = "",
        error: String? = null,
        onProgress: ((AirCatProgressEvent) -> Unit)? = null,
    ) {
        val event = AirCatProgressEvent(
            transferId = transferId,
            currentTrackIndex = currentTrackIndex,
            totalTracks = totalTracks,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            state = state,
            activeTrackTitle = activeTrackTitle,
            error = error,
        )
        _progressEvents.value = event
        onProgress?.invoke(event)
    }
}
