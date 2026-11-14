package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiGenreDisambiguationPolicyTest {
    private val p = MichiGenreDisambiguationPolicy

    @Test fun userPopRockExamplesAllShareOneKeyAndDisplay() {
        val forms = listOf("Rock Pop", "rockpop", "poprock", "Rock-Pop", "Pop-Rock",
            "pop-rock", "rock-pop", "pop_rock", "POP__ROCK", " rock   pop ")
        forms.forEach {
            assertEquals("pop rock", p.key(it))
            assertEquals("Pop Rock", p.suggestedTag(it))
        }
    }

    @Test fun noFuzzyMatchingNoUnapprovedTokenPermutation() {
        val different = listOf("Pop", "Rock", "Rock/Pop", "Pop/Rock", "Art Pop",
            "Power Pop", "Pop Punk", "Punk Rock", "Rock Punk", "Post Rock", "Post Punk",
            "Progressive Rock", "Prog Rock", "Synthwave", "Synth-Pop", "Afrobeat", "Afrobeats")
        assertEquals(different.size, different.map(p::key).toSet().size)
        assertEquals("rock/pop", p.key("Rock/Pop"))
        assertEquals("rock punk", p.key("Rock Punk"))
        assertEquals("punk rock", p.key("Punk Rock"))
    }

    @Test fun legacyUi7AliasesKeepExactExistingKeys() {
        val groups = mapOf(
            "synth-pop" to listOf("Synthpop", "Synth Pop", "synth-pop"),
            "new wave" to listOf("NewWave", "new-wave", "New Wave"),
            "lo-fi" to listOf("LoFi", "lo fi", "lo-fi"),
            "hip-hop" to listOf("HipHop", "hip hop", "hip-hop"),
            "k-pop" to listOf("Kpop", "k pop", "k-pop"),
            "j-pop" to listOf("Jpop", "j pop", "j-pop"),
            "r&b" to listOf("rnb", "R&B", "R and B"),
            "drum bass" to listOf("Drum & Bass", "Drum and Bass", "Drum N Bass"),
            "rock roll" to listOf("Rock 'n' Roll", "Rock n Roll", "Rock and Roll"),
            "italo disco" to listOf("Italo-Disco", "Italo Disco"),
        )
        groups.forEach { (key, variants) -> variants.forEach { assertEquals(key, p.key(it)) } }
    }

    @Test fun curatedTypographyWorksBeyondPopRock() {
        assertEquals("post-rock", p.key("POST_ROCK"))
        assertEquals("dream pop", p.key("DreamPop"))
        assertEquals("art rock", p.key("Art_Rock"))
        assertEquals("smooth jazz", p.key("smooth-jazz"))
        assertEquals("neo soul", p.key("NeoSoul"))
        assertEquals("bossa nova", p.key("BossaNova"))
        assertEquals("heavy metal", p.key("Heavy_Metal"))
        assertEquals("pop punk", p.key("PopPunk"))
        assertEquals("post punk", p.key("Post-Punk"))
        assertEquals("power pop", p.key("POWER_POP"))
        assertEquals("city pop", p.key("CityPop"))
        assertEquals("afro house", p.key("Afro-House"))
        assertEquals("latin jazz", p.key("LatinJazz"))
        assertEquals("symphonic metal", p.key("SymphonicMetal"))
    }

    @Test fun invalidPlaceholdersAndControlCodesAreNotGenres() {
        listOf("", "  ", "Unknown", "Sin clasificar", "Sin género", "N/A", "?",
            "rock\u0000pop", "x".repeat(97)).forEach { assertNull(p.resolve(it)) }
    }

    @Test fun rawTagsAndUnknownTaxonomyAreNotRewrittenOrGuessed() {
        val raw = "Art  Pop"
        assertEquals("Art Pop", p.suggestedTag(raw))
        assertEquals("art pop", p.key(raw))
        assertEquals("Rock/Pop", p.suggestedTag("Rock/Pop"))
        assertEquals("rock/pop", p.key("Rock/Pop"))
        assertTrue(p.resolve("Rock-Pop")!!.approvedAlias)
        assertFalse(p.resolve("Pop Rock")!!.approvedAlias)
    }

    @Test fun resolverHasZeroDependencyOnUserTasteOrNetwork() {
        assertEquals("pop rock", p.key("pOp_RoCk"))
        assertEquals("Pop Rock", p.display("pop rock", "rockpop"))
        assertEquals("Unknown Style", p.display("unknown style", "Unknown Style"))
        assertTrue(p.REGISTRY_VERSION >= 1)
    }
}
