package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataPreparedCommitSnapshotTest {
    private val id="9b44d1bc-110c-42a2-8450-838f0e793501"
    private val original="a".repeat(64)
    private val stage="b".repeat(64)
    private fun input()=MetadataPreparedCommitSnapshot.Input(
        id,MetadataRecoveryPhase.STAGE_READY,"","song-one",
        "content://media/external/audio/media/1",1024,original,2048,stage,"FLAC")

    private fun refusal(value:MetadataPreparedCommitSnapshot.Input) =
        (MetadataPreparedCommitSnapshot.evaluate(value) as
            MetadataPreparedCommitSnapshot.Decision.Refused).reason

    @Test fun validJournaledPrivateCandidateIsNotSourceWritePermission() {
        val result=MetadataPreparedCommitSnapshot.evaluate(input()) as
            MetadataPreparedCommitSnapshot.Decision.Candidate
        assertEquals(MetadataAudioContainer.FLAC,result.evidence.format)
        assertEquals("",result.evidence.sourceId)
        assertFalse(result.evidence.canCommitToProvider)
    }

    @Test fun onlyStageReadyMayEnterPreflight() {
        for (phase in MetadataRecoveryPhase.entries.filter { it!=MetadataRecoveryPhase.STAGE_READY })
            assertEquals(MetadataPreparedCommitSnapshot.Reason.NOT_STAGE_READY,
                refusal(input().copy(phase=phase)))
    }

    @Test fun invalidTransactionOrIdentityIsRejected() {
        assertEquals(MetadataPreparedCommitSnapshot.Reason.INVALID_TRANSACTION,
            refusal(input().copy(transactionId="../not-a-uuid")))
        assertEquals(MetadataPreparedCommitSnapshot.Reason.INVALID_TARGET,
            refusal(input().copy(trackId="")))
        assertEquals(MetadataPreparedCommitSnapshot.Reason.INVALID_TARGET,
            refusal(input().copy(uri="file:///external/a.flac")))
    }

    @Test fun rejectsInvalidOriginalFingerprintAndSizes() {
        assertEquals(MetadataPreparedCommitSnapshot.Reason.INVALID_SOURCE_FINGERPRINT,
            refusal(input().copy(originalSha256="invalid")))
        assertEquals(MetadataPreparedCommitSnapshot.Reason.INVALID_SOURCE_FINGERPRINT,
            refusal(input().copy(originalBytes=0)))
        assertEquals(MetadataPreparedCommitSnapshot.Reason.INVALID_SOURCE_FINGERPRINT,
            refusal(input().copy(originalBytes=1024L*1024*1024+1)))
    }

    @Test fun rejectsInvalidStageFingerprintAndSizes() {
        assertEquals(MetadataPreparedCommitSnapshot.Reason.INVALID_CANDIDATE_FINGERPRINT,
            refusal(input().copy(candidateSha256="A".repeat(64))))
        assertEquals(MetadataPreparedCommitSnapshot.Reason.INVALID_CANDIDATE_FINGERPRINT,
            refusal(input().copy(candidateBytes=0)))
        assertEquals(MetadataPreparedCommitSnapshot.Reason.INVALID_CANDIDATE_FINGERPRINT,
            refusal(input().copy(candidateBytes=1024L*1024*1024+1)))
    }

    @Test fun rejectsIdenticalSourceAndCandidate() {
        assertEquals(MetadataPreparedCommitSnapshot.Reason.IDENTICAL_BYTES,
            refusal(input().copy(candidateBytes=1024,candidateSha256=original)))
    }

    @Test fun rejectsUnsupportedWriterFormatsWithoutGuessingFromExtension() {
        for (format in listOf("OGG_OPUS","UNKNOWN","FLAC ",""))
            assertEquals(MetadataPreparedCommitSnapshot.Reason.UNSUPPORTED_FORMAT,
                refusal(input().copy(format=format)))
    }

    @Test fun matureAudioOnlyM4aCandidateMayEnterTypedPreflight() {
        val result = MetadataPreparedCommitSnapshot.evaluate(
            input().copy(format = "MP4_M4A"),
        ) as MetadataPreparedCommitSnapshot.Decision.Candidate

        assertEquals(MetadataAudioContainer.MP4_M4A, result.evidence.format)
        assertFalse(result.evidence.canCommitToProvider)
    }

    @Test fun id3VersionsHaveOnlyStructuralEvidenceNotCertification() {
        for (format in listOf("ID3_V23","ID3_V24")) {
            val result=MetadataPreparedCommitSnapshot.evaluate(input().copy(format=format)) as
                MetadataPreparedCommitSnapshot.Decision.Candidate
            assertTrue(result.evidence.format.name==format)
            assertFalse(result.evidence.canCommitToProvider)
        }
    }

    @Test fun additionalPrivateStageFormatsAreNotWriteGrants() {
        for(format in listOf("WAVPACK","APE")) {
            val result=MetadataPreparedCommitSnapshot.evaluate(input().copy(format=format)) as
                MetadataPreparedCommitSnapshot.Decision.Candidate
            assertEquals(format,result.evidence.format.name)
            assertFalse(result.evidence.canCommitToProvider)
        }
    }

    @Test fun sourceIdMayBeEmptyButCannotBeReplacedWithAnotherOwner() {
        val source=MetadataPreparedCommitSnapshot.evaluate(input()) as
            MetadataPreparedCommitSnapshot.Decision.Candidate
        val foreign=MetadataPreparedCommitSnapshot.evaluate(input().copy(sourceId="other")) as
            MetadataPreparedCommitSnapshot.Decision.Candidate
        assertTrue(source.evidence.sourceId!=foreign.evidence.sourceId)
    }
}
