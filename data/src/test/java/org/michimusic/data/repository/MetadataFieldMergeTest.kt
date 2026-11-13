package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataFieldMergeTest {
    @Test fun embeddedOverridesRetriever() = assertEquals("Björk", MetadataFieldMerge.choose("Björk", "Unknown Artist"))
    @Test fun missingEmbeddedFallsBackToRetriever() = assertEquals("Artist", MetadataFieldMerge.choose(null, "Artist"))
    @Test fun placeholderDoesNotOverrideEvidence() = assertEquals("Artist", MetadataFieldMerge.choose("Unknown Artist", "Artist"))
    @Test fun noEvidenceUsesExplicitFallback() = assertEquals("Unknown Album", MetadataFieldMerge.choose(null, "<unknown>", "Unknown Album"))
    @Test fun compoundArtistPreserved() = assertEquals("A & B", MetadataFieldMerge.choose("A & B", "A"))
}
