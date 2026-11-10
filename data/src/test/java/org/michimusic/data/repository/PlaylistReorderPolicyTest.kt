package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistReorderPolicyTest {
    @Test fun firstToLastDoesNotLoseUnresolvedSlots() {
        assertEquals(
            listOf("B", "remote-unresolved", "C", "A"),
            reorderedVisiblePlaylistIds(
                listOf("A", "remote-unresolved", "B", "C"),
                listOf("A", "B", "C"), 0, 2,
            ),
        )
    }

    @Test fun lastToFirstMovesOnlyVisibleTracks() {
        assertEquals(
            listOf("C", "x", "A", "B"),
            reorderedVisiblePlaylistIds(listOf("A", "x", "B", "C"), listOf("A", "B", "C"), 2, 0),
        )
    }

    @Test fun staleOrderAndInvalidPositionAreRejected() {
        assertNull(reorderedVisiblePlaylistIds(listOf("B", "A"), listOf("A", "B"), 0, 1))
        assertNull(reorderedVisiblePlaylistIds(listOf("A", "B"), listOf("A", "B"), 0, 3))
        assertNull(reorderedVisiblePlaylistIds(listOf("A"), emptyList(), 0, 0))
    }

    @Test fun ambiguousIdentityIsRejected() {
        assertNull(reorderedVisiblePlaylistIds(listOf("A", "A"), listOf("A", "A"), 0, 1))
    }

    @Test fun noMoveLeavesInputUnchanged() {
        val raw = listOf("A", "x", "B")
        assertEquals(raw, reorderedVisiblePlaylistIds(raw, listOf("A", "B"), 0, 0))
        assertEquals(listOf("A", "x", "B"), raw)
    }
}
