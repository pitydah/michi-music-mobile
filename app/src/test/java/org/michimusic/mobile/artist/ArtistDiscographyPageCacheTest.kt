package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistDiscographyPageCacheTest {
    private val album = ArtistPublicAlbum("bd6d4f63-5cd0-4d78-a292-167997794c21", "Disc", 1990, "Album")
    private fun page(offset: Int) = ArtistDiscographyPage(listOf(album), offset, offset + 1, 500)

    @Test fun boundedCacheIsIsolatedByMbidAndOffset() {
        var clock = 100L
        val cache = ArtistDiscographyPageCache(nowMs = { clock }, maxEntries = 2, ttlMs = 1000L)
        cache.put("artist-a", 0, page(0))
        cache.put("artist-b", 0, page(0))
        assertEquals(page(0), cache.get("artist-a", 0))
        cache.put("artist-a", 25, page(25))
        assertNull(cache.get("artist-b", 0)) // LRU evicted
        assertEquals(page(25), cache.get("artist-a", 25))
        clock += 1000L
        assertNull(cache.get("artist-a", 0)) // expired, not a permanent false claim
    }

    @Test fun offsetMismatchCannotPolluteAdjacentPagination() {
        val cache = ArtistDiscographyPageCache()
        try {
            cache.put("a", 25, page(0))
            throw AssertionError("mismatch accepted")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
