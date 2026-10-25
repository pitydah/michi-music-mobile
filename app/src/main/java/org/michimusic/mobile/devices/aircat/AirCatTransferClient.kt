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

data class AuthorizedTransferCriteria(
    val trackId: String,
    val destinationLibraryId: String? = null,
    val expectedSize: Long? = null,
    val expectedSha256: String? = null,
    val planId: String? = null,
)

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

    suspend fun recoverIncompleteTransfers(
        remoteDeviceId: String,
        linkClient: LinkClient,
        authorizedTrackIds: Set<String>? = null,
        authorizedCriteria: List<AuthorizedTransferCriteria>? = null,
        onProgress: ((AirCatProgressEvent) -> Unit)? = null,
    ): Result<List<File>>
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

        // 0. Enforce mandatory MusicLibraryRepository for managed transfers
        if (libraryRepository == null) {
            val err = AirCatException.ProtocolError("MusicLibraryRepository es obligatorio para transferencias gestionadas")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
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

                val (finalCandidate, tempCandidate) = org.michimusic.core.models.TransferFileIdentity.resolveFiles(
                    musicDir = transferManager.musicDir,
                    remoteDeviceId = intent.sourceMichiId,
                    destinationLibraryId = intent.destinationLibraryId,
                    trackId = item.logicalTrackId,
                    format = item.format,
                    planId = intent.transferId,
                )
                val finalPath = finalCandidate.absolutePath
                val tempPartPath = tempCandidate.absolutePath

                val downloadItem = DownloadItem(
                    trackId = item.logicalTrackId,
                    title = item.title,
                    format = item.format,
                    checksum = item.sha256,
                    size = item.size,
                    remoteDeviceId = intent.sourceMichiId,
                    destinationLibraryId = intent.destinationLibraryId,
                    planId = intent.transferId,
                )
                val effectiveKey = downloadItem.effectiveTransferKey
                val legacyKey = "${intent.sourceMichiId}:${intent.destinationLibraryId}:${item.logicalTrackId}"

                // Check if track is already completed in persistent checkpoint and file exists on disk
                val existingCp = checkpointRepository?.getCheckpoint(effectiveKey)
                    ?: checkpointRepository?.getCheckpoint(legacyKey)
                if (existingCp?.transferState == org.michimusic.core.models.TransferStatus.COMPLETED &&
                    finalCandidate.exists() &&
                    existingCp.remoteDeviceId == intent.sourceMichiId &&
                    existingCp.destinationLibraryId == intent.destinationLibraryId &&
                    item.size > 0 && finalCandidate.length() == item.size &&
                    item.sha256.isNotEmpty() && verifyChecksum(finalCandidate, item.sha256)
                ) {
                    downloadedFiles.add(finalCandidate)
                    transferredBytesAcc += finalCandidate.length()
                    continue
                }

                if (libraryRepository != null) {
                    val existingIdentity = libraryRepository.getTrackIdentity(item.logicalTrackId)
                    val existingHash = existingIdentity?.contentHash
                    if (!existingHash.isNullOrEmpty() && item.sha256.isNotEmpty() &&
                        !existingHash.equals(item.sha256, ignoreCase = true)
                    ) {
                        if (tempCandidate.exists()) {
                            tempCandidate.delete()
                        }
                        val err = AirCatException.CorruptedPayload(
                            "Conflicto de contenido: trackId ${item.logicalTrackId} ya existe con hash ${existingHash}, pero la transferencia reporta ${item.sha256}."
                        )
                        emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                        return@withContext Result.failure(err)
                    }
                }

                val existingBytes = if (tempCandidate.exists()) tempCandidate.length() else 0L
                val initialReceived = if (existingBytes > 0 && existingCp != null && existingCp.receivedBytes > 0) {
                    minOf(existingBytes, existingCp.receivedBytes)
                } else if (existingBytes > 0) {
                    existingBytes
                } else {
                    0L
                }

                // Record initial DOWNLOADING checkpoint in persistent repository
                checkpointRepository?.saveCheckpoint(
                    org.michimusic.core.models.PersistentCheckpoint(
                        transferKey = effectiveKey,
                        transferId = intent.transferId,
                        remoteDeviceId = intent.sourceMichiId,
                        sourceLibraryId = intent.sourceLibraryId,
                        destinationLibraryId = intent.destinationLibraryId,
                        trackId = item.logicalTrackId,
                        remoteTrackId = item.remoteTrackId,
                        tempFilePath = tempPartPath,
                        finalFilePath = finalPath,
                        expectedSize = item.size,
                        receivedBytes = initialReceived,
                        verifiedBytes = initialReceived,
                        expectedSha256 = item.sha256,
                        transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
                        createdAt = existingCp?.createdAt ?: System.currentTimeMillis(),
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
                    val preservedBytes = if (tempCandidate.exists()) tempCandidate.length() else 0L
                    checkpointRepository?.updateProgress(
                        transferKey = effectiveKey,
                        receivedBytes = preservedBytes,
                        verifiedBytes = preservedBytes,
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
                    val existingIdentity = libraryRepository.getTrackIdentity(item.logicalTrackId)
                    val existingHash = existingIdentity?.contentHash
                    if (!existingHash.isNullOrEmpty() && item.sha256.isNotEmpty() &&
                        !existingHash.equals(item.sha256, ignoreCase = true)
                    ) {
                        checkpointRepository?.updateProgress(
                            transferKey = effectiveKey,
                            receivedBytes = finalFile.length(),
                            verifiedBytes = finalFile.length(),
                            state = org.michimusic.core.models.TransferStatus.FAILED,
                        )
                        // Non-destructive: restore previous good file if backed up, else preserve if registered
                        val restored = transferManager.restoreBackup(finalFile)
                        if (!restored) {
                            val locations = libraryRepository.getLocationsForTrack(item.logicalTrackId)
                            val isRegisteredExistingFile = locations.any { it.uri == finalFile.absolutePath }
                            if (!isRegisteredExistingFile) {
                                finalFile.delete()
                            }
                        }
                        val err = AirCatException.CorruptedPayload(
                            "Conflicto de contenido: trackId ${item.logicalTrackId} ya existe con hash ${existingIdentity.contentHash}, pero la transferencia reporta ${item.sha256}."
                        )
                        emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                        return@withContext Result.failure(err)
                    }

                    try {
                        libraryRepository.registerTrackWithLocation(
                            identity = trackIdentity,
                            location = location,
                            libraryId = intent.destinationLibraryId,
                            origin = "AIRC_AT",
                        ).getOrThrow()
                    } catch (e: Exception) {
                        // Compensate: mark checkpoint as FAILED so it does not report completed inconsistently
                        checkpointRepository?.updateProgress(
                            transferKey = effectiveKey,
                            receivedBytes = finalFile.length(),
                            verifiedBytes = finalFile.length(),
                            state = org.michimusic.core.models.TransferStatus.FAILED
                        )
                        val restored = transferManager.restoreBackup(finalFile)
                        if (!restored) {
                            val locations = libraryRepository.getLocationsForTrack(item.logicalTrackId)
                            val isRegisteredExistingFile = locations.any { it.uri == finalFile.absolutePath }
                            if (!isRegisteredExistingFile) {
                                finalFile.delete()
                            }
                        }
                        emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = "Fallo al registrar en biblioteca: ${e.message}", onProgress = onProgress)
                        return@withContext Result.failure(e)
                    }
                }

                // Update persistent checkpoint to COMPLETED only after verified file download AND successful library registration
                checkpointRepository?.updateProgress(
                    transferKey = effectiveKey,
                    receivedBytes = finalFile.length(),
                    verifiedBytes = finalFile.length(),
                    state = org.michimusic.core.models.TransferStatus.COMPLETED
                )
                transferManager.discardBackup(finalFile)
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

    override suspend fun recoverIncompleteTransfers(
        remoteDeviceId: String,
        linkClient: LinkClient,
        authorizedTrackIds: Set<String>?,
        authorizedCriteria: List<AuthorizedTransferCriteria>?,
        onProgress: ((AirCatProgressEvent) -> Unit)?,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        val repo = checkpointRepository ?: return@withContext Result.success(emptyList())
        if (libraryRepository == null) {
            return@withContext Result.failure(AirCatException.ProtocolError("MusicLibraryRepository es obligatorio para transferencias gestionadas"))
        }

        // 1. Verify remote device trust and token binding
        val pairedDevice = pairedDeviceRegistry.getDevice(remoteDeviceId)
        if (pairedDevice == null || pairedDevice.isRevoked) {
            return@withContext Result.failure(AirCatException.UnauthorizedPeer(remoteDeviceId))
        }

        // Strict: credentials must be non-empty and matching
        if (linkClient.deviceToken.isEmpty() || pairedDevice.deviceToken.isEmpty() ||
            linkClient.deviceToken != pairedDevice.deviceToken) {
            return@withContext Result.failure(AirCatException.UnauthorizedPeer(remoteDeviceId))
        }

        val allCheckpoints = repo.getCheckpointsForDevice(remoteDeviceId)
        val pendingCheckpoints = allCheckpoints.filter { cp ->
            if (cp.remoteDeviceId != remoteDeviceId) return@filter false

            // Strict composite criteria matching:
            if (authorizedCriteria != null) {
                val match = authorizedCriteria.find { crit ->
                    val trackMatches = crit.trackId == cp.trackId
                    val libMatches = crit.destinationLibraryId == null || crit.destinationLibraryId == cp.destinationLibraryId
                    val sizeMatches = crit.expectedSize == null || crit.expectedSize == cp.expectedSize
                    val hashMatches = if (!crit.expectedSha256.isNullOrEmpty()) {
                        cp.expectedSha256.isNotEmpty() && crit.expectedSha256.equals(cp.expectedSha256, ignoreCase = true)
                    } else {
                        true
                    }
                    val planMatches = if (!crit.planId.isNullOrEmpty()) {
                        cp.transferId.isNotEmpty() && crit.planId == cp.transferId
                    } else {
                        true
                    }
                    trackMatches && libMatches && sizeMatches && hashMatches && planMatches
                }
                if (match == null) return@filter false
            } else if (authorizedTrackIds != null) {
                if (cp.trackId !in authorizedTrackIds) return@filter false
            }

            cp.transferState == org.michimusic.core.models.TransferStatus.DOWNLOADING ||
            cp.transferState == org.michimusic.core.models.TransferStatus.PAUSED ||
            cp.transferState == org.michimusic.core.models.TransferStatus.PENDING
        }

        if (pendingCheckpoints.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        val recoveredFiles = mutableListOf<File>()
        val totalExpectedBytes = pendingCheckpoints.sumOf { it.expectedSize }
        var bytesAccumulated = 0L

        pendingCheckpoints.forEachIndexed { index, cp ->
            // Re-verify peer authorization dynamically in case peer was revoked during transfer
            val currentDeviceState = pairedDeviceRegistry.getDevice(remoteDeviceId)
            if (currentDeviceState == null || currentDeviceState.isRevoked) {
                return@withContext Result.failure(AirCatException.UnauthorizedPeer(remoteDeviceId))
            }

            val ext = cp.finalFilePath.substringAfterLast('.', "mp3")
            val downloadItem = DownloadItem(
                trackId = cp.trackId,
                title = cp.trackId,
                format = ext,
                checksum = cp.expectedSha256,
                size = cp.expectedSize,
                remoteDeviceId = cp.remoteDeviceId,
                destinationLibraryId = cp.destinationLibraryId,
                transferKey = cp.transferKey,
                planId = cp.transferId,
            )

            emitEvent(
                transferId = cp.transferId,
                currentTrackIndex = index,
                totalTracks = pendingCheckpoints.size,
                bytesTransferred = bytesAccumulated,
                totalBytes = totalExpectedBytes,
                state = AirCatTransferState.TRANSFERRING,
                activeTrackTitle = cp.trackId,
                onProgress = onProgress,
            )

            val downloadResult = transferManager.downloadTrack(linkClient, downloadItem)
            if (downloadResult.isFailure) {
                val ex = downloadResult.exceptionOrNull() ?: Exception("Error reanudando ${cp.trackId}")
                val tempCandidate = File(cp.tempFilePath)
                val preservedBytes = if (tempCandidate.exists()) tempCandidate.length() else 0L
                repo.updateProgress(
                    transferKey = cp.transferKey,
                    receivedBytes = preservedBytes,
                    verifiedBytes = preservedBytes,
                    state = org.michimusic.core.models.TransferStatus.FAILED
                )
                emitEvent(
                    transferId = cp.transferId,
                    currentTrackIndex = index,
                    totalTracks = pendingCheckpoints.size,
                    bytesTransferred = bytesAccumulated,
                    totalBytes = totalExpectedBytes,
                    state = AirCatTransferState.FAILED,
                    error = ex.message,
                    onProgress = onProgress,
                )
                return@withContext Result.failure(ex)
            }

            val finalFile = downloadResult.getOrThrow()
            recoveredFiles.add(finalFile)
            bytesAccumulated += finalFile.length()

            // Register into Multi-Library domain transactionally preserving existing identity
            if (libraryRepository != null) {
                val existingIdentity = libraryRepository.getTrackIdentity(cp.trackId)
                val existingHash = existingIdentity?.contentHash
                if (!existingHash.isNullOrEmpty() && cp.expectedSha256.isNotEmpty() &&
                    !existingHash.equals(cp.expectedSha256, ignoreCase = true)
                ) {
                    repo.updateProgress(
                        transferKey = cp.transferKey,
                        receivedBytes = finalFile.length(),
                        verifiedBytes = finalFile.length(),
                        state = org.michimusic.core.models.TransferStatus.FAILED
                    )
                    // Non-destructive: restore previous good file if backed up, else preserve if registered
                    val restored = transferManager.restoreBackup(finalFile)
                    if (!restored) {
                        val locations = libraryRepository.getLocationsForTrack(cp.trackId)
                        val isRegisteredExistingFile = locations.any { it.uri == finalFile.absolutePath }
                        if (!isRegisteredExistingFile) {
                            finalFile.delete()
                        }
                    }
                    return@withContext Result.failure(
                        AirCatException.CorruptedPayload(
                            "Conflicto de contenido: trackId ${cp.trackId} ya existe con hash ${existingIdentity.contentHash}, pero el checkpoint reporta ${cp.expectedSha256}."
                        )
                    )
                }
                val trackIdentity = existingIdentity ?: TrackIdentity(
                    id = cp.trackId,
                    title = cp.trackId,
                    artist = "Unknown",
                    album = "Unknown",
                    durationMs = 0L,
                    contentHash = cp.expectedSha256.ifEmpty { null },
                )
                val location = TrackLocation(
                    id = "loc_aircat_${cp.destinationLibraryId}_${cp.trackId}",
                    trackId = cp.trackId,
                    type = TrackLocationType.SYNCED_MANAGED,
                    uri = finalFile.absolutePath,
                    sourceDeviceId = cp.remoteDeviceId,
                    remoteTrackId = cp.remoteTrackId,
                    availability = LocationAvailability.AVAILABLE,
                )
                try {
                    libraryRepository.registerTrackWithLocation(
                        identity = trackIdentity,
                        location = location,
                        libraryId = cp.destinationLibraryId,
                        origin = "AIRC_AT",
                    ).getOrThrow()
                } catch (e: Exception) {
                    repo.updateProgress(
                        transferKey = cp.transferKey,
                        receivedBytes = finalFile.length(),
                        verifiedBytes = finalFile.length(),
                        state = org.michimusic.core.models.TransferStatus.FAILED
                    )
                    val restored = transferManager.restoreBackup(finalFile)
                    if (!restored) {
                        val locations = libraryRepository.getLocationsForTrack(cp.trackId)
                        val isRegisteredExistingFile = locations.any { it.uri == finalFile.absolutePath }
                        if (!isRegisteredExistingFile) {
                            finalFile.delete()
                        }
                    }
                    return@withContext Result.failure(e)
                }
            }

            // Mark checkpoint completed
            repo.updateProgress(
                transferKey = cp.transferKey,
                receivedBytes = finalFile.length(),
                verifiedBytes = finalFile.length(),
                state = org.michimusic.core.models.TransferStatus.COMPLETED
            )
            transferManager.discardBackup(finalFile)
        }

        emitEvent(
            transferId = pendingCheckpoints.first().transferId,
            currentTrackIndex = pendingCheckpoints.size,
            totalTracks = pendingCheckpoints.size,
            bytesTransferred = totalExpectedBytes,
            totalBytes = totalExpectedBytes,
            state = AirCatTransferState.COMPLETED,
            onProgress = onProgress,
        )

        Result.success(recoveredFiles)
    }

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

    private fun verifyChecksum(file: File, expected: String): Boolean {
        if (!file.exists()) return false
        val hex = calculateSha256(file)
        return hex.equals(expected.trim(), ignoreCase = true)
    }

    private fun calculateSha256(file: File): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(file).use { fis ->
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
}
