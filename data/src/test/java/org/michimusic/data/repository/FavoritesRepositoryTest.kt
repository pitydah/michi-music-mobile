package org.michimusic.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.SettingsEntity

class FavoritesRepositoryTest {

    private lateinit var appDao: AppDao
    private lateinit var repository: FavoritesRepository
    private val savedSettings = mutableMapOf<String, String>()

    @Before
    fun setup() {
        appDao = mockk(relaxed = true)
        savedSettings.clear()

        coEvery { appDao.getSetting(DefaultFavoritesRepository.SETTING_KEY_FAVORITES) } answers {
            savedSettings[DefaultFavoritesRepository.SETTING_KEY_FAVORITES]
        }
        val slot = slot<SettingsEntity>()
        coEvery { appDao.setSetting(capture(slot)) } answers {
            savedSettings[slot.captured.key] = slot.captured.value
        }

        repository = DefaultFavoritesRepository(appDao)
    }

    @Test
    fun `observing favorites first loads the persisted set without emitting a false empty state`() = runTest {
        savedSettings[DefaultFavoritesRepository.SETTING_KEY_FAVORITES] = "saved_1,saved_2"
        assertEquals(setOf("saved_1", "saved_2"), repository.observeFavoriteTrackIds().first())
        assertEquals(setOf("saved_1", "saved_2"), repository.observeFavoriteTrackIds().first())
        coVerify(exactly = 1) { appDao.getSetting(DefaultFavoritesRepository.SETTING_KEY_FAVORITES) }
        coVerify(exactly = 0) { appDao.setSetting(any()) }
    }

    @Test
    fun `favoritesAreDistinctFromTopTracks and persist correctly`() = runTest {
        assertFalse(repository.isFavorite("track_1"))

        val added = repository.toggleFavorite("track_1")
        assertTrue(added)
        assertTrue(repository.isFavorite("track_1"))
        assertEquals("track_1", savedSettings[DefaultFavoritesRepository.SETTING_KEY_FAVORITES])

        repository.addFavorite("track_2")
        assertTrue(repository.isFavorite("track_2"))
        val stored = savedSettings[DefaultFavoritesRepository.SETTING_KEY_FAVORITES] ?: ""
        assertTrue(stored.contains("track_1") && stored.contains("track_2"))

        val removed = repository.toggleFavorite("track_1")
        assertFalse(removed)
        assertFalse(repository.isFavorite("track_1"))
        assertTrue(repository.isFavorite("track_2"))

        // Top tracks / play counts in AppDao are never called or altered
        coVerify(exactly = 0) { appDao.getTopTracks(any()) }
        coVerify(exactly = 0) { appDao.incrementPlayCount(any(), any()) }
    }

    @Test
    fun `a failed write never publishes the new favorite`() = runTest {
        // Load the initial (empty) set first, otherwise the failure could hide behind
        // the lazy initialisation.
        repository.isFavorite("track_1")
        coEvery { appDao.setSetting(any()) } answers { throw IllegalStateException("disk full") }

        val attempt = runCatching { repository.toggleFavorite("track_1") }

        assertTrue("the failure must reach the caller", attempt.isFailure)
        assertFalse(
            "the heart must not show a favorite that was never saved",
            repository.isFavorite("track_1"),
        )
    }

    @Test
    fun `a failed removal keeps the favorite visible`() = runTest {
        repository.toggleFavorite("track_1")
        coEvery { appDao.setSetting(any()) } answers { throw IllegalStateException("disk full") }

        val attempt = runCatching { repository.removeFavorite("track_1") }

        assertTrue(attempt.isFailure)
        assertTrue(repository.isFavorite("track_1"))
    }
}
