package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.Track

class AlbumPlaybackPolicyTest {
    private val tracks = listOf(
        Track(id = "missing", title = "Track 1"),
        Track(id = "b", title = "Track 2"),
        Track(id = "c", title = "Track 3"),
        Track(id = "c", title = "Duplicate Track 3"),
    )

    @Test fun albumCtaStartsWithFirstAvailableTrack() {
        val selection = requireNotNull(albumQueueSelection(tracks, setOf("b", "c")))
        assertEquals(listOf("b", "c"), selection.tracks.map { it.id })
        assertEquals(0, selection.startIndex)
    }

    @Test fun shuffledAlbumIsAvailableEvenWhenFirstTrackIsNot() {
        val selection = requireNotNull(albumQueueSelection(tracks, setOf("b", "c"), shuffle = true))
        assertEquals(setOf("b", "c"), selection.tracks.map { it.id }.toSet())
        assertEquals(2, selection.tracks.size)
        assertEquals(0, selection.startIndex)
    }

    @Test fun selectedTrackKeepsItsExactIdentityAndIndex() {
        val selection = requireNotNull(albumQueueSelection(tracks, setOf("b", "c"), "c"))
        assertEquals("c", selection.tracks[selection.startIndex].id)
        assertEquals(1, selection.startIndex)
        assertNull(albumQueueSelection(tracks, setOf("b", "c"), "missing"))
        assertNull(albumQueueSelection(tracks, setOf("b", "c"), "not-in-album"))
    }

    @Test fun noPlayableTracksDoesNotStartAnUnrelatedQueue() {
        assertNull(albumQueueSelection(tracks, emptySet()))
        assertNull(albumQueueSelection(emptyList(), setOf("b")))
    }
}
