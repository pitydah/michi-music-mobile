package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MichiGenreDarkWaveDisambiguationTest {
    @Test
    fun darkWaveSpellingsShareOneCanonicalIdentity() {
        assertEquals("darkwave", MichiGenreDisambiguationPolicy.key("Dark Wave"))
        assertEquals("darkwave", MichiGenreDisambiguationPolicy.key("Darkwave"))
        assertEquals("darkwave", MichiGenreDisambiguationPolicy.key("Dark-Wave"))
        assertEquals("Dark Wave", MichiGenreDisambiguationPolicy.display("darkwave", "fallback"))
        assertEquals("Dark Wave", MichiGenreDisambiguationPolicy.suggestedTag("Darkwave"))
    }

    @Test
    fun darkWaveNeverAbsorbsNeighbouringStyles() {
        listOf("Coldwave", "Cold Wave", "Dark Ambient", "New Wave", "Gothic Rock").forEach {
            assertNotEquals(
                "$it must stay separate from Dark Wave",
                "darkwave",
                MichiGenreDisambiguationPolicy.key(it),
            )
        }
        assertEquals("coldwave", MichiGenreDisambiguationPolicy.key("Coldwave"))
        assertEquals("dark ambient", MichiGenreDisambiguationPolicy.key("Dark Ambient"))
        assertEquals("new wave", MichiGenreDisambiguationPolicy.key("New Wave"))
        assertEquals("gothic rock", MichiGenreDisambiguationPolicy.key("Gothic Rock"))
    }

    @Test
    fun punctuationAndOrderRemainMeaningfulAcrossTheApprovedRegistry() {
        assertEquals("pop rock", MichiGenreDisambiguationPolicy.key("Rock Pop"))
        assertEquals("rock/pop", MichiGenreDisambiguationPolicy.key("Rock/Pop"))
        assertNotEquals(
            MichiGenreDisambiguationPolicy.key("Rock/Pop"),
            MichiGenreDisambiguationPolicy.key("Pop Rock"),
        )
        assertEquals("hip-hop", MichiGenreDisambiguationPolicy.key("Hip-Hop"))
        assertEquals("hip/hop", MichiGenreDisambiguationPolicy.key("Hip/Hop"))
        assertNotEquals(
            MichiGenreDisambiguationPolicy.key("Hip-Hop"),
            MichiGenreDisambiguationPolicy.key("Hip/Hop"),
        )
        assertEquals("r&b", MichiGenreDisambiguationPolicy.key("R&B"))
        assertEquals("r b", MichiGenreDisambiguationPolicy.key("R B"))
        assertNotEquals(
            MichiGenreDisambiguationPolicy.key("R&B"),
            MichiGenreDisambiguationPolicy.key("R B"),
        )
        assertEquals("post-rock", MichiGenreDisambiguationPolicy.key("Post Rock"))
        assertEquals("post-rock", MichiGenreDisambiguationPolicy.key("Post-Rock"))
        assertEquals("post-metal", MichiGenreDisambiguationPolicy.key("Post Metal"))
        assertEquals("post-metal", MichiGenreDisambiguationPolicy.key("Post-Metal"))
        assertEquals("norteno", MichiGenreDisambiguationPolicy.key("Norteño"))
        assertEquals("rai", MichiGenreDisambiguationPolicy.key("Raï"))
        assertEquals("j-pop", MichiGenreDisambiguationPolicy.key("J-Pop"))
        // Approved aliases may collapse punctuation; the non-approved separators above must not.
        assertEquals("j rock", MichiGenreDisambiguationPolicy.key("J-Rock"))
        assertEquals("j rock", MichiGenreDisambiguationPolicy.key("JRock"))
        assertEquals("coldwave", MichiGenreDisambiguationPolicy.key("Cold-Wave"))
        assertEquals("art pop", MichiGenreDisambiguationPolicy.key("Art-Pop"))
        assertEquals("nu jazz", MichiGenreDisambiguationPolicy.key("Nu-Jazz"))
        assertEquals("cha cha cha", MichiGenreDisambiguationPolicy.key("Cha-cha-chá"))
        assertNotEquals(
            MichiGenreDisambiguationPolicy.key("J-Pop"),
            MichiGenreDisambiguationPolicy.key("J-Rock"),
        )
        assertEquals("salsa romantica", MichiGenreDisambiguationPolicy.key("Salsa romántica"))
        assertNotEquals(
            MichiGenreDisambiguationPolicy.key("Salsa"),
            MichiGenreDisambiguationPolicy.key("Salsa romántica"),
        )
    }
}
