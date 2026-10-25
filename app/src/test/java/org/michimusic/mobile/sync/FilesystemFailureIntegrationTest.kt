package org.michimusic.mobile.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.michimusic.core.models.ApprovedSyncPlanRepository
import org.michimusic.core.models.SyncItemAction
import org.michimusic.core.models.SyncItemDecision
import org.michimusic.core.models.TransferCheckpointRepository
import org.michimusic.core.models.TransferFileIdentity
import org.michimusic.core.models.TransferLifecycleState
import org.michimusic.data.cache.ApprovedSyncPlanEntity
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.repository.DefaultMusicLibraryRepository
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.data.repository.RoomApprovedSyncPlanRepository
import org.michimusic.data.repository.RoomTransferCheckpointRepository
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.StreamRangeResult
import org.michimusic.link.dto.ManifestTrackDto
import org.michimusic.link.dto.SyncManifestDto
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class FilesystemFailureIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: MichiDatabase
    private lateinit var mockPairedRegistry: PairedDeviceRegistry
    private lateinit var mockClient: LinkClient
    private val json = Json { ignoreUnknownKeys = true }

    private val testDeviceId = "server_fs_failure_test"
    private val testDeviceToken = "token_fs_failure_12345"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        mockPairedRegistry = mockk(relaxed = true)
        mockClient = mockk(relaxed = true)

        val fakePairedDevice = PairedDevice(
            deviceId = testDeviceId,
            deviceName = "Production FS Test Server",
            lastUrl = "http://127.0.0.1:8400",
            deviceToken = testDeviceToken,
            isRevoked = false,
        )
        io.mockk.every { mockPairedRegistry.getDevice(testDeviceId) } returns fakePairedDevice
        io.mockk.every { mockPairedRegistry.getAllDevices() } returns listOf(fakePairedDevice)

        stopKoin()
        startKoin {
            modules(
                module {
                    single<MichiDatabase> { db }
                    single<TrackDao> { db.trackDao() }
                    single<PlaylistDao> { db.playlistDao() }
                    single<MusicLibraryRepository> {
                        DefaultMusicLibraryRepository(
                            libraryDao = db.libraryDao(),
                            identityDao = db.trackIdentityDao(),
                            locationDao = db.trackLocationDao(),
                            membershipDao = db.libraryMembershipDao(),
                        )
                    }
                    single<TransferCheckpointRepository> {
                        RoomTransferCheckpointRepository(db.transferCheckpointDao())
                    }
                    single<ApprovedSyncPlanRepository> {
                        RoomApprovedSyncPlanRepository(db.approvedSyncPlanDao())
                    }
                    single<PairedDeviceRegistry> { mockPairedRegistry }
                }
            )
        }

        SyncWorker.clientFactory = { _, _, _ -> mockClient }
    }

    @After
    fun tearDown() {
        SyncWorker.clientFactory = null
        db.close()
        stopKoin()
    }

    @Test
    fun reconcileOrphanBackups_restoresValidBackup_whenFinalFileIsMissing() = runBlocking {
        val transferManager = LinkTransferManager(context)
        val musicDir = transferManager.musicDir

        val trackId = "trk_orphan_reconcile"
        val (finalFile, _) = TransferFileIdentity.resolveFiles(
            musicDir = musicDir,
            remoteDeviceId = testDeviceId,
            destinationLibraryId = "lib_default",
            trackId = trackId,
            format = "mp3",
        )

        val bakFile = File("${finalFile.absolutePath}.bak")
        val backupBytes = "Original preserved music audio data".toByteArray()
        bakFile.writeBytes(backupBytes)

        if (finalFile.exists()) finalFile.delete()
        assertTrue("Backup file must exist", bakFile.exists())
        assertFalse("Final file must not exist before reconciliation", finalFile.exists())

        // Reconcile orphan backups
        val restoredCount = transferManager.reconcileOrphanBackups(musicDir)
        assertEquals(1, restoredCount)

        assertTrue("Final file must be restored from backup", finalFile.exists())
        assertEquals(backupBytes.size.toLong(), finalFile.length())
        assertFalse("Backup file must be cleaned up upon restoration", bakFile.exists())
    }

    @Test
    fun syncWorker_whenRoomRegistrationFails_rollsBackAndRestoresPreviousBackup() = runBlocking {
        // P0 Invariant: If Room registration fails after file download, the previous valid copy
        // must be restored from .bak without data loss.
        val trackId = "trk_rollback_test"
        val initialBytes = "Valid existing audio content".toByteArray()
        val planId = "plan_rollback_test"
        val destLibId = "lib_synced_$testDeviceId"

        val transferManager = LinkTransferManager(context)
        val (finalFile, _) = TransferFileIdentity.resolveFiles(
            musicDir = transferManager.musicDir,
            remoteDeviceId = testDeviceId,
            destinationLibraryId = destLibId,
            trackId = trackId,
            format = "mp3",
            planId = planId,
        )

        // Write existing valid copy to final destination
        finalFile.writeBytes(initialBytes)
        assertTrue(finalFile.exists())
        assertEquals(initialBytes.size.toLong(), finalFile.length())

        val incomingBytes = "New incoming audio content".toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val incomingSha = md.digest(incomingBytes).joinToString("") { "%02x".format(it) }

        val manifestTracks = listOf(
            ManifestTrackDto(
                trackId = trackId,
                title = "Rollback Test Track",
                artist = "Artist",
                album = "Album",
                duration = 120,
                size = incomingBytes.size.toLong(),
                format = "mp3",
                checksum = incomingSha,
            )
        )

        coEvery { mockClient.fetchSyncManifest(testDeviceId) } returns Result.success(
            SyncManifestDto(version = 1, tracks = manifestTracks, manifestId = "sha_manifest_rollback")
        )

        coEvery {
            mockClient.streamTrackRange(
                trackId = trackId,
                outputStream = any(),
                startBytes = 0L,
                expectedEtag = any(),
                bufferSize = any(),
                onProgress = any(),
            )
        } answers {
            val stream = secondArg<OutputStream>()
            stream.write(incomingBytes)
            Result.success(StreamRangeResult(incomingBytes.size.toLong(), 200, false, "etag_rollback"))
        }

        // Simulate Room database constraint / registration failure
        val failingMusicRepo = mockk<MusicLibraryRepository>()
        io.mockk.coEvery {
            failingMusicRepo.registerTrackWithLocation(any(), any(), any(), any())
        } returns Result.failure(java.sql.SQLException("Simulated SQLite constraint disk corruption"))

        stopKoin()
        startKoin {
            modules(
                module {
                    single<MichiDatabase> { db }
                    single<TrackDao> { db.trackDao() }
                    single<PlaylistDao> { db.playlistDao() }
                    single<MusicLibraryRepository> { failingMusicRepo }
                    single<TransferCheckpointRepository> { RoomTransferCheckpointRepository(db.transferCheckpointDao()) }
                    single<ApprovedSyncPlanRepository> { RoomApprovedSyncPlanRepository(db.approvedSyncPlanDao()) }
                    single<PairedDeviceRegistry> { mockPairedRegistry }
                }
            )
        }

        val approvedDecisions = listOf(
            SyncItemDecision(
                trackId = trackId,
                title = "Rollback Test Track",
                artist = "Artist",
                album = "Album",
                action = SyncItemAction.DOWNLOAD,
                sourceSizeBytes = incomingBytes.size.toLong(),
                estimatedDownloadBytes = incomingBytes.size.toLong(),
            )
        )

        val planEntity = ApprovedSyncPlanEntity(
            planId = planId,
            endpointId = testDeviceId,
            endpointName = "Production Desktop Node",
            scope = "FULL_LIBRARY",
            selectionJson = "{}",
            deliveryChoiceJson = "{}",
            itemsJson = json.encodeToString(approvedDecisions),
            manifestVersion = 1,
            manifestChecksum = "sha_manifest_rollback",
            totalSelectedTracks = 1,
            tracksToDownloadCount = 1,
            totalDownloadBytes = incomingBytes.size.toLong(),
            status = "READY",
            executionState = TransferLifecycleState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
        )
        db.approvedSyncPlanDao().insertPlan(planEntity)

        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(
                SyncWorker.buildInputData(
                    baseUrl = "http://127.0.0.1:8400",
                    deviceId = testDeviceId,
                    alias = "Production Desktop Node",
                    planId = planId,
                    expectedBytes = incomingBytes.size.toLong(),
                )
            )
            .build()

        val workerResult = worker.doWork()

        // SyncWorker must record error
        assertTrue("Worker must fail when registration fails", workerResult is ListenableWorker.Result.Failure)

        // Invariant: Last valid copy must be safely restored
        assertTrue("Original final file must be intact after rollback", finalFile.exists())
        assertEquals("Original content must be preserved", initialBytes.size.toLong(), finalFile.length())
        assertEquals(String(initialBytes), finalFile.readText())

        // Backup must be cleaned up / restored
        val bakFile = File("${finalFile.absolutePath}.bak")
        assertFalse("Backup file must not linger after restoration", bakFile.exists())
    }

    @Test
    fun syncWorker_startupReconciliation_restoresPreviousBackup_whenTargetExistsButRoomRegistrationFailed() = runBlocking {
        // Critical P0 Scenario from Audit:
        // 1. Valid copy A exists.
        // 2. Download B is promoted to finalFile, creating cancion.flac.bak containing A.
        // 3. Android crashes / process dies BEFORE B is registered in Room.
        // 4. On next app launch / SyncWorker start, cancion.flac exists and has length > 0.
        // 5. reconcileOrphanBackups MUST NOT delete cancion.flac.bak because B is not in Room.
        //    It MUST restore A over uncommitted B!
        val transferManager = LinkTransferManager(context)
        val musicDir = transferManager.musicDir
        val trackId = "trk_uncommitted_crash"
        val (finalFile, _) = TransferFileIdentity.resolveFiles(
            musicDir = musicDir,
            remoteDeviceId = testDeviceId,
            destinationLibraryId = "lib_default",
            trackId = trackId,
            format = "flac",
        )

        val copyA = "Original Valid Audio Copy A".toByteArray()
        val copyB = "Uncommitted Audio Copy B (never recorded in Room)".toByteArray()

        // Setup disk state representing crash between promotion and Room registration:
        val bakFile = File("${finalFile.absolutePath}.bak")
        bakFile.writeBytes(copyA)
        finalFile.writeBytes(copyB)

        assertTrue(bakFile.exists())
        assertTrue(finalFile.exists())

        // Verify that finalFile is NOT registered in Room track_locations
        val musicRepo = org.michimusic.mobile.di.musicLibraryRepository
        assertNotNull(musicRepo)
        assertFalse("finalFile is uncommitted and must not be registered in Room",
            musicRepo!!.isLocationRegistered(finalFile.absolutePath))

        // Reconcile orphan backups with Room verification
        val restoredCount = transferManager.reconcileOrphanBackups(
            targetDir = musicDir,
            isRegisteredInDatabase = { file -> musicRepo.isLocationRegistered(file.absolutePath) }
        )

        assertEquals("Must restore 1 uncommitted file from valid backup", 1, restoredCount)
        assertTrue("Final file must exist", finalFile.exists())
        assertEquals("Final file must be restored to copy A", copyA.size.toLong(), finalFile.length())
        assertEquals(String(copyA), finalFile.readText())
        assertFalse("Backup must be consumed during restoration", bakFile.exists())
    }

    @Test
    fun syncViewModel_retryPlan_rejectsAutomaticRetry_whenPlanFailedWithStaleManifest() = runBlocking {
        // P1 Invariant: Irreversible errors like PLAN_STALE must not be blindly reactivated
        val planId = "plan_stale_rejection_test"
        val planEntity = ApprovedSyncPlanEntity(
            planId = planId,
            endpointId = testDeviceId,
            endpointName = "Production Desktop Node",
            scope = "FULL_LIBRARY",
            selectionJson = "{}",
            deliveryChoiceJson = "{}",
            itemsJson = "[]",
            manifestVersion = 1,
            manifestChecksum = "sha_old_manifest",
            totalSelectedTracks = 1,
            tracksToDownloadCount = 1,
            totalDownloadBytes = 5000L,
            status = "FAILED",
            executionState = TransferLifecycleState.FAILED.name,
            errorMessage = "PLAN_STALE: El catálogo del servidor ha cambiado desde que se aprobó el plan.",
            createdAt = System.currentTimeMillis(),
        )
        db.approvedSyncPlanDao().insertPlan(planEntity)

        val approvedRepo = RoomApprovedSyncPlanRepository(db.approvedSyncPlanDao())
        val syncVm = SyncViewModel(
            context = context,
            linkDiscovery = mockk(relaxed = true),
            registry = mockPairedRegistry,
            connectionManager = mockk(relaxed = true),
            identity = mockk(relaxed = true),
            trackRepository = mockk(relaxed = true),
            approvedPlanRepository = approvedRepo,
        )

        // Attempt retry on stale plan
        syncVm.retryPlan(planId)

        // Give coroutine a tick to execute
        kotlinx.coroutines.delay(100)

        // Plan state in DB must remain FAILED and NOT be transitioned to QUEUED
        val planInDb = approvedRepo.getPlan(planId)
        assertEquals(TransferLifecycleState.FAILED.name, planInDb?.executionState)
    }
}

