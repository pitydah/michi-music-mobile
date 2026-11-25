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
    private val scopedEvidence = CertifiedWriteEvidence(
        transactionId = id,
        profileId = MediaStoreMp3TextProfile.ID,
        evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
        sourceId = "lib_device_local",
        trackId = "song_42",
        uri = "content://media/external/audio/media/42",
        originalSha256 = "b".repeat(64),
        candidateSha256 = sha,
        candidateBytes = 5041L,
    )

    private fun certified(evidence: CertifiedWriteEvidence = scopedEvidence,
                          fields: Boolean = true, owner: Boolean = true) =
        MetadataVerifiedReceiptGate.assessCertified(
            evidence, id, MetadataRecoveryPhase.POSTCHECK_VERIFIED, sha,
            MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED,
            fields, owner, backupIntact = true,
        )

    @Test fun exactMp3PermitCanIssueCertifiedReceiptOnlyAfterPhysicalProof() {
        assertEquals(
            MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare(id, sha),
            certified(),
        )
        assertTrue(certified(fields = false) is MetadataVerifiedReceiptGate.Decision.Refused)
        assertTrue(certified(owner = false) is MetadataVerifiedReceiptGate.Decision.Refused)
    }

    @Test fun exactSafProfileCanIssueReceiptWithoutBorrowingMediaStoreEvidence() {
        val saf = scopedEvidence.copy(
            profileId = SafLocalMp3TitleProfile.ID,
            evidenceId = SafLocalMp3TitleProfile.EVIDENCE_ID,
            sourceId = "safroot_" + "c".repeat(40),
            trackId = "saf_" + "d".repeat(40),
            uri = "content://com.android.externalstorage.documents/" +
                "document/primary%3ADownload%2FMichi%2Fsong.mp3",
        )
        assertEquals(
            MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare(id, sha),
            certified(saf),
        )
        assertEquals(
            MetadataVerifiedReceiptGate.Refusal.INVALID_TRANSACTION,
            (certified(saf.copy(
                profileId = MediaStoreMp3TextProfile.ID,
                evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
            )) as MetadataVerifiedReceiptGate.Decision.Refused).reason,
        )
    }

    @Test fun arbitraryProfileOrEvidenceLabelCannotCertifySource() {
        for (forged in listOf(
            scopedEvidence.copy(profileId = "FLAC_ALLOWED"),
            scopedEvidence.copy(evidenceId = "ANY"),
            scopedEvidence.copy(sourceId = "different_library"),
            scopedEvidence.copy(uri = "content://foreign/doc/1"),
            scopedEvidence.copy(originalSha256 = "NOT_A_HASH"),
            scopedEvidence.copy(candidateBytes = 0L),
        )) {
            assertEquals(
                MetadataVerifiedReceiptGate.Decision.Refused(
                    MetadataVerifiedReceiptGate.Refusal.INVALID_TRANSACTION,
                ),
                certified(forged),
            )
        }
    }

    @Test fun missingBackupDoesNotCreateReadyReceipt() {
        assertTrue(check(backup=false) is MetadataVerifiedReceiptGate.Decision.Refused)
    }
}
