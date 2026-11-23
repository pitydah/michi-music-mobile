package org.michimusic.data.metadata

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataTarget

class MetadataRecoveryJournalCleanupTest {
    private val tx = "c999c999-1234-4abc-8abc-999c999c999c"
    private val backupBytes = ByteArray(1024) { (it % 239).toByte() }
    private val stageBytes = ByteArray(1200) { ((it + 7) % 239).toByte() }
    private val entry = MetadataRecoveryJournalV1.Entry(
        tx, MetadataRecoveryPhase.SNAPSHOTTING, "lib_device_local", "track-1",
        "content://media/external/audio/media/7", backupBytes.size.toLong(), 98765,
    )
    private val permit = MetadataWritePermit(
        transactionId = tx, sourceId = entry.sourceId, trackId = entry.trackId,
        uri = entry.uri, originalSha256 = sha(backupBytes),
        candidateSha256 = sha(stageBytes), stageBytes = stageBytes.size.toLong(),
        profileId = MediaStoreMp3TextProfile.ID,
        evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
        container = MetadataAudioContainer.ID3_V24.name, revision = 4,
        fields = setOf(MetadataField.TITLE), explicitUserAction = true,
    )

    @Test fun cleanupAfterPostcheckDeletesEveryPrivateArtifactAndOwner() = runBlocking {
        val root = Files.createTempDirectory("me13-cleanup-").toFile()
        try {
            val journal = MetadataRecoveryJournalV1(root)
            val postcheck = preparePostcheck(journal, root)
            val owners = MetadataRecoveryOwnerStore(root)
            assertTrue(owners.write(postcheck, MetadataTarget(
                postcheck.sourceId, postcheck.trackId, postcheck.uri, "me04:owner")))
            assertTrue(journal.completeVerified(postcheck.id))
            assertEquals(MetadataRecoveryJournalV1.Inspection.Clear, journal.inspect())
            assertFalse(journal.backup(postcheck).exists())
            assertFalse(journal.stage(postcheck).exists())
            assertFalse(journal.intent(postcheck).exists())
            assertFalse(journal.owner(postcheck).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun cleanupResumesFromCleanupSafeWithMissingStage() = runBlocking {
        val root = Files.createTempDirectory("me13-cleanup-").toFile()
        try {
            val journal = MetadataRecoveryJournalV1(root)
            val postcheck = preparePostcheck(journal, root)
            val cleanup = postcheck.copy(phase = MetadataRecoveryPhase.CLEANUP_SAFE)
            journal.exclusive { journal.advance(postcheck, cleanup) }
            assertTrue(journal.stage(cleanup).delete())
            assertTrue(journal.completeVerified(cleanup.id))
            assertEquals(MetadataRecoveryJournalV1.Inspection.Clear, journal.inspect())
            assertFalse(journal.backup(cleanup).exists())
            assertFalse(journal.intent(cleanup).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun secondCleanupIsRefusedOnlyAfterTheJournalIsClear() = runBlocking {
        val root = Files.createTempDirectory("me13-cleanup-").toFile()
        try {
            val journal = MetadataRecoveryJournalV1(root)
            val postcheck = preparePostcheck(journal, root)
            assertTrue(journal.completeVerified(postcheck.id))
            assertFalse(journal.completeVerified(postcheck.id))
        } finally { root.deleteRecursively() }
    }

    private fun preparePostcheck(
        journal: MetadataRecoveryJournalV1,
        root: java.io.File,
    ): MetadataRecoveryJournalV1.Entry = runBlocking {
        journal.exclusive {
            journal.start(entry)
            journal.backup(entry).writeBytes(backupBytes)
            val backed = entry.copy(
                phase = MetadataRecoveryPhase.BACKUP_DURABLE,
                originalSha256 = sha(backupBytes),
            )
            journal.advance(entry, backed)
            journal.stage(backed).writeBytes(stageBytes)
            val staged = backed.copy(
                phase = MetadataRecoveryPhase.STAGE_READY,
                stageSha256 = sha(stageBytes), stageBytes = stageBytes.size.toLong(),
                format = MetadataAudioContainer.ID3_V24.name,
            )
            journal.advance(backed, staged)
            journal.intent(staged).writeBytes(ByteArray(16))
            val mutating = staged.copy(phase = MetadataRecoveryPhase.MUTATING)
            journal.advance(staged, mutating, permit)
            val unknown = mutating.copy(phase = MetadataRecoveryPhase.MUTATION_UNKNOWN)
            journal.advance(mutating, unknown)
            val postcheck = unknown.copy(phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED)
            journal.advance(unknown, postcheck)
            postcheck
        }
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
