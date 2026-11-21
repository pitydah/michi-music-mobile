package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataUnverifiedIndexRowPolicyTest {
    private val oldSha = "a".repeat(64)
    private val idx = MetadataIndexPublicationPolicy.IndexIdentity(
        "", "track-1", "content://media/11", 0, "")
    private fun row() = MetadataUnverifiedIndexRowPolicy.Row(
        "", "track-1", "content://media/11", 2048, 0, 0, "", "")
    @Test fun noHistoricalHashUsesActualJournalOriginalProofNotInventedProof() {
        assertEquals(MetadataUnverifiedIndexRowPolicy.Verdict.MayAttemptExternalCas,
            MetadataUnverifiedIndexRowPolicy.inspect(row(),idx,oldSha))
    }
    @Test fun mixedPartiallyVerifiedRowsAreBlocked() {
        assertTrue(MetadataUnverifiedIndexRowPolicy.inspect(row().copy(verifiedBytes=2048),
            idx,oldSha) is MetadataUnverifiedIndexRowPolicy.Verdict.Blocked)
        assertTrue(MetadataUnverifiedIndexRowPolicy.inspect(row().copy(verifiedAt=5),
            idx,oldSha) is MetadataUnverifiedIndexRowPolicy.Verdict.Blocked)
        assertTrue(MetadataUnverifiedIndexRowPolicy.inspect(row().copy(verifiedSha256=oldSha),
            idx,oldSha) is MetadataUnverifiedIndexRowPolicy.Verdict.Blocked)
    }
    @Test fun noTimestampOrSizeMayStandInForJournalSha() {
        assertTrue(MetadataUnverifiedIndexRowPolicy.inspect(row(),idx,"")
            is MetadataUnverifiedIndexRowPolicy.Verdict.Blocked)
        assertTrue(MetadataUnverifiedIndexRowPolicy.inspect(row(),idx,"0".repeat(64))
            is MetadataUnverifiedIndexRowPolicy.Verdict.Blocked)
    }
    @Test fun pathTrackAndOwnerAreExact() {
        assertTrue(MetadataUnverifiedIndexRowPolicy.inspect(row().copy(uri="content://other"),
            idx,oldSha) is MetadataUnverifiedIndexRowPolicy.Verdict.Blocked)
        assertTrue(MetadataUnverifiedIndexRowPolicy.inspect(row().copy(trackId="track-2"),
            idx,oldSha) is MetadataUnverifiedIndexRowPolicy.Verdict.Blocked)
    }
    @Test fun indexedHashCannotMasqueradeAsUnindexed() {
        assertTrue(MetadataUnverifiedIndexRowPolicy.inspect(row(),idx.copy(version=9),oldSha)
            is MetadataUnverifiedIndexRowPolicy.Verdict.Blocked)
        assertTrue(MetadataUnverifiedIndexRowPolicy.inspect(
            row(),idx.copy(observedSourceSha256=oldSha),oldSha)
            is MetadataUnverifiedIndexRowPolicy.Verdict.Blocked)
    }
}
