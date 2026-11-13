package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.data.cache.EmbeddedTagEvidence

class ColdMetadataStampPolicyTest {
    private fun tag(owner: String = "saf-a", timestamp: Long = 77L) = EmbeddedTagEvidence(
        sourceId = owner, trackId = "track", sizeBytes = 512L, modifiedAt = timestamp,
        parserVersion = EmbeddedTagEvidence.CURRENT_PARSER_VERSION)
    private fun ok(before: RecoveryDocumentStamp? = RecoveryDocumentStamp(512L, 77L),
        after: RecoveryDocumentStamp? = RecoveryDocumentStamp(512L, 77L),
        evidence: EmbeddedTagEvidence? = tag()) = ColdMetadataStampPolicy.matches(
        "saf-a", "track", "content://authority/document/1", 512L,
        before, after, evidence)
    @Test fun unchangedDocumentAndOwnerPass() = assertTrue(ok())
    @Test fun missingProviderStampOrMtimeZeroFails() {
        assertFalse(ok(before = null))
        assertFalse(ok(after = RecoveryDocumentStamp(512L, 0L)))
        assertFalse(ok(before = RecoveryDocumentStamp(512L, 0L)))
    }
    @Test fun sameSizeDifferentTimestampFails() {
        assertFalse(ok(after = RecoveryDocumentStamp(512L, 78L)))
        assertFalse(ok(evidence = tag(timestamp = 78L)))
    }
    @Test fun swappedOwnerOrIndexFails() {
        assertFalse(ok(evidence = tag(owner = "saf-b")))
        assertFalse(ok(evidence = null))
        assertFalse(ColdMetadataStampPolicy.matches("saf-a", "track", "https://peer", 512L,
            RecoveryDocumentStamp(512L, 77L), RecoveryDocumentStamp(512L, 77L), tag()))
    }
}
