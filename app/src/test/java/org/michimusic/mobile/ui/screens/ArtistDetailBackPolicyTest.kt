package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistDetailBackPolicyTest {
    @Test fun enrichmentMustReturnToArtistBeforeLeavingLibrary() {
        assertEquals(ArtistDetailBackDestination.OVERVIEW, artistDetailBackDestination(true))
        assertEquals(ArtistDetailBackDestination.LIBRARY, artistDetailBackDestination(false))
    }
}
