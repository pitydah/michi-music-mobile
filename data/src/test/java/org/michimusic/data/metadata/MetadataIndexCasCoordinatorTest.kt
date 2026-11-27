package org.michimusic.data.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MediaStoreM4aTextProfile
import org.michimusic.core.metadata.SafLocalMp3TitleProfile
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

    @Test fun m4aPermitRequiresExactProfileRevisionAndTitleField() {
        val entry = MetadataRecoveryJournalV1.Entry(
            id = tx,
            phase = MetadataRecoveryPhase.STAGE_READY,
            sourceId = source.sourceId,
            trackId = source.trackId,
            uri = source.uri,
            expectedBytes = 100,
            expectedModifiedAt = 1,
            originalSha256 = source.observedSourceSha256,
            stageSha256 = sha,
            stageBytes = 120,
            format = "MP4_M4A",
        )
        fun permit(
            revision: Int = 1,
            fields: Set<MetadataField> = setOf(MetadataField.TITLE),
            profileId: String = MediaStoreM4aTextProfile.ID,
        ) = MetadataWritePermit(
            transactionId = tx,
            sourceId = source.sourceId,
            trackId = source.trackId,
            uri = source.uri,
            originalSha256 = source.observedSourceSha256,
            candidateSha256 = sha,
            stageBytes = 120,
            profileId = profileId,
            evidenceId = MediaStoreM4aTextProfile.EVIDENCE_ID,
            container = "MP4_M4A",
            revision = revision,
            fields = fields,
            explicitUserAction = true,
        )

        assertEquals(true, permit().matches(entry))
        assertEquals(false, permit(revision = 2).matches(entry))
        assertEquals(
            false,
            permit(fields = setOf(MetadataField.SUBTITLE)).matches(entry),
        )
        assertEquals(
            true,
            permit(fields = setOf(MetadataField.ARTIST, MetadataField.TRACK_TOTAL))
                .matches(entry),
        )
        assertEquals(
            false,
            permit(profileId = MediaStoreMp3TextProfile.ID).matches(entry),
        )
    }

    @Test fun safPermitCannotBorrowMediaStoreAuthorityOrChangeItsScope() {
        val safSource = "safroot_" + "1".repeat(40)
        val safUri = "content://com.android.externalstorage.documents/" +
            "document/primary%3ADownload%2Ftrack.mp3"
        val entry = MetadataRecoveryJournalV1.Entry(
            id = tx,
            phase = MetadataRecoveryPhase.STAGE_READY,
            sourceId = safSource,
            trackId = "saftrack_" + "2".repeat(40),
            uri = safUri,
            expectedBytes = 100,
            expectedModifiedAt = 1,
            originalSha256 = source.observedSourceSha256,
            stageSha256 = sha,
            stageBytes = 120,
            format = "ID3_V23",
        )
        fun permit(
            profileId: String = SafLocalMp3TitleProfile.ID,
            evidenceId: String = SafLocalMp3TitleProfile.EVIDENCE_ID,
            revision: Int = 3,
            fields: Set<MetadataField> = setOf(MetadataField.TITLE),
            sourceId: String = entry.sourceId,
            uri: String = entry.uri,
        ) = MetadataWritePermit(
            transactionId = tx,
            sourceId = sourceId,
            trackId = entry.trackId,
            uri = uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = sha,
            stageBytes = 120,
            profileId = profileId,
            evidenceId = evidenceId,
            container = "ID3_V23",
            revision = revision,
            fields = fields,
            explicitUserAction = true,
        )

        assertEquals(true, permit().matches(entry))
        assertEquals(false, permit(revision = 4).matches(entry))
        assertEquals(
            false,
            permit(fields = setOf(MetadataField.SYNCED_LYRICS)).matches(entry),
        )
        assertEquals(
            true,
            permit(fields = setOf(MetadataField.ARTIST, MetadataField.COMPOSER))
                .matches(entry),
        )
        assertEquals(
            false,
            permit(
                profileId = MediaStoreMp3TextProfile.ID,
                evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
            ).matches(entry),
        )
        assertEquals(false, permit(sourceId = "lib_device_local").matches(entry))
        assertEquals(
            false,
            permit(uri = "content://media/external/audio/media/1").matches(entry),
        )
    }
}
