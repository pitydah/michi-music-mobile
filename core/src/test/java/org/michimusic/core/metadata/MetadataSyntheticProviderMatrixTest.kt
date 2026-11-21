package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.michimusic.core.metadata.MetadataSyntheticProviderMatrix as M
import org.michimusic.core.metadata.MetadataSyntheticProviderEvidence as E

class MetadataSyntheticProviderMatrixTest {
    private val id="me08-fixture-" + "a".repeat(32)
    private val complete = listOf(
        M.Step(id,E.Cut.BEFORE_OPEN,E.Outcome.UNCHANGED,E.Restore.STILL_ORIGINAL),
        M.Step(id,E.Cut.AFTER_TRUNCATE,E.Outcome.TRUNCATED_TO_ZERO,E.Restore.SYNTHETIC_ORIGINAL_RESTORED),
        M.Step(id,E.Cut.AFTER_FIRST_BLOCK,E.Outcome.PARTIAL_OR_FOREIGN_BYTES,E.Restore.SYNTHETIC_ORIGINAL_RESTORED),
        M.Step(id,E.Cut.AFTER_SYNC,E.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER,E.Restore.SYNTHETIC_ORIGINAL_RESTORED),
        M.Step(id,E.Cut.NONE,E.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER,E.Restore.SYNTHETIC_ORIGINAL_RESTORED),
    )
    @Test fun completeSetIsStillSynthetic() { assertEquals(M.Verdict.COMPLETE_SYNTHETIC_ONLY,M.evaluate(complete)) }
    @Test fun missingCutIsIncomplete() { assertEquals(M.Verdict.INCOMPLETE_SCENARIOS,M.evaluate(complete.dropLast(1))) }
    @Test fun duplicateCutIsIncomplete() { assertEquals(M.Verdict.INCOMPLETE_SCENARIOS,M.evaluate(complete.mapIndexed { i,s -> if (i==4) s.copy(cut=E.Cut.AFTER_SYNC) else s })) }
    @Test fun failedRestoreBlocks() { assertEquals(M.Verdict.UNRECOVERED_TEST_BYTES,M.evaluate(complete.mapIndexed { i,s -> if(i==1) s.copy(restoration=E.Restore.NOT_RESTORED) else s })) }
    @Test fun wrongDocumentBlocks() { assertEquals(M.Verdict.INVALID_FIXTURE,M.evaluate(complete.mapIndexed { i,s -> if(i==1) s.copy(documentId="other") else s })) }
    @Test fun writeModeMustBeExplicitRwt() { assertEquals(M.Verdict.INVALID_FIXTURE,M.evaluate(complete.mapIndexed { i,s -> if(i==1) s.copy(mode="w") else s })) }
    @Test fun truncatedWasNotCandidate() { assertEquals(M.Verdict.INCONSISTENT_OBSERVATION,M.evaluate(complete.mapIndexed { i,s -> if(i==1) s.copy(outcome=E.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER) else s })) }
    @Test fun emptyIsInvalid() { assertEquals(M.Verdict.INVALID_FIXTURE,M.evaluate(emptyList())) }
    @Test fun fixtureOutcomeNoRealGrant() { for (v in M.Verdict.entries) assertFalse(M.mayEnableOriginalWrite(v)) }
}
