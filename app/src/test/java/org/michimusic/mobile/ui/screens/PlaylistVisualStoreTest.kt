package org.michimusic.mobile.ui.screens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Before fun clear() {
        context.getSharedPreferences("michi_settings", 0).edit().clear().commit()
        File(context.filesDir, PLAYLIST_PRIVATE_IMAGE_DIRECTORY).deleteRecursively()
    }

    @After fun cleanPrivateCopies() {
        File(context.filesDir, PLAYLIST_PRIVATE_IMAGE_DIRECTORY).deleteRecursively()
    }

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

    @Test fun coverAndBannerAdjustmentsUseIndependentFields() = runTest {
        val store = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        assertTrue(store.setVisualAdjustment("a", "cover", 0.1f, 0.9f, 2.5f))
        assertTrue(store.setVisualAdjustment("a", "banner", 0.8f, 0.2f, 1.4f))
        val loaded = store.read("a")
        assertEquals(0.1f, loaded.coverFocalX, 0f)
        assertEquals(0.9f, loaded.coverFocalY, 0f)
        assertEquals(2.5f, loaded.coverScale, 0f)
        assertEquals(0.8f, loaded.bannerFocalX, 0f)
        assertEquals(0.2f, loaded.bannerFocalY, 0f)
        assertEquals(1.4f, loaded.bannerScale, 0f)

        assertEquals(0.5f, store.read("b").bannerFocalY, 0f)
        // Removing the cover image does not rewrite banner metadata.
        assertTrue(store.saveImage("a", "cover", null))
        val afterRemove = store.read("a")
        assertEquals(0.8f, afterRemove.bannerFocalX, 0f)
        assertEquals(0.2f, afterRemove.bannerFocalY, 0f)
        assertEquals(1.4f, afterRemove.bannerScale, 0f)
    }

    @Test fun legacyFocalKeyRemainsReadableUntilNewBannerKeyExists() = runTest {
        val prefs = context.getSharedPreferences("michi_settings", 0)
        prefs.edit().putFloat(playlistVisualKey("a", "focal"), 0.8f).commit()
        val store = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        assertEquals(0.8f, store.read("a").bannerFocalY, 0f)
        prefs.edit().putFloat(playlistVisualKey("a", "banner_focal_y"), 0.25f).commit()
        assertEquals(0.25f, store.read("a").bannerFocalY, 0f)
    }

    @Test fun clearDeletesOnlyThisPlaylistsPrivateCopiesAndKeepsSharedSources() = runTest {
        val importer = PlaylistPrivateImageImporter(context)
        val p1Cover = importPng(importer, "p1", "cover")
        val p1Banner = importPng(importer, "p1", "banner")
        val p2Cover = importPng(importer, "p2", "cover")
        val prefs = context.getSharedPreferences("michi_settings", 0)
        prefs.edit()
            .putString(playlistVisualKey("p1", "cover"), p1Cover.token)
            .putString(playlistVisualKey("p1", "banner"), p1Banner.token)
            // A foreign playlist token placed in p1's slot must NOT be deleted
            // by p1's owner filter, even if a corrupt write referenced it.
            .putString(playlistVisualKey("p2", "cover"), p2Cover.token)
            .commit()
        val store = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        assertTrue(store.clear("p1"))
        assertNull(importer.resolve(p1Cover.token))
        assertNull(importer.resolve(p1Banner.token))
        assertNotNull(importer.resolve(p2Cover.token))
        assertTrue(store.clear("p2"))
        assertNull(importer.resolve(p2Cover.token))
    }

    @Test fun clearDoesNotDeleteAnotherPlaylistsCopyReferencedFromThisOne() = runTest {
        val importer = PlaylistPrivateImageImporter(context)
        val foreign = importPng(importer, "p2", "cover")
        context.getSharedPreferences("michi_settings", 0).edit()
            .putString(playlistVisualKey("p1", "cover"), foreign.token)
            .putString(playlistVisualKey("p2", "cover"), foreign.token)
            .commit()
        val store = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        store.clear("p1")
        assertNotNull(importer.resolve(foreign.token))
        store.clear("p2")
    }

    private fun importPng(
        importer: PlaylistPrivateImageImporter,
        id: String,
        field: String,
    ): PlaylistImageImportResult.Success {
        val bytes = PlaylistTestImages.png64x32
        return importer.importFromStream(
            id, field, "image/png", bytes.size.toLong(), ByteArrayInputStream(bytes),
        ) as PlaylistImageImportResult.Success
    }
}
