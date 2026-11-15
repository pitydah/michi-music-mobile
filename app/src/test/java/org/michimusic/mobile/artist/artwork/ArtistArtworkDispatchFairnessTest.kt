package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistArtworkDispatchFairnessTest {
    @Test fun sameGenerationUsesSameHashedTag() {
        val tag = ArtistArtworkDispatchFairness.activeTag("michi.artist.auto.aabb0011", 8L)
        assertEquals(tag, ArtistArtworkDispatchFairness.activeTag("michi.artist.auto.aabb0011", 8L))
        assertTrue(tag!!.endsWith(".e8"))
    }

    @Test fun returningRootGetsDifferentWorkTag() {
        val original = ArtistArtworkDispatchFairness.activeTag("michi.artist.auto.aabb0011", 8L)
        val returned = ArtistArtworkDispatchFairness.activeTag("michi.artist.auto.aabb0011", 10L)
        assertTrue(original != returned)
        assertTrue(ArtistArtworkDispatchFairness.eligible(returned, setOf(original!!), emptySet()))
    }

    @Test fun activeWorkNeverConsumesNewDispatchBudget() {
        val active = ArtistArtworkDispatchFairness.activeTag("michi.artist.auto.aabb0011", 8L)!!
        assertFalse(ArtistArtworkDispatchFairness.eligible(active, setOf(active), emptySet()))
        val next = ArtistArtworkDispatchFairness.activeTag("michi.artist.auto.eecc6677", 8L)
        assertTrue(ArtistArtworkDispatchFairness.eligible(next, setOf(active), emptySet()))
    }

    @Test fun duplicateWithinSameDispatchIsRejected() {
        val tag = ArtistArtworkDispatchFairness.activeTag("michi.artist.auto.99887766", 12L)!!
        assertFalse(ArtistArtworkDispatchFairness.eligible(tag, emptySet(), setOf(tag)))
    }

    @Test fun onlyActiveWorkManagerStatesBlockNewDispatch() {
        for (state in listOf("RUNNING", "ENQUEUED", "BLOCKED")) {
            assertTrue(ArtistArtworkDispatchFairness.isActiveWorkState(state))
        }
        for (state in listOf("SUCCEEDED", "FAILED", "CANCELLED")) {
            assertFalse(ArtistArtworkDispatchFairness.isActiveWorkState(state))
        }
    }

    @Test fun invalidEpochAndInvalidKeysFailClosed() {
        assertEquals(null, ArtistArtworkDispatchFairness.activeTag("michi.artist.auto.ok", -1L))
        assertEquals(null, ArtistArtworkDispatchFairness.activeTag("some-private-path", 1L))
        assertEquals(null, ArtistArtworkDispatchFairness.activeTag("michi.artist.auto.wrong key", 1L))
    }

    @Test fun dispatchIsBoundedAndDoesNotInstallPeriodicWork() {
        assertEquals(128, ArtistArtworkDispatchFairness.MAX_PAGE_SIZE)
        assertEquals(8, ArtistArtworkDispatchFairness.MAX_PAGES)
        assertEquals(16, ArtistArtworkDispatchFairness.MAX_NEW_WORK)
    }
}
