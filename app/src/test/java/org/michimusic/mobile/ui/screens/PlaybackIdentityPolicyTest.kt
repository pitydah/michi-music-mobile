package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.Track

/**
 * Queue identity is what the user experiences as "the song I tapped". These cases pin the
 * regressions reported by the static audit: a repeated song collapsing onto its first
 * occurrence, a stale index silently playing the first track, and a next-track label that
 * ignores shuffle and repeat.
 */
class PlaybackIdentityPolicyTest {

    private fun track(id: String, title: String = id) = Track(id = id, title = title)

    private val repeatedQueue = listOf(
        track("a", "First"),
        track("b", "Second"),
        track("a", "First"),
        track("c", "Third"),
    )

    @Test
    fun `the same song twice keeps two distinct queue positions`() {
        // Both entries share the id "a"; only the position tells them apart.
        assertEquals(0, resolvedQueueIndex(repeatedQueue.size, 0))
        assertEquals(2, resolvedQueueIndex(repeatedQueue.size, 2))
    }

    @Test
    fun `an unknown position is rejected instead of falling back to the first track`() {
        assertNull(resolvedQueueIndex(repeatedQueue.size, -1))
        assertNull(resolvedQueueIndex(repeatedQueue.size, repeatedQueue.size))
        assertNull(resolvedQueueIndex(0, 0))
    }

    @Test
    fun `the next track follows the real position when a song repeats`() {
        // Position 2 is the second "First": the successor must be "Third", not "Second".
        assertEquals("c", nextQueueTrack(repeatedQueue, 2, REPEAT_MODE_OFF, shuffleEnabled = false)?.id)
        assertEquals("b", nextQueueTrack(repeatedQueue, 0, REPEAT_MODE_OFF, shuffleEnabled = false)?.id)
    }

    @Test
    fun `shuffle never promises a next track`() {
        assertNull(nextQueueTrack(repeatedQueue, 0, REPEAT_MODE_OFF, shuffleEnabled = true))
        assertNull(nextQueueTrack(repeatedQueue, 3, REPEAT_MODE_ALL, shuffleEnabled = true))
    }

    @Test
    fun `repeat follows the media3 values`() {
        // 1 = REPEAT_MODE_ONE, 2 = REPEAT_MODE_ALL.
        assertEquals("c", nextQueueTrack(repeatedQueue, 3, REPEAT_MODE_ONE, shuffleEnabled = false)?.id)
        assertEquals("a", nextQueueTrack(repeatedQueue, 3, REPEAT_MODE_ALL, shuffleEnabled = false)?.id)
        assertNull(nextQueueTrack(repeatedQueue, 3, REPEAT_MODE_OFF, shuffleEnabled = false))
    }

    @Test
    fun `an empty queue or a stale position has no next track`() {
        assertNull(nextQueueTrack(emptyList(), 0, REPEAT_MODE_ALL, shuffleEnabled = false))
        assertNull(nextQueueTrack(repeatedQueue, -1, REPEAT_MODE_ALL, shuffleEnabled = false))
    }

    @Test
    fun `a track that left the filtered list resolves to no position`() {
        val visible = listOf(track("a"), track("b"))
        assertEquals(1, indexOfTrackOrNull(visible, "b"))
        // The filter changed while the action sheet was open.
        assertNull(indexOfTrackOrNull(visible, "c"))
    }

    @Test
    fun `the selected album is kept valid across library changes`() {
        val ids = listOf("album-1", "album-2")
        assertEquals("album-2", reconcileSelectedAlbumId("album-2", ids))
        // The album disappeared in a rescan: fall back so the carousel and the detail agree.
        assertEquals("album-1", reconcileSelectedAlbumId("album-gone", ids))
        assertEquals("album-1", reconcileSelectedAlbumId(null, ids))
        assertNull(reconcileSelectedAlbumId("album-1", emptyList()))
    }
}
