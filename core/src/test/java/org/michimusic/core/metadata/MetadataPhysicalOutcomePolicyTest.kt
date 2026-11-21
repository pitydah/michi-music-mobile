package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataPhysicalOutcomePolicyTest {
    private val before = "a".repeat(64)
    private val after = "b".repeat(64)
    private fun expected()=MetadataPhysicalOutcomePolicy.Expected(200,before,240,after)
    private fun classify(bytes:Long,sha:String) = MetadataPhysicalOutcomePolicy.classify(
        expected(),MetadataPhysicalOutcomePolicy.Observed(bytes,sha))

    @Test fun originalRemainsIntactAfterInterruptedWrite() {
        assertEquals(MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT,classify(200,before))
    }
    @Test fun sourceExactCandidateBytesAreRecognized() {
        assertEquals(MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED,classify(240,after))
    }
    @Test fun changedBytesSameSizeAreUnknown() {
        assertEquals(MetadataPhysicalOutcomePolicy.State.UNKNOWN_BYTES,classify(200,"c".repeat(64)))
    }
    @Test fun correctDigestWrongSizeIsUnknown() {
        assertEquals(MetadataPhysicalOutcomePolicy.State.UNKNOWN_BYTES,classify(239,after))
    }
    @Test fun unknownDigestAfterProviderOverwriteIsNotSaved() {
        assertEquals(MetadataPhysicalOutcomePolicy.State.UNKNOWN_BYTES,classify(240,"d".repeat(64)))
    }
    @Test fun missingReadbackNeverBecomesSaved() {
        assertEquals(MetadataPhysicalOutcomePolicy.State.READBACK_UNAVAILABLE,
            MetadataPhysicalOutcomePolicy.classify(expected(),null))
    }
    @Test fun uppercaseShaOrTruncatedDigestIsUnverified() {
        assertEquals(MetadataPhysicalOutcomePolicy.State.READBACK_UNAVAILABLE,classify(240,"B".repeat(64)))
        assertEquals(MetadataPhysicalOutcomePolicy.State.READBACK_UNAVAILABLE,classify(240,"b".repeat(10)))
    }
    @Test fun identicalOriginalAndCandidateCannotRepresentEdit() {
        val bad=MetadataPhysicalOutcomePolicy.Expected(200,before,200,before)
        assertEquals(MetadataPhysicalOutcomePolicy.State.INVALID_EXPECTATION,
            MetadataPhysicalOutcomePolicy.classify(bad,MetadataPhysicalOutcomePolicy.Observed(200,before)))
    }
    @Test fun invalidExpectedDigestBlocksEvenWithPhysicalReadback() {
        val bad=expected().copy(candidateSha256="fake")
        assertEquals(MetadataPhysicalOutcomePolicy.State.INVALID_EXPECTATION,
            MetadataPhysicalOutcomePolicy.classify(bad,MetadataPhysicalOutcomePolicy.Observed(240,after)))
    }
    @Test fun reconciliationNeverPublishesWithoutFieldReadback() {
        assertEquals(MetadataReconciliationPolicy.State.BLOCKED_UNVERIFIED,
            MetadataReconciliationPolicy.evaluate(MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED,false,true))
    }
    @Test fun verifiedReceiptNeedsCurrentOwner() {
        assertEquals(MetadataReconciliationPolicy.State.BLOCKED_UNKNOWN,
            MetadataReconciliationPolicy.evaluate(MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED,true,false))
    }
    @Test fun verifiedByteAndFieldEvidenceMayRefreshIndex() {
        assertEquals(MetadataReconciliationPolicy.State.ELIGIBLE_AFTER_FIELD_READBACK,
            MetadataReconciliationPolicy.evaluate(MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED,true,true))
    }
    @Test fun originalStillPresentNeedsNoIndexMutation() {
        assertEquals(MetadataReconciliationPolicy.State.ORIGINAL_NOT_MODIFIED,
            MetadataReconciliationPolicy.evaluate(MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT,true,true))
    }
}
