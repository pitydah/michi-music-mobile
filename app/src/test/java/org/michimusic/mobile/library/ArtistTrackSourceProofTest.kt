package org.michimusic.mobile.library

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistTrackSourceProofTest {
    @Test fun exactBytesProduceStableProof() {
        val b=byteArrayOf(0,1,2,3,4)
        val a=ArtistTrackSourceProof.digest{ByteArrayInputStream(b)}
        val c=ArtistTrackSourceProof.digest{ByteArrayInputStream(b)}
        assertEquals(a,c)
        assertEquals(5L,a?.byteCount)
        assertEquals(64,a?.sha256?.length)
    }
    @Test fun changedBytesProduceDifferentProof() {
        val a=ArtistTrackSourceProof.digest{ByteArrayInputStream(byteArrayOf(1,2,3))}
        val b=ArtistTrackSourceProof.digest{ByteArrayInputStream(byteArrayOf(1,2,4))}
        assertTrue(a!=b)
    }
    @Test fun knownCatalogueHashAnchorsTheExactBytes() {
        val a=ArtistTrackSourceProof.digest{ByteArrayInputStream(byteArrayOf(5,6,7))}!!
        assertTrue(ArtistTrackSourceProof.consistentWithCatalog(a, a.sha256 + "\u0000metadata"))
        assertTrue(!ArtistTrackSourceProof.consistentWithCatalog(a, "f".repeat(64)+"\u0000metadata"))
    }
    @Test fun truncatedReadNeverCountsAsFullProof() {
        assertEquals(null, ArtistTrackSourceProof.digest(maxBytes=10) { ByteArrayInputStream(ByteArray(11){2}) })
    }
    @Test fun emptyOrUnopenableBytesAreNotProof() {
        assertEquals(null, ArtistTrackSourceProof.digest{null})
        assertEquals(null, ArtistTrackSourceProof.digest{ByteArrayInputStream(byteArrayOf())})
    }
}
