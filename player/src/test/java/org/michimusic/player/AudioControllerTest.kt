package org.michimusic.player

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Regression tests for the mediaId contract between AudioController (the session client used
// by every screen that plays local media: AlbumsScreen, HomeScreen, SearchScreen, etc.) and
// LibraryProvider.resolveForPlayback (running inside MichiMediaLibrarySessionCallback, hosted
// by MichiPlaybackService). AudioController used to send the raw Track.id as mediaId;
// LibraryProvider only recognizes ids built with LibraryProvider.buildSongId(), so every
// queue was silently discarded (resolved=0) and ExoPlayer never received anything to play -
// confirmed on-device via the MichiPlaybackDebug logs.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioControllerTest {

    private fun controllerWithMockedSession(mediaController: MediaController): AudioController {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val audioController = AudioController(context, scope)
        // AudioController only builds MediaItems once its private `mediaController` is
        // non-null (post MediaController.Builder(...).buildAsync() connection). Injecting the
        // mock via reflection exercises the real playQueue()/addToQueue() code paths without
        // standing up a real bound MediaLibraryService connection.
        val field = AudioController::class.java.getDeclaredField("mediaController")
        field.isAccessible = true
        field.set(audioController, mediaController)
        return audioController
    }

    @Test
    fun `playQueue sends the canonical LibraryProvider song mediaId, not the raw track id`() {
        val mediaControllerMock = mockk<MediaController>(relaxed = true)
        val audioController = controllerWithMockedSession(mediaControllerMock)
        val track = Track(id = "local_1000258014", title = "Test Song")

        audioController.playQueue(listOf(track))

        val itemsSlot = slot<List<MediaItem>>()
        verify { mediaControllerMock.setMediaItems(capture(itemsSlot), 0, 0L) }

        val sentMediaId = itemsSlot.captured.single().mediaId
        assertEquals(LibraryProvider.buildSongId("local_1000258014"), sentMediaId)
        assertEquals("song/local_1000258014", sentMediaId)
        // This is exactly the format LibraryProvider.resolveForPlayback/extractSongId expect;
        // sending track.id directly (the pre-fix behavior) would make this null.
        assertEquals("local_1000258014", LibraryProvider.extractSongId(sentMediaId))
    }

    @Test
    fun `addToQueue also sends the canonical LibraryProvider song mediaId`() {
        val mediaControllerMock = mockk<MediaController>(relaxed = true)
        val audioController = controllerWithMockedSession(mediaControllerMock)
        val track = Track(id = "local_999", title = "Another Song")

        audioController.addToQueue(track)

        val itemSlot = slot<MediaItem>()
        verify { mediaControllerMock.addMediaItem(capture(itemSlot)) }

        assertEquals("song/local_999", itemSlot.captured.mediaId)
    }
}
