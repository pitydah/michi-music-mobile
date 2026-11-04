package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.Track

class ArtistPlaybackPolicyTest {
    private val tracks = listOf(Track("first", "One"), Track("middle", "Two"), Track("last", "Three"))

    @Test fun exactQueueIndicesForFirstMiddleLast() {
        assertEquals(0, artistQueueSelection(tracks, "first", setOf("first", "middle", "last"))?.index)
        assertEquals(1, artistQueueSelection(tracks, "middle", setOf("first", "middle", "last"))?.index)
        assertEquals(2, artistQueueSelection(tracks, "last", setOf("first", "middle", "last"))?.index)
    }
    @Test fun missingTrackDoesNotPlayAnotherTrack() {
        assertNull(artistQueueSelection(tracks, "missing", setOf("first", "middle", "last")))
        assertNull(artistQueueSelection(tracks, "", setOf("first", "middle", "last")))
    }
    @Test fun indexTracksIdentityAfterReorder() {
        val shuffled = listOf(tracks[2], tracks[0], tracks[1])
        assertEquals(0, artistQueueSelection(shuffled, "last", setOf("first", "middle", "last"))?.index)
        assertEquals("last", artistQueueSelection(shuffled, "last", setOf("first", "middle", "last"))?.tracks?.first()?.id)
    }
    @Test fun offlineTracksAreExcludedWithoutReorderingQueue() {
        val selection = artistQueueSelection(tracks, "last", setOf("first", "last"))!!
        assertEquals(listOf("first", "last"), selection.tracks.map { it.id })
        assertEquals(1, selection.index)
    }
    @Test fun unavailableSelectedTrackNeverFallsBackToAnotherSong() {
        assertNull(artistQueueSelection(tracks, "middle", setOf("first", "last")))
        assertNull(artistQueueSelection(tracks, "missing", setOf("first")))
    }
    @Test fun playAllSkipsUnavailableItemsAndStartsAtFirstEligible() {
        val selection = artistFirstPlayableSelection(tracks, setOf("middle", "last"))!!
        assertEquals(listOf("middle", "last"), selection.tracks.map { it.id })
        assertEquals(0, selection.index)
    }
    @Test fun entireUnavailableAlbumHasNoPlaybackAction() {
        assertNull(artistFirstPlayableSelection(tracks, emptySet()))
        assertEquals(emptyList<Track>(), artistPlayableQueue(tracks, emptySet()))
    }
    @Test fun duplicateIdentityCannotShiftSelectedIndex() {
        val repeated = listOf(tracks[0], tracks[0], tracks[2])
        val selection = artistQueueSelection(repeated, "last", setOf("first", "last"))!!
        assertEquals(listOf("first", "last"), selection.tracks.map { it.id })
        assertEquals(1, selection.index)
    }
    @Test fun unavailableFirstSongDoesNotForceStartIndexZeroWrongId() {
        val selection = artistQueueSelection(tracks, "last", setOf("last"))!!
        assertEquals(0, selection.index)
        assertEquals("last", selection.tracks.single().id)
    }
}
