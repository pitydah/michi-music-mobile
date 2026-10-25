package org.michimusic.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DaoTests {

    private lateinit var database: MichiDatabase
    private lateinit var trackDao: TrackDao
    private lateinit var playlistDao: PlaylistDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackDao = database.trackDao()
        playlistDao = database.playlistDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `TrackDao inserts and retrieves tracks`() = runBlocking {
        val track1 = CachedTrack(id = "1", title = "A Song", artist = "Artist A")
        val track2 = CachedTrack(id = "2", title = "Z Song", artist = "Artist B")
        
        trackDao.insertAll(listOf(track1, track2))
        
        val tracks = trackDao.getAllTracks().first()
        assertEquals(2, tracks.size)
        // Ordered by title
        assertEquals("A Song", tracks[0].title)
        assertEquals("Z Song", tracks[1].title)
    }

    @Test
    fun `TrackDao marks track as downloaded`() = runBlocking {
        val track = CachedTrack(id = "1", title = "A Song", downloaded = false)
        trackDao.insert(track)
        
        trackDao.markDownloaded("1")
        
        val retrieved = trackDao.getTrackById("1")
        assertNotNull(retrieved)
        assertEquals(true, retrieved?.downloaded)
    }

    @Test
    fun `TrackDao deletes track`() = runBlocking {
        val track = CachedTrack(id = "1", title = "A Song")
        trackDao.insert(track)
        
        trackDao.delete(track)
        
        val retrieved = trackDao.getTrackById("1")
        assertNull(retrieved)
    }

    @Test
    fun `PlaylistDao inserts and retrieves playlists ordered by name`() = runBlocking {
        val p1 = CachedPlaylist(id = "p1", name = "Z Playlist")
        val p2 = CachedPlaylist(id = "p2", name = "A Playlist")
        
        playlistDao.insertAll(listOf(p1, p2))
        
        val playlists = playlistDao.getAllPlaylists()
        assertEquals(2, playlists.size)
        assertEquals("A Playlist", playlists[0].name)
        assertEquals("Z Playlist", playlists[1].name)
    }

    @Test
    fun `PlaylistDao deletes playlist by id`() = runBlocking {
        val p1 = CachedPlaylist(id = "p1", name = "Z Playlist")
        playlistDao.insert(p1)
        
        playlistDao.deleteById("p1")
        
        val playlists = playlistDao.getAllPlaylists()
        assertEquals(0, playlists.size)
    }

    @Test
    fun `TransferCheckpointDao strict exact match does not touch other plans`() = runBlocking {
        val checkpointDao = database.transferCheckpointDao()

        val cpPlanA = TransferCheckpointEntity(
            transferKey = "dev1:lib1:trk1:plan_A",
            transferId = "plan_A",
            remoteDeviceId = "dev1",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib1",
            trackId = "trk1",
            remoteTrackId = "trk1",
            tempFilePath = "/tmp/a.part",
            finalFilePath = "/tmp/a.mp3",
            expectedSize = 1000L,
            receivedBytes = 200L,
            verifiedBytes = 200L,
            etag = "etag_a",
            expectedSha256 = "hash_a",
            transferState = "DOWNLOADING",
            createdAt = 1000L,
            updatedAt = 1000L,
        )

        checkpointDao.insertCheckpoint(cpPlanA)

        // Exact query on prefix or another plan must return null
        val prefixQuery = checkpointDao.getCheckpoint("dev1:lib1:trk1")
        assertNull("Prefix query without planId must not match plan_A checkpoint", prefixQuery)

        val planBQuery = checkpointDao.getCheckpoint("dev1:lib1:trk1:plan_B")
        assertNull("Query for plan_B must not match plan_A", planBQuery)

        // Updating progress with prefix or plan_B key must not touch plan_A
        val updatedRowsPrefix = checkpointDao.updateProgress(
            key = "dev1:lib1:trk1",
            received = 999L,
            verified = 999L,
            state = "COMPLETED",
            updatedAt = 2000L
        )
        assertEquals(0, updatedRowsPrefix)

        val updatedRowsPlanB = checkpointDao.updateProgress(
            key = "dev1:lib1:trk1:plan_B",
            received = 888L,
            verified = 888L,
            state = "COMPLETED",
            updatedAt = 2000L
        )
        assertEquals(0, updatedRowsPlanB)

        // Verify plan_A record remained pristine
        val retrievedPlanA = checkpointDao.getCheckpoint("dev1:lib1:trk1:plan_A")
        assertNotNull(retrievedPlanA)
        assertEquals(200L, retrievedPlanA?.receivedBytes)
        assertEquals("DOWNLOADING", retrievedPlanA?.transferState)
    }

    @Test
    fun `TrackLocationDao queries location by exact uri`() = runBlocking {
        val locationDao = database.trackLocationDao()
        val identityDao = database.trackIdentityDao()

        val trackId = "trk_uri_test"
        identityDao.insertIdentity(
            TrackIdentityEntity(
                trackId = trackId,
                title = "Title",
                artist = "Artist",
                album = "Album",
                durationMs = 180000L,
            )
        )

        val uri = "/data/user/0/org.michimusic/files/synced_music/song.mp3"
        locationDao.insertLocation(
            TrackLocationEntity(
                locationId = "loc_1",
                trackId = trackId,
                kind = "SYNCED_MANAGED",
                uri = uri,
            )
        )

        val found = locationDao.getLocationByUri(uri)
        assertNotNull("Should find location by exact URI", found)
        assertEquals("loc_1", found?.locationId)

        val notFound = locationDao.getLocationByUri("/unregistered/path/song.mp3")
        assertNull("Should return null for non-registered URI", notFound)
    }
}
