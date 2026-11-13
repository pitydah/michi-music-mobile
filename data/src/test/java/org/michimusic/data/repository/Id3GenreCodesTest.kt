package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Id3GenreCodesTest {
    @Test fun indexAndTconShareTheSamePublicStandard() {
        assertEquals("Rock", Id3GenreCodes.nameForIndex(17))
        assertEquals("Jazz", Id3GenreCodes.nameForIndex(8))
        assertEquals(listOf("Rock", "Jazz"), Id3GenreCodes.tconValues("(17)\u0000(8)"))
    }
    @Test fun unknownNumbersAreNeverConvertedToInventedGenres() {
        assertNull(Id3GenreCodes.nameForIndex(255))
        assertEquals(emptyList<String>(), Id3GenreCodes.tconValues("(255)"))
        assertEquals(listOf("R&B/Soul"), Id3GenreCodes.tconValues("R&B/Soul"))
    }
}
