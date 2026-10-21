package org.michimusic.mobile.devices.aircat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AirCatTransferClient {

    private val _progressEvents = MutableStateFlow<AirCatProgressEvent?>(null)
    override val progressEvents: StateFlow<AirCatProgressEvent?> = _progressEvents.asStateFlow()

    private val _processedTransferIds = MutableStateFlow<Set<String>>(emptySet())
    override val processedTransferIds: StateFlow<Set<String>> = _processedTransferIds.asStateFlow()

    private val cancelledTransferIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pausedTransferIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override suspend fun executeTransfer(
        intent: TransferIntent,
        manifest: AirCatManifest,
        linkClient: LinkClient,
        availableStorageBytes: Long,
        onProgress: ((AirCatProgressEvent) -> Unit)?,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        cancelledTransferIds.remove(intent.transferId)
        pausedTransferIds.remove(intent.transferId)

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

                val downloadItem = DownloadItem(
                    trackId = item.logicalTrackId,
                    title = item.title,
                    format = item.format,
                    checksum = item.sha256,
                    size = item.size,
                )

                val downloadResult = transferManager.downloadTrack(linkClient, downloadItem)
                if (downloadResult.isFailure) {
                    val ex = downloadResult.exceptionOrNull() ?: Exception("Error descargando pista ${item.logicalTrackId}")
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = ex.message, onProgress = onProgress)
                    return@withContext Result.failure(ex)
                }

                val finalFile = downloadResult.getOrThrow()
                downloadedFiles.add(finalFile)
                transferredBytesAcc += finalFile.length()

                // Register into Multi-Library domain transactionally
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
                        // Compensate: do not report completed if library registration fails
                        emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = "Fallo al registrar en biblioteca: ${e.message}", onProgress = onProgress)
                        return@withContext Result.failure(e)
                    }
                }
            }

            _processedTransferIds.value = _processedTransferIds.value + intent.transferId
            emitEvent(intent.transferId, manifest.totalTracks, manifest.totalTracks, manifest.totalBytes, manifest.totalBytes, AirCatTransferState.COMPLETED, onProgress = onProgress)
            Result.success(downloadedFiles)

        } catch (ce: CancellationException) {
            emitEvent(intent.transferId, downloadedFiles.size, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.PAUSED, onProgress = onProgress)
            throw ce
        } catch (e: Exception) {
            emitEvent(intent.transferId, downloadedFiles.size, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = e.message, onProgress = onProgress)
            Result.failure(e)
        }
    }

    override fun pauseTransfer(transferId: String) {
        pausedTransferIds.add(transferId)
    }

    override fun resumeTransfer(transferId: String) {
        pausedTransferIds.remove(transferId)
    }

    override fun cancelTransfer(transferId: String) {
        cancelledTransferIds.add(transferId)
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
