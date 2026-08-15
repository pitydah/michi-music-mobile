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
}
