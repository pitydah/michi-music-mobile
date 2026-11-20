package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.mobile.screens.trackSearchMatches
import org.michimusic.mobile.screens.trackSearchRank
import org.michimusic.mobile.screens.groupSearchAlbums

class UI8SearchContractTest {
    private val cure = Track(id = "cure-burn", title = "Burn", artist = "The Cure", album = "The Crow")

    @Test fun artistAndAlbumQueriesReturnSongsAndPermitOnlyCurrentActions() {
        assertTrue(trackSearchMatches(cure, "the cure"))
        assertTrue(trackSearchMatches(cure, " THÉ Cure "))
        assertTrue(trackSearchMatches(cure, "the crow"))
        assertTrue(searchTrackActionPermitted("the cure", "TRACKS", cure))
        assertTrue(searchTrackActionPermitted("the crow", "ALL", cure))
        assertFalse(searchTrackActionPermitted("the cure", "ARTISTS", cure))
        assertFalse(searchTrackActionPermitted("unrelated", "ALL", cure))
    }

    @Test fun songTitleRanksAheadOfRelatedArtistMatch() {
        val exact = Track(id = "song", title = "The Cure", artist = "Other")
        assertTrue(trackSearchRank(exact, "the cure") < trackSearchRank(cure, "the cure"))
    }

    @Test fun albumGroupingDoesNotCollideOnDelimiterInRealTags() {
        val a = Track(id = "a", title = "A", album = "A___B", artist = "C")
        val b = Track(id = "b", title = "B", album = "A", artist = "B___C")
        val groups = groupSearchAlbums(listOf(a, b))
        assertEquals(2, groups.size)
        assertEquals(listOf(a), groups["A___B" to "C"])
        assertEquals(listOf(b), groups["A" to "B___C"])
    }

    @Test fun epochGuardStillRejectsSameQueryFromOldGeneration() {
        assertFalse(searchSnapshotCurrent("the cure", "TRACKS", 3L, "the cure", "TRACKS", 4L))
        assertTrue(searchSnapshotCurrent("the cure", "TRACKS", 4L, "the cure", "TRACKS", 4L))
    }
}
