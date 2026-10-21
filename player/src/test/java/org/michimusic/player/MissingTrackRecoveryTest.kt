package org.michimusic.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MissingTrackRecoveryTest {

    @Test
    fun `playback error skips to next media item if available`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        val listenerSlot = slot<Player.Listener>()

        every { player.addListener(capture(listenerSlot)) } returns Unit
        every { player.hasNextMediaItem() } returns true

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        val listener = listenerSlot.captured
        val error = PlaybackException("File not found", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        listener.onPlayerError(error)

        verify { player.seekToNextMediaItem() }
        verify { player.prepare() }
        verify { player.play() }
    }

    @Test
    fun `playback error does not skip if no next media item`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        val listenerSlot = slot<Player.Listener>()

        every { player.addListener(capture(listenerSlot)) } returns Unit
        every { player.hasNextMediaItem() } returns false

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        val listener = listenerSlot.captured
        val error = PlaybackException("File not found", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        listener.onPlayerError(error)

        verify(exactly = 0) { player.seekToNextMediaItem() }
    }
}
