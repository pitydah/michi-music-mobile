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
class PlaylistLaunchHistoryStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("michi_playlist_launch_history", 0).edit().clear().commit()
    }

    @Test fun acceptedPlaylistPersistsForAnotherStoreInstance() = runTest {
        val original = PlaylistLaunchHistoryStore(context, UnconfinedTestDispatcher(testScheduler))
        assertTrue(original.recordAccepted("  id-123 ", 4))
        val recovered = PlaylistLaunchHistoryStore(context, UnconfinedTestDispatcher(testScheduler)).read()
        assertEquals("id-123", recovered?.playlistId)
    }

    @Test fun failedOrEmptyCommandsNeverWriteEvidence() = runTest {
        val store = PlaylistLaunchHistoryStore(context, UnconfinedTestDispatcher(testScheduler))
        assertFalse(store.recordAccepted("", 3))
        assertFalse(store.recordAccepted("valid", 0))
        assertNull(store.read())
    }

    @Test fun invalidAndExpiredEvidenceAreNotDisplayed() = runTest {
        val prefs = context.getSharedPreferences("michi_playlist_launch_history", 0)
        prefs.edit().putString("playlist_id", "x").putLong("accepted_at", 1L).commit()
        assertNull(PlaylistLaunchHistoryStore(context, UnconfinedTestDispatcher(testScheduler)).read())
    }

    @Test fun historyConsentAndLivePlaylistIdentityAreMandatory() {
        val evidence = PlaylistLaunchEvidence("abc", 100L)
        assertNull(validPlaylistContinuityId(evidence, false, mapOf("abc" to 2)))
        assertNull(validPlaylistContinuityId(evidence, true, mapOf("other" to 2)))
        assertNull(validPlaylistContinuityId(evidence, true, mapOf("abc" to 0)))
        assertEquals("abc", validPlaylistContinuityId(evidence, true, mapOf("abc" to 2)))
    }
}
