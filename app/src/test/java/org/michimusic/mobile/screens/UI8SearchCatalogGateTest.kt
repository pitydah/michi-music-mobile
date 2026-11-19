package org.michimusic.mobile.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

class UI8SearchCatalogGateTest {
    @Test fun startingNewRootRevokesOldResultImmediately() {
        val gate = SearchCatalogGate()
        val old = gate.begin()
        val track = Track(id = "old", title = "Root A")
        assertTrue(gate.complete(old, listOf(track)))
        assertEquals(listOf(track), (gate.current() as SearchCatalogLoad.Ready).tracks)

        val new = gate.begin()
        assertEquals(old.generation + 1L, new.generation)
        assertSame(new, gate.current())
        assertFalse(gate.complete(old, listOf(track)))
        assertFalse(gate.fail(old, "late failure"))
        assertSame(new, gate.current())
    }

    @Test fun failedReloadCannotRetainPreviousRootAndRetryOnlyPublishesNewTracks() {
        val gate = SearchCatalogGate()
        val first = gate.begin()
        assertTrue(gate.complete(first, listOf(Track(id = "old", title = "Root A"))))

        val failing = gate.begin()
        assertTrue(gate.fail(failing, "SAF revoked"))
        val failed = gate.current() as SearchCatalogLoad.Failed
        assertEquals("SAF revoked", failed.reason)
        assertEquals(failing.generation, failed.generation)

        val retry = gate.begin()
        assertFalse(gate.complete(failing, listOf(Track(id = "stale", title = "stale"))))
        assertTrue(gate.complete(retry, listOf(Track(id = "new", title = "Root B"))))
        assertEquals(listOf("new"), (gate.current() as SearchCatalogLoad.Ready).tracks.map { it.id })
    }

    @Test fun olderLoadCompletionCannotPublishAfterNewerLoadFinishes() {
        val gate = SearchCatalogGate()
        val old = gate.begin()
        val newer = gate.begin()
        assertTrue(gate.complete(newer, listOf(Track(id = "new", title = "new"))))
        assertFalse(gate.complete(old, listOf(Track(id = "old", title = "old"))))
        assertEquals(listOf("new"), (gate.current() as SearchCatalogLoad.Ready).tracks.map { it.id })
    }

    @Test fun normalRefreshAlsoInvalidatesUntilItFinishes() {
        val gate = SearchCatalogGate()
        val first = gate.begin()
        assertTrue(gate.complete(first, listOf(Track(id = "a", title = "a"))))
        val refresh = gate.begin()
        assertTrue(gate.current() is SearchCatalogLoad.Loading)
        assertTrue(gate.complete(refresh, listOf(Track(id = "a", title = "a"), Track(id = "b", title = "b"))))
        assertEquals(2, (gate.current() as SearchCatalogLoad.Ready).tracks.size)
    }
}
