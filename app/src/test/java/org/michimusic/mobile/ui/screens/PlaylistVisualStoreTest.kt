package org.michimusic.mobile.ui.screens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PlaylistVisualStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before fun clear() { context.getSharedPreferences("michi_settings", 0).edit().clear().commit() }

    @Test fun perIdPinIsPersistentAndDoesNotChangeOtherPlaylists() = runTest {
        val store = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        store.setPinned("a", true)
        assertTrue(store.read("a").pinned)
        assertFalse(store.read("b").pinned)
        store.clear("a")
        assertFalse(store.read("a").pinned)
    }

    @Test fun changesAreObservableAcrossHomeCatalogAndDetailStoreInstances() = runTest {
        val first = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        val second = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        val initial = PlaylistVisualStore.revision.value
        first.setPinned("album_playlist", true)
        assertTrue(PlaylistVisualStore.revision.value > initial)
        assertTrue(second.read("album_playlist").pinned)
        val afterPin = PlaylistVisualStore.revision.value
        second.setBannerFocal("album_playlist", .8f)
        assertTrue(PlaylistVisualStore.revision.value > afterPin)
        second.clear("album_playlist")
        assertFalse(first.read("album_playlist").pinned)
    }

    @Test fun densityAndSortSurviveNewStoreInstances() = runTest {
        val store = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        store.setDensity(PlaylistTrackDensity.EXPANDED)
        store.setCatalogSort(PlaylistCatalogSort.TRACKS)
        val restored = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        assertEquals(PlaylistTrackDensity.EXPANDED, restored.readDensity())
        assertEquals(PlaylistCatalogSort.TRACKS, restored.readCatalogSort())
    }

    @Test fun staleOrFakeContentUriNeverBecomesCover() = runTest {
        val store = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        context.getSharedPreferences("michi_settings", 0).edit()
            .putString(playlistVisualKey("a", "cover"), "content://unknown/missing")
            .putString(playlistVisualKey("a", "banner"), "file:///tmp/photo.png")
            .commit()
        assertNull(store.read("a").coverUri)
        assertNull(store.read("a").bannerUri)
    }

    @Test fun invalidEnumsAndFocalFallbackAreSafe() = runTest {
        val store = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        context.getSharedPreferences("michi_settings", 0).edit()
            .putString("ui5_playlist_sort", "OLD_ENUM")
            .putString("ui5_playlist_density", "INVALID")
            .putFloat(playlistVisualKey("a", "focal"), Float.NaN)
            .commit()
        assertEquals(PlaylistCatalogSort.NAME, store.readCatalogSort())
        assertEquals(PlaylistTrackDensity.COMPACT, store.readDensity())
        assertEquals(0.5f, store.read("a").bannerFocalY)
    }
}
