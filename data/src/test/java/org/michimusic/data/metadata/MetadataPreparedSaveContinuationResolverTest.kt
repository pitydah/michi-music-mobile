package org.michimusic.data.metadata

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget

class MetadataPreparedSaveContinuationResolverTest {
    private val transactionId = "12341234-1234-4abc-8abc-123412341234"
    private val target = MetadataTarget(
        "source",
        "track",
        "content://media/external/audio/media/9",
        "owner-token",
    )
    private val original = ByteArray(64) { it.toByte() }
    private val candidate = ByteArray(80) { (it * 3).toByte() }

    @Test
    fun reconstructsOnlyFromExactDurableJournalIntentAndOwner() {
        val fixture = fixture()
        try {
            val result = fixture.resolver.resolve(
                transactionId, target.sourceId, target.trackId, target.uri,
            )
            assertTrue(result is MetadataPreparedSaveContinuationResolver.Result.Ready)
            val token = (result as MetadataPreparedSaveContinuationResolver.Result.Ready)
                .continuation
            assertEquals(transactionId, token.transactionId)
            assertEquals(sha(candidate), token.candidateSha256)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun transactionIdCannotAuthorizeAnotherPreparedIdentity() {
        val fixture = fixture()
        try {
            assertEquals(
                MetadataPreparedSaveContinuationResolver.Result.Refused(
                    "PREPARED_IDENTITY_MISMATCH",
                ),
                fixture.resolver.resolve(
                    transactionId, target.sourceId, "other-track", target.uri,
                ),
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun missingOwnerReceiptFailsClosedAfterProcessReconstruction() {
        val fixture = fixture()
        try {
            assertTrue(fixture.owners.file(transactionId).delete())
            assertEquals(
                MetadataPreparedSaveContinuationResolver.Result.Refused(
                    "OWNER_RECEIPT_MISSING",
                ),
                fixture.resolver.resolve(
                    transactionId, target.sourceId, target.trackId, target.uri,
                ),
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private data class Fixture(
        val root: java.io.File,
        val owners: MetadataRecoveryOwnerStore,
        val resolver: MetadataPreparedSaveContinuationResolver,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("michi-continuation-").toFile()
        val journal = MetadataRecoveryJournalV1(root)
        val owners = MetadataRecoveryOwnerStore(root)
        val start = MetadataRecoveryJournalV1.Entry(
            id = transactionId,
            phase = MetadataRecoveryPhase.SNAPSHOTTING,
            sourceId = target.sourceId,
            trackId = target.trackId,
            uri = target.uri,
            expectedBytes = original.size.toLong(),
            expectedModifiedAt = 1,
            ownerEvidenceToken = target.evidenceToken,
        )
        journal.start(start)
        journal.backup(start).writeBytes(original)
        val backup = start.copy(
            phase = MetadataRecoveryPhase.BACKUP_DURABLE,
            originalSha256 = sha(original),
        )
        journal.advance(start, backup)
        journal.stage(start).writeBytes(candidate)
        val prepared = backup.copy(
            phase = MetadataRecoveryPhase.STAGE_READY,
            stageSha256 = sha(candidate),
            stageBytes = candidate.size.toLong(),
            format = MetadataAudioContainer.ID3_V24.name,
        )
        journal.advance(backup, prepared)
        val snapshot = MetadataSnapshot(
            target = target,
            tagsVerified = true,
            known = mapOf(MetadataField.TITLE to listOf("Before")),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        val review = MetadataEditPreview.review(
            snapshot,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("After"))),
        ) as MetadataReview.Ready
        val intent = MetadataCommitIntentManifest.create(
            transactionId = transactionId,
            review = review,
            provider = MetadataDocumentProvider.MEDIASTORE,
            container = MetadataAudioContainer.ID3_V24,
            originalBytes = original.size.toLong(),
            originalSha256 = sha(original),
            candidateBytes = candidate.size.toLong(),
            candidateSha256 = sha(candidate),
        )
        MetadataCommitIntentStore(journal).write(prepared, intent)
        check(owners.write(prepared, target))
        return Fixture(
            root,
            owners,
            MetadataPreparedSaveContinuationResolver(
                journal,
                MetadataCommitIntentStore(journal),
                owners,
            ),
        )
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
