package org.michimusic.mobile.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UI8SearchLoadingPolicyTest {
    @Test fun emptyAndSingleLetterQueriesDoNotSpinWithUnreadyCatalog() {
        listOf("", " ", "a", " Á ", "\n", "　").forEach { q ->
            assertFalse("short query must present guidance: [$q]", searchCatalogNeedsLoading(q, false))
        }
    }

    @Test fun actionableQueriesWaitForCatalogIncludingAccentAndWhitespaceNormalization() {
        listOf("ab", " Ár ", "  the cure  ", "niño").forEach { q ->
            assertTrue("actionable query must wait for ready root: [$q]", searchCatalogNeedsLoading(q, false))
        }
    }

    @Test fun playlistQueriesNeverWaitForMusicCatalog() {
        listOf("", "ab", "the cure", "Latin Jazz").forEach { q ->
            assertFalse(searchCatalogNeedsLoading(q, true))
        }
    }
}
