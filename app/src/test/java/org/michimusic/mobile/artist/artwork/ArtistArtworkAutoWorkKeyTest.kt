package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistArtworkAutoWorkKeyTest {
    @Test fun sameAssociationAcrossTracksDeduplicates() {
        val credit = ArtistArtworkAutoWorkKey.creditKey("  MIDNIGHT OIL  ")!!
        assertEquals("midnight oil", credit)
        val name = ArtistArtworkAutoWorkKey.uniqueName("lib-local", credit, "assoc-1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")!!
        assertEquals(name, ArtistArtworkAutoWorkKey.uniqueName("lib-local", "midnight oil", "assoc-1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertTrue(!name.contains("midnight") && !name.contains("assoc-1"))
    }

    @Test fun differentAssociationsAndLibrariesNeverShareAJob() {
        val a = ArtistArtworkAutoWorkKey.uniqueName("lib-A", "artist", "assoc-A", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertNotEquals(a, ArtistArtworkAutoWorkKey.uniqueName("lib-B", "artist", "assoc-A", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertNotEquals(a, ArtistArtworkAutoWorkKey.uniqueName("lib-A", "artist", "assoc-B", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertNotEquals(a, ArtistArtworkAutoWorkKey.uniqueName("lib-A", "another", "assoc-A", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }

    @Test fun controlCharactersInLibraryScopeCannotForgeWorkKeys() {
        assertNull(ArtistArtworkAutoWorkKey.uniqueName("lib\u0000root", "artist", "assoc", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertNull(ArtistArtworkAutoWorkKey.uniqueName("lib\nroot", "artist", "assoc", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertNull(ArtistArtworkAutoWorkKey.uniqueName("lib", "artist", "assoc\u0000alt", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertNull(ArtistArtworkAutoWorkKey.uniqueName("lib", "artist\u0000alt", "assoc", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }

    @Test fun changingSourceFingerprintNeverReusesAnOldWorkName() {
        val oldRoot = "a".repeat(64)
        val newRoot = "b".repeat(64)
        val oldJob = ArtistArtworkAutoWorkKey.uniqueName("lib", "artist", "assoc", oldRoot)
        assertNotEquals(oldJob, ArtistArtworkAutoWorkKey.uniqueName("lib", "artist", "assoc", newRoot))
        assertNull(ArtistArtworkAutoWorkKey.uniqueName("lib", "artist", "assoc", "bad-root"))
    }

    @Test fun aggregateOrMalformedInputIsDiscarded() {
        assertNull(ArtistArtworkAutoWorkKey.uniqueName("SCOPE_ALL", "artist", "a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertNull(ArtistArtworkAutoWorkKey.uniqueName("", "artist", "a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertNull(ArtistArtworkAutoWorkKey.uniqueName("lib", "artist", "", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertNull(ArtistArtworkAutoWorkKey.creditKey("  "))
        assertNull(ArtistArtworkAutoWorkKey.creditKey("x".repeat(121)))
        assertNull(ArtistArtworkAutoWorkKey.uniqueName("lib", "ARTIST", "a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }
}
