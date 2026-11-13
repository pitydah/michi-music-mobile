package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Id3ExpandedGenreCodesTest {
    @Test fun standardExtendedIndicesAreMappedNotLost() {
        assertEquals("Folk", Id3GenreCodes.nameForIndex(80))
        assertEquals("Swing", Id3GenreCodes.nameForIndex(83))
        assertEquals("Bebop", Id3GenreCodes.nameForIndex(85))
        assertEquals("Chamber Music", Id3GenreCodes.nameForIndex(104))
        assertEquals("Salsa", Id3GenreCodes.nameForIndex(143))
        assertEquals("Synthpop", Id3GenreCodes.nameForIndex(147))
        assertNull(Id3GenreCodes.nameForIndex(148))
        assertNull(Id3GenreCodes.nameForIndex(255))
    }

    @Test fun tconNumericBracketAndLiteralSemicolonRemainDistinct() {
        assertEquals(listOf("Swing", "Salsa", "Jazz; Fusion"),
            Id3GenreCodes.tconValues("(83)\u0000143\u0000Jazz; Fusion"))
    }

    @Test fun invalidAndUnknownNumericValuesDoNotCreateGenres() {
        assertEquals(emptyList<String>(), Id3GenreCodes.tconValues("(255)"))
        assertEquals(emptyList<String>(), Id3GenreCodes.tconValues("148"))
        assertEquals("Pop rock", Id3GenreCodes.tconValues("Pop rock").single())
    }

    @Test fun adjacentLegacyBracketedTconCodesAreExpandedOnlyWhenUnambiguous() {
        assertEquals(listOf("Rock", "Alternative"), Id3GenreCodes.tconValues("(17)(20)"))
        assertEquals(emptyList<String>(), Id3GenreCodes.tconValues("(17)(255)"))
        assertEquals(listOf("(17)Not two genres"),
            Id3GenreCodes.tconValues("(17)Not two genres"))
    }

    @Test fun thirtyThreeValidTconCodesNeverPublishOnlyFirstThirtyTwo() {
        assertTrue(Id3GenreCodes.tconValues((1..33).joinToString("\u0000") { "17" }).isEmpty())
        assertEquals(32, Id3GenreCodes.tconValues((1..32).joinToString("\u0000") { "17" }).size)
    }

    @Test fun malformedHugeTconDoesNotProduceUnboundedAllocations() {
        assertTrue(Id3GenreCodes.tconValues("Jazz\u0000".repeat(1_600)).isEmpty())
        assertTrue(Id3GenreCodes.tconValues("A\u0000".repeat(260)).isEmpty())
    }
}
