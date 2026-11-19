package org.michimusic.mobile.ui.screens

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Two windows/editors must never silently overwrite a newer appearance. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UI8PlaylistVisualConflictTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        context.getSharedPreferences("michi_settings", 0).edit().clear().commit()
    }

    @After fun reset() {
        context.getSharedPreferences("michi_settings", 0).edit().clear().commit()
    }

    @Test fun staleDraftCannotRewriteNewerCrop() = runTest {
        val storeA = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        val storeB = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        val baseline = storeA.read("p1")
        assertTrue(storeB.setVisualAdjustment("p1", "cover", 0.8f, 0.2f, 1.5f))
        val result = storeA.applyVisualEdit("p1", PlaylistVisualEditRequest(
            expectedVisual = baseline, coverFocalX = 0.1f, bannerScale = 2f,
        ))
        assertEquals(PlaylistVisualEditResult.Conflict, result)
        assertEquals(0.8f, storeA.read("p1").coverFocalX, 0.001f)
        assertEquals(1.5f, storeA.read("p1").coverScale, 0.001f)
        assertEquals(1f, storeA.read("p1").bannerScale, 0.001f)
    }

    @Test fun pinChangeDoesNotInvalidateVisualDraft() = runTest {
        val storeA = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        val storeB = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        val baseline = storeA.read("p1")
        storeB.setPinned("p1", true)
        val result = storeA.applyVisualEdit("p1", PlaylistVisualEditRequest(
            expectedVisual = baseline, bannerScale = 1.4f,
        ))
        assertEquals(PlaylistVisualEditResult.Success, result)
        assertTrue(storeA.read("p1").pinned)
        assertEquals(1.4f, storeB.read("p1").bannerScale, 0.001f)
    }

    @Test fun removingBothImagesCanResetAllCropParametersAtomically() = runTest {
        val store = PlaylistVisualStore(context, UnconfinedTestDispatcher(testScheduler))
        assertTrue(store.setVisualAdjustment("p1", "cover", 0.8f, 0.2f, 1.5f))
        assertTrue(store.setVisualAdjustment("p1", "banner", 0.1f, 0.9f, 2f))
        val prior = store.read("p1")
        val result = store.applyVisualEdit("p1", PlaylistVisualEditRequest(
            expectedVisual = prior,
            cover = PlaylistVisualImageChange.Remove,
            banner = PlaylistVisualImageChange.Remove,
            coverFocalX = 0.5f, coverFocalY = 0.5f, coverScale = 1f,
            bannerFocalX = 0.5f, bannerFocalY = 0.5f, bannerScale = 1f,
        ))
        assertEquals(PlaylistVisualEditResult.Success, result)
        val after = store.read("p1")
        assertEquals(null, after.coverUri)
        assertEquals(null, after.bannerUri)
        assertEquals(0.5f, after.coverFocalX, 0.001f)
        assertEquals(0.5f, after.coverFocalY, 0.001f)
        assertEquals(1f, after.coverScale, 0.001f)
        assertEquals(0.5f, after.bannerFocalX, 0.001f)
        assertEquals(0.5f, after.bannerFocalY, 0.001f)
        assertEquals(1f, after.bannerScale, 0.001f)
    }

    @Test fun baselinePredicateIgnoresPinningButRejectsActualChanges() {
        val base = PlaylistVisualState(coverFocalX = 0.4f)
        assertTrue(playlistVisualBaselineMatches(base, base.copy(pinned = true)))
        assertTrue(!playlistVisualBaselineMatches(base, base.copy(coverFocalY = 0.9f)))
        assertTrue(!playlistVisualBaselineMatches(base, base.copy(bannerUri = "content://other")))
    }
}
