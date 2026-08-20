package org.michimusic.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Reproduces, and guards against regressing, the on-device crash:
//   java.lang.IllegalStateException: Player is accessed on the wrong thread.
//   Current thread: 'DefaultDispatcher-worker-1'  Expected thread: 'main'
//   at MichiPlaybackService.saveNow (via deferSave's saveScope, which runs on Dispatchers.IO)
//
// Root cause: deferSave() scheduled saveNow() on saveScope (Dispatchers.IO), and saveNow()
// read ExoPlayer properties (mediaItemCount, currentPosition, etc.) directly - a violation of
// Media3's "Player must only be touched from its application thread" contract. The fix splits
// saveNow() into snapshotState() (the only place that reads the Player - must run on the main/
// application thread) and persistence (stateStore.save(), fine off-main). deferSave() now
// wraps the snapshot in withContext(Dispatchers.Main) before persisting on the IO-scoped
// saveScope.
//
// These tests use reflection to reach MichiPlaybackService's private members - the same
// approach already used for AudioController's mediaController - rather than widening the
// class's visibility just to make it testable.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MichiPlaybackServiceThreadingTest {

    private fun Any.callPrivate(name: String): Any? {
        val method = javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        return try {
            method.invoke(this)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Reflection wraps the invoked method's real exception - unwrap it so callers see
            // the actual IllegalStateException ExoPlayer throws, not the reflection wrapper.
            throw e.cause ?: e
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.readPrivateField(name: String): T {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as T
    }

    private fun buildStartedService(): MichiPlaybackService {
        val controller = Robolectric.buildService(MichiPlaybackService::class.java)
        controller.create()
        val service = controller.get()
        val player = service.readPrivateField<ExoPlayer>("player")
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(LibraryProvider.buildSongId("local_1"))
                .setUri("content://media/external/audio/media/1")
                .build(),
        )
        player.prepare()
        return service
    }

    @Test
    fun `snapshotState reads the Player correctly when called from its own application thread`() {
        val service = buildStartedService()

        val state = service.callPrivate("snapshotState") as PlaybackStateStore.SavedState?

        assertNotNull("snapshotState must succeed on the thread that built the Player", state)
        assertEquals(1, state!!.mediaIds.size)
        assertEquals(LibraryProvider.buildSongId("local_1"), state.mediaIds.single())
    }

    @Test
    fun `snapshotState throws off the application thread, reproducing the exact crash mechanism`() {
        val service = buildStartedService()

        var caught: Throwable? = null
        val thread = Thread {
            try {
                service.callPrivate("snapshotState")
            } catch (t: Throwable) {
                caught = t
            }
        }
        thread.start()
        thread.join(5_000)

        assertTrue(
            "Reading the Player off its application thread must fail exactly like the on-device crash " +
                "(IllegalStateException from ExoPlayerImpl.verifyApplicationThread) - this is why " +
                "deferSave() must hop back to Dispatchers.Main before calling snapshotState()",
            caught is IllegalStateException,
        )
    }

    @Test
    fun `deferSave schedules the save on saveScope without touching the Player synchronously on this thread`() {
        val service = buildStartedService()

        // deferSave() itself must return immediately (it only launches a coroutine that
        // delays 3s before doing anything) - it must not eagerly read the Player on whatever
        // thread calls it, and scheduling it must not immediately fail.
        service.callPrivate("deferSave")

        val saveJob = service.readPrivateField<Job>("saveJob")

        assertFalse("deferSave's job must not fail before its delay even elapses", saveJob.isCancelled)
        assertFalse("deferSave's job must not already be done - it is still waiting on delay(3000)", saveJob.isCompleted)
    }
}
