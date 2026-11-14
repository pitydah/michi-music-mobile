package org.michimusic.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

/** Media3 index is authoritative; looking up IDs can select the wrong owner. */
class MichiIntelligencePlaybackBridgeQueueTest {
    private fun track(id: String = "1", owner: String = "local") = Track(
        id = id, title = "Track", sourceId = owner, filepath = "/music/$id.flac",
        source = TrackSource.LOCAL, duration = 120_000L,
    )

    @Test fun largeQueueIndexCheckIsConstantTime() {
        val selected = track()
        val huge = List(50_000) { index -> if (index == 49_999) selected else track("$index") }
        assertTrue(MichiIntelligencePlaybackBridge.hasCanonicalQueuePosition(huge, 49_999, selected))
        assertFalse(MichiIntelligencePlaybackBridge.hasCanonicalQueuePosition(huge, 0, selected))
    }

    @Test fun identicalIdsAcrossOwnersAreUnambiguousByAuthoritativeIndex() {
        val selected = track("one", "local")
        val other = track("one", "foreign")
        assertTrue(MichiIntelligencePlaybackBridge.hasCanonicalQueuePosition(listOf(selected, other), 0, selected))
        assertFalse(MichiIntelligencePlaybackBridge.hasCanonicalQueuePosition(listOf(selected, other), 1, selected))
    }

    @Test fun reconstructedOrMismatchedItemFailsClosed() {
        val selected = track("one")
        assertFalse(MichiIntelligencePlaybackBridge.hasCanonicalQueuePosition(listOf(selected), -1, selected))
        assertFalse(MichiIntelligencePlaybackBridge.hasCanonicalQueuePosition(listOf(selected), 1, selected))
        assertFalse(MichiIntelligencePlaybackBridge.hasCanonicalQueuePosition(listOf(selected), 0, selected.copy()))
        assertFalse(MichiIntelligencePlaybackBridge.hasCanonicalQueuePosition(
            listOf(selected.copy(source = TrackSource.STREAMING)), 0, selected.copy(source = TrackSource.STREAMING)))
        assertFalse(MichiIntelligencePlaybackBridge.hasCanonicalQueuePosition(
            listOf(selected.copy(filepath = "")), 0, selected.copy(filepath = "")))
    }
}
