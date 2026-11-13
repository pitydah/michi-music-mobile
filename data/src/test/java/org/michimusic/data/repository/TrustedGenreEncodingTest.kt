package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedGenreEncodingTest {
    @Test fun literalAndStructuredGenresStayDistinct() {
        assertEquals(listOf("Jazz; Fusion"), TrustedGenreEncoding.decode("Jazz; Fusion", "Jazz; Fusion"))
        assertEquals(listOf("Jazz", "Fusion"), TrustedGenreEncoding.decode("Jazz; Fusion", "Jazz\u0000Fusion"))
        assertEquals(listOf("R&B/Soul"), TrustedGenreEncoding.decode("R&B/Soul", "R&B/Soul"))
    }
    @Test fun neverTruncatesThirtyThreeValues() {
        val values = (1..33).map { "Style $it" }
        assertTrue(TrustedGenreEncoding.decode(values.joinToString("; "), values.joinToString("\u0000")).isEmpty())
        assertEquals(32, TrustedGenreEncoding.decode(
            values.take(32).joinToString("; "), values.take(32).joinToString("\u0000")).size)
    }
    @Test fun corruptOrContradictorySidecarsFailClosed() {
        assertTrue(TrustedGenreEncoding.decode("Rock", "Jazz").isEmpty())
        assertTrue(TrustedGenreEncoding.decode("Rock", "Rock\u0000").isEmpty())
        assertTrue(TrustedGenreEncoding.decode("Rock", "Rock\uFFFD").isEmpty())
        assertTrue(TrustedGenreEncoding.decode("", "Rock").isEmpty())
    }
    @Test fun impossibleLengthsAreRefusedBeforeSplit() {
        assertTrue(TrustedGenreEncoding.decode("X", "X".repeat(8193)).isEmpty())
        assertTrue(TrustedGenreEncoding.decode("X", "X".repeat(2049)).isEmpty())
    }
}
