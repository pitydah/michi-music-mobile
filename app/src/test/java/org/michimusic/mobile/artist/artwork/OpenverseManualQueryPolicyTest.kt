package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenverseManualQueryPolicyTest {
    @Test fun twoBoundedTermsOnly() {
        assertEquals(listOf("Midnight Oil live", "Midnight Oil"),
            OpenverseManualQueryPolicy.queries("Midnight Oil live", "Midnight Oil"))
    }
    @Test fun equivalentNamesDoNotRepeatNetworkRequest() {
        assertEquals(listOf("Midnight Oil"),
            OpenverseManualQueryPolicy.queries(" Midnight Oil  ", "midnight oil"))
    }
    @Test fun blankUserQueryNeverLeadsToUnsolicitedArtistLookup() {
        assertEquals(emptyList<String>(), OpenverseManualQueryPolicy.queries("  ", "Known artist"))
    }
    @Test fun controlsAreRemovedAndLengthBounded() {
        val terms=OpenverseManualQueryPolicy.queries(" artist\n\r name  ", "x".repeat(300))
        assertEquals("artist name",terms[0])
        assertEquals(120,terms[1].length)
        assertEquals(2,terms.size)
    }
    @Test fun noVariantsForBlankConfirmedName() {
        assertEquals(listOf("Artist"),OpenverseManualQueryPolicy.queries("Artist", ""))
    }
}
