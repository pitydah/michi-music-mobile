package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class GenreMetadataMergeTest {
    @Test fun placeholdersCannotMaskEmbeddedGenreOrFallback() {
        assertEquals("Jazz", GenreMetadataMerge.choose("Jazz", "N/A", null))
        assertEquals("Rock", GenreMetadataMerge.choose(null, "Sin clasificar", "Rock"))
        assertEquals("", GenreMetadataMerge.choose(null, "?", "<unknown>"))
    }

    @Test fun embeddedWins() = assertEquals("Jazz", GenreMetadataMerge.choose("Jazz", "Rock", "Pop"))
    @Test fun providerFallback() = assertEquals("Rock", GenreMetadataMerge.choose("", "Rock", "Pop"))
    @Test fun retrieverFallback() = assertEquals("Pop", GenreMetadataMerge.choose(null, "Unknown", "Pop"))
    @Test fun doesNotSplitComposite() = assertEquals("R&B/Soul", GenreMetadataMerge.choose("R&B/Soul", null, null))
    @Test fun doesNotSplitMultipleValues() = assertEquals("Jazz; Blues", GenreMetadataMerge.choose("Jazz; Blues", null, null))
    @Test fun noFabrication() = assertEquals("", GenreMetadataMerge.choose(null, null, null))
}
