package org.michimusic.player

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var controller: PlayerController
    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        // ExoPlayer can be instantiated in Robolectric
        controller = PlayerController(context)
    }

    @After
    fun teardown() {
        controller.release()
        Dispatchers.resetMain()
    }

    @Test
    fun `playQueue updates state and sets media items`() {
        val tracks = listOf(
            Track(id = "1", title = "T1", artist = "A", filepath = "http://fake.url/1.mp3", duration = 10000L),
            Track(id = "2", title = "T2", artist = "A", filepath = "http://fake.url/2.mp3", duration = 20000L)
        )
        
        controller.playQueue(tracks, 0)
        
        val state = controller.state.value
        assertEquals(2, state.queue.size)
        assertEquals("1", state.currentTrack?.id)
        assertTrue(state.isPlaying)
        assertEquals(0, state.queueIndex)
    }

    @Test
    fun `addToQueue appends track to queue`() {
        val track1 = Track(id = "1", title = "T1", filepath = "http://fake.url/1.mp3")
        controller.playQueue(listOf(track1))
        
        val track2 = Track(id = "2", title = "T2", filepath = "http://fake.url/2.mp3")
        controller.addToQueue(track2)
        
        val state = controller.state.value
        assertEquals(2, state.queue.size)
        assertEquals("2", state.queue[1].id)
    }

    @Test
    fun `clearQueue removes all tracks`() {
        val track1 = Track(id = "1", title = "T1", filepath = "http://fake.url/1.mp3")
        controller.playQueue(listOf(track1))
        
        controller.clearQueue()
        
        val state = controller.state.value
        assertEquals(0, state.queue.size)
        assertEquals(null, state.currentTrack)
    }

    @Test
    fun `setRepeatMode updates exoplayer repeat mode`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        
        // ExoPlayer state is synchronous in Robolectric thread
        assertEquals(Player.REPEAT_MODE_ALL, controller.getExoPlayer().repeatMode)
    }

    @Test
    fun `toggleShuffle updates exoplayer shuffle mode`() {
        val initial = controller.getExoPlayer().shuffleModeEnabled
        controller.toggleShuffle()
        assertEquals(!initial, controller.getExoPlayer().shuffleModeEnabled)
    }
}
