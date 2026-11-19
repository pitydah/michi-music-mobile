package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.mobile.screens.buildSearchCatalogSnapshot

class UI8SearchPlaybackIdentityTest {
    private fun track(id: String, owner: String, location: String, source: TrackSource = TrackSource.SYNCED) =
        Track(id = id, title = "Same name", artist = "Same artist", album = "Same album",
            filepath = location, source = source, sourceId = owner)

    @Test fun identicalServerLocalIdsDoNotCollapseDistinctLibraries() {
        val first = track("42", "server-A", "music/42.flac")
        val second = track("42", "server-B", "music/42.flac")
        val catalog = buildSearchCatalogSnapshot(emptyList(), listOf(first, second))
        assertEquals(2, catalog.size)
        assertEquals(1, safeSearchQueueTrackIndex(catalog, second))
    }

    @Test fun sameIdFromLocalAndSyncedAreIndependentlySelectable() {
        val local = track("42", "", "music/42.flac", TrackSource.LOCAL)
        val synced = track("42", "server-A", "music/42.flac")
        val catalog = buildSearchCatalogSnapshot(listOf(local), listOf(synced))
        assertEquals(2, catalog.size)
        assertEquals(0, safeSearchQueueTrackIndex(catalog, local))
        assertEquals(1, safeSearchQueueTrackIndex(catalog, synced))
    }

    @Test fun distinctLocationsUnderOneOwnerStayDistinct() {
        val one = track("42", "server-A", "album/disc1.flac")
        val two = track("42", "server-A", "album/disc2.flac")
        assertEquals(2, buildSearchCatalogSnapshot(emptyList(), listOf(one, two)).size)
        assertEquals(1, safeSearchQueueTrackIndex(listOf(one, two), two))
    }

    @Test fun staleDeletedTrackDoesNotQueueAnyOtherMatchingId() {
        val old = track("42", "old-server", "music/42.flac")
        val current = track("42", "new-server", "music/42.flac")
        assertNull(safeSearchQueueTrackIndex(listOf(current), old))
    }

    @Test fun duplicateExactIdentityFailsClosedEvenIfMetadataDiffers() {
        val original = track("42", "server-A", "music/42.flac")
        val duplicate = original.copy(title = "Renamed metadata")
        assertNull(safeSearchQueueTrackIndex(listOf(original, duplicate), original))
        assertEquals(1, buildSearchCatalogSnapshot(emptyList(), listOf(original, duplicate)).size)
    }

    @Test fun playingIndicatorNeverHighlightsSameIdFromAnotherLibrary() {
        val active = track("42", "server-A", "music/42.flac")
        val another = track("42", "server-B", "music/42.flac")
        assertTrue(sameSearchTrackIdentity(active, active.copy(title = "Updated title")))
        assertFalse(sameSearchTrackIdentity(active, another))
        assertFalse(sameSearchTrackIdentity(null, active))
    }

    @Test fun emptyQueueAndMissingTrackAreNonPlayable() {
        val candidate = track("42", "server-A", "music/42.flac")
        assertNull(safeSearchQueueTrackIndex(emptyList(), candidate))
        assertNull(safeSearchQueueTrackIndex(listOf(track("43", "server-A", "music/43.flac")), candidate))
    }
}
