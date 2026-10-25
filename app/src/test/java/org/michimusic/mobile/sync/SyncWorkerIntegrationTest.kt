package org.michimusic.mobile.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.michimusic.core.models.TransferLifecycleState
import org.michimusic.data.cache.ApprovedSyncPlanDao
import org.michimusic.data.cache.ApprovedSyncPlanEntity
import org.michimusic.data.cache.LibraryDao
import org.michimusic.data.cache.LibraryMembershipDao
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.cache.TrackIdentityDao
import org.michimusic.data.cache.TrackLocationDao
import org.michimusic.data.cache.TransferCheckpointDao
import org.michimusic.data.repository.DefaultMusicLibraryRepository
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.data.repository.RoomApprovedSyncPlanRepository
import org.michimusic.data.repository.RoomTransferCheckpointRepository
import org.michimusic.link.LinkClient
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.dto.ManifestTrackDto
import org.michimusic.link.dto.SyncManifestDto
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SyncWorkerIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: MichiDatabase
    private lateinit var mockPairedRegistry: PairedDeviceRegistry
    private lateinit var mockClient: LinkClient
    private val json = Json { ignoreUnknownKeys = true }

    private val testDeviceId = "server_node_integration"
    private val testDeviceToken = "token_secret_12345"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // 1. In-memory Room database
        db = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        org.robolectric.shadows.ShadowStatFs.registerStats(
            context.filesDir.absolutePath,
            1_000_000,
            1_000_000,
            1_000_000
        )

        mockPairedRegistry = mockk(relaxed = true)
        val pairedDevice = PairedDevice(
            deviceId = testDeviceId,
            deviceName = "Production Desktop Node",
            deviceToken = testDeviceToken,
            lastUrl = "http://127.0.0.1:8400",
            isRevoked = false,
        )
        every { mockPairedRegistry.getDevice(testDeviceId) } returns pairedDevice

        val musicLibraryRepo = DefaultMusicLibraryRepository(
            libraryDao = db.libraryDao(),
            identityDao = db.trackIdentityDao(),
            locationDao = db.trackLocationDao(),
            membershipDao = db.libraryMembershipDao(),
        )

        val approvedPlanRepo = RoomApprovedSyncPlanRepository(db.approvedSyncPlanDao())
        val checkpointRepo = RoomTransferCheckpointRepository(db.transferCheckpointDao())

        // 2. Setup Koin test context
        stopKoin()
        startKoin {
            modules(
                module {
                    single<MichiDatabase> { db }
                    single<TrackDao> { db.trackDao() }
                    single<PlaylistDao> { db.playlistDao() }
                    single<LibraryDao> { db.libraryDao() }
                    single<TrackIdentityDao> { db.trackIdentityDao() }
                    single<TrackLocationDao> { db.trackLocationDao() }
                    single<LibraryMembershipDao> { db.libraryMembershipDao() }
                    single<ApprovedSyncPlanDao> { db.approvedSyncPlanDao() }
                    single<TransferCheckpointDao> { db.transferCheckpointDao() }
                    single<PairedDeviceRegistry> { mockPairedRegistry }
                    single<MusicLibraryRepository> { musicLibraryRepo }
                    single<ApprovedSyncPlanRepository> { approvedPlanRepo }
                    single<TransferCheckpointRepository> { checkpointRepo }
                }
            )
        }

        // 3. Mock LinkClient responding to manifest and stream requests
        mockClient = mockk(relaxed = true)
        every { mockClient.deviceToken } returns testDeviceToken

        SyncWorker.clientFactory = { _, _, _ -> mockClient }
    }

    @After
    fun tearDown() {
        SyncWorker.clientFactory = null
        stopKoin()
        db.close()
    }

    @Test
    fun syncWorker_executesAuthoritativePlan_downloadsOnlyAuthorized2TracksOutOf100() = runBlocking {
        // Manifest containing 100 tracks
        val manifestTracks = (1..100).map { i ->
            ManifestTrackDto(
                trackId = "trk_$i",
                title = "Title $i",
                artist = "Artist $i",
                album = "Album $i",
                duration = 180,
                size = 1000L,
                format = "mp3",
                checksum = "",
            )
        }

        coEvery { mockClient.fetchSyncManifest(testDeviceId) } returns Result.success(
            SyncManifestDto(
                version = 1,
                tracks = manifestTracks,
                manifestId = "sha_manifest_100",
            )
        )

        // Stream simulation writing actual dummy bytes to disk
        coEvery {
            mockClient.streamTrackRange(
                trackId = any(),
                outputStream = any(),
                startBytes = any(),
                expectedEtag = any(),
                bufferSize = any(),
                onProgress = any(),
            )
        } answers {
            val stream = secondArg<OutputStream>()
            val onProg = arg<((Long, Long?) -> Unit)?>(5)
            val bytes = ByteArray(1000) { 0x42 }
            stream.write(bytes)
            onProg?.invoke(1000L, 1000L)
            Result.success(
                org.michimusic.link.StreamRangeResult(
                    bytesRead = 1000L,
                    statusCode = 200,
                    isPartialContent = false,
                    etag = "etag_test",
                )
            )
        }

        // Authorize ONLY trk_7 and trk_42 in the approved plan
        val approvedDecisions = listOf(
            SyncItemDecision(
                trackId = "trk_7",
                title = "Title 7",
                artist = "Artist 7",
                album = "Album 7",
                action = SyncItemAction.DOWNLOAD,
                sourceSizeBytes = 1000L,
                estimatedDownloadBytes = 1000L,
            ),
            SyncItemDecision(
                trackId = "trk_42",
                title = "Title 42",
                artist = "Artist 42",
                album = "Album 42",
                action = SyncItemAction.DOWNLOAD,
                sourceSizeBytes = 1000L,
                estimatedDownloadBytes = 1000L,
            ),
            SyncItemDecision(
                trackId = "trk_99",
                title = "Title 99",
                artist = "Artist 99",
                album = "Album 99",
                action = SyncItemAction.SKIP_ALREADY_EXISTS,
                sourceSizeBytes = 1000L,
                estimatedDownloadBytes = 0L,
            ),
        )

        val planId = "plan_test_100_tracks"
        val planEntity = ApprovedSyncPlanEntity(
            planId = planId,
            endpointId = testDeviceId,
            endpointName = "Production Desktop Node",
            scope = "FULL_LIBRARY",
            selectionJson = "{}",
            deliveryChoiceJson = "{}",
            itemsJson = json.encodeToString(approvedDecisions),
            manifestVersion = 1,
            manifestChecksum = "sha_manifest_100",
            totalSelectedTracks = 3,
            tracksToDownloadCount = 2,
            totalDownloadBytes = 2000L,
            status = "READY",
            executionState = TransferLifecycleState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
        )
        db.approvedSyncPlanDao().insertPlan(planEntity)

        // Build and execute SyncWorker with WorkManager testing framework
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(
                SyncWorker.buildInputData(
                    baseUrl = "http://127.0.0.1:8400",
                    deviceId = testDeviceId,
                    alias = "Production Desktop Node",
                    planId = planId,
                    expectedBytes = 2000L,
                )
            )
            .build()

        val workResult = worker.doWork()

        // 1. Verify Worker success and counts
        assertTrue("SyncWorker must return Result.Success, but returned $workResult", workResult is ListenableWorker.Result.Success)
        val output = (workResult as ListenableWorker.Result.Success).outputData
        assertEquals(2, output.getInt(SyncWorker.RESULT_DOWNLOADED, -1))
        assertEquals(0, output.getInt(SyncWorker.RESULT_ERROR, -1))

        // 2. Verify Room database state: exactly 2 downloaded tracks
        val downloadedTracks = db.trackDao().getDownloadedTracks().first()
        assertEquals(2, downloadedTracks.size)
        assertEquals(setOf("trk_7", "trk_42"), downloadedTracks.map { it.id }.toSet())

        // 3. Multi-Library domain identity and location registration
        val identity7 = db.trackIdentityDao().getTrackIdentity("trk_7")
        assertNotNull("trk_7 must be registered in track_identities", identity7)
        assertEquals("Title 7", identity7?.title)

        val identity42 = db.trackIdentityDao().getTrackIdentity("trk_42")
        assertNotNull("trk_42 must be registered in track_identities", identity42)
        assertEquals("Title 42", identity42?.title)

        // 4. Verify unauthorized tracks from the 100-track manifest were NEVER downloaded or registered
        assertNull("trk_1 was not authorized and must not exist in track_identities", db.trackIdentityDao().getTrackIdentity("trk_1"))
        assertNull("trk_50 was not authorized and must not exist in track_identities", db.trackIdentityDao().getTrackIdentity("trk_50"))

        // 5. Verify CAS state transition from QUEUED -> TRANSFERRING -> COMPLETED
        val updatedPlan = db.approvedSyncPlanDao().getPlan(planId)
        assertNotNull("Plan must exist in database", updatedPlan)
        assertEquals(TransferLifecycleState.COMPLETED.name, updatedPlan?.executionState)
    }

    @Test
    fun syncWorker_failsClosedImmediately_whenPlanAlreadyClaimedByAnotherWorker() = runBlocking {
        val planId = "plan_claimed_concurrent"
        val approvedDecisions = listOf(
            SyncItemDecision(
                trackId = "trk_concurrent",
                title = "Concurrent Track",
                artist = "Artist",
                album = "Album",
                action = SyncItemAction.DOWNLOAD,
                sourceSizeBytes = 1000L,
                estimatedDownloadBytes = 1000L,
            )
        )

        // Insert plan already in TRANSFERRING state (simulating a concurrent worker that won the CAS race)
        val planEntity = ApprovedSyncPlanEntity(
            planId = planId,
            endpointId = testDeviceId,
            endpointName = "Production Desktop Node",
            scope = "FULL_LIBRARY",
            selectionJson = "{}",
            deliveryChoiceJson = "{}",
            itemsJson = json.encodeToString(approvedDecisions),
            manifestVersion = 1,
            manifestChecksum = "sha_concurrent",
            totalSelectedTracks = 1,
            tracksToDownloadCount = 1,
            totalDownloadBytes = 1000L,
            status = "READY",
            executionState = TransferLifecycleState.TRANSFERRING.name, // Already claimed!
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
                    expectedBytes = 1000L,
                )
            )
            .build()

        val workResult = worker.doWork()

        // Must fail closed immediately
        assertTrue("SyncWorker must return Result.Failure, but returned $workResult", workResult is ListenableWorker.Result.Failure)
        val output = (workResult as ListenableWorker.Result.Failure).outputData
        assertEquals(0, output.getInt(SyncWorker.RESULT_DOWNLOADED, -1))
        assertEquals(1, output.getInt(SyncWorker.RESULT_ERROR, -1))

        // Ensure 0 downloaded tracks and no side effects in database
        val downloaded = db.trackDao().getDownloadedTracks().first()
        assertTrue("No tracks must be downloaded", downloaded.isEmpty())
        assertNull(db.trackIdentityDao().getTrackIdentity("trk_concurrent"))
    }

    @Test
    fun syncWorker_resumesPartialTrack_whenEstimatedDownloadBytesIsLessThanSourceSize() = runBlocking {
        val trackId = "trk_partial_resume"
        val manifestTracks = listOf(
            ManifestTrackDto(
                trackId = trackId,
                title = "Partial Track",
                artist = "Artist",
                album = "Album",
                duration = 200,
                size = 2000L, // Total file size is 2000 bytes
                format = "mp3",
                checksum = "",
            )
        )

        coEvery { mockClient.fetchSyncManifest(testDeviceId) } returns Result.success(
            SyncManifestDto(
                version = 1,
                tracks = manifestTracks,
                manifestId = "sha_partial",
            )
        )

        // Ensure synced_music dir exists and prepare .part file with 1000 bytes
        val syncedMusicDir = File(context.filesDir, "synced_music").also { it.mkdirs() }
        val safeDev = testDeviceId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "device" }
        val destLibId = "lib_synced_$safeDev"
        val safeLib = destLibId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "lib" }
        val safeTrack = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "track" }
        val partFile = File(syncedMusicDir, "${safeDev}_${safeLib}_${safeTrack}.mp3.part")
        partFile.writeBytes(ByteArray(1000) { 0x41 })

        // Mock streamTrackRange resuming at offset 1000L
        coEvery {
            mockClient.streamTrackRange(
                trackId = trackId,
                outputStream = any(),
                startBytes = 1000L,
                expectedEtag = any(),
                bufferSize = any(),
                onProgress = any(),
            )
        } answers {
            val stream = secondArg<OutputStream>()
            val onProg = arg<((Long, Long?) -> Unit)?>(5)
            val bytes = ByteArray(1000) { 0x42 }
            stream.write(bytes)
            onProg?.invoke(2000L, 2000L)
            Result.success(
                org.michimusic.link.StreamRangeResult(
                    bytesRead = 1000L,
                    statusCode = 206,
                    isPartialContent = true,
                    etag = "etag_partial",
                )
            )
        }

        // Decision specifies RESUME_PARTIAL with sourceSizeBytes = 2000L and estimatedDownloadBytes = 1000L
        val approvedDecisions = listOf(
            SyncItemDecision(
                trackId = trackId,
                title = "Partial Track",
                artist = "Artist",
                album = "Album",
                action = SyncItemAction.RESUME_PARTIAL,
                sourceSizeBytes = 2000L,
                estimatedDownloadBytes = 1000L,
            )
        )

        val planId = "plan_partial_resume"
        val planEntity = ApprovedSyncPlanEntity(
            planId = planId,
            endpointId = testDeviceId,
            endpointName = "Production Desktop Node",
            scope = "FULL_LIBRARY",
            selectionJson = "{}",
            deliveryChoiceJson = "{}",
            itemsJson = json.encodeToString(approvedDecisions),
            manifestVersion = 1,
            manifestChecksum = "sha_partial",
            totalSelectedTracks = 1,
            tracksToDownloadCount = 1,
            totalDownloadBytes = 1000L,
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
                    expectedBytes = 1000L,
                )
            )
            .build()

        val workResult = worker.doWork()

        assertTrue("SyncWorker must succeed for RESUME_PARTIAL, but returned $workResult", workResult is ListenableWorker.Result.Success)
        val output = (workResult as ListenableWorker.Result.Success).outputData
        assertEquals(1, output.getInt(SyncWorker.RESULT_DOWNLOADED, -1))
        assertEquals(0, output.getInt(SyncWorker.RESULT_ERROR, -1))

        // Verify the track is registered in DB
        val downloaded = db.trackDao().getDownloadedTracks().first()
        assertEquals(1, downloaded.size)
        assertEquals(trackId, downloaded.first().id)

        // Verify final file size is 2000 bytes (1000 pre-existing + 1000 resumed)
        val finalFile = File(downloaded.first().filepath)
        assertTrue("Final file must exist", finalFile.exists())
        assertEquals(2000L, finalFile.length())
    }
}
