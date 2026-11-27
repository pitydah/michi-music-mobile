package org.michimusic.mobile.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistTrackProofPairTest {
    private fun claim(signature: String = "f".repeat(64) + "\u000042") =
        ArtistExactOccurrenceClaim("lib", "album", "A", "A", "source\u0000id\u0000/file", "id", signature)
    private val valid = ArtistTrackByteProof("f".repeat(64), 42L)

    @Test fun twoEqualReadsProduceAdmissionProof() = runBlocking {
        var reads = 0
        assertEquals(valid, ArtistTrackProofPair.read(claim()) { reads++; valid })
        assertEquals(2, reads)
    }
    @Test fun changedBytesAreRefused() = runBlocking {
        var reads = 0
        assertNull(ArtistTrackProofPair.read(claim()) {
            reads++
            if (reads == 1) valid else valid.copy(sha256 = "a".repeat(64))
        })
        assertEquals(2, reads)
    }
    @Test fun unreadableSourceIsRefusedWithoutSecondRead() = runBlocking {
        var reads = 0
        assertNull(ArtistTrackProofPair.read(claim()) { reads++; null })
        assertEquals(1, reads)
    }
    @Test fun wrongCatalogHashRefusedBeforeSecondRead() = runBlocking {
        var reads = 0
        assertNull(ArtistTrackProofPair.read(claim("a".repeat(64) + "\u000042")) {
            reads++; valid
        })
        assertEquals(1, reads)
    }
    @Test fun wrongCatalogSizeRejected() = runBlocking {
        assertNull(ArtistTrackProofPair.read(claim("unknown\u000043")) { valid })
    }
    @Test fun hugeProofIsRejected() = runBlocking {
        assertNull(ArtistTrackProofPair.read(claim("unknown\u00000")) {
            valid.copy(byteCount = ArtistTrackSourceProof.MAX_BYTES + 1)
        })
    }
}
