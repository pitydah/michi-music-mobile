package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiGenreIntelligenceDisambiguationTest {
    @Test fun approvedMultiValueGenresDeduplicateBeforeLearning() {
        assertEquals(listOf("pop rock", "dream pop"),
            MichiGenreIntelligencePolicy.keys("", listOf("Rock-Pop", "pop_rock", "PopRock", "DreamPop")))
    }

    @Test fun unverifiedSemicolonNeverInventsNewGenres() {
        assertEquals(listOf("pop rock;rock"),
            MichiGenreIntelligencePolicy.keys("Pop Rock;Rock"))
        assertEquals(listOf("pop rock", "rock"),
            MichiGenreIntelligencePolicy.keys("Pop Rock\u0000Rock"))
    }

    @Test fun noAffinitiesFromUnverifiedOwners() {
        assertTrue(MichiGenreIntelligencePolicy.verifiedKeys("PopRock", false, true).isEmpty())
        assertTrue(MichiGenreIntelligencePolicy.verifiedKeys("PopRock", true, false).isEmpty())
        assertEquals(listOf("pop rock"), MichiGenreIntelligencePolicy.verifiedKeys("PopRock", true, true))
    }

    @Test fun editingPolicyDeduplicatesApprovedAliasesButPreservesExplicitTagChoice() {
        val decision = MichiGenreNormalizationPolicy.validateGenres(
            listOf("rockpop", "Pop-Rock", "Pop Rock", "Art Rock", "artrock"))
        assertEquals(listOf("rockpop", "Art Rock"), decision)
        assertEquals("Pop Rock", MichiGenreDisambiguationPolicy.suggestedTag("rockpop"))
        assertEquals("rockpop", decision!!.first()) // no invisible rewrite in filesystem
    }
}
