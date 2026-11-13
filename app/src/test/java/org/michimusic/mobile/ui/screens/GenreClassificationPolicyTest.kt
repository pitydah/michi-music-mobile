package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreClassificationPolicyTest {
    @Test fun validatedSpellingAliasesShareOnlyOneIdentity() {
        assertEquals(genreGroupingKey("Synthpop"), genreGroupingKey("SYNTH POP"))
        assertEquals(genreGroupingKey("NewWave"), genreGroupingKey("New Wave"))
        assertEquals(genreGroupingKey("Lo-Fi"), genreGroupingKey("LoFi"))
        assertEquals(genreGroupingKey("Hip-Hop"), genreGroupingKey("hip hop"))
        assertEquals(genreGroupingKey("Italo Disco"), genreGroupingKey("Italo-Disco"))
    }

    @Test fun distinctMusicalStylesAreNeverAutomaticallyMerged() {
        assertNotEquals(genreGroupingKey("Afrobeat"), genreGroupingKey("Afrobeats"))
        assertNotEquals(genreGroupingKey("Metal"), genreGroupingKey("Heavy Metal"))
        assertNotEquals(genreGroupingKey("Salsa"), genreGroupingKey("Salsa romántica"))
        assertNotEquals(genreGroupingKey("R&B"), genreGroupingKey("R&B/Soul"))
        assertNotEquals(genreGroupingKey("Pop"), genreGroupingKey("K-Pop"))
    }

    @Test fun missingMetadataMarkersAreNotGenres() {
        listOf("", "N/A", "Sin género", "sin clasificar", "unknown", "<unknown>", " ? ")
            .forEach { assertTrue(genreTagIsUnknown(it)) }
    }

    @Test fun displayLabelIsStableForOnlyVettedAliases() {
        assertEquals("Synth-Pop", genreCanonicalDisplayName(genreGroupingKey("SYNTH POP"), "SYNTH POP"))
        assertEquals("Rock & Roll", genreCanonicalDisplayName(genreGroupingKey("rock n roll"), "rock n roll"))
        assertEquals("New Wave", genreCanonicalDisplayName(genreGroupingKey("NewWave"), "NewWave"))
        assertEquals("Folk Rock", genreCanonicalDisplayName(genreGroupingKey("Folk Rock"), "Folk Rock"))
    }

    @Test fun accentsAndWhitespaceFoldWithoutEditingMetadata() {
        val original = "  Clásica   "
        assertEquals("clasica", genreGroupingKey(original))
        assertEquals("  Clásica   ", original)
        assertEquals(genreGroupingKey("clasica"), genreGroupingKey("Clásica"))
    }

    @Test fun deterministicRepresentativeNeverFabricatesTag() {
        assertEquals("Jazz", genrePreferredDisplayTitle("jazz", "Jazz"))
        assertEquals("Jazz", genrePreferredDisplayTitle("Jazz", "jazz"))
        assertEquals("Clásica", genrePreferredDisplayTitle("clasica", "Clásica"))
    }

}
