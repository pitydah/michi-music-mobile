package org.michimusic.player

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Reproduces the real regression end-to-end through the actual production entry points.
// LibraryProvider is constructed and NEVER manually refreshed anywhere in this test - no
// provider.refresh() call as an artificial precondition - exactly like a fresh
// MichiPlaybackService process where onGetLibraryRoot/onPlaybackResumption never fire
// because AudioController only ever connects a plain MediaController, never a MediaBrowser.
// Before this fix, onSetMediaItems/onAddMediaItems read LibraryProvider.cachedTracks as-is
// (emptyList()) and every queued track was silently dropped (resolved=0), reproducing the
// exact on-device bug.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MichiMediaLibrarySessionCallbackTest {

    private class FakeLocalMediaRepository(private val tracks: List<Track>) : LocalMediaRepository() {
        override suspend fun loadTracks(): List<Track> = tracks
    }

    private fun buildCallback(tracks: List<Track>): MichiMediaLibrarySessionCallback {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // No provider.refresh() call here - loading must come exclusively from the real
        // production path (onSetMediaItems/onAddMediaItems -> LibraryProvider.ensureLoaded()).
        val provider = LibraryProvider(context, FakeLocalMediaRepository(tracks))
        val stateStore = mockk<PlaybackStateStore>(relaxed = true)
        // Unconfined: the fakes never truly suspend, so the callback's internal
        // scope.launch{} runs to completion synchronously and the returned future is
        // already done by the time onSetMediaItems/onAddMediaItems returns - no manual
        // coroutine-scheduler advancing required.
        return MichiMediaLibrarySessionCallback(provider, stateStore, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun `onSetMediaItems resolves a local track even though LibraryProvider was never manually refreshed`() {
        val track = Track(
            id = "local_1000258014",
            title = "Test Song",
            filepath = "content://media/external/audio/media/1000258014",
        )
        val callback = buildCallback(listOf(track))
        val queuedItem = MediaItem.Builder()
            .setMediaId(LibraryProvider.buildSongId(track.id))
            .setUri(track.filepath)
            .build()

        val future = callback.onSetMediaItems(
            mockk<MediaSession>(relaxed = true),
            mockk<MediaSession.ControllerInfo>(relaxed = true),
            listOf(queuedItem),
            0,
            0L,
        )

        assertTrue("onSetMediaItems must resolve synchronously under the Unconfined test scope", future.isDone)
        val result = future.get()
        assertEquals(1, result.mediaItems.size)
        assertEquals("song/local_1000258014", result.mediaItems.single().mediaId)
    }

    @Test
    fun `onSetMediaItems resolves a full two-track queue without dropping any item`() {
        val tracks = listOf(
            Track(id = "local_1000258014", title = "Song A", filepath = "content://media/external/audio/media/1000258014"),
            Track(id = "local_1000380277", title = "Song B", filepath = "content://media/external/audio/media/1000380277"),
        )
        val callback = buildCallback(tracks)
        val queuedItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(LibraryProvider.buildSongId(track.id))
                .setUri(track.filepath)
                .build()
        }

        val future = callback.onSetMediaItems(
            mockk<MediaSession>(relaxed = true),
            mockk<MediaSession.ControllerInfo>(relaxed = true),
            queuedItems,
            0,
            0L,
        )

        val result = future.get()
        assertEquals(2, result.mediaItems.size)
        assertEquals(
            listOf("local_1000258014", "local_1000380277"),
            result.mediaItems.map { LibraryProvider.extractSongId(it.mediaId) },
        )
    }

    @Test
    fun `onAddMediaItems resolves a local track even though LibraryProvider was never manually refreshed`() {
        val track = Track(
            id = "local_999",
            title = "Another Song",
            filepath = "content://media/external/audio/media/999",
        )
        val callback = buildCallback(listOf(track))
        val queuedItem = MediaItem.Builder()
            .setMediaId(LibraryProvider.buildSongId(track.id))
            .setUri(track.filepath)
            .build()

        val future = callback.onAddMediaItems(
            mockk<MediaSession>(relaxed = true),
            mockk<MediaSession.ControllerInfo>(relaxed = true),
            listOf(queuedItem),
        )

        assertTrue(future.isDone)
        val result = future.get()
        assertEquals(1, result.size)
        assertEquals("song/local_999", result.single().mediaId)
    }
}
