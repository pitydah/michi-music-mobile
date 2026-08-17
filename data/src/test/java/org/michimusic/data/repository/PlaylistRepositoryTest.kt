package org.michimusic.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.PlaylistDao

class PlaylistRepositoryTest {

    private lateinit var fakeDao: TestPlaylistDao
    private lateinit var repository: PlaylistRepository

    @Before
    fun setup() {
        fakeDao = TestPlaylistDao()
        repository = PlaylistRepository(fakeDao)
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
