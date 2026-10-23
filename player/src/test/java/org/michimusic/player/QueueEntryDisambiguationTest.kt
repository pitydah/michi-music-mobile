package org.michimusic.player

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.michimusic.core.models.QueueEntry
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

class QueueEntryDisambiguationTest {

    private val trackA = Track(
        id = "track-a",
        title = "Track A",
        artist = "Artist A",
        album = "Album A",
        duration = 180000L,
        source = TrackSource.LOCAL,
    )

    private val trackB = Track(
        id = "track-b",
        title = "Track B",
        artist = "Artist B",
        album = "Album B",
        duration = 210000L,
        source = TrackSource.LOCAL,
    )

    @Test
    fun queueEntries_differentiateDuplicateTracksAcrossQueue() {
        val entry1 = QueueEntry(
            entryId = "entry-1",
            track = trackA,
            position = 0,
            canonicalTrackId = trackA.id,
        )

        val entry2 = QueueEntry(
            entryId = "entry-2",
            track = trackB,
            position = 1,
            canonicalTrackId = trackB.id,
        )

        val entry3 = QueueEntry(
            entryId = "entry-3",
            track = trackA, // Duplicate of Track A at position 2
            position = 2,
            canonicalTrackId = trackA.id,
        )

        val queue = listOf(entry1, entry2, entry3)

        // Verifications
        assertEquals(3, queue.size)
        assertEquals(entry1.track.id, entry3.track.id)
        assertNotEquals(entry1.entryId, entry3.entryId)
        assertEquals(0, entry1.position)
        assertEquals(2, entry3.position)
    }

    @Test
    fun queueEntries_reorderingPreservesEntryIdentity() {
        val entry1 = QueueEntry(entryId = "entry-1", track = trackA, position = 0)
        val entry2 = QueueEntry(entryId = "entry-2", track = trackB, position = 1)
        val entry3 = QueueEntry(entryId = "entry-3", track = trackA, position = 2)

        val list = mutableListOf(entry1, entry2, entry3)
        // Move item at index 2 to index 0: [entry3, entry1, entry2]
        val item = list.removeAt(2)
        list.add(0, item)

        val reindexed = list.mapIndexed { idx, e -> e.copy(position = idx) }

        assertEquals("entry-3", reindexed[0].entryId)
        assertEquals(0, reindexed[0].position)
        assertEquals("entry-1", reindexed[1].entryId)
        assertEquals(1, reindexed[1].position)
        assertEquals("entry-2", reindexed[2].entryId)
        assertEquals(2, reindexed[2].position)
    }
}
