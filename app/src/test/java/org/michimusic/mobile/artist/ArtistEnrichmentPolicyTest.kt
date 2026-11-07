package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistEnrichmentPolicyTest {
    private val a = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val b = "7c67d5a5-c7c1-4c0f-b1d4-0fb23a3dd549"

    @Test fun `combined credit stays an intact local credit`() {
        assertEquals("Patti Austin & James Ingram", localArtistCredit(" Patti Austin & James Ingram "))
        assertEquals("Artista desconocido", localArtistCredit("  "))
    }

    @Test fun `matching by display text alone cannot bind a remote artist`() {
        val results = listOf(
            ArtistLookupCandidate(a, "Alex", area = "US"),
            ArtistLookupCandidate(b, "Alex", area = "GB"),
        )
        assertNull(selectedArtistMbid(results, null))
        assertEquals(b, selectedArtistMbid(results, b))
        assertNull(selectedArtistMbid(results, "a-not-returned-id"))
        assertNull(selectedArtistMbid(results, "01234567-0123-0123-0123-012345678901"))
    }

    @Test fun `uuid syntax is validated and normalized`() {
        assertEquals(a, canonicalArtistMbid(a.uppercase(java.util.Locale.ROOT)))
        assertNull(canonicalArtistMbid("Midnight Oil"))
        assertNull(canonicalArtistMbid("../../wiki/Bad"))
    }

    @Test fun `commons licenses and credits must be valid before presenting images`() {
        val valid = CommonsPortrait(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a0/portrait.jpg",
            "https://commons.wikimedia.org/wiki/File:portrait.jpg",
            "CC BY-SA 4.0",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            "Photo: author example",
        )
        assertTrue(allowedCommonsPortrait(valid))
        assertTrue(allowedCommonsPortrait(valid.copy(
            imageUrl = "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a0/portrait.jpg/960px-portrait.jpg",
        )))
        assertFalse(allowedCommonsPortrait(valid.copy(license = "CC BY-NC 4.0")))
        assertFalse(allowedCommonsPortrait(valid.copy(license = "Fair use")))
        assertFalse(allowedCommonsPortrait(valid.copy(attribution = "")))
        assertFalse(allowedCommonsPortrait(valid.copy(attribution = "None")))
        assertFalse(allowedCommonsPortrait(valid.copy(attribution = "null")))
        assertFalse(allowedCommonsPortrait(valid.copy(imageUrl = "http://upload.wikimedia.org/image.jpg")))
        assertFalse(allowedCommonsPortrait(valid.copy(imageUrl = "https://example.com/picture.jpg")))
    }

    @Test fun `cached biographies never open untrusted source links`() {
        assertTrue(allowedWikipediaSource(null))
        assertTrue(allowedWikipediaSource("https://en.wikipedia.org/wiki/Midnight_Oil"))
        assertFalse(allowedWikipediaSource("http://en.wikipedia.org/wiki/Midnight_Oil"))
        assertFalse(allowedWikipediaSource("https://en.wikipedia.org.evil.com/wiki/Midnight_Oil"))
        assertFalse(allowedWikipediaSource("https://attacker@en.wikipedia.org/wiki/Midnight_Oil"))
        assertFalse(allowedWikipediaSource("https://en.wikipedia.org/wiki/"))
        assertTrue(allowedArtistBiography(null, null))
        assertFalse(allowedArtistBiography("Texto biográfico", null))
        assertFalse(allowedArtistBiography("Texto biográfico", "https://evil.example/wiki/Artist"))
        assertTrue(allowedArtistBiography("Texto biográfico", "https://es.wikipedia.org/wiki/Artista"))
    }
}
