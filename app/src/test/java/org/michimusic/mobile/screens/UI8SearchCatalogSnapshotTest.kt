package org.michimusic.mobile.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

class UI8SearchCatalogSnapshotTest {
    @Test fun preservesDistinctLocationsAndSourcesEvenWithSameMetadata() {
        val local = Track(id = "42", title = "Same", artist = "A", album = "B",
            filepath = "content://music/42", source = TrackSource.LOCAL)
        val anotherLocation = local.copy(filepath = "content://other/42")
        val synced = local.copy(filepath = "file://cache/42", source = TrackSource.SYNCED)
        val catalog = buildSearchCatalogSnapshot(
            listOf(local, local, anotherLocation), listOf(synced, synced),
        )
        assertEquals(listOf(local, anotherLocation, synced), catalog)
    }

    @Test fun preservesTrackOrderingAndProducesIndependentSnapshot() {
        val a = Track(id = "a", title = "A")
        val b = Track(id = "b", title = "B")
        val input = mutableListOf(a, b)
        val result = buildSearchCatalogSnapshot(input, emptyList())
        input.clear()
        assertEquals(listOf(a, b), result)
        assertNotSame(input, result)
    }

    @Test fun preservesOwningLibraryWhenServerLocalIdsAndPathsCollide() {
        val a = Track(id = "same", title = "Original", source = TrackSource.SYNCED,
            sourceId = "micro-a", filepath = "file://sync/song.flac")
        val b = a.copy(sourceId = "micro-b")
        val snapshot = buildSearchCatalogSnapshot(emptyList(), listOf(a, a, b, b))
        assertEquals(listOf(a, b), snapshot)
    }

    @Test fun refreshPreservesLocalRowsAndReplacesOnlySyncedOwners() {
        val local = Track(id = "42", title = "Local", source = TrackSource.LOCAL,
            filepath = "content://local/42")
        val oldPeer = Track(id = "42", title = "Old", source = TrackSource.SYNCED,
            sourceId = "micro-1", filepath = "file://cache/42")
        val newPeer = oldPeer.copy(title = "New", sourceId = "micro-2")
        val old = SearchCatalogLoad.Ready(3L, listOf(local, oldPeer))
        assertEquals(listOf(local, oldPeer, newPeer),
            replaceSearchSyncedTracks(old, listOf(oldPeer, newPeer)))
        assertEquals(listOf(local), replaceSearchSyncedTracks(old, emptyList()))
        // The old published snapshot remains immutable for in-flight readers.
        assertEquals(listOf(local, oldPeer), old.tracks)
    }

    @Test fun largeCatalogPreservesSourceOrderAndRemovesExactDuplicates() {
        val original = List(10_000) { i -> Track(id = "id-$i", title = "T$i",
            filepath = "content://songs/$i", source = TrackSource.LOCAL) }
        val repeats = original.filterIndexed { i, _ -> i % 3 == 0 }
        val snapshot = buildSearchCatalogSnapshot(original, repeats)
        assertEquals(10_000, snapshot.size)
        assertEquals(original, snapshot)
    }
}
