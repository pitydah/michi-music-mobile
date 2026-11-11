package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.michimusic.core.models.Track

class PlaylistVisualOccurrenceKeysTest {
    private fun track(id: String, sourceId: String = "local") = Track(
        id = id, title = id, artist = "Test", sourceId = sourceId,
    )

    @Test fun duplicateTrackIdGetsDistinctUiKeysWithoutChangingTrackId() {
        val items = listOf(track("A"), track("B"), track("A"))
        val keys = playlistVisualOccurrenceKeys(items)
        assertEquals(3, keys.distinct().size)
        assertEquals(listOf("A", "B", "A"), items.map { it.id })
    }

    @Test fun sourceCollisionsDoNotAliasUiKeys() {
        assertNotEquals(
            playlistVisualOccurrenceKeys(listOf(track("a:b", "c"))).single(),
            playlistVisualOccurrenceKeys(listOf(track("b", "c:a"))).single(),
        )
    }
}
