package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiArtworkMemoryTest {
    @Test fun evictsTheLeastRecentlyAccessedKeyButNeverExceedsCapacity() {
        val cache = MichiArtworkMemory<String, String>(limit = 2)
        cache.put("artist-A", "art-A")
        cache.put("artist-B", "art-B")
        assertEquals("art-A", cache["artist-A"]) // Make B least recently used.
        cache.put("artist-C", "art-C")
        assertEquals(2, cache.size)
        assertNull(cache["artist-B"])
        assertEquals("art-A", cache["artist-A"])
        assertEquals("art-C", cache["artist-C"])
    }

    @Test fun aStructuralArtistKeyCannotLeakAcrossAlbumMemberships() {
        val cache = MichiArtworkMemory<ArtistMosaicEntry, String>(limit = 2)
        val first = ArtistMosaicEntry("ART", listOf("album-1"), 1, 3)
        val second = first.copy(albumIds = listOf("album-2"))
        cache.put(first, "verified-local-file")
        assertNull(cache[second])
        assertEquals("verified-local-file", cache[first])
    }

    @Test fun aScopeOrRevisionChangeCanClearEveryCachedHint() {
        val cache = MichiArtworkMemory<Int, String>(limit = 24)
        cache.put(1, "file")
        cache.clear()
        assertNull(cache[1])
        assertEquals(0, cache.size)
    }

    @Test fun allLibrariesNeverReusesASelectionBeforeCurrentConsensus() {
        assertFalse(michiArtworkHintAllowed(null, true))
        assertFalse(michiArtworkHintAllowed(null, false))
        assertFalse(michiArtworkHintAllowed("library-1", false))
        assertTrue(michiArtworkHintAllowed("library-1", true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun capacityCannotBeZero() {
        MichiArtworkMemory<String, String>(limit = 0)
    }
}
