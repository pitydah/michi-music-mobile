package org.michimusic.mobile.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextPolicyTest {
    @Test fun accentAndCaseAreEquivalent() {
        assertTrue(searchTextMatches("Canción del corazón", "corazon"))
        assertTrue(searchTextMatches("Björk", "BJORK"))
        assertTrue(searchTextMatches("Música", "muSica"))
    }
    @Test fun blankAndSingleCharacterCannotAuthorizeSearch() {
        assertFalse(searchTextMatches("Bach", " "))
        assertFalse(searchTextMatches("Bach", "b"))
        assertFalse(searchTextMatches("Bach", "x"))
    }
    @Test fun normalizationIsDeterministicAndLiteral() {
        assertEquals("mas musica en vivo", normalizedSearchText("  Más   Música en VIVO "))
        assertFalse(searchTextMatches("Mix jazz", ".*"))
    }
    @Test fun rankingIsExactThenPrefixThenWordBoundaryThenSubstring() {
        val q = "sol"
        assertEquals(0, searchMatchRank("Sol", q))
        assertEquals(1, searchMatchRank("Sol naciente", q))
        assertEquals(2, searchMatchRank("Luz de sol", q))
        assertEquals(3, searchMatchRank("Absoluto", q))
        assertEquals(Int.MAX_VALUE, searchMatchRank("Luna", q))
    }
}
