package org.michimusic.mobile.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumNavigationPolicyTest {
    @Test fun artistDiscographyShowsReturnToArtist() {
        assertEquals("Volver a artista", albumDetailBackLabel("artist/{artistKey}?enrich={enrich}"))
        assertEquals("Volver a artista", albumDetailBackLabel("artist/Midnight%20Oil?enrich=false"))
    }

    @Test fun libraryAlbumsShowsReturnToAlbums() {
        assertEquals("Volver a álbumes", albumDetailBackLabel("library"))
        assertEquals("Volver a álbumes", albumDetailBackLabel(null))
    }
}
