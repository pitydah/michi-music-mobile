package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ME-10K tests for OpenCode; not run or compiled in Drive. */
class MetadataPrivateSessionIdentityTest {
    private fun target(source: String="", track: String="42",
        uri: String="content://media/external/audio/42", proof: String="reader-v1:abc") =
        MetadataTarget(source, track, uri, proof)

    @Test fun sameOwnerHasStableLowercaseSha256() {
        val a=MetadataPrivateSessionIdentity.digest(target())
        assertEquals(a, MetadataPrivateSessionIdentity.digest(target()))
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }
    @Test fun aChangedSourceIsNotTheSameOwner() {
        assertNotEquals(MetadataPrivateSessionIdentity.digest(target()),
            MetadataPrivateSessionIdentity.digest(target(source="peer-A")))
    }
    @Test fun aChangedTrackIsNotTheSameOwner() {
        assertNotEquals(MetadataPrivateSessionIdentity.digest(target()),
            MetadataPrivateSessionIdentity.digest(target(track="43")))
    }
    @Test fun aChangedUriIsNotTheSameOwner() {
        assertNotEquals(MetadataPrivateSessionIdentity.digest(target()),
            MetadataPrivateSessionIdentity.digest(target(uri="content://media/external/audio/43")))
    }
    @Test fun changedColdEvidenceInvalidatesLegacyAssociation() {
        assertNotEquals(MetadataPrivateSessionIdentity.digest(target()),
            MetadataPrivateSessionIdentity.digest(target(proof="reader-v1:def")))
    }
    @Test fun lengthFramingPreventsAmbiguousConcatenation() {
        assertNotEquals(MetadataPrivateSessionIdentity.digest(target(source="a",track="bc")),
            MetadataPrivateSessionIdentity.digest(target(source="ab",track="c")))
    }
}
