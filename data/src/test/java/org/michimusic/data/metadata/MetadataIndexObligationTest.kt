package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataTarget

/** A new certified obligation is V3-only, revision-aware and identity-bound. */
class MetadataIndexObligationTest {
    private val tx = "a999a999-1234-4abc-8abc-999a999a9999"
    private val sourceId = "lib_device_local"
    private val trackId = "local_1"
    private val uri = "content://media/external_primary/audio/media/1"
    private val target = MetadataTarget(sourceId, trackId, uri, "me13:obligation-token")
    private val entry = MetadataRecoveryJournalV1.Entry(
        tx, MetadataRecoveryPhase.POSTCHECK_VERIFIED, sourceId, trackId, uri,
        expectedBytes = 100L, expectedModifiedAt = 7L,
        originalSha256 = "a".repeat(64), stageSha256 = "b".repeat(64),
        stageBytes = 110L, format = MetadataAudioContainer.ID3_V24.name,
        ownerEvidenceToken = target.evidenceToken,
    )
    private val intent = MetadataCommitIntentManifest.Receipt(
        transactionId = tx, ownerSha256 = MetadataPrivateSessionIdentity.digest(target),
        provider = MetadataDocumentProvider.MEDIASTORE,
        container = MetadataAudioContainer.ID3_V24,
        originalBytes = 100L, originalSha256 = "a".repeat(64),
        candidateBytes = 110L, candidateSha256 = "b".repeat(64),
        changes = listOf(MetadataCommitIntentManifest.Change(
            MetadataField.TITLE, 1, 1, "c".repeat(64), "d".repeat(64))),
    )
    private val root: File
        get() = Files.createTempDirectory("me13-obligation-").toFile()

    @Test fun identityBoundObligationIsActionableV3() {
        val dir = root
        try {
            assertTrue(MetadataIndexObligation.write(dir, entry, target, intent, null))
            val receipt = MetadataIndexReconciliationReceipt.read(dir, tx)!!
            assertTrue(receipt.actionable)
            assertEquals(3, receipt.version)
            assertEquals(0L, receipt.previousMetadataRevision)
            assertEquals(entry.expectedBytes, receipt.previousSize)
            assertEquals(tx, receipt.transactionId)
            assertEquals(target.evidenceToken, receipt.ownerEvidenceToken)
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingOwnerIntentNeverFallsBackToV1() {
        val dir = root
        try {
            assertFalse(MetadataIndexObligation.write(dir, entry, target, null, null))
            assertTrue(MetadataIndexReconciliationReceipt.entries(dir).isEmpty())
            assertFalse(MetadataIndexReconciliationReceipt.file(dir, tx).exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun foreignOwnerOrTransactionIsRefused() {
        val dir = root
        try {
            assertFalse(MetadataIndexObligation.write(
                dir, entry.copy(stageSha256 = "e".repeat(64)), target, intent, null))
            assertFalse(MetadataIndexObligation.write(
                dir, entry, target.copy(trackId = "other"), intent, null))
            assertTrue(MetadataIndexReconciliationReceipt.entries(dir).isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
