package org.michimusic.mobile.devices.aircat

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.LocationAvailability
import org.michimusic.core.models.TrackIdentity
import org.michimusic.core.models.TrackLocation
import org.michimusic.core.models.TrackLocationType
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.port.PinnedOriginalFileSource
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class AirCatContractTest {

    private lateinit var tempDir: File
    // A33/A67: recovery only repairs paths that live inside the managed root.
    private val musicRoot: File get() = File(tempDir, "synced_music").also { it.mkdirs() }
    private lateinit var mockContext: Context
    private lateinit var transferManager: LinkTransferManager
    private lateinit var pairedRegistry: PairedDeviceRegistry
    private lateinit var libraryRepo: MusicLibraryRepository
    private lateinit var aircatClient: DefaultAirCatTransferClient
    private lateinit var durableCheckpoints: org.michimusic.core.models.TransferCheckpointRepository
    private val checkpointRows = java.util.concurrent.ConcurrentHashMap<String, org.michimusic.core.models.PersistentCheckpoint>()

    // A27/A67: the client refuses any transfer that no Home authority signed.
    // These tests mint real Ed25519 grants so what is under test stays the
    // transfer contract, never a grant parser shortcut.
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val authorityKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val authorityPublic = authorityKey.generatePublicKey().encoded
    private val authorityId = michiIdOf(authorityPublic)
    private val tlsPin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val pinnedBaseUrl = "https://source.example"


    /**
     * A27: the Home anchor is provisioned by enrollment. The provider mirrors that
     * anchor for every source id the test declares, so the contract under test is
     * always the recovery/transfer rule, never a missing enrollment.
     */
    private val homeAnchor: AirCatHomeAnchor
        get() = AirCatHomeAnchor(
            homeId = authorityId,
            authorityMichiId = authorityId,
            authorityPublicKeyBase64Url = encoder.encodeToString(authorityPublic),
            sourceMichiId = "",
            tlsSpkiSha256 = tlsPin,
            pinnedBaseUrl = pinnedBaseUrl,
        )

    private val grantSecurity = AirCatSignedGrantSecurity(object : AirCatHomeAuthorityProvider {
        override fun anchorFor(homeId: String): AirCatHomeAnchor? =
            homeAnchor.takeIf { it.homeId == homeId }

        override fun anchorForSource(sourceMichiId: String): AirCatHomeAnchor? =
            homeAnchor.copy(sourceMichiId = sourceMichiId)
    })

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("aircat_test").toFile()
        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns tempDir
        // A33/A67: recovery preflights the managed root before writing anything, so
        // the destination directory has to exist for a legitimate repair batch.
        File(tempDir, "synced_music").mkdirs()

        durableCheckpoints = mockk(relaxed = true)
        checkpointRows.clear()
        coEvery { durableCheckpoints.getCheckpoint(any()) } answers {
            checkpointRows[firstArg<String>()]
        }
        coEvery { durableCheckpoints.saveCheckpoint(any()) } answers {
            val cp = firstArg<org.michimusic.core.models.PersistentCheckpoint>()
            checkpointRows[cp.transferKey] = cp
        }
        coEvery { durableCheckpoints.updateProgress(any(), any(), any(), any()) } answers {
            val key = firstArg<String>()
            val cp = checkpointRows[key]
            if (cp != null) checkpointRows[key] = cp.copy(
                receivedBytes = secondArg<Long>(),
                verifiedBytes = thirdArg<Long>(),
                transferState = arg(3),
            )
        }
        transferManager = LinkTransferManager(mockContext, durableCheckpoints)
        pairedRegistry = mockk(relaxed = true)
        // A68-03: the commit receipt re-reads what the library recorded. A relaxed
        // mock answers "nothing", so every receipt would fail; a stateful fake keeps
        // the receipt honest because it can only succeed on a row that exists.
        libraryRepo = FakeMusicLibraryRepository()

        // A27: the Home anchor is provisioned by enrollment; `homeAnchor` and
        // `grantSecurity` are shared by every client built in this class.

        aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = durableCheckpoints,
            signedGrantSecurity = grantSecurity,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Base64url(BLAKE3-256(pubkey)) is the only Michi identity a grant accepts. */
    private fun michiIdOf(publicKey: ByteArray): String {
        val digest = Blake3Digest(256).also { it.update(publicKey, 0, publicKey.size) }
        return encoder.encodeToString(ByteArray(32).also { digest.doFinal(it, 0) })
    }

    /** Grants reject any identity that is not a canonical 43-char Link id. */
    /** A well-formed SHA-256 so a fixture manifest can be canonically hashed. */
    private val sampleItemSha = "0".repeat(64)

    /**
     * A67: a manifest that cannot be canonically hashed, a grant bound to another
     * transfer or an expired window is refused by the signed-grant boundary before
     * any business validation runs. That order is the contract, so these cases
     * assert the refusal reason rather than a downstream exception class.
     */
    private fun assertGrantRefused(reason: String, result: Result<*>) {
        val refused = result.exceptionOrNull() as? AirCatException.ProtocolError
        assertTrue(
            "expected AIRC_HOME_GRANT_REFUSED: $reason but was ${result.exceptionOrNull()}",
            refused?.message?.startsWith("AIRC_HOME_GRANT_REFUSED: $reason") == true,
        )
    }

    private fun mid(tag: String): String = tag.padEnd(43, 'x').take(43)

    /** Mints the Home-signed grant this intent needs to reach the transfer contract. */
    private fun authorized(intent: TransferIntent, manifest: AirCatManifest): TransferIntent {
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val nonceBase64Url = encoder.encodeToString(nonce)
        val issuedAt = System.currentTimeMillis()
        val sha = AirCatCanonicalWire.manifestSha256(manifest) ?: "0".repeat(64)
        val grant = AirCatSignedGrant(
            version = 1,
            homeId = authorityId,
            authorityMichiId = authorityId,
            transferId = intent.transferId,
            sourceMichiId = intent.sourceMichiId,
            destinationMichiId = intent.destinationMichiId,
            sourceLibraryId = intent.sourceLibraryId,
            destinationLibraryId = intent.destinationLibraryId,
            selectionScope = intent.selectionScope,
            scopes = listOf("content:read", "content:transfer"),
            manifestSha256 = sha,
            issuedAtMillis = issuedAt,
            expiresAtMillis = minOf(issuedAt + 300_000L, intent.expiresAt),
            nonceBase64Url = nonceBase64Url,
            signatureBase64Url = "",
        )
        val bytes = AirCatCanonicalWire.signedGrantBytes(grant)
        val signer = Ed25519Signer().also {
            it.init(true, authorityKey)
            it.update(bytes, 0, bytes.size)
        }
        return intent.copy(
            signedGrant = grant.copy(
                signatureBase64Url = encoder.encodeToString(signer.generateSignature()),
            ),
            authorizationNonceBase64Url = nonceBase64Url,
        )
    }

    /** A28: the pinned endpoint is the only origin a grant may be presented to. */

    /**
     * A27/A67: the client now obtains its byte source through
     * [LinkClient.pinnedOriginalFileSource], which refuses an unpinned endpoint.
     * The double keeps the pinned-transport contract while delegating the actual
     * range read to the client the test already stubbed.
     */
    private fun pinnedSourceOf(client: LinkClient) = object : PinnedOriginalFileSource {
        override val servesOriginalBytes: Boolean get() = true
        override val verifiedTransport: Boolean get() = true
        override fun close() = Unit
        override suspend fun streamTrackRange(
            trackId: String,
            outputStream: java.io.OutputStream,
            startBytes: Long,
            expectedEtag: String?,
            bufferSize: Int,
            onProgress: ((bytesRead: Long, totalExpected: Long?) -> Unit)?,
        ) = client.streamTrackRange(trackId, outputStream, startBytes, expectedEtag, bufferSize, onProgress)
    }

    private fun pinnedLink(client: LinkClient): LinkClient {
        every { client.baseUrl } returns pinnedBaseUrl
        // A67: the transfer client hands out a pinned original source, so every
        // client used by these contracts must answer that call as well.
        every { client.pinnedOriginalFileSource(any(), any()) } returns pinnedSourceOf(client)
        return client
    }

    /** The fake persistence layer must prove read-after-write, not just return Unit. */
    private fun stubDurableCompletion(
        repo: org.michimusic.core.models.TransferCheckpointRepository,
        initial: org.michimusic.core.models.PersistentCheckpoint,
    ) {
        var persisted = initial
        coEvery { repo.getCheckpoint(initial.transferKey) } answers { persisted }
        coEvery { repo.updateProgress(initial.transferKey, any(), any(), any()) } answers {
            persisted = persisted.copy(
                receivedBytes = secondArg<Long>(),
                verifiedBytes = thirdArg<Long>(),
                transferState = arg(3),
            )
        }
    }

    /**
     * A68-01: a network recovery is authorised by a fresh Home grant over an
     * independent original manifest, never by the checkpoints it resumes. Room holds
     * bytes and hashes; it cannot authorise the transfer that produced them.
     *
     * The manifest and the intent must describe the same transfer as every pending
     * checkpoint in the batch: one transfer id, one destination library, and one item
     * per checkpoint with matching remote id, size and hash.
     */
    private fun recoveryAuthority(
        deviceId: String,
        transferId: String,
        sourceLibraryId: String,
        destinationLibraryId: String,
        items: List<AirCatManifestItem>,
    ): Pair<TransferIntent, AirCatManifest> {
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = transferId,
            sourceDeviceId = deviceId,
            totalTracks = items.size,
            totalBytes = items.sumOf { it.size },
            items = items,
        )
        val intent = TransferIntent(
            transferId = transferId,
            sourceMichiId = deviceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = sourceLibraryId,
            destinationLibraryId = destinationLibraryId,
        )
        return authorized(intent, manifest) to manifest
    }

    private fun item(
        logicalTrackId: String,
        remoteTrackId: String,
        size: Long,
        sha256: String,
    ) = AirCatManifestItem(
        logicalTrackId = logicalTrackId,
        remoteTrackId = remoteTrackId,
        title = logicalTrackId,
        artist = "Michi QA",
        album = "Contract Album",
        format = "mp3",
        size = size,
        sha256 = sha256,
    )

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun aircatUnauthorizedPeerRejectedTest() = runBlocking {
        val sourceId = mid("unknown_rogue_peer")
        every { pairedRegistry.getDevice(sourceId) } returns null

        val intent = TransferIntent(
            transferId = "xfer_unauth_1",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_unauth_1",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, sampleItemSha)
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk()),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Unauthorized peer must be rejected", result.isFailure)
        assertTrue("expected AirCatException.UnauthorizedPeer but was ${result.exceptionOrNull()}", result.exceptionOrNull() is AirCatException.UnauthorizedPeer)
        assertEquals("state must be FAILED but was ${aircatClient.progressEvents.value?.state}", AirCatTransferState.FAILED, aircatClient.progressEvents.value?.state)
    }

    @Test
    fun aircatManifestVersionMismatchTest() = runBlocking {
        val sourceId = mid("trusted_peer_1")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val intent = TransferIntent(
            transferId = "xfer_version_mismatch",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
            manifestVersion = 1, // Intent specifies version 1
        )
        val manifest = AirCatManifest(
            manifestVersion = 2, // Manifest received version 2
            transferId = "xfer_version_mismatch",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk()),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Manifest version mismatch must be rejected", result.isFailure)
        assertGrantRefused("AIRC_GRANT_VERSION", result)
    }

    @Test
    fun aircatDestinationCapacityTest() = runBlocking {
        val sourceId = mid("trusted_peer_1")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val intent = TransferIntent(
            transferId = "xfer_no_capacity",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_no_capacity",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 500L * 1024 * 1024, // 500 MB
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 500L * 1024 * 1024, sampleItemSha)
            ),
        )

        // Only 150 MB available -> does not satisfy 500 MB + 100 MB margin
        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk()),
            availableStorageBytes = 150L * 1024 * 1024,
        )

        assertTrue("Insufficient storage must fail transfer", result.isFailure)
        assertTrue("expected AirCatException.InsufficientStorage but was ${result.exceptionOrNull()}", result.exceptionOrNull() is AirCatException.InsufficientStorage)
    }

    @Test
    fun aircatDuplicatedTransferIdTest() = runBlocking {
        val sourceId = mid("trusted_peer_1")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val content = "AirCat Audio Track".toByteArray()
        val peer = FakeAirCatPeer(sourceId)
        every { peer.mockClient.deviceToken } returns "token"
        peer.setupTrackStreaming("rem_1", content)

        val intent = TransferIntent(
            transferId = "xfer_duplicate_check",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_duplicate_check",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = content.size.toLong(),
            items = listOf(
                AirCatManifestItem("trk_dup", "rem_1", "Song", "Artist", "Album", "mp3", content.size.toLong(), sha256(content))
            )
        )

        // First execution succeeds
        val res1 = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(peer.mockClient),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )
        assertTrue("res1 must succeed but was ${res1.exceptionOrNull()}", res1.isSuccess)
        assertEquals(AirCatTransferState.COMPLETED, aircatClient.progressEvents.value?.state)

        // Second execution with same transferId must be rejected
        val res2 = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(peer.mockClient),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )
        assertTrue("Duplicate transferId must fail", res2.isFailure)
        assertTrue(res2.exceptionOrNull() is AirCatException.DuplicatedTransfer)
    }

    @Test
    fun aircatSuccessfulTransferBindsLibraryTest() = runBlocking {
        val sourceId = mid("trusted_peer_node")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Desktop Node", deviceToken = "tok")

        val content = "Hi-Fi Studio Master Audio Stream".toByteArray()
        val expectedHash = sha256(content)
        val peer = FakeAirCatPeer(sourceId)
        every { peer.mockClient.deviceToken } returns "tok"
        peer.setupTrackStreaming("remote_hifi_1", content)

        val intent = TransferIntent(
            transferId = "xfer_success_binding",
            sourceMichiId = sourceId,
            destinationMichiId = mid("phone_client"),
            sourceLibraryId = "lib_desktop",
            destinationLibraryId = "lib_phone_synced",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_success_binding",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = content.size.toLong(),
            items = listOf(
                AirCatManifestItem("trk_hifi", "remote_hifi_1", "Moonlight", "Michi Jazz", "Night Sessions", "flac", content.size.toLong(), expectedHash)
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(peer.mockClient),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("result must succeed but was ${result.exceptionOrNull()}", result.isSuccess)
        val files = result.getOrThrow()
        assertEquals(1, files.size)
        assertTrue(files[0].exists())
        assertEquals(content.size.toLong(), files[0].length())
        assertEquals(expectedHash, sha256(files[0].readBytes()))

        // The registration is read back from the library that received it, not from
        // a verifier that would accept a call nobody persisted.
        val fakeLibrary = libraryRepo as FakeMusicLibraryRepository
        val recorded = fakeLibrary.registrations.single()
        assertEquals("lib_phone_synced", recorded.libraryId)
        assertEquals("AIRC_AT", recorded.origin)
        assertEquals("trk_hifi", recorded.identity.id)
        assertEquals("Moonlight", recorded.identity.title)
        assertEquals(expectedHash, recorded.identity.contentHash)
        assertEquals("trk_hifi", recorded.location.trackId)
        assertEquals(sourceId, recorded.location.sourceDeviceId)
        assertEquals("remote_hifi_1", recorded.location.remoteTrackId)
        assertEquals(files[0].absolutePath, recorded.location.uri)
        assertEquals(org.michimusic.core.models.TrackLocationType.SYNCED_MANAGED, recorded.location.type)
    }

    @Test
    fun aircatRevokedPeerRejectedTest() = runBlocking {
        val sourceId = mid("revoked_peer_1")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(
            deviceId = sourceId,
            deviceName = "Revoked Node",
            deviceToken = "token",
            isRevoked = true,
        )

        val intent = TransferIntent(
            transferId = "xfer_revoked_1",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_revoked_1",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, sampleItemSha)
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk()),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Revoked peer must be rejected", result.isFailure)
        assertTrue("expected AirCatException.RevokedPeer but was ${result.exceptionOrNull()}", result.exceptionOrNull() is AirCatException.RevokedPeer)
    }

    @Test
    fun aircatExpiredIntentRejectedTest() = runBlocking {
        var currentTime = 10_000L
        aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            clock = { currentTime },
            signedGrantSecurity = grantSecurity,
        )

        val sourceId = mid("trusted_peer_exp")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_expired_1",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
            expiresAt = 9_999L // Expired
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_expired_1",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, "")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk()),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Expired transfer must fail", result.isFailure)
        assertGrantRefused("AIRC_GRANT_EXPIRED", result)
    }

    @Test
    fun aircatMismatchedTransferIdRejectedTest() = runBlocking {
        val sourceId = mid("trusted_peer_mis")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_intent_id",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_manifest_id_different",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, "")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk()),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertGrantRefused("AIRC_GRANT_TRANSFER", result)
    }

    @Test
    fun aircatInvalidManifestStructureRejectedTest() = runBlocking {
        val sourceId = mid("trusted_peer_struct")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_bad_struct",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        // Declares 2 tracks and 2000 bytes, but provides 1 item with 1000 bytes
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_bad_struct",
            sourceDeviceId = sourceId,
            totalTracks = 2,
            totalBytes = 2000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, "")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk()),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertGrantRefused("AIRC_GRANT_MANIFEST", result)
    }

    @Test
    fun aircatPauseAndCancelTransferTest() = runBlocking {
        val transferId = "xfer_cancel_1"
        assertFalse(aircatClient.isTransferCancelled(transferId))
        assertFalse(aircatClient.isTransferPaused(transferId))

        aircatClient.pauseTransfer(transferId)
        assertTrue(aircatClient.isTransferPaused(transferId))

        aircatClient.resumeTransfer(transferId)
        // A17: without an active session, resume is a no-op and stays PAUSED.
        assertTrue(aircatClient.isTransferPaused(transferId))
        assertEquals(AirCatTransferState.PAUSED, aircatClient.progressEvents.value?.state)

        aircatClient.cancelTransfer(transferId)
        assertTrue(aircatClient.isTransferCancelled(transferId))
    }

    @Test
    fun aircatPersistentCheckpointResumeAcrossRecreationTest() = runBlocking {
        val sourceId = mid("trusted_peer_1")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val trackData = "Recoverable audio content".toByteArray()
        val trackSha256 = sha256(trackData)
        val transferKey = "$sourceId:lib_dest:trk_recover_1"
        val completedFile = File(transferManager.musicDir, "${sourceId}_lib_dest_trk_recover_1.mp3")
        completedFile.parentFile?.mkdirs()
        completedFile.writeBytes(trackData)

        val completedCheckpoint = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = transferKey,
            transferId = "xfer_persisted_1",
            remoteDeviceId = sourceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
            trackId = "trk_recover_1",
            remoteTrackId = "rem_1",
            tempFilePath = "${completedFile.absolutePath}.part",
            finalFilePath = completedFile.absolutePath,
            expectedSize = trackData.size.toLong(),
            receivedBytes = trackData.size.toLong(),
            verifiedBytes = trackData.size.toLong(),
            expectedSha256 = trackSha256,
            transferState = org.michimusic.core.models.TransferStatus.COMPLETED,
            createdAt = 1000L,
            updatedAt = 2000L,
        )
        val checkpointRepo = FakeTransferCheckpointRepository(listOf(completedCheckpoint))

        // The library already knows this file: the identity hash and a SYNCED_MANAGED
        // location pointing at the final path. Without them no receipt can be honest.
        (libraryRepo as FakeMusicLibraryRepository).apply {
            seedIdentity(
                TrackIdentity(
                    id = "trk_recover_1",
                    title = "Recovered Song",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 1000L,
                    contentHash = trackSha256,
                ),
            )
            seedLocation(
                TrackLocation(
                    id = "loc_recover_1",
                    trackId = "trk_recover_1",
                    type = TrackLocationType.SYNCED_MANAGED,
                    uri = completedFile.absolutePath,
                    sourceDeviceId = sourceId,
                    remoteTrackId = "rem_1",
                ),
            )
        }

        // Instantiate new client instance simulating application process recreation
        val newAircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
                   signedGrantSecurity = grantSecurity,
        )

        val intent = TransferIntent(
            transferId = "xfer_persisted_1",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_persisted_1",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = trackData.size.toLong(),
            items = listOf(
                AirCatManifestItem("trk_recover_1", "rem_1", "Recovered Song", "Artist", "Album", "mp3", trackData.size.toLong(), trackSha256)
            )
        )

        val pinnedClient = mockk<LinkClient>(relaxed = true)
        every { pinnedClient.deviceToken } returns "token"
        // Reusing a confirmed file must not stream a single byte.
        var streams = 0
        coEvery {
            pinnedClient.streamTrackRange(any(), any(), any(), any(), any(), any())
        } answers {
            streams++
            Result.failure(java.io.IOException("a confirmed file must not be downloaded again"))
        }
        val result = newAircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(pinnedClient),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(
            "Should succeed by resuming from checkpoint without re-downloading, but was ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        val files = result.getOrThrow()
        assertEquals(1, files.size)
        assertEquals(completedFile.absolutePath, files.first().absolutePath)
        assertEquals(AirCatTransferState.COMPLETED, newAircatClient.progressEvents.value?.state)
        assertEquals("the confirmed file must be reused, not downloaded", 0, streams)
        assertEquals(completedCheckpoint.expectedSha256, checkpointRepo.row(transferKey)?.expectedSha256)
    }

    @Test
    fun aircatRecoverIncompleteTransfersResumesPendingCheckpoints() = runBlocking {
        val sourceId = mid("trusted_peer_resume")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val trackData = "Incomplete recovered audio content".toByteArray()
        val trackSha256 = sha256(trackData)
        val transferKey = "$sourceId:lib_dest:trk_pending_resume"
        val targetFile = File(transferManager.musicDir, "${sourceId}_lib_dest_trk_pending_resume.mp3")
        targetFile.parentFile?.mkdirs()

        val partFile = File("${targetFile.absolutePath}.part")
        val halfSize = trackData.size / 2
        partFile.writeBytes(trackData.copyOfRange(0, halfSize))

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        val pendingCp = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = transferKey,
            transferId = "xfer_incomplete_1",
            remoteDeviceId = sourceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
            trackId = "trk_pending_resume",
            remoteTrackId = "rem_pending_1",
            tempFilePath = partFile.absolutePath,
            finalFilePath = targetFile.absolutePath,
            expectedSize = trackData.size.toLong(),
            receivedBytes = halfSize.toLong(),
            verifiedBytes = halfSize.toLong(),
            expectedSha256 = trackSha256,
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 1000L,
            updatedAt = 2000L,
        )

        coEvery { checkpointRepo.getCheckpointsForDevice(sourceId) } returns listOf(pendingCp)
        stubDurableCompletion(checkpointRepo, pendingCp)

        // Mock linkClient streaming remaining part
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        pinnedLink(mockClient)
        every { mockClient.deviceToken } returns "token"
        io.mockk.every {
            kotlinx.coroutines.runBlocking {
                mockClient.streamTrackRange(
                    trackId = any(),
                    outputStream = any(),
                    startBytes = any(),
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val out = secondArg<java.io.OutputStream>()
            val offset = thirdArg<Long>()
            val remaining = trackData.copyOfRange(offset.toInt(), trackData.size)
            out.write(remaining)
            Result.success(
                org.michimusic.link.StreamRangeResult(
                    bytesRead = remaining.size.toLong(),
                    statusCode = 206,
                    isPartialContent = true,
                    etag = "etag-resume",
                    contentLength = remaining.size.toLong(),
                )
            )
        }

        val client = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
                   signedGrantSecurity = grantSecurity,
        )

        val (authorizationIntent, authorizationManifest) = recoveryAuthority(
            deviceId = sourceId,
            transferId = "xfer_incomplete_1",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
            items = listOf(
                item("trk_pending_resume", "rem_pending_1", trackData.size.toLong(), trackSha256),
            ),
        )
        val recoveryResult = client.recoverIncompleteTransfers(
            remoteDeviceId = sourceId,
            linkClient = pinnedLink(mockClient),
            authorizationIntent = authorizationIntent,
            authorizationManifest = authorizationManifest,
        )
        assertTrue("Expected recovery to succeed, but was: ${recoveryResult.exceptionOrNull()}",
            recoveryResult.isSuccess)
        val files = recoveryResult.getOrThrow()
        assertEquals(1, files.size)
        assertEquals(targetFile.absolutePath, files.first().absolutePath)
        assertTrue(targetFile.exists())
        assertEquals(trackData.size.toLong(), targetFile.length())
    }

    @Test
    fun aircatRecoverIncompleteTransfersFailsWhenPeerIsRevoked() = runBlocking {
        val revokedDeviceId = mid("revoked_node_123")
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        pinnedLink(mockClient)

        val revokedDevice = PairedDevice(
            deviceId = revokedDeviceId,
            deviceName = "Revoked Node",
            deviceToken = "token_revoked",
            lastUrl = "http://192.168.1.55:8400",
            isRevoked = true,
        )
        every { pairedRegistry.getDevice(revokedDeviceId) } returns revokedDevice

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        val client = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
                   signedGrantSecurity = grantSecurity,
        )

        val result = client.recoverIncompleteTransfers(revokedDeviceId, pinnedLink(mockClient))
        assertTrue(result.isFailure)
        assertTrue("expected AirCatException.UnauthorizedPeer but was ${result.exceptionOrNull()}", result.exceptionOrNull() is AirCatException.UnauthorizedPeer)
    }

    @Test
    fun aircatRecoverIncompleteTransfersFailsWhenTokenMismatches() = runBlocking {
        val deviceId = mid("mismatch_node_123")
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        pinnedLink(mockClient)
        every { mockClient.deviceToken } returns "token_spoofed_or_stale"

        val device = PairedDevice(
            deviceId = deviceId,
            deviceName = "Legit Node",
            deviceToken = "token_legit_secret",
            lastUrl = "http://192.168.1.55:8400",
            isRevoked = false,
        )
        every { pairedRegistry.getDevice(deviceId) } returns device

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        val client = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
                   signedGrantSecurity = grantSecurity,
        )

        val result = client.recoverIncompleteTransfers(deviceId, pinnedLink(mockClient))
        assertTrue(result.isFailure)
        assertTrue("expected AirCatException.UnauthorizedPeer but was ${result.exceptionOrNull()}", result.exceptionOrNull() is AirCatException.UnauthorizedPeer)
    }

    @Test
    fun aircatRecoverIncompleteTransfersAbortsWhenPeerRevokedMidTransfer() = runBlocking {
        val deviceId = mid("dynamic_revocation_node")
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        pinnedLink(mockClient)
        every { mockClient.deviceToken } returns "valid_token"

        var isRevokedFlag = false
        every { pairedRegistry.getDevice(any<String>()) } answers {
            PairedDevice(
                deviceId = deviceId,
                deviceName = "Dynamic Node",
                deviceToken = "valid_token",
                lastUrl = "http://192.168.1.55:8400",
                isRevoked = isRevokedFlag,
            )
        }

        val cp1 = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib:t1",
            transferId = "xfer1",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib",
            trackId = "t1",
            remoteTrackId = "t1",
            tempFilePath = File(musicRoot, "t1.part").absolutePath,
            finalFilePath = File(musicRoot, "t1.mp3").absolutePath,
            expectedSize = 100L,
            receivedBytes = 50L,
            verifiedBytes = 0L,
            expectedSha256 = sha256(ByteArray(100)),
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
        )
        val cp2 = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib:t2",
            transferId = "xfer1",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib",
            trackId = "t2",
            remoteTrackId = "t2",
            tempFilePath = File(musicRoot, "t2.part").absolutePath,
            finalFilePath = File(musicRoot, "t2.mp3").absolutePath,
            expectedSize = 100L,
            receivedBytes = 50L,
            verifiedBytes = 0L,
            expectedSha256 = sha256(ByteArray(100)),
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
        )

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cp1, cp2)
        stubDurableCompletion(checkpointRepo, cp1)

        val mockTransferManager = mockk<LinkTransferManager>(relaxed = true)
        // A33/A67: a relaxed mock would hand the preflight a mocked File, and the
        // managed root must be a real directory before a repair batch may write.
        every { mockTransferManager.musicDir } returns File(tempDir, "synced_music").also { it.mkdirs() }
        coEvery { mockTransferManager.downloadTrack(any(), any(), any(), any()) } answers {
            isRevokedFlag = true // Revoked after first accepted download, before the next item.
            Result.success(File(musicRoot, "t1.mp3").apply { writeBytes(ByteArray(100)) })
        }
        every { mockTransferManager.finalizeAfterCommit(any(), any(), any(), any(), any()) } returns true

        val client = DefaultAirCatTransferClient(
            transferManager = mockTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
                   signedGrantSecurity = grantSecurity,
        )

        // The batch is authorised before any byte moves; revocation then has to stop
        // the second download on its own.
        val (authorizationIntent, authorizationManifest) = recoveryAuthority(
            deviceId = deviceId,
            transferId = "xfer1",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib",
            items = listOf(
                item("t1", "t1", 100L, sha256(ByteArray(100))),
                item("t2", "t2", 100L, sha256(ByteArray(100))),
            ),
        )

        val result = client.recoverIncompleteTransfers(
            remoteDeviceId = deviceId,
            linkClient = pinnedLink(mockClient),
            authorizationIntent = authorizationIntent,
            authorizationManifest = authorizationManifest,
        )
        assertTrue(result.isFailure)
        // A67 gives revocation its own exception: a peer revoked mid-transfer is not
        // merely unauthorised, and reporting it as such hid the reason from the UI.
        assertTrue("expected AirCatException.RevokedPeer but was ${result.exceptionOrNull()}",
            result.exceptionOrNull() is AirCatException.RevokedPeer)
        // Only 1 download track call was made before revocation aborted recovery
        coVerify(exactly = 1) { mockTransferManager.downloadTrack(any(), any(), any(), any()) }
    }

    @Test
    fun aircatExecuteTransferFailsWhenLibraryRegistrationFails() = runBlocking {
        val transferId = "aircat_fail_lib_reg"
        val sourceId = mid("test_source_device_1")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val trackData = "TestDataForFailedLibReg".toByteArray()
        val expectedSha = sha256(trackData)
        val peer = FakeAirCatPeer(sourceId)
        every { peer.mockClient.deviceToken } returns "token"
        peer.setupTrackStreaming("track_fail_reg", trackData)

        // A library that accepts the call and then fails, so the test exercises the
        // production compensation path rather than a mock answering "no".
        val failingLibraryRepo = FakeMusicLibraryRepository(
            registrationFailure = IllegalStateException("Database constraint violation"),
        )

        val checkpointMap = mutableMapOf<String, org.michimusic.core.models.PersistentCheckpoint>()
        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.saveCheckpoint(any()) } answers {
            val cp = firstArg<org.michimusic.core.models.PersistentCheckpoint>()
            checkpointMap[cp.transferKey] = cp
        }
        coEvery { checkpointRepo.updateProgress(any(), any(), any(), any()) } answers {
            val key = firstArg<String>()
            val rec = secondArg<Long>()
            val ver = thirdArg<Long>()
            val state = arg<org.michimusic.core.models.TransferStatus>(3)
            val old = checkpointMap[key]
            if (old != null) {
                checkpointMap[key] = old.copy(receivedBytes = rec, verifiedBytes = ver, transferState = state)
            } else {
                checkpointMap[key] = org.michimusic.core.models.PersistentCheckpoint(
                    transferKey = key,
                    transferId = "xfer",
                    remoteDeviceId = sourceId,
                    sourceLibraryId = "lib_src",
                    destinationLibraryId = "lib_dest",
                    trackId = "track_fail_reg",
                    remoteTrackId = "track_fail_reg",
                    tempFilePath = "",
                    finalFilePath = "",
                    expectedSize = 100L,
                    receivedBytes = rec,
                    verifiedBytes = ver,
                    expectedSha256 = "",
                    transferState = state,
                )
            }
        }
        coEvery { checkpointRepo.getCheckpoint(any()) } answers {
            val queryKey = firstArg<String>()
            checkpointMap[queryKey] ?: checkpointMap.entries.firstOrNull { it.key.startsWith("$queryKey:") }?.value
        }

        val testTransferManager = LinkTransferManager(mockContext, checkpointRepo)
        val client = DefaultAirCatTransferClient(
            transferManager = testTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = failingLibraryRepo,
            checkpointRepository = checkpointRepo,
                   signedGrantSecurity = grantSecurity,
        )

        val manifestItem = AirCatManifestItem(
            logicalTrackId = "track_fail_reg",
            remoteTrackId = "track_fail_reg",
            title = "Failed Registration Track",
            artist = "Artist",
            album = "Album",
            format = "mp3",
            size = trackData.size.toLong(),
            sha256 = expectedSha,
        )

        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = transferId,
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = trackData.size.toLong(),
            items = listOf(manifestItem),
        )

        val intent = TransferIntent(
            transferId = transferId,
            sourceMichiId = sourceId,
            destinationMichiId = mid("dest_client"),
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )

        // The transfer has to clear Home authorisation and the TLS pin before the
        // library write is even attempted; failing earlier would prove nothing.
        val authorizedIntent = authorized(intent, manifest)
        val result = client.executeTransfer(
            authorizedIntent,
            manifest,
            pinnedLink(peer.mockClient),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )
        assertTrue("executeTransfer should fail if library registration fails", result.isFailure)

        // Checkpoint must NOT be marked COMPLETED
        val checkpoint = checkpointRepo.getCheckpoint("$sourceId:lib_dest:track_fail_reg")
        assertEquals(org.michimusic.core.models.TransferStatus.FAILED, checkpoint?.transferState)
    }

    @Test
    fun aircatRecoverIncompleteTransfers_strictCompositeCriteria_matchesDestinationLibraryAndHash() = runBlocking {
        val deviceId = mid("node_composite_strict")
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        pinnedLink(mockClient)
        every { mockClient.deviceToken } returns "token_strict"
        every { pairedRegistry.getDevice(deviceId) } returns PairedDevice(
            deviceId = deviceId,
            deviceName = "Strict Node",
            deviceToken = "token_strict",
            lastUrl = "http://192.168.1.10:8400",
        )

        val cp1 = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_target_1:track_strict_1",
            transferId = "xfer_1",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_target_1",
            trackId = "track_strict_1",
            remoteTrackId = "track_strict_1",
            tempFilePath = File(musicRoot, "temp1.part").absolutePath,
            finalFilePath = File(musicRoot, "final1.mp3").absolutePath,
            expectedSize = 5L,
            receivedBytes = 0L,
            verifiedBytes = 0L,
            expectedSha256 = sha256("hello".toByteArray()),
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val cp2 = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_target_1:track_strict_2",
            transferId = "xfer_1",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_target_1",
            trackId = "track_strict_2",
            remoteTrackId = "track_strict_2",
            tempFilePath = File(musicRoot, "temp2.part").absolutePath,
            finalFilePath = File(musicRoot, "final2.mp3").absolutePath,
            expectedSize = 2000L,
            receivedBytes = 1000L,
            verifiedBytes = 0L,
            expectedSha256 = sha256(ByteArray(2000) { it.toByte() }),
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cp1, cp2)
        stubDurableCompletion(checkpointRepo, cp1)

        val mockTransferManager = mockk<LinkTransferManager>(relaxed = true)
        // A33/A67: a relaxed mock would hand the preflight a mocked File, and the
        // managed root must be a real directory before a repair batch may write.
        every { mockTransferManager.musicDir } returns File(tempDir, "synced_music").also { it.mkdirs() }
        val dummyFile = File(musicRoot, "final1.mp3").also { it.writeText("hello") }
        coEvery { mockTransferManager.downloadTrack(any(), any(), any(), any()) } returns Result.success(dummyFile)
        every { mockTransferManager.finalizeAfterCommit(any(), any(), any(), any(), any()) } returns true

        val client = DefaultAirCatTransferClient(
            transferManager = mockTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
                   signedGrantSecurity = grantSecurity,
        )

        // Only authorize track_strict_1 destined for lib_target_1
        val criteria = listOf(
            AuthorizedTransferCriteria(
                trackId = "track_strict_1",
                destinationLibraryId = "lib_target_1",
                expectedSize = 5L,
                expectedSha256 = sha256("hello".toByteArray()),
            ),
            AuthorizedTransferCriteria(
                trackId = "track_strict_2",
                destinationLibraryId = "lib_correct_dest_not_wrong", // does not match cp2
                expectedSize = 2000L,
                expectedSha256 = sha256(ByteArray(2000) { it.toByte() }),
            )
        )

        // Room stores bytes and hashes; it cannot authorise the transfer that produced
        // them. Recovery over the network needs a Home grant that covers this batch.
        val (authorizationIntent, authorizationManifest) = recoveryAuthority(
            deviceId = deviceId,
            transferId = "xfer_1",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_target_1",
            items = listOf(
                item("track_strict_1", "track_strict_1", 5L, sha256("hello".toByteArray())),
                item("track_strict_2", "track_strict_2", 2000L, sha256(ByteArray(2000) { it.toByte() })),
            ),
        )

        val result = client.recoverIncompleteTransfers(
            remoteDeviceId = deviceId,
            linkClient = pinnedLink(mockClient),
            authorizedCriteria = criteria,
            authorizationIntent = authorizationIntent,
            authorizationManifest = authorizationManifest,
        )

        assertTrue("result must succeed but was ${result.exceptionOrNull()}", result.isSuccess)
        val files = result.getOrThrow()
        assertEquals(1, files.size)
        // Exactly one download was performed (for track_strict_1), cp2 was rejected
        coVerify(exactly = 1) { mockTransferManager.downloadTrack(any(), any(), any(), any()) }
    }

    @Test
    fun aircatRecoverIncompleteTransfers_strictHashMatching_rejectsEmptyOrMismatchingHashWhenHashIsExpected() = runBlocking {
        val deviceId = mid("node_hash_matching_strict")
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        pinnedLink(mockClient)
        every { mockClient.deviceToken } returns "token_strict"
        every { pairedRegistry.getDevice(deviceId) } returns PairedDevice(
            deviceId = deviceId,
            deviceName = "Hash Node",
            deviceToken = "token_strict",
            lastUrl = "http://192.168.1.10:8400",
        )

        // cpEmpty: Checkpoint has empty hash, but criteria expects a specific hash -> MUST BE REJECTED
        val cpEmpty = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_1:track_empty_hash",
            transferId = "xfer_empty",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_1",
            trackId = "track_empty_hash",
            remoteTrackId = "track_empty_hash",
            tempFilePath = File(musicRoot, "temp_empty.part").absolutePath,
            finalFilePath = File(musicRoot, "final_empty.mp3").absolutePath,
            expectedSize = 1000L,
            receivedBytes = 500L,
            verifiedBytes = 0L,
            expectedSha256 = "", // Empty hash in checkpoint!
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        // cpMismatch: Checkpoint has mismatched hash -> MUST BE REJECTED
        val cpMismatch = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_1:track_mismatch_hash",
            transferId = "xfer_mismatch",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_1",
            trackId = "track_mismatch_hash",
            remoteTrackId = "track_mismatch_hash",
            tempFilePath = File(musicRoot, "temp_mismatch.part").absolutePath,
            finalFilePath = File(musicRoot, "final_mismatch.mp3").absolutePath,
            expectedSize = 1000L,
            receivedBytes = 500L,
            verifiedBytes = 0L,
            expectedSha256 = sha256(ByteArray(1000) { 9 }), // a real hash, just not the authorised one
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        // cpValid: Checkpoint has matching hash -> MUST BE ACCEPTED
        val cpValid = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_1:track_valid_hash",
            transferId = "xfer_valid",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_1",
            trackId = "track_valid_hash",
            remoteTrackId = "track_valid_hash",
            tempFilePath = File(musicRoot, "temp_valid.part").absolutePath,
            finalFilePath = File(musicRoot, "final_valid.mp3").absolutePath,
            expectedSize = "hello valid".length.toLong(),
            receivedBytes = 0L,
            verifiedBytes = 0L,
            expectedSha256 = sha256("hello valid".toByteArray()),
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cpEmpty, cpMismatch, cpValid)
        stubDurableCompletion(checkpointRepo, cpValid)

        val mockTransferManager = mockk<LinkTransferManager>(relaxed = true)
        // A33/A67: a relaxed mock would hand the preflight a mocked File, and the
        // managed root must be a real directory before a repair batch may write.
        every { mockTransferManager.musicDir } returns File(tempDir, "synced_music").also { it.mkdirs() }
        val dummyFile = File(musicRoot, "final_valid.mp3").also { it.writeText("hello valid") }
        coEvery { mockTransferManager.downloadTrack(any(), any(), any(), any()) } returns Result.success(dummyFile)
        every { mockTransferManager.finalizeAfterCommit(any(), any(), any(), any(), any()) } returns true

        val client = DefaultAirCatTransferClient(
            transferManager = mockTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
                   signedGrantSecurity = grantSecurity,
        )

        // Phase A: a checkpoint whose hash is empty cannot appear in a canonical manifest,
        // so no Home grant can authorise this batch. The refusal has to cover the whole
        // batch: nothing may be downloaded on the strength of an unauthenticatable row.
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cpEmpty, cpValid)
        val (emptyIntent, emptyManifest) = recoveryAuthority(
            deviceId = deviceId,
            transferId = "xfer_valid",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_1",
            items = listOf(
                item("track_valid_hash", "track_valid_hash", "hello valid".length.toLong(),
                    sha256("hello valid".toByteArray())),
            ),
        )
        val refused = client.recoverIncompleteTransfers(
            remoteDeviceId = deviceId,
            linkClient = pinnedLink(mockClient),
            authorizationIntent = emptyIntent,
            authorizationManifest = emptyManifest,
        )
        // The empty hash is refused by the checkpoint preflight before the signed
        // manifest is even consulted; either refusal is fail-closed, and what matters
        // is that no unauthenticatable row can authorise a download.
        val refusal = refused.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "an unauthenticatable checkpoint must refuse the batch, but was $refusal",
            refusal.contains("AIRC_RECOVERY_INVALID_CHECKPOINT") ||
                refusal.contains("AIRC_RECOVERY_NOT_IN_SIGNED_MANIFEST"),
        )
        coVerify(exactly = 0) { mockTransferManager.downloadTrack(any(), any(), any(), any()) }

        // Phase B: both checkpoints are authenticatable, so the grant covers them and the
        // per-track criteria decide. The mismatching hash never reaches the wire.
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cpMismatch, cpValid)
        val (batchIntent, batchManifest) = recoveryAuthority(
            deviceId = deviceId,
            transferId = "xfer_valid",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_1",
            items = listOf(
                item("track_mismatch_hash", "track_mismatch_hash", 1000L, sha256(ByteArray(1000) { 9 })),
                item("track_valid_hash", "track_valid_hash", "hello valid".length.toLong(),
                    sha256("hello valid".toByteArray())),
            ),
        )
        val criteria = listOf(
            AuthorizedTransferCriteria(
                trackId = "track_mismatch_hash",
                destinationLibraryId = "lib_1",
                expectedSize = 1000L,
                expectedSha256 = sha256(ByteArray(1000) { 7 }),
            ),
            AuthorizedTransferCriteria(
                trackId = "track_valid_hash",
                destinationLibraryId = "lib_1",
                expectedSize = "hello valid".length.toLong(),
                expectedSha256 = sha256("hello valid".toByteArray()).uppercase(), // Case insensitive match
            ),
        )

        val result = client.recoverIncompleteTransfers(
            remoteDeviceId = deviceId,
            linkClient = pinnedLink(mockClient),
            authorizedCriteria = criteria,
            authorizationIntent = batchIntent,
            authorizationManifest = batchManifest,
        )

        assertTrue("result must succeed but was ${result.exceptionOrNull()}", result.isSuccess)
        val files = result.getOrThrow()
        assertEquals("Only cpValid should be recovered; the mismatching hash must be rejected", 1, files.size)
        coVerify(exactly = 1) { mockTransferManager.downloadTrack(any(), any(), any(), any()) }
    }

    @Test
    fun recoverIncompleteTransfers_rejectsCheckpoint_whenPlanIdDoesNotMatch() = runBlocking {
        val deviceId = mid("dev_plan_isolation")
        every { pairedRegistry.getDevice(deviceId) } returns PairedDevice(deviceId = deviceId, deviceName = "Node", deviceToken = "tok")

        val cpPlanA = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_1:track_iso:plan_A",
            transferId = "plan_A",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_1",
            destinationLibraryId = "lib_1",
            trackId = "track_iso",
            remoteTrackId = "track_iso",
            tempFilePath = File(tempDir, "plan_A.part").absolutePath,
            finalFilePath = File(tempDir, "plan_A.mp3").absolutePath,
            expectedSize = 1000L,
            receivedBytes = 500L,
            verifiedBytes = 500L,
            expectedSha256 = "hash123",
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cpPlanA)

        val mockTransferManager = mockk<LinkTransferManager>(relaxed = true)
        // A33/A67: a relaxed mock would hand the preflight a mocked File, and the
        // managed root must be a real directory before a repair batch may write.
        every { mockTransferManager.musicDir } returns File(tempDir, "synced_music").also { it.mkdirs() }
        val client = DefaultAirCatTransferClient(
            transferManager = mockTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
                   signedGrantSecurity = grantSecurity,
        )

        // Authorized criteria specifies plan_B
        val criteria = listOf(
            AuthorizedTransferCriteria(
                trackId = "track_iso",
                destinationLibraryId = "lib_1",
                expectedSize = 1000L,
                expectedSha256 = "hash123",
                planId = "plan_B",
            )
        )

        val mockClient = mockk<LinkClient>(relaxed = true)
        every { mockClient.deviceToken } returns "tok"

        val result = client.recoverIncompleteTransfers(
            remoteDeviceId = deviceId,
            linkClient = pinnedLink(mockClient),
            authorizedCriteria = criteria,
        )

        assertTrue("result must succeed but was ${result.exceptionOrNull()}", result.isSuccess)
        val files = result.getOrThrow()
        assertEquals("Checkpoints from plan_A must NOT be recovered under plan_B criteria", 0, files.size)
        coVerify(exactly = 0) { mockTransferManager.downloadTrack(any(), any(), any(), any()) }
    }

    @Test
    fun executeTransfer_failsClosed_whenExistingIdentityHasConflictingContentHash() = runBlocking {
        val sourceId = mid("peer_conflict")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val hashOld = "0".repeat(64)
        val hashNew = "1".repeat(64)

        // Seed library repository with an existing identity for trk_conflict with hashOld
        (libraryRepo as FakeMusicLibraryRepository).seedIdentity(
            org.michimusic.core.models.TrackIdentity(
                id = "trk_conflict",
                title = "Existing Track",
                artist = "Artist",
                album = "Album",
                durationMs = 1000L,
                contentHash = hashOld,
            )
        )

        val intent = TransferIntent(
            transferId = "xfer_conflict",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_conflict",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_conflict", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, hashNew)
            )
        )

        val authorizedClient = mockk<LinkClient>(relaxed = true)
        every { authorizedClient.deviceToken } returns "tok"
        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(authorizedClient),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Conflicting content hash must fail execution", result.isFailure)
        assertTrue("expected AirCatException.CorruptedPayload but was ${result.exceptionOrNull()}", result.exceptionOrNull() is AirCatException.CorruptedPayload)
    }

    @Test
    fun aircatTransfer_failsClosed_whenLibraryRepositoryIsNull() = runBlocking {
        val clientWithoutRepo = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = null,
            checkpointRepository = mockk(relaxed = true),
                   signedGrantSecurity = grantSecurity,
        )

        val intent = TransferIntent(
            transferId = "xfer_no_repo",
            sourceMichiId = mid("dev_src"),
            destinationMichiId = mid("client_me"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_no_repo",
            sourceDeviceId = mid("dev_src"),
            totalTracks = 0,
            totalBytes = 0L,
            items = emptyList(),
        )

        val execResult = clientWithoutRepo.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )
        assertTrue("executeTransfer must fail when libraryRepository is null", execResult.isFailure)
        assertTrue(execResult.exceptionOrNull() is AirCatException.ProtocolError)

        val recoverResult = clientWithoutRepo.recoverIncompleteTransfers(
            remoteDeviceId = "dev_src",
            linkClient = pinnedLink(mockk(relaxed = true)),
        )
        assertTrue("recoverIncompleteTransfers must fail when libraryRepository is null", recoverResult.isFailure)
        assertTrue(recoverResult.exceptionOrNull() is AirCatException.ProtocolError)
    }

    @Test
    fun aircatTransfer_nonDestructivePolicy_neverDeletesExistingRegisteredTrackOnConflict() = runBlocking {
        val sourceId = mid("node_registered_conflict")
        val existingFile = File(tempDir, "existing_valid_song.flac")
        existingFile.writeBytes("Pre-existing high quality audio".toByteArray())
        assertTrue(existingFile.exists())

        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(
            deviceId = sourceId,
            deviceName = "Conflict Node",
            deviceToken = "token_ok",
            lastUrl = "http://192.168.1.100:8400",
        )

        val hashOriginal = "a".repeat(64)
        val hashIncoming = "b".repeat(64)

        (libraryRepo as FakeMusicLibraryRepository).apply {
            seedIdentity(
                TrackIdentity(
                    id = "trk_registered",
                    title = "Existing Song",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 120000L,
                    contentHash = hashOriginal,
                )
            )
            // The existing file is already a registered managed location.
            seedLocation(
                TrackLocation(
                    id = "loc_local_1",
                    trackId = "trk_registered",
                    type = TrackLocationType.SYNCED_MANAGED,
                    uri = existingFile.absolutePath,
                    availability = LocationAvailability.AVAILABLE,
                )
            )
        }

        val intent = TransferIntent(
            transferId = "xfer_reg_conflict",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_me"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_reg_conflict",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_registered", "rem_1", "Song", "Artist", "Album", "flac", 1000L, hashIncoming)
            )
        )

        val authorizedClient = mockk<LinkClient>(relaxed = true)
        every { authorizedClient.deviceToken } returns "token_ok"
        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(authorizedClient),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertTrue("expected AirCatException.CorruptedPayload but was ${result.exceptionOrNull()}", result.exceptionOrNull() is AirCatException.CorruptedPayload)
        // Decisive check: existing valid local file MUST NOT be deleted
        assertTrue("Registered local file must remain intact and non-deleted", existingFile.exists())
    }

    @Test
    fun manifestTrackCountMismatch_itemsEmptyButTotalTracksPositive_rejectedWithInvalidManifestStructure() = runBlocking {
        val sourceId = mid("peer_mismatch")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_mismatch_1",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_mismatch_1",
            sourceDeviceId = sourceId,
            totalTracks = 5,
            totalBytes = 5000L,
            items = emptyList() // 0 items vs 5 declared tracks!
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertGrantRefused("AIRC_GRANT_MANIFEST", result)
    }

    @Test
    fun manifestLegitimateZeroTracks_finishesAsNoFilesWithoutClaimingVerified() = runBlocking {
        val sourceId = mid("peer_empty")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_empty_ok",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_empty_ok",
            sourceDeviceId = sourceId,
            totalTracks = 0,
            totalBytes = 0L,
            items = emptyList()
        )

        val updates = mutableListOf<AirCatProgressEvent>()
        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
            onProgress = { updates.add(it) }
        )

        assertTrue("Empty manifest transfer should succeed legitimately", result.isSuccess)
        val finalStatus = updates.lastOrNull()
        assertNotNull("Should emit progress", finalStatus)
        // A68-06: an empty catalogue is legitimate and finishes, but it is not a completed
        // file transfer: nothing was transferred, so it may not borrow COMPLETED.
        assertEquals(AirCatTransferState.NO_FILES, finalStatus!!.state)
        assertFalse("0 tracks must not claim checksumVerified = true", finalStatus.checksumVerified)
        assertFalse("0 tracks must not claim libraryCommitted = true", finalStatus.libraryCommitted)
    }

    @Test
    fun manifestTrackWithMalformedSha256_rejectedWithInvalidManifestStructure() = runBlocking {
        val sourceId = mid("peer_bad_sha")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_bad_sha",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_bad_sha",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 100L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Title", "Artist", "Album", "mp3", 100L, "NOT_A_VALID_SHA256_HASH_STRING")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertGrantRefused("AIRC_GRANT_MANIFEST", result)
    }

    @Test
    fun manifestWithDuplicateTrackIds_rejectedWithInvalidManifestStructure() = runBlocking {
        val sourceId = mid("peer_dup")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)

        val intent = TransferIntent(
            transferId = "xfer_dup",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_dup",
            sourceDeviceId = sourceId,
            totalTracks = 2,
            totalBytes = 200L,
            items = listOf(
                AirCatManifestItem("trk_dup", "rem_1", "Title 1", "Artist", "Album", "mp3", 100L, hashA),
                AirCatManifestItem("trk_dup", "rem_2", "Title 2", "Artist", "Album", "mp3", 100L, hashB)
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertGrantRefused("AIRC_GRANT_MANIFEST", result)
    }

    @Test
    fun transferWithEmptySha256OnItem_rejectedBeforeStreaming() = runBlocking {
        val sourceId = mid("peer_empty_sha")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val content = "Unhashed audio bytes".toByteArray()
        val peer = FakeAirCatPeer(sourceId)
        every { peer.mockClient.deviceToken } returns "tok"
        peer.setupTrackStreaming("rem_1", content)

        val intent = TransferIntent(
            transferId = "xfer_no_sha",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_no_sha",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = content.size.toLong(),
            items = listOf(
                AirCatManifestItem("trk_no_sha", "rem_1", "Title", "Artist", "Album", "mp3", content.size.toLong(), "")
            )
        )

        val updates = mutableListOf<AirCatProgressEvent>()
        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(peer.mockClient),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
            onProgress = { updates.add(it) }
        )

        assertTrue("Managed AirCat without SHA-256 must fail before I/O", result.isFailure)
        assertGrantRefused("AIRC_GRANT_MANIFEST", result)
        assertEquals(AirCatTransferState.FAILED, updates.lastOrNull()?.state)
        assertTrue("No managed file can be committed unverified", checkpointRows.isEmpty())
    }

    @Test
    fun manifestWithOverflowRequiredBytes_rejectedWithInvalidManifestStructure() = runBlocking {
        val sourceId = mid("peer_overflow")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val hugeBytes = Long.MAX_VALUE - 1000L
        val intent = TransferIntent(
            transferId = "xfer_overflow",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_overflow",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = hugeBytes,
            items = listOf(
                AirCatManifestItem("trk_huge", "rem_huge", "Title", "Artist", "Album", "mp3", hugeBytes, "a".repeat(64))
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = Long.MAX_VALUE,
        )

        assertTrue("Manifest requiring overflow bytes must fail", result.isFailure)
        assertTrue("expected AirCatException.InvalidManifestStructure but was ${result.exceptionOrNull()}", result.exceptionOrNull() is AirCatException.InvalidManifestStructure)
    }

    @Test
    fun manifestWithZeroTotalBytesAndNonEmptyItems_rejectedWithInvalidManifestStructure() = runBlocking {
        val sourceId = mid("peer_zero_total")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_zero_total",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_zero_total",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 0L, // Claims 0 bytes despite having a 500-byte track!
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Title", "Artist", "Album", "mp3", 500L, "a".repeat(64))
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertGrantRefused("AIRC_GRANT_MANIFEST", result)
    }

    @Test
    fun manifestEmptyItemsWithNonZeroTotalBytes_rejectedWithInvalidManifestStructure() = runBlocking {
        val sourceId = mid("peer_empty_nonzero")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_empty_nonzero",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_empty_nonzero",
            sourceDeviceId = sourceId,
            totalTracks = 0,
            totalBytes = 500L, // Claims 500 bytes with 0 items!
            items = emptyList()
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertGrantRefused("AIRC_GRANT_MANIFEST", result)
    }

    @Test
    fun manifestWithNegativeTrackSize_rejectedWithInvalidManifestStructure() = runBlocking {
        val sourceId = mid("peer_neg_size")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_neg_size",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_neg_size",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = -100L,
            items = listOf(
                AirCatManifestItem("trk_neg", "rem_neg", "Title", "Artist", "Album", "mp3", -100L, "a".repeat(64))
            )
        )

        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertGrantRefused("AIRC_GRANT_MANIFEST", result)
    }

    @Test
    fun manifestWithZeroTracksAndZeroBytes_succeedsEvenWithLowStorage() = runBlocking {
        val sourceId = mid("peer_empty_zero")
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_empty_zero",
            sourceMichiId = sourceId,
            destinationMichiId = mid("client_id"),
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_empty_zero",
            sourceDeviceId = sourceId,
            totalTracks = 0,
            totalBytes = 0L,
            items = emptyList()
        )

        // Only 10 KB free storage (far less than the 100 MB safetyMargin!)
        val result = aircatClient.executeTransfer(
            intent = authorized(intent, manifest),
            manifest = manifest,
            linkClient = pinnedLink(mockk(relaxed = true)),
            availableStorageBytes = 10L * 1024,
        )

        assertTrue("Legitimate 0 tracks / 0 bytes transfer must succeed without demanding 100MB margin (K49B-06)", result.isSuccess)
        assertEquals(0, result.getOrThrow().size)
    }
}


