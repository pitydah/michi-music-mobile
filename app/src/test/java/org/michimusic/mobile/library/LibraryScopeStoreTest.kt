package org.michimusic.mobile.library

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.LibraryType
import org.michimusic.core.models.MusicLibrary
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.SettingsEntity
import org.michimusic.data.repository.MusicLibraryRepository

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryScopeStoreTest {

    private lateinit var appDao: AppDao
    private lateinit var libraryRepo: MusicLibraryRepository
    private val settingsMap = mutableMapOf<String, String>()

    @Before
    fun setup() {
        appDao = mockk(relaxed = true)
        libraryRepo = mockk(relaxed = true)
        settingsMap.clear()

        coEvery { appDao.getSetting(LibraryScopeStore.KEY_ACTIVE_SCOPE) } answers {
            settingsMap[LibraryScopeStore.KEY_ACTIVE_SCOPE]
        }
        val slot = slot<SettingsEntity>()
        coEvery { appDao.setSetting(capture(slot)) } answers {
            settingsMap[slot.captured.key] = slot.captured.value
        }

        coEvery { libraryRepo.observeLibraries() } returns flowOf(
            listOf(
                MusicLibrary(id = "lib_device_local", name = "Este teléfono", type = LibraryType.DEVICE_LOCAL),
                MusicLibrary(id = "lib_custom_1", name = "Jazz", type = LibraryType.USER_CUSTOM),
            )
        )
    }

    // The store collects for the lifetime of the scope it receives. Handing it a
    // detached TestScope let a coroutine from one test surface as
    // `UncaughtExceptionsBeforeTest` in the next one under the full F-Droid suite, so
    // every case now owns the store inside its own `runTest` scope: a failure is
    // attributed to the test that caused it and `runTest` cancels whatever is left.
    @Test
    fun `scope defaults to All when no setting stored`() = runTest(StandardTestDispatcher()) {
        val store = LibraryScopeStore(appDao, libraryRepo, this)
        testScheduler.advanceUntilIdle()

        assertEquals(LibraryScope.All, store.activeScope.value)
    }

    @Test
    fun `scope loads persisted valid library`() = runTest(StandardTestDispatcher()) {
        settingsMap[LibraryScopeStore.KEY_ACTIVE_SCOPE] = "lib_custom_1"

        val store = LibraryScopeStore(appDao, libraryRepo, this)
        testScheduler.advanceUntilIdle()

        assertEquals(LibraryScope.One("lib_custom_1"), store.activeScope.value)
    }

    @Test
    fun `scope falls back to All if persisted library no longer exists`() =
        runTest(StandardTestDispatcher()) {
            settingsMap[LibraryScopeStore.KEY_ACTIVE_SCOPE] = "lib_deleted_999"

            val store = LibraryScopeStore(appDao, libraryRepo, this)
            testScheduler.advanceUntilIdle()

            assertEquals(LibraryScope.All, store.activeScope.value)
            assertEquals(LibraryScopeStore.VALUE_ALL, settingsMap[LibraryScopeStore.KEY_ACTIVE_SCOPE])
        }

    @Test
    fun `setScope updates state and persists setting`() = runTest(StandardTestDispatcher()) {
        val store = LibraryScopeStore(appDao, libraryRepo, this)
        testScheduler.advanceUntilIdle()

        store.setScope(LibraryScope.One("lib_device_local"))
        testScheduler.advanceUntilIdle()

        assertEquals(LibraryScope.One("lib_device_local"), store.activeScope.value)
        assertEquals("lib_device_local", settingsMap[LibraryScopeStore.KEY_ACTIVE_SCOPE])
        coVerify { appDao.setSetting(match { it.value == "lib_device_local" }) }
    }
}
