package org.michimusic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.TrackDto
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.CachedTrack

class SyncedTrackRepositoryTest {

    private lateinit var db: MichiDatabase
    private lateinit var repository: SyncedTrackRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SyncedTrackRepository(db.trackDao(), db.playlistDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveLibrary_insertsNewTracks() = runTest {
        val tracks = listOf(
            TrackDto(id = "t1", title = "Song 1", artist = "A", album = "X"),
            TrackDto(id = "t2", title = "Song 2", artist = "B", album = "Y"),
        )
        repository.saveLibrary(tracks)
        val count = repository.count()
        assertEquals(2, count)
    }

    @Test
    fun saveLibrary_updatesExistingTrack() = runTest {
        val initial = listOf(TrackDto(id = "t1", title = "Old Title", artist = "A", album = "X"))
        repository.saveLibrary(initial)

        val updated = listOf(TrackDto(id = "t1", title = "New Title", artist = "A", album = "X"))
        repository.saveLibrary(updated)

        val all = repository.getAllSynced().first()
        assertEquals("New Title", all.first().title)
    }

    @Test
    fun saveLibrary_preservesDownloadedStatus() = runTest {
        val tracks = listOf(TrackDto(id = "t1", title = "Song", artist = "A", album = "X"))
        repository.saveLibrary(tracks)
        db.trackDao().markDownloadedWithPath("t1", "/music/song.mp3")

        repository.saveLibrary(tracks)
        val track = repository.getById("t1")
        assertNotNull(track)
        assertTrue(track!!.downloaded)
        assertEquals("/music/song.mp3", track.filepath)
    }

    @Test
    fun saveLibrary_removesTrackNotInNewSync() = runTest {
        val existing = listOf(
            TrackDto(id = "t1", title = "Keep", artist = "A", album = "X"),
            TrackDto(id = "t2", title = "Remove", artist = "B", album = "Y"),
        )
        repository.saveLibrary(existing)

        val newSync = listOf(TrackDto(id = "t1", title = "Keep", artist = "A", album = "X"))
        repository.saveLibrary(newSync)

        val all = repository.getAllSynced().first()
        assertEquals(1, all.size)
        assertEquals("t1", all.first().id)
    }

    @Test
    fun saveLibrary_keepsDownloadedTrackEvenIfRemoved() = runTest {
        val existing = listOf(
            TrackDto(id = "t1", title = "Song", artist = "A", album = "X"),
        )
        repository.saveLibrary(existing)
        db.trackDao().markDownloaded("t1")

        repository.saveLibrary(emptyList())
        val all = repository.getAllSynced().first()
        assertTrue(all.isNotEmpty())
        assertEquals("t1", all.first().id)
    }

    @Test
    fun getDownloadedIds_returnsOnlyDownloaded() = runTest {
        val tracks = listOf(
            TrackDto(id = "t1", title = "Song 1", artist = "A", album = "X"),
            TrackDto(id = "t2", title = "Song 2", artist = "B", album = "Y"),
        )
        repository.saveLibrary(tracks)
        db.trackDao().markDownloaded("t1")

        val downloaded = repository.getDownloadedIds()
        assertEquals(setOf("t1"), downloaded)
    }

    @Test
    fun saveManifestPlaylists_insertsPlaylists() = runTest {
        val playlists = listOf(
            ManifestPlaylist(playlistId = "pl1", name = "Favorites", trackIds = listOf("t1", "t2")),
        )
        repository.saveManifestPlaylists(playlists)
        val all = db.playlistDao().getAll()
        assertEquals(1, all.size)
        assertEquals("Favorites", all.first().name)
    }
}
