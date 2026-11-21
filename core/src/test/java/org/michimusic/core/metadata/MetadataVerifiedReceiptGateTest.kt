package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataVerifiedReceiptGateTest {
    private val id = "3e4989fb-6efe-432b-a00e-ef3b598a9202"
    private val sha = "a".repeat(64)
    private fun check(phase: MetadataRecoveryPhase = MetadataRecoveryPhase.POSTCHECK_VERIFIED,
        physical: MetadataPhysicalOutcomePolicy.State = MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED,
        fields: Boolean = true, owner: Boolean = true, backup: Boolean = true,
        tx: String = id, hash: String = sha) = MetadataVerifiedReceiptGate.assess(
        tx,phase,hash,physical,fields,owner,backup)

    @Test fun rejectsStagingAsSaved() {
        assertEquals(MetadataVerifiedReceiptGate.Decision.Refused(
            MetadataVerifiedReceiptGate.Refusal.NOT_SOURCE_MUTATION),
            check(phase=MetadataRecoveryPhase.STAGE_READY))
    }
    @Test fun rejectsSnapshottingAsSaved() {
        assertEquals(MetadataVerifiedReceiptGate.Refusal.NOT_SOURCE_MUTATION,
            (check(phase=MetadataRecoveryPhase.SNAPSHOTTING) as MetadataVerifiedReceiptGate.Decision.Refused).reason)
    }
    @Test fun rejectsUncertifiedEvenWithMatchingShaAndFieldReadback() {
        assertEquals(MetadataVerifiedReceiptGate.Refusal.WRITER_NOT_CERTIFIED,
            (check() as MetadataVerifiedReceiptGate.Decision.Refused).reason)
    }
    @Test fun rejectsBadTransactionId() {
        assertEquals(MetadataVerifiedReceiptGate.Refusal.INVALID_TRANSACTION,
            (check(tx="../escape") as MetadataVerifiedReceiptGate.Decision.Refused).reason)
    }
    @Test fun rejectsInvalidDigest() {
        assertEquals(MetadataVerifiedReceiptGate.Refusal.INVALID_TRANSACTION,
            (check(hash="ABC") as MetadataVerifiedReceiptGate.Decision.Refused).reason)
    }
    @Test fun noReceiptCanBeIssuedFromCurrentUncertifiedPatch() {
        val phases=MetadataRecoveryPhase.entries
        for (phase in phases) assertTrue(check(phase=phase) is MetadataVerifiedReceiptGate.Decision.Refused)
    }
    @Test fun unknownSourceDoesNotCreateReadyReceipt() {
        assertTrue(check(physical=MetadataPhysicalOutcomePolicy.State.UNKNOWN_BYTES,owner=false)
            is MetadataVerifiedReceiptGate.Decision.Refused)
    }
    @Test fun unavailableReadbackDoesNotCreateReadyReceipt() {
        assertTrue(check(physical=MetadataPhysicalOutcomePolicy.State.READBACK_UNAVAILABLE,fields=false)
            is MetadataVerifiedReceiptGate.Decision.Refused)
    }
    @Test fun missingBackupDoesNotCreateReadyReceipt() {
        assertTrue(check(backup=false) is MetadataVerifiedReceiptGate.Decision.Refused)
    }
}
