package org.michimusic.data.repository

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao

class PlaylistRepositoryTest {

    private lateinit var fakeDao: TestPlaylistDao
    private lateinit var repository: PlaylistRepository

    @Before
    fun setup() {
        fakeDao = TestPlaylistDao()
        repository = PlaylistRepository(fakeDao, mockk<TrackDao>(relaxed = true), LocalMediaRepository())
    }

    @Test
    fun createPlaylist_persistsAndReturnsPlaylist() = runTest {
        val created = repository.createPlaylist("Synthwave Nights", listOf("t1", "t2"))
        assertEquals("Synthwave Nights", created.name)
        assertEquals(2, created.trackCount)

        val all = repository.getAllPlaylists()
        assertEquals(1, all.size)
        assertEquals(created.id, all.first().id)
    }

    @Test
    fun getById_returnsExistingPlaylist() = runTest {
        val created = repository.createPlaylist("Favorites")
        val found = repository.getById(created.id)
        assertNotNull(found)
        assertEquals("Favorites", found?.name)
    }

    @Test
    fun getById_unknownId_returnsNull() = runTest {
        repository.createPlaylist("Favorites")
        val found = repository.getById("does-not-exist")
        assertNull(found)
    }

    @Test
    fun deletePlaylist_removesFromStorage() = runTest {
        val p1 = repository.createPlaylist("Chill")
        val p2 = repository.createPlaylist("Focus")
        assertEquals(2, repository.getAllPlaylists().size)

        repository.deletePlaylist(p1.id)
        val remaining = repository.getAllPlaylists()
        assertEquals(1, remaining.size)
        assertEquals(p2.id, remaining.first().id)
        assertNull(repository.getById(p1.id))
    }

    // --- trackIds parsing / mapping ---

    @Test
    fun getAllPlaylists_populatesTrackIdsFromCsv() = runTest {
        fakeDao.insert(CachedPlaylist(id = "p1", name = "Mix", trackIds = "t1,t2,t3", trackCount = 3))

        val playlist = repository.getAllPlaylists().first()
        assertEquals(listOf("t1", "t2", "t3"), playlist.trackIds)
    }

    @Test
    fun getAllPlaylists_emptyCsv_returnsEmptyTrackIds() = runTest {
        fakeDao.insert(CachedPlaylist(id = "p1", name = "Empty", trackIds = "", trackCount = 0))

        val playlist = repository.getAllPlaylists().first()
        assertTrue(playlist.trackIds.isEmpty())
    }

    @Test
    fun getAllPlaylists_trimsWhitespaceAroundIds() = runTest {
        fakeDao.insert(CachedPlaylist(id = "p1", name = "Spaced", trackIds = " t1 , t2 ,t3 ", trackCount = 3))

        val playlist = repository.getAllPlaylists().first()
        assertEquals(listOf("t1", "t2", "t3"), playlist.trackIds)
    }

    @Test
    fun getAllPlaylists_preservesStoredOrder() = runTest {
        fakeDao.insert(CachedPlaylist(id = "p1", name = "Ordered", trackIds = "trackC,trackA,trackB", trackCount = 3))

        val playlist = repository.getAllPlaylists().first()
        assertEquals(listOf("trackC", "trackA", "trackB"), playlist.trackIds)
    }

    @Test
    fun getById_populatesTrackIds() = runTest {
        fakeDao.insert(CachedPlaylist(id = "p1", name = "Mix", trackIds = "t1,t2", trackCount = 2))

        val playlist = repository.getById("p1")
        assertEquals(listOf("t1", "t2"), playlist?.trackIds)
    }

    @Test
    fun observePlaylists_populatesTrackIds() = runTest {
        fakeDao.insert(CachedPlaylist(id = "p1", name = "Mix", trackIds = "t1, t2 ,t3", trackCount = 3))

        val playlists = repository.observePlaylists().first()
        assertEquals(listOf("t1", "t2", "t3"), playlists.first().trackIds)
    }

    @Test
    fun addTracksToPlaylist_trimsIncomingIdsBeforeStoring() = runTest {
        val created = repository.createPlaylist("Chill")
        repository.addTracksToPlaylist(created.id, listOf(" t1 ", "t2", " t3"))

        val updated = repository.getById(created.id)
        assertEquals(listOf("t1", "t2", "t3"), updated?.trackIds)
    }

    // --- 3B: adding tracks ---

    @Test
    fun addTracksToPlaylist_addsSingleTrack() = runTest {
        val created = repository.createPlaylist("Chill")
        repository.addTracksToPlaylist(created.id, listOf("t1"))

        val updated = repository.getById(created.id)
        assertEquals(listOf("t1"), updated?.trackIds)
        assertEquals(1, updated?.trackCount)
    }

    @Test
    fun addTracksToPlaylist_appendsSeveralTracksPreservingSelectionOrder() = runTest {
        val created = repository.createPlaylist("Chill", listOf("A", "B"))
        repository.addTracksToPlaylist(created.id, listOf("D", "C"))

        val updated = repository.getById(created.id)
        assertEquals(listOf("A", "B", "D", "C"), updated?.trackIds)
        assertEquals(4, updated?.trackCount)
    }

    @Test
    fun addTracksToPlaylist_ignoresTrackAlreadyInPlaylist() = runTest {
        val created = repository.createPlaylist("Chill", listOf("A", "B"))
        repository.addTracksToPlaylist(created.id, listOf("B", "C"))

        val updated = repository.getById(created.id)
        assertEquals(listOf("A", "B", "C"), updated?.trackIds)
        assertEquals(3, updated?.trackCount)
    }

    @Test
    fun addTracksToPlaylist_updatesTrackCount() = runTest {
        val created = repository.createPlaylist("Chill")
        repository.addTracksToPlaylist(created.id, listOf("t1", "t2", "t3"))

        val updated = repository.getById(created.id)
        assertEquals(3, updated?.trackCount)
    }

    @Test
    fun addTracksToPlaylist_persistsAcrossNewRepositoryInstance() = runTest {
        val created = repository.createPlaylist("Chill", listOf("A"))
        repository.addTracksToPlaylist(created.id, listOf("B", "C"))

        // Simulate closing and reopening the app: a fresh Repository over the same DAO.
        val reopened = PlaylistRepository(fakeDao, mockk(relaxed = true), LocalMediaRepository())
        val reloaded = reopened.getById(created.id)
        assertEquals(listOf("A", "B", "C"), reloaded?.trackIds)
        assertEquals(3, reloaded?.trackCount)
    }

    // --- resolving trackIds to Track, preserving order ---

    @Test
    fun getTracksForPlaylist_resolvesInStoredOrder() = runTest {
        val trackDao = mockk<TrackDao>()
        val repoWithTracks = PlaylistRepository(fakeDao, trackDao, CountingLocalMediaRepository(emptyList()))
        coEvery { trackDao.getTrackById("trackC") } returns cachedTrack("trackC", "C")
        coEvery { trackDao.getTrackById("trackA") } returns cachedTrack("trackA", "A")
        coEvery { trackDao.getTrackById("trackB") } returns cachedTrack("trackB", "B")

        val playlist = Playlist(id = "p1", name = "Ordered", trackIds = listOf("trackC", "trackA", "trackB"), trackCount = 3)
        val tracks = repoWithTracks.getTracksForPlaylist(playlist)

        assertEquals(listOf("C", "A", "B"), tracks.map { it.title })
    }

    @Test
    fun getTracksForPlaylist_skipsMissingTrack() = runTest {
        val trackDao = mockk<TrackDao>()
        val repoWithTracks = PlaylistRepository(fakeDao, trackDao, CountingLocalMediaRepository(emptyList()))
        coEvery { trackDao.getTrackById("t1") } returns cachedTrack("t1", "One")
        coEvery { trackDao.getTrackById("ghost") } returns null

        val playlist = Playlist(id = "p1", name = "Mix", trackIds = listOf("t1", "ghost"), trackCount = 2)
        val tracks = repoWithTracks.getTracksForPlaylist(playlist)

        assertEquals(1, tracks.size)
        assertEquals("One", tracks.first().title)
    }

    @Test
    fun getTracksForPlaylist_resolvesLocalTrackFromLocalMediaRepository() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val localMedia = CountingLocalMediaRepository(
            listOf(Track(id = "local_1", title = "Device Song", artist = "Local Artist")),
        )
        val repoWithTracks = PlaylistRepository(fakeDao, trackDao, localMedia)

        val playlist = Playlist(id = "p1", name = "Mix", trackIds = listOf("local_1"), trackCount = 1)
        val tracks = repoWithTracks.getTracksForPlaylist(playlist)

        assertEquals(listOf("Device Song"), tracks.map { it.title })
    }

    @Test
    fun getTracksForPlaylist_resolvesRemoteTrackFromTrackDao() = runTest {
        val trackDao = mockk<TrackDao>()
        coEvery { trackDao.getTrackById("remote_1") } returns cachedTrack("remote_1", "Synced Song")
        val localMedia = CountingLocalMediaRepository(emptyList())
        val repoWithTracks = PlaylistRepository(fakeDao, trackDao, localMedia)

        val playlist = Playlist(id = "p1", name = "Mix", trackIds = listOf("remote_1"), trackCount = 1)
        val tracks = repoWithTracks.getTracksForPlaylist(playlist)

        assertEquals(listOf("Synced Song"), tracks.map { it.title })
    }

    @Test
    fun getTracksForPlaylist_mixedLocalAndRemote_preservesStoredOrder() = runTest {
        val trackDao = mockk<TrackDao>()
        // Only "remote_A" is expected to reach TrackDao at all: "local_C"/"local_B" resolve
        // from the local map first via the `?:` short-circuit, before trackDao is ever called.
        coEvery { trackDao.getTrackById("remote_A") } returns cachedTrack("remote_A", "A")
        val localMedia = CountingLocalMediaRepository(
            listOf(
                Track(id = "local_C", title = "C"),
                Track(id = "local_B", title = "B"),
            ),
        )
        val repoWithTracks = PlaylistRepository(fakeDao, trackDao, localMedia)

        val playlist = Playlist(
            id = "p1",
            name = "Mixed",
            trackIds = listOf("local_C", "remote_A", "local_B"),
            trackCount = 3,
        )
        val tracks = repoWithTracks.getTracksForPlaylist(playlist)

        assertEquals(listOf("C", "A", "B"), tracks.map { it.title })
    }

    @Test
    fun getTracksForPlaylist_idMissingFromBothSources_isOmitted() = runTest {
        val trackDao = mockk<TrackDao>()
        coEvery { trackDao.getTrackById(any()) } returns null
        val localMedia = CountingLocalMediaRepository(emptyList())
        val repoWithTracks = PlaylistRepository(fakeDao, trackDao, localMedia)

        val playlist = Playlist(id = "p1", name = "Mix", trackIds = listOf("ghost"), trackCount = 1)
        val tracks = repoWithTracks.getTracksForPlaylist(playlist)

        assertTrue(tracks.isEmpty())
    }

    @Test
    fun getTracksForPlaylist_callsLocalLoadTracksExactlyOnce() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val localMedia = CountingLocalMediaRepository(
            listOf(
                Track(id = "local_1", title = "One"),
                Track(id = "local_2", title = "Two"),
                Track(id = "local_3", title = "Three"),
            ),
        )
        val repoWithTracks = PlaylistRepository(fakeDao, trackDao, localMedia)

        val playlist = Playlist(
            id = "p1",
            name = "Mix",
            trackIds = listOf("local_1", "local_2", "local_3"),
            trackCount = 3,
        )
        repoWithTracks.getTracksForPlaylist(playlist)

        assertEquals(1, localMedia.loadTracksCallCount)
    }

    @Test
    fun getTracksForPlaylist_emptyTrackIds_doesNotQueryLocalLibrary() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val localMedia = CountingLocalMediaRepository(emptyList())
        val repoWithTracks = PlaylistRepository(fakeDao, trackDao, localMedia)

        val playlist = Playlist(id = "p1", name = "Empty", trackIds = emptyList(), trackCount = 0)
        val tracks = repoWithTracks.getTracksForPlaylist(playlist)

        assertTrue(tracks.isEmpty())
        assertEquals(0, localMedia.loadTracksCallCount)
    }

}

private fun cachedTrack(id: String, title: String) = CachedTrack(
    id = id,
    title = title,
    artist = "Artist $title",
)

private class CountingLocalMediaRepository(
    private val tracks: List<Track>,
) : LocalMediaRepository() {
    var loadTracksCallCount = 0
        private set

    override suspend fun loadTracks(): List<Track> {
        loadTracksCallCount++
        return tracks
    }
}

private class TestPlaylistDao : PlaylistDao {
    private val playlists = mutableListOf<CachedPlaylist>()

    override suspend fun getAllPlaylists(): List<CachedPlaylist> = playlists.sortedBy { it.name }

    override suspend fun insert(playlist: CachedPlaylist) {
        playlists.removeAll { it.id == playlist.id }
        playlists.add(playlist)
    }

    override suspend fun insertAll(playlists: List<CachedPlaylist>) {
        this.playlists.addAll(playlists)
    }

    override suspend fun deleteById(id: String) {
        playlists.removeAll { it.id == id }
    }

    override suspend fun deleteAll() {
        playlists.clear()
    }

    override fun observeAll(): Flow<List<CachedPlaylist>> = flowOf(playlists.sortedBy { it.name })
}
