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
import org.michimusic.mobile.devices.aircat.port.PeerPairingStatus
import org.michimusic.mobile.devices.aircat.port.PeerTrustPort
import org.michimusic.mobile.devices.aircat.port.RegistryPeerTrustPort
import java.io.File

data class AuthorizedTransferCriteria(
    val trackId: String,
    val destinationLibraryId: String? = null,
    val expectedSize: Long? = null,
    val expectedSha256: String? = null,
    val planId: String? = null,
)

interface AirCatTransferClient {
    /** Must be true before the Devices coordinator makes any remote HTTP request. */
    fun authenticatedSourceReady(sourceMichiId: String, baseUrl: String): Boolean = false
    fun enrolledSourceTlsPin(sourceMichiId: String, baseUrl: String): String? = null

    val progressEvents: StateFlow<AirCatProgressEvent?>
    /** A31: independent progress for each transfer, including paused sessions. */
    val progressByTransfer: StateFlow<Map<String, AirCatProgressEvent>>
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
        // Only for a fresh authenticated network recovery. Pure local journal
        // finalization does not need a new remote grant.
        authorizationIntent: TransferIntent? = null,
        authorizationManifest: AirCatManifest? = null,
    ): Result<List<File>>

    fun clearProgress() {}
    /** A31: dismiss only one terminal UI receipt, never an active transfer. */
    fun dismissProgress(transferId: String) {}
}

class DefaultAirCatTransferClient(
    private val transferManager: LinkTransferManager,
    private val pairedDeviceRegistry: PairedDeviceRegistry,
    private val libraryRepository: MusicLibraryRepository?,
    private val checkpointRepository: org.michimusic.core.models.TransferCheckpointRepository? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    // Injectable AM2 seam: legacy constructors/Koin stay source-compatible.
    private val peerTrust: PeerTrustPort = RegistryPeerTrustPort(pairedDeviceRegistry),
    // A68: a resume must hold the same process/cross-process lease as an explicit
    // transfer, so a null lease can only refuse the resume — never run it unguarded.
    // Production wires the shared coordinator in SyncModule; a test that exercises
    // resume supplies its own. Keep the default null: inventing a lease from an
    // unknown directory would guard nothing.
    private val resumeLease: AirCatLeaseCoordinator? = null,
    // Default MUST be fail-closed; no grant/anchor is inferred from UDP or local pairing.
    private val signedGrantSecurity: AirCatSignedGrantSecurity =
        AirCatSignedGrantSecurity(AirCatHomeAuthorityProvider.Unavailable),
) : AirCatTransferClient, AirCatOwnedClientCustodian {

    override fun authenticatedSourceReady(sourceMichiId: String, baseUrl: String): Boolean =
        signedGrantSecurity.enrolledSourceReady(sourceMichiId, baseUrl)

    override fun enrolledSourceTlsPin(sourceMichiId: String, baseUrl: String): String? =
        signedGrantSecurity.enrolledTlsPin(sourceMichiId, baseUrl)

    private val progressLedger = AirCatProgressLedger()
    private val _progressEvents = MutableStateFlow<AirCatProgressEvent?>(null)
    override val progressEvents: StateFlow<AirCatProgressEvent?> = _progressEvents.asStateFlow()
    private val _progressByTransfer = MutableStateFlow<Map<String, AirCatProgressEvent>>(emptyMap())
    override val progressByTransfer: StateFlow<Map<String, AirCatProgressEvent>> =
        _progressByTransfer.asStateFlow()

    @Synchronized
    private fun publishProgress(event: AirCatProgressEvent): AirCatProgressEvent? {
        // A42: all progress producers, including direct control callbacks, share
        // one safe projection; never publish raw network/exception details.
        val safeEvent = event.copy(
            error = AirCatUserFacingFailurePolicy.safeMessage(event.error),
            activeTrackTitle = AirCatUserFacingFailurePolicy.safeTitle(event.activeTrackTitle),
        )
        val updated = progressLedger.record(safeEvent) ?: return null
        _progressByTransfer.value = updated
        _progressEvents.value = safeEvent
        return safeEvent
    }

    @Synchronized
    override fun clearProgress() {
        progressLedger.clear()
        _progressByTransfer.value = emptyMap()
        _progressEvents.value = null
    }

    @Synchronized
    override fun dismissProgress(transferId: String) {
        val remaining = progressLedger.dismissTerminal(transferId) ?: return
        _progressByTransfer.value = remaining
        _progressEvents.value = remaining.values.lastOrNull()
    }

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
    private val ownedClients = java.util.concurrent.ConcurrentHashMap<String, LinkClient>()
    private val clientScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val resumeScheduler = AirCatResumeScheduler(clientScope, resumeLease)

    /** A23: recheck local pairing and expiry at meaningful file/commit boundaries.
     * This is NOT TLS/Ed25519 proof, nor a background revocation subscription.
     */
    private fun continuityRefusal(
        remoteId: String,
        token: String,
        transferId: String,
        expiresAt: Long = 0L,
        sourceBaseUrl: String? = null,
    ): AirCatException? {
        // Recheck the encrypted Home anchor at every file/commit boundary.
        // Local QR revocation invalidates a still-in-date grant immediately.
        if (sourceBaseUrl != null &&
            !signedGrantSecurity.enrolledSourceReady(remoteId, sourceBaseUrl)) {
            return AirCatException.RevokedPeer(remoteId)
        }
        val now = clock()
        val refusal = AirCatRuntimeGuardPolicy.refuse(
            pairing = peerTrust.status(remoteId),
            sessionTokenMatches = peerTrust.matchesSessionToken(remoteId, token),
            nowMillis = now,
            expiresAtMillis = expiresAt,
            cancelled = cancelledTransferIds.contains(transferId),
            paused = pausedTransferIds.contains(transferId),
        )
        return when (refusal) {
            AirCatRuntimeGuardPolicy.Refusal.CANCELLED ->
                AirCatException.ProtocolError("Transferencia AirCat cancelada")
            AirCatRuntimeGuardPolicy.Refusal.PAUSED ->
                AirCatException.ProtocolError("Transferencia AirCat pausada")
            AirCatRuntimeGuardPolicy.Refusal.EXPIRED -> AirCatException.TransferExpired(expiresAt, now)
            AirCatRuntimeGuardPolicy.Refusal.REVOKED -> AirCatException.RevokedPeer(remoteId)
            AirCatRuntimeGuardPolicy.Refusal.UNAUTHORIZED -> AirCatException.UnauthorizedPeer(remoteId)
            null -> null
        }
    }

    override fun adoptOwnedClient(transferId: String, client: LinkClient) {
        require(transferId.isNotBlank()) { "Transfer ID vacío" }
        check(ownedClients.putIfAbsent(transferId, client) == null) {
            "La transferencia ya tiene un cliente HTTP propietario"
        }
    }

    private fun releaseOwnedClient(transferId: String) {
        runCatching { ownedClients.remove(transferId)?.close() }
    }

    override fun releaseIfNotRetained(transferId: String) {
        // Cancel may remove the session before its in-flight I/O has unwound.
        // Never close the HTTP client until that active job has finished.
        val running = activeJobs[transferId]
        val pendingResume = resumeScheduler.pendingJob(transferId)
        if (activeSessions[transferId] == null &&
            (running == null || running.isCompleted) &&
            (pendingResume == null || pendingResume.isCompleted)) {
            releaseOwnedClient(transferId)
        }
    }

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
        // Preflight has no owned socket/job/session. Register them only once
        // repository, trust, manifest and storage validation have succeeded.

        // A27 P0: proof of signed manifest + enrolled Home authority + TLS pin
        // is mandatory BEFORE any transfer network/filesystem activity, including
        // zero-track success. The bare Link bearer is not an AirCat grant.
        val verifiedGrant = signedGrantSecurity.verify(
            intent, manifest, intent.signedGrant, clock(),
        ).getOrElse { failure ->
            val err = AirCatException.ProtocolError("AIRC_HOME_GRANT_REFUSED: ${failure.message}")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes,
                AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }
        if (!signedGrantSecurity.enrolledSourceReady(intent.sourceMichiId, linkClient.baseUrl) ||
            org.michimusic.link.AirCatTlsPinPolicy.refusal(linkClient.baseUrl, verifiedGrant.tlsPin) != null) {
            val err = AirCatException.ProtocolError("AIRC_TLS_PIN_REQUIRED: autenticación de transporte ausente")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes,
                AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }
        val grantHeader = intent.signedGrant!!.toHttpHeader()

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

        // AM2: pairing status is a local preflight hint, NOT cryptographic authorization.
        val sourceTrust = peerTrust.status(intent.sourceMichiId)
        if (sourceTrust == PeerPairingStatus.MISSING) {
            val err = AirCatException.UnauthorizedPeer(intent.sourceMichiId)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }
        if (sourceTrust == PeerPairingStatus.REVOKED) {
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

        // 6. Validate non-negative totals and calculate required storage with overflow check (K49-01)
        if (manifest.totalTracks < 0 || manifest.totalBytes < 0) {
            val err = AirCatException.InvalidManifestStructure("Valores negativos en totalTracks (${manifest.totalTracks}) o totalBytes (${manifest.totalBytes})")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // 7. Legitimate 0 tracks / 0 bytes empty transfer (K49-02, K49B-06):
        // Finish immediately without requiring the 100 MB storage safety margin.
        // A68-06: this is NOT a completed file transfer. Nothing was downloaded, no
        // byte was verified and no row was committed, so it reports NO_FILES instead
        // of borrowing COMPLETED and leaving two false receipts in the payload.
        if (manifest.totalTracks == 0 && manifest.totalBytes == 0L && manifest.items.isEmpty()) {
            _processedTransferIds.value = _processedTransferIds.value + intent.transferId
            activeSessions.remove(intent.transferId)
            emitEvent(
                transferId = intent.transferId,
                currentTrackIndex = 0,
                totalTracks = 0,
                bytesTransferred = 0L,
                totalBytes = 0L,
                state = AirCatTransferState.NO_FILES,
                checksumVerified = false,
                libraryCommitted = false,
                onProgress = onProgress,
            )
            return@withContext Result.success(emptyList())
        }

        val safetyMargin = 100L * 1024 * 1024
        val requiredBytes = try {
            Math.addExact(manifest.totalBytes, safetyMargin)
        } catch (e: ArithmeticException) {
            val err = AirCatException.InvalidManifestStructure("Desbordamiento numérico al calcular espacio requerido: totalBytes excede el rango admisible")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        if (availableStorageBytes < requiredBytes) {
            val err = AirCatException.InsufficientStorage(requiredBytes, availableStorageBytes)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // 8. Validate manifest items and byte sum consistency (K49-02)
        if (manifest.items.isEmpty()) {
            val err = AirCatException.InvalidManifestStructure("Manifiesto vacío pero con totalTracks (${manifest.totalTracks}) o totalBytes (${manifest.totalBytes}) no nulo")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // K48-03: Always enforce items.size == totalTracks
        if (manifest.items.size != manifest.totalTracks) {
            val err = AirCatException.InvalidManifestStructure("Items declarados (${manifest.items.size}) no coinciden con totalTracks (${manifest.totalTracks})")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // Check for blank IDs
        if (manifest.items.any { it.logicalTrackId.isBlank() }) {
            val err = AirCatException.InvalidManifestStructure("El manifiesto contiene pistas con logicalTrackId vacío o en blanco")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }
        if (manifest.items.any { it.remoteTrackId.isBlank() }) {
            val err = AirCatException.InvalidManifestStructure("El manifiesto contiene pistas con remoteTrackId vacío o en blanco")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // Check for duplicate track IDs
        val distinctIds = manifest.items.map { it.logicalTrackId }.toSet()
        if (distinctIds.size != manifest.items.size) {
            val err = AirCatException.InvalidManifestStructure("El manifiesto contiene IDs de pista duplicados")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // Check for negative sizes or overflow in bytes sum
        var itemsSumBytes = 0L
        for (item in manifest.items) {
            if (item.size < 0) {
                val err = AirCatException.InvalidManifestStructure("Pista '${item.logicalTrackId}' tiene tamaño negativo (${item.size})")
                emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                return@withContext Result.failure(err)
            }
            try {
                itemsSumBytes = Math.addExact(itemsSumBytes, item.size)
            } catch (e: ArithmeticException) {
                val err = AirCatException.InvalidManifestStructure("Desbordamiento numérico en suma de tamaños de pista")
                emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                return@withContext Result.failure(err)
            }
        }

        // K49-02: Strict equality between item sum and totalBytes in ALL cases
        if (itemsSumBytes != manifest.totalBytes) {
            val err = AirCatException.InvalidManifestStructure("Suma de bytes (${itemsSumBytes}B) no coincide con totalBytes (${manifest.totalBytes}B)")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // A22: strict AirCat never starts I/O for hashless, zero-size, or missing
        // checkpoint repositories; legacy Link downloads remain a separate API.
        for (item in manifest.items) {
            val refusal = AirCatStrictFilePolicy.refusal(item.remoteTrackId, item.size, item.sha256)
            if (refusal != null) {
                val err = AirCatException.InvalidManifestStructure("${item.logicalTrackId}: $refusal")
                emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes,
                    AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                return@withContext Result.failure(err)
            }
        }
        if (checkpointRepository == null) {
            val err = AirCatException.ProtocolError("AirCat requiere checkpoint persistente antes de abrir la red")
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes,
                AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // A22: directly-invoked AirCat clients must not bypass the session/registry
        // binding already checked by the Devices coordinator. This is local token
        // consistency, not proof of an authenticated TLS or Ed25519 remote peer.
        if (!peerTrust.matchesSessionToken(intent.sourceMichiId, linkClient.deviceToken)) {
            val err = AirCatException.UnauthorizedPeer(intent.sourceMichiId)
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes,
                AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }

        // 8. Transfer execution (Room-aware backup reconciliation)
        transferManager.reconcileOrphanBackups(
            targetDir = transferManager.musicDir,
            isRegisteredInDatabase = { file ->
                libraryRepository?.isLocationRegistered(file.absolutePath) ?: false
            }
        )
        val downloadedFiles = mutableListOf<File>()
        var transferredBytesAcc = 0L
        var allItemsChecksumVerified = true
        var allItemsLibraryCommitted = (libraryRepository != null)
        // A44: one immutable, persisted receipt per file; no fabricated batch truth.
        val committedFileReceipts = mutableListOf<AirCatCommitReceiptPolicy.Confirmed>()

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

        try {
            progressLedger.authorizeRestart(intent.transferId) // explicit accepted new attempt
            emitEvent(intent.transferId, 0, manifest.totalTracks, 0L, manifest.totalBytes, AirCatTransferState.TRANSFERRING, onProgress = onProgress)
            for ((index, item) in manifest.items.withIndex()) {
                continuityRefusal(intent.sourceMichiId, linkClient.deviceToken,
                    intent.transferId, minOf(intent.expiresAt, verifiedGrant.expiresAtMillis),
                    sourceBaseUrl = linkClient.baseUrl)?.let { refusal ->
                    return@withContext rejectedAtBoundary(
                        intent.transferId, index, manifest.totalTracks, transferredBytesAcc,
                        manifest.totalBytes, refusal, onProgress)
                }
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
                    remoteTrackId = item.remoteTrackId,
                    title = item.title,
                    format = item.format,
                    checksum = item.sha256,
                    size = item.size,
                    remoteDeviceId = intent.sourceMichiId,
                    destinationLibraryId = intent.destinationLibraryId,
                    planId = intent.transferId,
                    requiresLibraryCommit = true,
                )
                val effectiveKey = downloadItem.effectiveTransferKey
                val legacyKey = "${intent.sourceMichiId}:${intent.destinationLibraryId}:${item.logicalTrackId}"

                if (item.sha256.isEmpty() || !isValidSha256(item.sha256)) {
                    allItemsChecksumVerified = false
                }

                // A25: cached reuse is a *durable transaction receipt*, not
                // merely a same-sized, same-hash file and a registered path.
                // Exact transfer, remote track, library, logical identity,
                // final location and checkpoint counters must all corroborate.
                val existingCp = checkpointRepository?.getCheckpoint(effectiveKey)
                    ?: checkpointRepository?.getCheckpoint(legacyKey)
                val existingLocationProven = libraryRepository?.getLocationsForTrack(item.logicalTrackId)?.any { location ->
                    location.uri == finalCandidate.absolutePath &&
                        location.type == TrackLocationType.SYNCED_MANAGED &&
                        location.sourceDeviceId == intent.sourceMichiId &&
                        location.remoteTrackId == item.remoteTrackId
                } == true
                val existingIdentitySha = libraryRepository?.getTrackIdentity(item.logicalTrackId)?.contentHash
                if (AirCatCommitReceiptPolicy.isCommitted(
                        existingCp, effectiveKey, intent.transferId, intent.sourceMichiId,
                        intent.destinationLibraryId, item.logicalTrackId, item.remoteTrackId,
                        finalCandidate.absolutePath, item.size, item.sha256,
                    ) && existingLocationProven &&
                    existingIdentitySha.equals(item.sha256, ignoreCase = true) &&
                    finalCandidate.isFile && finalCandidate.length() == item.size &&
                    verifyChecksum(finalCandidate, item.sha256) &&
                    !transferManager.hasPendingRecovery(finalCandidate)
                ) {
                    val cachedReceipt = AirCatCommitReceiptPolicy.confirm(
                        // Re-read Room *after* the file SHA check; an old checkpoint
                        // object cannot certify a concurrently changed row.
                        checkpointRepository?.getCheckpoint(effectiveKey), effectiveKey,
                        intent.transferId, intent.sourceMichiId,
                        intent.destinationLibraryId, item.logicalTrackId, item.remoteTrackId,
                        finalCandidate.absolutePath, item.size, item.sha256,
                        existingLocationProven, existingIdentitySha, journalSettled = true)
                        ?: return@withContext Result.failure(AirCatException.ProtocolError(
                            "AIRC_CACHE_RECEIPT_CHANGED"))
                    committedFileReceipts.add(cachedReceipt)
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

                // A25: an AirCat journal is the authoritative crash-recovery ticket.
                // NEVER overwrite its persisted COMPLETED/COMMITTING receipt with
                // DOWNLOADING (Room INSERT OR REPLACE would destroy recoverability).
                if (transferManager.hasPendingRecovery(finalCandidate)) {
                    val err = AirCatException.ProtocolError(
                        "AIRC_RECOVERY_REQUIRED: reparar journal antes de sobrescribir checkpoint")
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc,
                        manifest.totalBytes, AirCatTransferState.FAILED, error = err.message,
                        onProgress = onProgress)
                    return@withContext Result.failure(err)
                }
                // A checkpoint's exact source/destination/hash is needed to
                // preserve a server ETag and verified offset. An arbitrary .part
                // is NOT a receipt, even if its byte count looks plausible.
                val validPrevious = existingCp?.takeIf { cp ->
                    cp.transferKey == effectiveKey && cp.transferId == intent.transferId &&
                        cp.remoteDeviceId == intent.sourceMichiId &&
                        cp.destinationLibraryId == intent.destinationLibraryId &&
                        cp.trackId == item.logicalTrackId && cp.remoteTrackId == item.remoteTrackId &&
                        cp.tempFilePath == tempPartPath && cp.finalFilePath == finalPath &&
                        cp.expectedSize == item.size &&
                        cp.expectedSha256.equals(item.sha256, ignoreCase = true)
                }
                val existingBytes = if (tempCandidate.isFile) tempCandidate.length() else 0L
                val initialReceived = if (validPrevious != null && existingBytes > 0L) {
                    minOf(existingBytes, validPrevious.receivedBytes.coerceAtLeast(0L))
                } else 0L

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
                        verifiedBytes = minOf(initialReceived, validPrevious?.verifiedBytes ?: 0L),
                        expectedSha256 = item.sha256,
                        etag = validPrevious?.etag.orEmpty(),
                        transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
                        createdAt = validPrevious?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )

                // A40: write-bound, event-driven continuity on the FIRST download.
                // Cancellation/expiry check before every <=64 KiB write; Home
                // revocation and token trust recheck at <=1 MiB / 2s intervals.
                val continuity = AirCatTransferContinuityGate(
                    nowMillis = clock,
                    expiresAtMillis = minOf(intent.expiresAt, verifiedGrant.expiresAtMillis),
                    isCancelled = { cancelledTransferIds.contains(intent.transferId) },
                    isPaused = { pausedTransferIds.contains(intent.transferId) },
                    verifyTrust = {
                        continuityRefusal(intent.sourceMichiId, linkClient.deviceToken,
                            intent.transferId, minOf(intent.expiresAt, verifiedGrant.expiresAtMillis),
                            sourceBaseUrl = linkClient.baseUrl)
                    },
                )
                val downloadResult = linkClient.pinnedOriginalFileSource(
                    verifiedGrant.tlsPin, grantHeader,
                ).use { source ->
                    transferManager.downloadTrack(source, downloadItem,
                        beforeWrite = continuity::beforeWrite, beforeCommit = continuity::beforeCommit)
                }
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
                        verifiedBytes = existingCp?.verifiedBytes ?: 0L,
                        state = org.michimusic.core.models.TransferStatus.FAILED
                    )
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = ex.message, onProgress = onProgress)
                    return@withContext Result.failure(ex)
                }

                val finalFile = downloadResult.getOrThrow()
                // A23: a peer revoked during streaming must not be registered
                // into Room or declared committed merely because SHA passed.
                continuityRefusal(intent.sourceMichiId, linkClient.deviceToken,
                    intent.transferId, minOf(intent.expiresAt, verifiedGrant.expiresAtMillis),
                    sourceBaseUrl = linkClient.baseUrl)?.let { refusal ->
                    return@withContext rejectedAtBoundary(
                        intent.transferId, index, manifest.totalTracks, transferredBytesAcc,
                        manifest.totalBytes, refusal, onProgress)
                }
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
                        // Never delete an unregistered final automatically: it may be an
                        // orphan protected by A20's durable recovery ticket.
                        transferManager.restoreBackup(finalFile)
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
                        // Leave a no-backup orphan intact until on-demand reconciliation.
                        transferManager.restoreBackup(finalFile)
                        emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED, error = "Fallo al registrar en biblioteca: ${e.message}", onProgress = onProgress)
                        return@withContext Result.failure(e)
                    }
                }

                // A20: require real Room + persistent checkpoint before COMPLETED.
                if (libraryRepository == null || checkpointRepository == null) {
                    val err = AirCatException.ProtocolError("AirCat requiere biblioteca y checkpoint persistente para confirmar transferencia")
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes,
                        AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                    return@withContext Result.failure(err)
                }
                checkpointRepository.updateProgress(
                    transferKey = effectiveKey,
                    receivedBytes = finalFile.length(),
                    verifiedBytes = finalFile.length(),
                    state = org.michimusic.core.models.TransferStatus.COMPLETED
                )
                val durableReceipt = AirCatCommitReceiptPolicy.isCommitted(
                    recorded = checkpointRepository.getCheckpoint(effectiveKey),
                    transferKey = effectiveKey,
                    transferId = intent.transferId,
                    sourceId = intent.sourceMichiId,
                    destinationLibraryId = intent.destinationLibraryId,
                    logicalTrackId = item.logicalTrackId,
                    remoteTrackId = item.remoteTrackId,
                    finalPath = finalFile.absolutePath,
                    expectedSize = item.size,
                    expectedSha256 = item.sha256,
                )
                if (!durableReceipt) {
                    val err = AirCatException.ProtocolError("Checkpoint AirCat no persistido o inconsistente: respaldo protegido")
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc,
                        manifest.totalBytes, AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                    return@withContext Result.failure(err)
                }
                continuityRefusal(intent.sourceMichiId, linkClient.deviceToken,
                    intent.transferId, minOf(intent.expiresAt, verifiedGrant.expiresAtMillis),
                    sourceBaseUrl = linkClient.baseUrl)?.let { refusal ->
                    return@withContext rejectedAtBoundary(
                        intent.transferId, index, manifest.totalTracks, transferredBytesAcc,
                        manifest.totalBytes, refusal, onProgress)
                }
                if (!transferManager.finalizeAfterCommit(
                        finalFile, effectiveKey, item.sha256, libraryCommitted = true, checkpointCommitted = true
                    )) {
                    // A25: Room and checkpoint ALREADY contain a confirmed
                    // COMPLETED receipt. A journal cleanup failure is NOT a
                    // failed download. Preserve COMPLETED so the exact A23
                    // committed-repair path can safely finish on user request.
                    val err = AirCatException.ProtocolError("Pendiente reparación segura del journal AirCat; respaldo preservado")
                    emitEvent(intent.transferId, index, manifest.totalTracks, transferredBytesAcc, manifest.totalBytes,
                        AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
                    return@withContext Result.failure(err)
                }
                // A44: independent post-commit readback, including the registered
                // location and identity content hash, before issuing this receipt.
                val finalLocationRegistered = libraryRepository.getLocationsForTrack(item.logicalTrackId).any { location ->
                    location.uri == finalFile.absolutePath &&
                        location.type == TrackLocationType.SYNCED_MANAGED &&
                        location.sourceDeviceId == intent.sourceMichiId &&
                        location.remoteTrackId == item.remoteTrackId
                }
                val finalContentSha = libraryRepository.getTrackIdentity(item.logicalTrackId)?.contentHash
                val committedReceipt = AirCatCommitReceiptPolicy.confirm(
                    checkpointRepository.getCheckpoint(effectiveKey), effectiveKey, intent.transferId,
                    intent.sourceMichiId, intent.destinationLibraryId,
                    item.logicalTrackId, item.remoteTrackId, finalFile.absolutePath,
                    item.size, item.sha256, finalLocationRegistered, finalContentSha,
                    journalSettled = !transferManager.hasPendingRecovery(finalFile))
                    ?: return@withContext Result.failure(AirCatException.ProtocolError(
                        "AIRC_FILE_RECEIPT_INCOMPLETE"))
                committedFileReceipts.add(committedReceipt)
            }

            val isVerified = manifest.items.isNotEmpty() && allItemsChecksumVerified
            val isCommitted = manifest.items.isNotEmpty() && allItemsLibraryCommitted && libraryRepository != null
            // A42: cannot publish success or seal this transfer ID until every
            // expected file is present at its exact original size AND both
            // persistent Room/checkpoint and SHA-verification receipts exist.
            if (!AirCatBatchCompletionPolicy.completeWithReceipts(
                    manifest, downloadedFiles, committedFileReceipts,
                    isVerified, isCommitted, transferredBytesAcc)) {
                val err = AirCatException.ProtocolError("AIRC_BATCH_RECEIPT_INCOMPLETE")
                emitEvent(intent.transferId, downloadedFiles.size, manifest.totalTracks,
                    transferredBytesAcc, manifest.totalBytes, AirCatTransferState.FAILED,
                    error = err.message, onProgress = onProgress)
                return@withContext Result.failure(err)
            }
            _processedTransferIds.value = _processedTransferIds.value + intent.transferId
            activeSessions.remove(intent.transferId)
            emitEvent(
                transferId = intent.transferId,
                currentTrackIndex = manifest.totalTracks,
                totalTracks = manifest.totalTracks,
                bytesTransferred = manifest.totalBytes,
                totalBytes = manifest.totalBytes,
                state = AirCatTransferState.COMPLETED,
                checksumVerified = isVerified,
                libraryCommitted = isCommitted,
                onProgress = onProgress,
            )
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
            // Remove only this coroutine's registration: a newer resume must
            // never be erased by an older transfer's finally block.
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.let { owner ->
                activeJobs.remove(intent.transferId, owner)
            }
            // Paused/retriable sessions retain their source until success/cancel.
            releaseIfNotRetained(intent.transferId)
        }
    }

    /** UI state changes are keyed by transfer ID, never by another session's event. */
    private fun reportControlState(
        transferId: String,
        state: AirCatTransferState,
        reason: String? = null,
    ) {
        val previous = progressLedger.latest(transferId)
        publishProgress(previous?.copy(state = state, error = reason)
            ?: AirCatProgressEvent(transferId, 0, 0, 0L, 0L, state, error = reason))
    }

    override fun pauseTransfer(transferId: String) {
        if (transferId in _processedTransferIds.value || cancelledTransferIds.contains(transferId)) return
        pausedTransferIds.add(transferId)
        resumeScheduler.cancel(transferId)
        activeJobs[transferId]?.cancel()
        reportControlState(transferId, AirCatTransferState.PAUSED)
    }

    private fun reportResumeBlocked(transferId: String, reason: String) {
        pausedTransferIds.add(transferId)
        reportControlState(transferId, AirCatTransferState.PAUSED, reason)
    }

    override fun resumeTransfer(transferId: String) {
        val session = activeSessions[transferId] ?: return // No fabricated TRANSFERRING state.
        if (cancelledTransferIds.contains(transferId)) return
        val previous = activeJobs[transferId]
        if (previous?.isActive == true) return // Existing operation is genuinely running.
        resumeScheduler.request(
            transferId = transferId,
            previous = previous,
            canRun = { activeSessions[transferId] === session && !cancelledTransferIds.contains(transferId) },
            onBusy = { reportResumeBlocked(transferId, "AirCat ocupado: reintente la reanudación") },
            onStarted = { ownerJob ->
                // A49: fence old work before a resumed state is visible.
                activeJobs[transferId] = ownerJob
                pausedTransferIds.remove(transferId)
                progressLedger.authorizeRestart(transferId)
                reportControlState(transferId, AirCatTransferState.TRANSFERRING)
            },
            onError = { error ->
                reportResumeBlocked(transferId, AirCatUserFacingFailurePolicy.describe(error).message)
            },
            // Final HTTP cleanup occurs after scheduler reservation and I/O finish.
            onSettled = { settledJob ->
                // A51: cleanup a failed/aborted resume only if this Job still owns
                // the transfer. An older completion must not delete a newer owner.
                activeJobs.remove(transferId, settledJob)
                releaseIfNotRetained(transferId)
            },
        ) {
            // The scheduler owns the same process and cross-process file lock
            // as the initial coordinator for the entire streaming/commit call.
            executeTransfer(
                intent = session.intent,
                manifest = session.manifest,
                linkClient = session.linkClient,
                availableStorageBytes = session.availableStorageBytes,
                onProgress = session.onProgress,
            )
        }
    }

    override fun cancelTransfer(transferId: String) {
        cancelledTransferIds.add(transferId)
        val pendingResume = resumeScheduler.cancel(transferId)
        val running = activeJobs[transferId]
        running?.cancel()
        activeSessions.remove(transferId)
        // A queued resume can own the HTTP client before it appears in activeJobs.
        // Await BOTH jobs, including cancellation cleanup, before closing it.
        val users = listOfNotNull(pendingResume, running).distinct()
        if (users.any { !it.isCompleted }) {
            clientScope.launch {
                users.forEach { it.join() }
                releaseOwnedClient(transferId)
            }
        } else {
            releaseOwnedClient(transferId)
        }
        reportControlState(transferId, AirCatTransferState.CANCELLED)
    }

    override fun isTransferPaused(transferId: String): Boolean = pausedTransferIds.contains(transferId)

    override fun isTransferCancelled(transferId: String): Boolean = cancelledTransferIds.contains(transferId)

    override suspend fun recoverIncompleteTransfers(
        remoteDeviceId: String,
        linkClient: LinkClient,
        authorizedTrackIds: Set<String>?,
        authorizedCriteria: List<AuthorizedTransferCriteria>?,
        onProgress: ((AirCatProgressEvent) -> Unit)?,
        authorizationIntent: TransferIntent?,
        authorizationManifest: AirCatManifest?,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        val repo = checkpointRepository ?: return@withContext Result.failure(
            AirCatException.ProtocolError("No hay repositorio de checkpoints para recuperación AirCat"))
        val library = libraryRepository ?: return@withContext Result.failure(
            AirCatException.ProtocolError("MusicLibraryRepository es obligatorio para transferencias gestionadas"))

        // A33: pure local repair is still an import of Home-controlled content.
        // Revoke the source in QR enrollment and even offline cleanup must stop;
        // this check reads local trust only and starts no HTTP connection.
        if (!signedGrantSecurity.enrolledSourceReady(remoteDeviceId, linkClient.baseUrl)) {
            return@withContext Result.failure(AirCatException.RevokedPeer(remoteDeviceId))
        }
        // AM2: use the registry adapter; never expose the stored token to AirCat.
        if (peerTrust.status(remoteDeviceId) != PeerPairingStatus.PAIRED_UNVERIFIED ||
            !peerTrust.matchesSessionToken(remoteDeviceId, linkClient.deviceToken)) {
            return@withContext Result.failure(AirCatException.UnauthorizedPeer(remoteDeviceId))
        }

        val allCheckpoints = repo.getCheckpointsForDevice(remoteDeviceId)
        // A24: the explicit user's allowed subset applies to ALL repair types,
        // including COMPLETED and COMMITTING journal cleanup (not only downloads).
        val selected = allCheckpoints.filter { cp ->
            AirCatRecoveryAuthorizationPolicy.permits(
                cp, remoteDeviceId, authorizedTrackIds, authorizedCriteria)
        }
        // A33: validate the ENTIRE explicit repair selection before writing
        // Room/journals, not just its byte total. Reject duplicate destinations,
        // foreign paths/symlinks and mismatched source/track identities as one
        // atomic preflight. No partial commits when a later row is malicious.
        AirCatRecoveryBatchPreflightPolicy.refusal(
            selected, remoteDeviceId, transferManager.musicDir,
        )?.let { reason ->
            return@withContext Result.failure(AirCatException.InvalidManifestStructure(reason))
        }
        val repairedCommittedFiles = mutableListOf<File>()
        val repairedTransferKeys = mutableSetOf<String>()
        for (cp in selected) {
            val final = File(cp.finalFilePath)
            if (!transferManager.hasPendingRecovery(final)) continue
            if (!transferManager.hasMatchingPendingJournal(final, cp.transferKey, cp.expectedSha256)) {
                return@withContext Result.failure(AirCatException.ProtocolError(
                    "AirCat: journal no pertenece a la transferencia o aún está PREPARED; respaldo preservado"))
            }
            if (cp.transferState == org.michimusic.core.models.TransferStatus.FAILED) {
                return@withContext Result.failure(AirCatException.ProtocolError(
                    "AirCat: checkpoint FAILED posee journal; requiere inspección manual"))
            }
            continuityRefusal(remoteDeviceId, linkClient.deviceToken, cp.transferId,
                sourceBaseUrl = linkClient.baseUrl)?.let { refusal ->
                return@withContext rejectedAtBoundary(
                    cp.transferId, 0, selected.size, 0L, 0L, refusal, onProgress)
            }
            val exactRegisteredLocation = library.getLocationsForTrack(cp.trackId).any { location ->
                location.uri == cp.finalFilePath &&
                    location.sourceDeviceId == cp.remoteDeviceId &&
                    location.remoteTrackId == cp.remoteTrackId &&
                    location.type == TrackLocationType.SYNCED_MANAGED
            }
            val registeredIdentitySha = library.getTrackIdentity(cp.trackId)?.contentHash
            val receipt = if (cp.transferState != org.michimusic.core.models.TransferStatus.COMPLETED) {
                // The Room commit may have succeeded while the checkpoint row
                // was still PENDING/DOWNLOADING/PAUSED/COMMITTING. A journal and
                // exact source file + Room proof are mandatory before promotion.
                val assessment = transferManager.diagnoseRecovery(final, true, true)
                if (!AirCatInterruptedCommitPolicy.mayConfirmExistingRoomCommit(
                        cp, remoteDeviceId, transferManager.musicDir,
                        exactRegisteredLocation, registeredIdentitySha, assessment)) {
                    return@withContext Result.failure(AirCatException.ProtocolError(
                        "AirCat: checkpoint interrumpido no corroborado por journal, SHA y biblioteca; reparación manual obligatoria"))
                }
                repo.updateProgress(cp.transferKey, final.length(), final.length(),
                    org.michimusic.core.models.TransferStatus.COMPLETED)
                repo.getCheckpoint(cp.transferKey) ?: return@withContext Result.failure(
                    AirCatException.ProtocolError("AirCat: checkpoint no persistió durante reparación"))
            } else cp
            // A persisted row may change between update and readback. Bind the
            // receipt to the ORIGINAL selected plan, not just to itself.
            if (!AirCatCommitReceiptPolicy.isCommitted(
                    receipt, cp.transferKey, cp.transferId, cp.remoteDeviceId,
                    cp.destinationLibraryId, cp.trackId, cp.remoteTrackId,
                    cp.finalFilePath, cp.expectedSize, cp.expectedSha256)) {
                return@withContext Result.failure(AirCatException.ProtocolError(
                    "AirCat: los campos del checkpoint cambiaron durante la reparación"))
            }
            if (!AirCatCommittedRecoveryPolicy.mayFinalize(
                    receipt, remoteDeviceId, transferManager.musicDir,
                    exactRegisteredLocation, registeredIdentitySha)) {
                return@withContext Result.failure(AirCatException.ProtocolError(
                    "AirCat: journal pendiente no corroborado por biblioteca y checkpoint; respaldo conservado"))
            }
            // Re-check revocation and the presented Link token AFTER all journal
            // hashing and Room reads, immediately before releasing the backup.
            continuityRefusal(remoteDeviceId, linkClient.deviceToken, cp.transferId,
                sourceBaseUrl = linkClient.baseUrl)?.let { refusal ->
                return@withContext rejectedAtBoundary(
                    cp.transferId, 0, selected.size, 0L, 0L, refusal, onProgress)
            }
            if (!transferManager.finalizeAfterCommit(
                    final, receipt.transferKey, receipt.expectedSha256,
                    libraryCommitted = true, checkpointCommitted = true)) {
                return@withContext Result.failure(AirCatException.ProtocolError(
                    "AirCat: no se pudo cerrar el journal tras el commit; respaldo conservado"))
            }
            repairedCommittedFiles.add(final)
            repairedTransferKeys.add(cp.transferKey)
        }
        // A24: pre-promotion transfers may resume .part if NO journal exists;
        // repaired transfers must not be redownloaded after closing their journal.
        val pendingCheckpoints = selected.filter { cp ->
            if (cp.transferKey in repairedTransferKeys) return@filter false
            cp.transferState == org.michimusic.core.models.TransferStatus.DOWNLOADING ||
            cp.transferState == org.michimusic.core.models.TransferStatus.PAUSED ||
            cp.transferState == org.michimusic.core.models.TransferStatus.PENDING ||
            (cp.transferState == org.michimusic.core.models.TransferStatus.COMMITTING &&
                !transferManager.hasPendingRecovery(File(cp.finalFilePath)))
        }

        if (pendingCheckpoints.isEmpty()) {
            return@withContext Result.success(repairedCommittedFiles)
        }

        // A27: pure journal finalization above is local and offline. Actual
        // network recovery requires fresh authenticated authority, pinned TLS,
        // the ORIGINAL complete manifest and an explicit user request. Never
        // construct a manifest from Room checkpoints (that would let Room data
        // authorize itself). A subset can be resumed only if its rows match the
        // signed original manifest exactly.
        val resumeIntent = authorizationIntent ?: return@withContext Result.failure(
            AirCatException.ProtocolError("AIRC_NEW_HOME_GRANT_REQUIRED"))
        val resumeManifest = authorizationManifest ?: return@withContext Result.failure(
            AirCatException.ProtocolError("AIRC_ORIGINAL_MANIFEST_REQUIRED"))
        val verifiedResume = signedGrantSecurity.verify(
            resumeIntent, resumeManifest, resumeIntent.signedGrant, clock(),
        ).getOrElse { return@withContext Result.failure(AirCatException.ProtocolError(
            "AIRC_RECOVERY_GRANT_REFUSED: ${it.message}")) }
        if (resumeIntent.sourceMichiId != remoteDeviceId ||
            !signedGrantSecurity.enrolledSourceReady(remoteDeviceId, linkClient.baseUrl) ||
            org.michimusic.link.AirCatTlsPinPolicy.refusal(linkClient.baseUrl, verifiedResume.tlsPin) != null) {
            return@withContext Result.failure(AirCatException.ProtocolError("AIRC_RECOVERY_TLS_REFUSED"))
        }
        val originalItems = resumeManifest.items.associateBy { it.logicalTrackId }
        if (originalItems.size != resumeManifest.items.size || pendingCheckpoints.any { cp ->
                val item = originalItems[cp.trackId]
                cp.transferId != resumeIntent.transferId ||
                    cp.remoteDeviceId != resumeIntent.sourceMichiId ||
                    cp.sourceLibraryId != resumeIntent.sourceLibraryId ||
                    cp.destinationLibraryId != resumeIntent.destinationLibraryId ||
                    item == null || item.remoteTrackId != cp.remoteTrackId ||
                    item.size != cp.expectedSize ||
                    !item.sha256.equals(cp.expectedSha256, ignoreCase = true)
            }) {
            return@withContext Result.failure(AirCatException.ProtocolError(
                "AIRC_RECOVERY_NOT_IN_SIGNED_MANIFEST"))
        }
        val resumeGrantHeader = resumeIntent.signedGrant!!.toHttpHeader()

        // A33: never run a directory-wide legacy .bak repair while executing a
        // selected, Home-granted AirCat recovery. Such a scan could overwrite
        // an unrelated track (and waste I/O) outside this explicit selection.
        // Only the selected, corroborated journal paths above may be finalized.

        val repairedInitially = repairedCommittedFiles.size
        val recoveredFiles = repairedCommittedFiles
        val totalExpectedBytes = AirCatByteTotalPolicy.exactTotal(pendingCheckpoints.map { it.expectedSize })
            ?: return@withContext Result.failure(AirCatException.InvalidManifestStructure(
                "Recovery byte total overflow; no transfers resumed"))
        var bytesAccumulated = 0L
        var allItemsChecksumVerified = true
        val allItemsLibraryCommitted = true

        pendingCheckpoints.forEachIndexed { index, cp ->
            val strictRefusal = AirCatStrictFilePolicy.refusal(
                cp.remoteTrackId, cp.expectedSize, cp.expectedSha256)
            if (strictRefusal != null) {
                return@withContext Result.failure(AirCatException.InvalidManifestStructure(
                    "Recuperación ${cp.trackId}: $strictRefusal"))
            }
            if (cp.transferKey.isBlank() || cp.destinationLibraryId.isBlank() || cp.trackId.isBlank() ||
                cp.finalFilePath.isBlank() || cp.remoteDeviceId != remoteDeviceId) {
                return@withContext Result.failure(AirCatException.InvalidManifestStructure(
                    "Checkpoint de recuperación incompleto o de otro dispositivo"))
            }
            // Revocation or token change since recovery start blocks this item.
            continuityRefusal(remoteDeviceId, linkClient.deviceToken, cp.transferId,
                minOf(resumeIntent.expiresAt, verifiedResume.expiresAtMillis),
                sourceBaseUrl = linkClient.baseUrl)?.let { refusal ->
                return@withContext rejectedAtBoundary(
                    cp.transferId, index, pendingCheckpoints.size, bytesAccumulated, totalExpectedBytes, refusal, onProgress)
            }

            if (cp.expectedSha256.isEmpty() || !isValidSha256(cp.expectedSha256)) {
                allItemsChecksumVerified = false
            }

            val ext = cp.finalFilePath.substringAfterLast('.', "mp3")
            val downloadItem = DownloadItem(
                trackId = cp.trackId,
                remoteTrackId = cp.remoteTrackId.ifBlank { cp.trackId },
                title = cp.trackId,
                format = ext,
                checksum = cp.expectedSha256,
                size = cp.expectedSize,
                remoteDeviceId = cp.remoteDeviceId,
                destinationLibraryId = cp.destinationLibraryId,
                transferKey = cp.transferKey,
                planId = cp.transferId,
                requiresLibraryCommit = true,
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

            // A40: explicit recovery is not an exception to active trust.
            val continuity = AirCatTransferContinuityGate(
                nowMillis = clock,
                expiresAtMillis = minOf(resumeIntent.expiresAt, verifiedResume.expiresAtMillis),
                isCancelled = { cancelledTransferIds.contains(cp.transferId) },
                isPaused = { pausedTransferIds.contains(cp.transferId) },
                verifyTrust = {
                    continuityRefusal(remoteDeviceId, linkClient.deviceToken, cp.transferId,
                        minOf(resumeIntent.expiresAt, verifiedResume.expiresAtMillis),
                        sourceBaseUrl = linkClient.baseUrl)
                },
            )
            val downloadResult = linkClient.pinnedOriginalFileSource(
                verifiedResume.tlsPin, resumeGrantHeader,
            ).use { source ->
                transferManager.downloadTrack(source, downloadItem,
                    beforeWrite = continuity::beforeWrite, beforeCommit = continuity::beforeCommit)
            }
            if (downloadResult.isFailure) {
                val ex = downloadResult.exceptionOrNull() ?: Exception("Error reanudando ${cp.trackId}")
                val tempCandidate = File(cp.tempFilePath)
                val preservedBytes = if (tempCandidate.exists()) tempCandidate.length() else 0L
                repo.updateProgress(
                    transferKey = cp.transferKey,
                    receivedBytes = preservedBytes,
                    verifiedBytes = cp.verifiedBytes,
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
            // A23: never import a file after revocation during its network stream.
            continuityRefusal(remoteDeviceId, linkClient.deviceToken, cp.transferId,
                minOf(resumeIntent.expiresAt, verifiedResume.expiresAtMillis),
                sourceBaseUrl = linkClient.baseUrl)?.let { refusal ->
                return@withContext rejectedAtBoundary(
                    cp.transferId, index, pendingCheckpoints.size, bytesAccumulated, totalExpectedBytes, refusal, onProgress)
            }
            recoveredFiles.add(finalFile)
            bytesAccumulated += finalFile.length()

            // Library is mandatory and verified before any recovery I/O.
            val existingIdentity = library.getTrackIdentity(cp.trackId)
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
                    // Never delete an orphaned final without durable ownership evidence.
                    transferManager.restoreBackup(finalFile)
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
                    library.registerTrackWithLocation(
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
                    // No guessed cleanup: an A20 journal explicitly identifies the orphan.
                    transferManager.restoreBackup(finalFile)
                    return@withContext Result.failure(e)
                }

            // Mark checkpoint completed only after library registration.
            repo.updateProgress(
                transferKey = cp.transferKey,
                receivedBytes = finalFile.length(),
                verifiedBytes = finalFile.length(),
                state = org.michimusic.core.models.TransferStatus.COMPLETED
            )
            val durableReceipt = AirCatCommitReceiptPolicy.isCommitted(
                recorded = repo.getCheckpoint(cp.transferKey),
                transferKey = cp.transferKey,
                transferId = cp.transferId,
                sourceId = cp.remoteDeviceId,
                destinationLibraryId = cp.destinationLibraryId,
                logicalTrackId = cp.trackId,
                remoteTrackId = cp.remoteTrackId,
                finalPath = finalFile.absolutePath,
                expectedSize = cp.expectedSize,
                expectedSha256 = cp.expectedSha256,
            )
            if (!durableReceipt) {
                return@withContext Result.failure(AirCatException.ProtocolError(
                    "Checkpoint de recuperación inconsistente: no se declara completado"))
            }
            continuityRefusal(remoteDeviceId, linkClient.deviceToken, cp.transferId,
                minOf(resumeIntent.expiresAt, verifiedResume.expiresAtMillis),
                sourceBaseUrl = linkClient.baseUrl)?.let { refusal ->
                return@withContext rejectedAtBoundary(
                    cp.transferId, index, pendingCheckpoints.size, bytesAccumulated, totalExpectedBytes, refusal, onProgress)
            }
            if (!transferManager.finalizeAfterCommit(
                    finalFile, cp.transferKey, cp.expectedSha256,
                    libraryCommitted = true, checkpointCommitted = true
                )) {
                // A25: keep the COMPLETED Room receipt. Rewriting FAILED
                // would make a valid committed journal unrecoverable next time.
                return@withContext Result.failure(AirCatException.ProtocolError(
                    "Journal de recuperación AirCat sin confirmar; respaldo intacto"))
            }
        }

        val isVerified = pendingCheckpoints.isNotEmpty() && allItemsChecksumVerified
        val isCommitted = pendingCheckpoints.isNotEmpty() && allItemsLibraryCommitted
        // No falsely verified recovery: every pending file must be returned
        // with exact committed byte totals; earlier repaired files are separate.
        if (!isVerified || !isCommitted || bytesAccumulated != totalExpectedBytes ||
            recoveredFiles.size != repairedInitially + pendingCheckpoints.size) {
            val err = AirCatException.ProtocolError("AIRC_BATCH_RECEIPT_INCOMPLETE")
            emitEvent(pendingCheckpoints.first().transferId, pendingCheckpoints.size,
                pendingCheckpoints.size, bytesAccumulated, totalExpectedBytes,
                AirCatTransferState.FAILED, error = err.message, onProgress = onProgress)
            return@withContext Result.failure(err)
        }
        emitEvent(
            transferId = pendingCheckpoints.first().transferId,
            currentTrackIndex = pendingCheckpoints.size,
            totalTracks = pendingCheckpoints.size,
            bytesTransferred = totalExpectedBytes,
            totalBytes = totalExpectedBytes,
            state = AirCatTransferState.COMPLETED,
            checksumVerified = isVerified,
            libraryCommitted = isCommitted,
            onProgress = onProgress,
        )

        Result.success(recoveredFiles)
    }

    /** Every runtime refusal emits a terminal or paused UI state, never a stale spinner. */
    private suspend fun rejectedAtBoundary(
        transferId: String,
        index: Int,
        total: Int,
        bytes: Long,
        totalBytes: Long,
        refusal: AirCatException,
        onProgress: ((AirCatProgressEvent) -> Unit)?,
    ): Result<List<File>> {
        val state = when {
            cancelledTransferIds.contains(transferId) -> AirCatTransferState.CANCELLED
            pausedTransferIds.contains(transferId) -> AirCatTransferState.PAUSED
            else -> AirCatTransferState.FAILED
        }
        val safe = AirCatUserFacingFailurePolicy.describe(refusal)
        emitEvent(transferId, index, total, bytes, totalBytes,
            state, error = safe.message, onProgress = onProgress)
        return Result.failure(refusal)
    }

    private suspend fun emitEvent(
        transferId: String,
        currentTrackIndex: Int,
        totalTracks: Int,
        bytesTransferred: Long,
        totalBytes: Long,
        state: AirCatTransferState,
        activeTrackTitle: String = "",
        error: String? = null,
        checksumVerified: Boolean = false,
        libraryCommitted: Boolean = false,
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
            checksumVerified = checksumVerified,
            libraryCommitted = libraryCommitted,
        )
        // A49: late callbacks from an older (already-cancelled) attempt do not
        // own the lane once a resume Job has acquired that same transfer ID.
        // Recovery without an active network owner keeps the existing path.
        val currentOwner = activeJobs[transferId]
        val emittingOwner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        if (!AirCatAttemptOwnerPolicy.mayPublish(currentOwner, emittingOwner)) return
        val safeEvent = publishProgress(event)
        if (safeEvent != null) {
            // A callback is presentation only; always emit the sanitized receipt.
            runCatching { onProgress?.invoke(safeEvent) }
        }
    }

    private fun verifyChecksum(file: File, expected: String): Boolean {
        if (!file.exists()) return false
        val hex = calculateSha256(file)
        return hex.equals(expected.trim(), ignoreCase = true)
    }

    private fun calculateSha256(file: File): String {
        // A50: shared NOFOLLOW reader also protects cached and journal-verified originals.
        return org.michimusic.link.AirCatSafeFileHash.sha256(file)
    }

    companion object {
        fun isValidSha256(hash: String?): Boolean {
            if (hash.isNullOrEmpty() || hash.length != 64) return false
            return hash.all { c ->
                (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            }
        }
    }
}
