package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI7 consumes the same key as Michi Intelligence, without changing Track.genre. */
class GenreDisambiguationIntegrationTest {
    @Test fun variantsBecomeOneUi7CardAndMatchApprovedPopRockArtwork() {
        listOf("rockpop", "poprock", "Pop-Rock", "Rock-Pop", "pop_rock", "Rock Pop")
            .forEach { assertEquals("pop rock", genreGroupingKey(it)) }
        assertEquals("Pop Rock", genreCanonicalDisplayName("pop rock", "rockpop"))
    }

    @Test fun legacyCanonicalKeysArePreservedForEditorialAndMosaic() {
        assertEquals("synth-pop", genreGroupingKey("Synthpop"))
        assertEquals("hip-hop", genreGroupingKey("Hip Hop"))
        assertEquals("drum bass", genreGroupingKey("Drum & Bass"))
        assertEquals("rock roll", genreGroupingKey("Rock 'n' Roll"))
    }

    @Test fun noInventedGenresOrUnapprovedMerges() {
        assertNotEquals(genreGroupingKey("Pop"), genreGroupingKey("Pop Rock"))
        assertNotEquals(genreGroupingKey("Rock/Pop"), genreGroupingKey("Pop Rock"))
        assertNotEquals(genreGroupingKey("Rock Punk"), genreGroupingKey("Punk Rock"))
        assertNotEquals(genreGroupingKey("Afrobeat"), genreGroupingKey("Afrobeats"))
        assertTrue(genreTagIsUnknown("sin género"))
    }
}
