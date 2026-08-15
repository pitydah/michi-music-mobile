package org.michimusic.player

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackStateStoreTest {

    private lateinit var store: PlaybackStateStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = PlaybackStateStore(context)
        store.clear()
    }

    @Test
    fun `save and restore empty state`() {
        val state = store.restore()
        assertTrue(state.mediaIds.isEmpty())
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun `save and restore populated state`() {
        val original = PlaybackStateStore.SavedState(
            mediaIds = listOf("t1", "t2", "t3"),
            startIndex = 1,
            positionMs = 15000L,
            repeatMode = Player.REPEAT_MODE_ALL,
            shuffleMode = true
        )
        
        store.save(original)
        
        val restored = store.restore()
        
        assertEquals(listOf("t1", "t2", "t3"), restored.mediaIds)
        assertEquals(1, restored.startIndex)
        assertEquals(15000L, restored.positionMs)
        assertEquals(Player.REPEAT_MODE_ALL, restored.repeatMode)
        assertEquals(true, restored.shuffleMode)
    }

    @Test
    fun `clear removes state`() {
        store.save(PlaybackStateStore.SavedState(mediaIds = listOf("t1")))
        
        store.clear()
        
        val restored = store.restore()
        assertTrue(restored.mediaIds.isEmpty())
    }
}
