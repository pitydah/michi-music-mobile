package org.michimusic.data.repository

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao

class SyncedTrackRepositoryTest {

    private lateinit var trackDao: FakeTrackDao
    private lateinit var playlistDao: FakePlaylistDao
    private lateinit var repository: SyncedTrackRepository

    @Before
    fun setup() {
        trackDao = FakeTrackDao()
        playlistDao = FakePlaylistDao()
        repository = SyncedTrackRepository(trackDao, playlistDao)
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
        trackDao.markDownloadedWithPath("t1", "/music/song.mp3")

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
        trackDao.markDownloaded("t1")

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
        trackDao.markDownloaded("t1")

        val downloaded = repository.getDownloadedIds()
        assertEquals(setOf("t1"), downloaded)
    }

    @Test
    fun saveManifestPlaylists_insertsPlaylists() = runTest {
        val playlists = listOf(
            ManifestPlaylist(playlistId = "pl1", name = "Favorites", trackIds = listOf("t1", "t2")),
        )
        repository.saveManifestPlaylists(playlists)
        val all = playlistDao.getAllPlaylists()
        assertEquals(1, all.size)
        assertEquals("Favorites", all.first().name)
    }
}

private class FakeTrackDao : TrackDao {
    private val tracks = MutableStateFlow<Map<String, CachedTrack>>(emptyMap())

    override fun getAllTracks(): Flow<List<CachedTrack>> =
        tracks.map { it.values.toList().sortedBy { t -> t.title } }

    override fun getAllTracksPagingSource(): PagingSource<Int, CachedTrack> =
        throw UnsupportedOperationException()

    override suspend fun getTrackById(id: String): CachedTrack? =
        tracks.value[id]

    override fun getDownloadedTracks(): Flow<List<CachedTrack>> =
        tracks.map { it.values.filter { t -> t.downloaded } }

    override suspend fun insertAll(items: List<CachedTrack>) {
        val updated = tracks.value.toMutableMap()
        items.forEach { updated[it.id] = it }
        tracks.value = updated
    }

    override suspend fun insert(track: CachedTrack) {
        val updated = tracks.value.toMutableMap()
        updated[track.id] = track
        tracks.value = updated
    }

    override suspend fun delete(track: CachedTrack) {
        val updated = tracks.value.toMutableMap()
        updated.remove(track.id)
        tracks.value = updated
    }

    override suspend fun deleteAll() {
        tracks.value = emptyMap()
    }

    override suspend fun count(): Int = tracks.value.size

    override suspend fun getUndownloaded(): List<CachedTrack> =
        tracks.value.values.filter { !it.downloaded }

    override suspend fun markDownloaded(id: String) {
        tracks.value[id]?.let {
            val updated = tracks.value.toMutableMap()
            updated[id] = it.copy(downloaded = true)
            tracks.value = updated
        }
    }

    override suspend fun markDownloadedWithPath(id: String, filepath: String) {
        tracks.value[id]?.let {
            val updated = tracks.value.toMutableMap()
            updated[id] = it.copy(downloaded = true, filepath = filepath)
            tracks.value = updated
        }
    }
}

private class FakePlaylistDao : PlaylistDao {
    private val playlists = mutableListOf<CachedPlaylist>()

    override suspend fun getAllPlaylists(): List<CachedPlaylist> =
        playlists.toList().sortedBy { it.name }

    override suspend fun insertAll(items: List<CachedPlaylist>) {
        playlists.addAll(items)
    }

    override suspend fun deleteAll() {
        playlists.clear()
    }
}
