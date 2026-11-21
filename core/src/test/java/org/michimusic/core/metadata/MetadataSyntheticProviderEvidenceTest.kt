package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.michimusic.core.metadata.MetadataSyntheticProviderEvidence as E

class MetadataSyntheticProviderEvidenceTest {
    private val a = E.Fingerprint(10, "a".repeat(64))
    private val b = E.Fingerprint(4, "b".repeat(64))
    private fun trial(read: E.Fingerprint? = a, cut: E.Cut = E.Cut.NONE,
        closed: Boolean = true) = E.Trial(E.FIXTURE_AUTHORITY, "me08-fixture-" + "a".repeat(32),a,b,read,cut,closed)

    @Test fun unchangedAfterCut() { assertEquals(E.Outcome.UNCHANGED,E.classify(trial(cut=E.Cut.BEFORE_OPEN))) }
    @Test fun exactCandidateIsOnlySynthetic() { assertEquals(E.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER,E.classify(trial(b))) }
    @Test fun candidateWithoutCloseStillNotCertified() { assertEquals(E.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER,E.classify(trial(b,closed=false))) }
    @Test fun halfWriteIsUnknown() { assertEquals(E.Outcome.PARTIAL_OR_FOREIGN_BYTES,E.classify(trial(E.Fingerprint(2,"c".repeat(64))))) }
    @Test fun sameSizeForeignBytesAreUnknown() { assertEquals(E.Outcome.PARTIAL_OR_FOREIGN_BYTES,E.classify(trial(E.Fingerprint(4,"c".repeat(64))))) }
    @Test fun zeroBytesAreExplicitlyTruncated() { assertEquals(E.Outcome.TRUNCATED_TO_ZERO,E.classify(trial(E.Fingerprint(0,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")))) }
    @Test fun missingReadbackFailsClosed() { assertEquals(E.Outcome.OBSERVATION_UNAVAILABLE,E.classify(trial(null))) }
    @Test fun incorrectAuthorityRejected() { assertEquals(E.Outcome.INVALID_FIXTURE,E.classify(trial().copy(authority="media"))) }
    @Test fun incorrectDocumentRejected() { assertEquals(E.Outcome.INVALID_FIXTURE,E.classify(trial().copy(documentId="user-audio"))) }
    @Test fun invalidHashRejected() { assertEquals(E.Outcome.INVALID_FIXTURE,E.classify(trial().copy(original=a.copy(sha256="bad")))) }
    @Test fun identicalCandidateIsInvalidTrial() { assertEquals(E.Outcome.INVALID_FIXTURE,E.classify(trial().copy(candidate=a))) }
    @Test fun restoreRequiresConfirmation() { assertEquals(E.Restore.BLOCKED,E.restoration(trial(b),a,false)) }
    @Test fun restoredOriginalRecognized() { assertEquals(E.Restore.SYNTHETIC_ORIGINAL_RESTORED,E.restoration(trial(b),a,true)) }
    @Test fun failedRestoreNotMisreported() { assertEquals(E.Restore.NOT_RESTORED,E.restoration(trial(b),b,true)) }
    @Test fun unchangedDoesNotNeedRestore() { assertEquals(E.Restore.STILL_ORIGINAL,E.restoration(trial(a),null,true)) }
    @Test fun allResultsForeverWithholdProductCertification() {
        for (outcome in E.Outcome.entries) assertFalse(E.mayEnableRealSourceWrites(outcome))
    }
}
