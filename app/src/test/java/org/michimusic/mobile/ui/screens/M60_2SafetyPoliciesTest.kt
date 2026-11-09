package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M60_2SafetyPoliciesTest {
    @Test fun invalidQueueResultsNeverFallbackToTheFirstTrack() {
        assertNull(safeSearchQueueIndex(-1, 3))
        assertNull(safeSearchQueueIndex(0, 0))
        assertNull(safeSearchQueueIndex(3, 3))
        assertNull(safeSearchQueueIndex(Int.MAX_VALUE, 1))
    }

    @Test fun validQueueIndicesRetainTheExactSelection() {
        assertEquals(0, safeSearchQueueIndex(0, 3))
        assertEquals(2, safeSearchQueueIndex(2, 3))
    }

    @Test fun noSeekWithMissingOrUnknownMedia() {
        assertNull(safeNowPlayingSeekMillis(10f, 200f, false))
        assertNull(safeNowPlayingSeekMillis(10f, 0f, true))
        assertNull(safeNowPlayingSeekMillis(Float.NaN, 200f, true))
        assertNull(safeNowPlayingSeekMillis(Float.POSITIVE_INFINITY, 200f, true))
        assertNull(safeNowPlayingSeekMillis(2f, Float.NaN, true))
        assertNull(safeNowPlayingSeekMillis(2f, Float.POSITIVE_INFINITY, true))
        assertNull(safeNowPlayingSeekMillis(2f, Float.MAX_VALUE, true))
    }

    @Test fun seekingClampsToKnownDuration() {
        assertEquals(0L, safeNowPlayingSeekMillis(-10f, 200f, true))
        assertEquals(10000L, safeNowPlayingSeekMillis(10f, 200f, true))
        assertEquals(200000L, safeNowPlayingSeekMillis(201f, 200f, true))
    }

    @Test fun queueIdentityRejectsStaleResultAndKeepsExactPosition() {
        val fresh = listOf("fresh-a", "fresh-b", "fresh-c")
        assertEquals(0, safeSearchQueueTrackIdIndex(fresh, "fresh-a"))
        assertEquals(2, safeSearchQueueTrackIdIndex(fresh, "fresh-c"))
        assertNull(safeSearchQueueTrackIdIndex(fresh, "old-id"))
        assertNull(safeSearchQueueTrackIdIndex(listOf("duplicate", "unique", "duplicate"), "duplicate"))
        assertNull(safeSearchQueueTrackIdIndex(fresh, ""))
        assertNull(safeSearchQueueTrackIdIndex(emptyList(), "fresh-a"))
    }

    @Test fun airCatGateSurvivesRepeatedAcquisitionsUntilRelease() {
        val gate = AirCatUiStartGate()
        assertEquals(false, gate.pending)
        val first = requireNotNull(gate.tryAcquire())
        assertEquals(1L, first)
        assertEquals(true, gate.pending)
        assertNull(gate.tryAcquire())
        assertNull(gate.tryAcquire())
        gate.release(first)
        assertEquals(false, gate.pending)
        val second = requireNotNull(gate.tryAcquire())
        assertEquals(2L, second)
        // Delayed duplicate completion of transfer #1 must NOT unlock #2.
        gate.release(first)
        assertEquals(true, gate.pending)
        assertNull(gate.tryAcquire())
        gate.release(second)
        assertEquals(false, gate.pending)
    }

    @Test fun searchDebounceCannotActivateResultsFromAnOldQueryOrFilter() {
        assertEquals(true, searchActionPermitted("beat", "ALL", "TRACK", "Beat It"))
        assertEquals(true, searchActionPermitted("queen", "ARTISTS", "ARTIST", "Queen"))
        assertEquals(true, searchActionPermitted("night", "ALBUMS", "ALBUM", "Night Visions"))
        assertEquals(false, searchActionPermitted("new query", "ALL", "TRACK", "Old Song"))
        assertEquals(false, searchActionPermitted("queen", "ARTISTS", "TRACK", "Queen Song"))
        assertEquals(false, searchActionPermitted("queen", "TRACKS", "ARTIST", "Queen"))
        assertEquals(false, searchActionPermitted("q", "ALL", "TRACK", "Queen"))
        assertEquals(false, searchActionPermitted("  ", "ALL", "TRACK", "Queen"))
        assertEquals(true, searchActionPermitted(" queen ", "ALL", "TRACK", "Queen"))
        assertEquals(false, searchActionPermitted("queen", "NONE", "TRACK", "Queen"))
    }

    @Test fun responseSnapshotRequiresExactEpochAndFilter() {
        // Identical query text is NOT proof of identity: A->B->A must change epoch.
        assertEquals(true, searchSnapshotCurrent("abc", "ALL", 14L, "abc", "ALL", 14L))
        assertEquals(false, searchSnapshotCurrent("abc", "ALL", 13L, "abc", "ALL", 14L))
        assertEquals(false, searchSnapshotCurrent("abc", "ARTISTS", 14L, "abc", "ALL", 14L))
        assertEquals(false, searchSnapshotCurrent("old", "ALL", 14L, "new", "ALL", 14L))
        assertEquals(true, searchSnapshotCurrent("abc", "ALL", 14L, "abc ", "ALL", 14L))
    }

    @Test fun seeksNeverOverflowOrExceedDuration() {
        assertNull(safeNowPlayingSeekMillis(3f, Float.NEGATIVE_INFINITY, true))
        assertNull(safeNowPlayingSeekMillis(3f, -1f, true))
        assertEquals(0L, safeNowPlayingSeekMillis(-Float.MAX_VALUE, 12f, true))
        assertEquals(12000L, safeNowPlayingSeekMillis(Float.MAX_VALUE, 12f, true))
    }

    @Test fun oldOrUninitializedSnapshotsAreNeverCurrent() {
        assertEquals(false, searchSnapshotCurrent("music", "ALL", -1L, "music", "ALL", -1L))
        assertEquals(false, searchSnapshotCurrent("music", "ALL", 3L, "music", "ALL", 4L))
        assertEquals(false, searchSnapshotCurrent("music", "ALL", 4L, "music", "TRACKS", 4L))
    }

    @Test fun airCatBlocksConcurrentAndStaleDestinations() {
        val eligible = setOf("server-1", "server-2")
        assertEquals(true, allowAirCatUiStart(false, false, eligible, "server-1"))
        assertEquals(false, allowAirCatUiStart(true, false, eligible, "server-1"))
        assertEquals(false, allowAirCatUiStart(false, true, eligible, "server-1"))
        assertEquals(false, allowAirCatUiStart(false, false, eligible, "offline"))
        assertEquals(false, allowAirCatUiStart(false, false, eligible, ""))
        assertEquals(false, allowAirCatUiStart(false, false, emptySet(), "server-1"))
    }
}
