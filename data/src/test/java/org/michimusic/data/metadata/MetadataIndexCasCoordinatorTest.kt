package org.michimusic.data.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataArtworkEvidence
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.metadata.MetadataVerifiedReceiptGate

class MetadataIndexCasCoordinatorTest {
    private val tx = "12345678-1234-4123-8123-123456789abc"
    private val source = MetadataIndexPublicationPolicy.IndexIdentity(
        "lib_device_local", "1", "content://media/1", 2, "a".repeat(64))
    private val target = MetadataTarget("lib_device_local", "1", "content://media/1", "evidence")
    private val sha = "b".repeat(64)
    private val delta = MetadataSourceIndexDelta.Result.PreparedForExternalCas(
        MetadataSourceIndexDelta.Delta(target, tx, sha, emptyMap(), emptySet(), candidateBytes = 120))
    private val receipt = MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare(tx, sha)

    @Test fun uncataloguedWriterDoesNotEvenReadRoom() = runBlocking {
        var requests = 0
        val coordinator = MetadataIndexCasCoordinator(object : MetadataIndexCasCoordinator.Port {
            override suspend fun current(targetSource: String,targetTrack: String):
                MetadataIndexPublicationPolicy.IndexIdentity? { requests++;return null }
            override suspend fun updateIfVersion(
                expected: MetadataIndexPublicationPolicy.IndexIdentity,
                delta: MetadataSourceIndexDelta.Delta): Boolean { requests++;return true }
        })
        val result = coordinator.attempt(source, delta, MetadataRecoveryPhase.POSTCHECK_VERIFIED,
            receipt, true, { true })
        assertEquals(MetadataIndexCasCoordinator.Result.Blocked(
            MetadataIndexCasCoordinator.Refusal.NOT_CERTIFIED), result)
        assertEquals(0, requests)
    }

    @Test fun exactTypedPermitReachesTheExistingCas() = runBlocking {
        var updates = 0
        val coordinator = MetadataIndexCasCoordinator(object : MetadataIndexCasCoordinator.Port {
            override suspend fun current(targetSource: String,targetTrack: String) = source
            override suspend fun updateIfVersion(
                expected: MetadataIndexPublicationPolicy.IndexIdentity,
                delta: MetadataSourceIndexDelta.Delta): Boolean { updates++;return true }
        })
        val permit = MetadataWritePermit(
            transactionId = tx, sourceId = source.sourceId, trackId = source.trackId,
            uri = source.uri, originalSha256 = source.observedSourceSha256,
            candidateSha256 = sha, stageBytes = 120, profileId = MediaStoreMp3TextProfile.ID,
            evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID, container = "ID3_V24",
            revision = 4, fields = setOf(MetadataField.TITLE), explicitUserAction = true,
        )
        assertEquals(MetadataIndexCasCoordinator.Result.AppliedToIndexOnly(tx),
            coordinator.attempt(source, delta, MetadataRecoveryPhase.POSTCHECK_VERIFIED,
                receipt, true, { true }, permit))
        assertEquals(1, updates)
    }

    @Test fun exactAssetOnlyPermitReachesTheExistingCas() = runBlocking {
        var updates = 0
        val coordinator = MetadataIndexCasCoordinator(object : MetadataIndexCasCoordinator.Port {
            override suspend fun current(targetSource: String, targetTrack: String) = source
            override suspend fun updateIfVersion(
                expected: MetadataIndexPublicationPolicy.IndexIdentity,
                delta: MetadataSourceIndexDelta.Delta,
            ): Boolean { updates++; return true }
        })
        val resource = MetadataCommitIntentManifest.ResourceChange.PictureReplacement(
            ordinal = 0,
            format = MetadataArtworkEvidence.Format.ID3_APIC,
            type = 3,
            description = "",
            beforeMime = "image/png",
            beforeBytes = 68,
            beforeSha256 = "c".repeat(64),
            afterMime = "image/png",
            afterBytes = 69,
            afterSha256 = "d".repeat(64),
        )
        val permit = MetadataWritePermit(
            transactionId = tx, sourceId = source.sourceId, trackId = source.trackId,
            uri = source.uri, originalSha256 = source.observedSourceSha256,
            candidateSha256 = sha, stageBytes = 120,
            profileId = MediaStoreMp3TextProfile.ID,
            evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
            container = "ID3_V24", revision = 4, fields = emptySet(),
            explicitUserAction = true, resources = listOf(resource),
        )

        assertEquals(
            MetadataIndexCasCoordinator.Result.AppliedToIndexOnly(tx),
            coordinator.attempt(
                source, delta, MetadataRecoveryPhase.POSTCHECK_VERIFIED,
                receipt, true, { true }, permit,
            ),
        )
        assertEquals(1, updates)
    }

    @Test fun permitWithoutExactCandidateCannotOpenTheCas() = runBlocking {
        var requests = 0
        val coordinator = MetadataIndexCasCoordinator(object : MetadataIndexCasCoordinator.Port {
            override suspend fun current(targetSource: String,targetTrack: String):
                MetadataIndexPublicationPolicy.IndexIdentity? { requests++;return source }
            override suspend fun updateIfVersion(
                expected: MetadataIndexPublicationPolicy.IndexIdentity,
                delta: MetadataSourceIndexDelta.Delta): Boolean { requests++;return true }
        })
        val permit = MetadataWritePermit(
            transactionId = tx, sourceId = source.sourceId, trackId = source.trackId,
            uri = source.uri, originalSha256 = source.observedSourceSha256,
            candidateSha256 = "c".repeat(64), stageBytes = 120,
            profileId = MediaStoreMp3TextProfile.ID,
            evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID, container = "ID3_V24",
            revision = 4, fields = setOf(MetadataField.TITLE), explicitUserAction = true,
        )
        assertEquals(MetadataIndexCasCoordinator.Result.Blocked(
            MetadataIndexCasCoordinator.Refusal.NOT_CERTIFIED),
            coordinator.attempt(source, delta, MetadataRecoveryPhase.POSTCHECK_VERIFIED,
                receipt, true, { true }, permit))
        assertEquals(0, requests)
    }
}
