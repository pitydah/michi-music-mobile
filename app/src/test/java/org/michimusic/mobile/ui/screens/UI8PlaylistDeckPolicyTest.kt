package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure gesture and visual-order contract; playback/library order never changes. */
class UI8PlaylistDeckPolicyTest {
    @Test fun horizontalCommitRejectsVerticalAndDiagonalDrags() {
        assertFalse(PlaylistDeckPolicy.shouldCommit(79f, 0f, 400f, 300f))
        assertTrue(PlaylistDeckPolicy.shouldCommit(80f, 0f, 400f, 300f))
        assertTrue(PlaylistDeckPolicy.shouldCommit(-80f, 0f, 400f, 300f))
        assertFalse(PlaylistDeckPolicy.shouldCommit(0f, 72f, 400f, 300f))
        assertFalse(PlaylistDeckPolicy.shouldCommit(80f, 100f, 400f, 300f))
        assertTrue(PlaylistDeckPolicy.shouldCommit(100f, 35f, 400f, 300f))
    }

    @Test fun invalidTouchCoordinatesNeverAdvance() {
        assertFalse(PlaylistDeckPolicy.shouldCommit(Float.NaN, 0f, 400f, 300f))
        assertFalse(PlaylistDeckPolicy.shouldCommit(Float.POSITIVE_INFINITY, 0f, 400f, 300f))
        assertFalse(PlaylistDeckPolicy.shouldCommit(999f, 0f, 0f, 300f))
        assertFalse(PlaylistDeckPolicy.shouldCommit(999f, 0f, -400f, 300f))
    }

    @Test fun oneAndDisabledDoNotAdvance() {
        assertEquals(listOf("a"), PlaylistDeckPolicy.advance(listOf("a"), "a", enabled = true))
        assertEquals(listOf("a", "b"), PlaylistDeckPolicy.advance(listOf("a", "b"), "a", enabled = false))
        assertFalse(PlaylistDeckPolicy.canAdvance(listOf("a", "b"), "b", enabled = true))
    }

    @Test fun staleCallbackCannotRotateNewFront() {
        val changed = listOf("c", "a", "b")
        assertEquals(changed, PlaylistDeckPolicy.advance(changed, "a", enabled = true))
    }

    @Test fun reverseActionCyclesBackWithoutMutatingSource() {
        val original = listOf("a", "b", "c")
        val previous = PlaylistDeckPolicy.retreat(original, "a", enabled = true)
        assertEquals(listOf("c", "a", "b"), previous)
        assertEquals(original, PlaylistDeckPolicy.advance(previous, "c", enabled = true))
        assertEquals(listOf("a", "b", "c"), original)
    }

    @Test fun disabledAndStaleReverseCannotChangeDeck() {
        val original = listOf("a", "b", "c")
        assertEquals(original, PlaylistDeckPolicy.retreat(original, "a", enabled = false))
        assertEquals(original, PlaylistDeckPolicy.retreat(original, "c", enabled = true))
        assertEquals(listOf("a"), PlaylistDeckPolicy.retreat(listOf("a"), "a", enabled = true))
    }

    @Test fun circularRotationDoesNotMutateSource() {
        val original = listOf("a", "b", "c")
        val second = PlaylistDeckPolicy.advance(original, "a", enabled = true)
        val third = PlaylistDeckPolicy.advance(second, "b", enabled = true)
        val reset = PlaylistDeckPolicy.advance(third, "c", enabled = true)
        assertEquals(listOf("b", "c", "a"), second)
        assertEquals(listOf("c", "a", "b"), third)
        assertEquals(original, reset)
        assertEquals(listOf("a", "b", "c"), original)
    }
}
