package org.michimusic.data.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.michimusic.core.metadata.MetadataPrivateTrialPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase

/** Tests authored for OpenCode/Robolectric, not run by the Drive workstream. */
@RunWith(RobolectricTestRunner::class)
class MetadataPrivateRestoreTrialTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val journalDir get() = File(context.noBackupFilesDir,"michi-metadata-editor-v1")
    private val trialDir get() = File(context.noBackupFilesDir,"michi-metadata-private-trials-v1")
    private val sourceId = ""
    private val trackId = "track-trial"
    private val uri = "content://media/external/audio/media/905"
    private val original = ByteArray(131088) { (it % 251).toByte() }
    private val candidate = ByteArray(142024) { ((it * 7) % 241).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(b).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }

    @Before fun cleanStart() { journalDir.deleteRecursively();trialDir.deleteRecursively() }
    @After fun cleanFinish() { journalDir.deleteRecursively();trialDir.deleteRecursively() }

    private suspend fun staged(): MetadataPrivateRestoreTrial.Target {
        val j = MetadataRecoveryJournalV1(journalDir)
        val id=UUID.randomUUID().toString()
        val initial=MetadataRecoveryJournalV1.Entry(id,MetadataRecoveryPhase.SNAPSHOTTING,
            sourceId,trackId,uri,original.size.toLong(),777)
        j.exclusive {
            j.start(initial)
            j.backup(initial).writeBytes(original)
            val backed=initial.copy(phase=MetadataRecoveryPhase.BACKUP_DURABLE,originalSha256=sha(original))
            j.advance(initial,backed)
            j.stage(backed).writeBytes(candidate)
            j.advance(backed,backed.copy(phase=MetadataRecoveryPhase.STAGE_READY,
                stageSha256=sha(candidate),stageBytes=candidate.size.toLong(),format="MP3"))
        }
        return MetadataPrivateRestoreTrial.Target(sourceId,trackId,uri,id)
    }

    @Test fun noGestureDoesNotAllocateTrial() = runBlocking {
        val t=staged()
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.NOT_USER_REQUESTED),
            MetadataPrivateRestoreTrial(context).run(t,false))
        assertFalse(trialDir.exists())
    }
    @Test fun completeTrialWritesOnlyPrivateCopyAndPreservesBackup() = runBlocking {
        val t=staged(); val lab=MetadataPrivateRestoreTrial(context)
        assertEquals(MetadataPrivateRestoreTrial.Outcome.TrialFinished(
            MetadataPrivateTrialPolicy.State.CANDIDATE_PRIVATE_INTACT),lab.run(t,true))
        assertEquals(candidate.toList(),File(trialDir,"trial-${t.transactionId}.bin").readBytes().toList())
        assertEquals(original.toList(),File(journalDir,"backup-${t.transactionId}.bin").readBytes().toList())
        assertEquals(MetadataPrivateTrialPolicy.State.CANDIDATE_PRIVATE_INTACT,lab.inspect(t,true))
    }
    @Test fun truncationCutLeavesUnknownPrivateBytesRecoverable() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        assertEquals(MetadataPrivateRestoreTrial.Outcome.SimulatedCrashPoint(
            MetadataPrivateTrialPolicy.CutPoint.AFTER_TRUNCATE),
            lab.run(t,true,MetadataPrivateTrialPolicy.CutPoint.AFTER_TRUNCATE))
        val state=lab.inspect(t,true)
        assertEquals(MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN,state)
        assertTrue(File(journalDir,"backup-${t.transactionId}.bin").exists())
        assertEquals(MetadataPrivateRestoreTrial.Outcome.TrialFinished(
            MetadataPrivateTrialPolicy.State.ORIGINAL_PRIVATE_INTACT),lab.restorePrivate(t,true,true))
    }
    @Test fun partialChunkCutHasUnknownBytesAndRecoveryWorks() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        assertEquals(MetadataPrivateRestoreTrial.Outcome.SimulatedCrashPoint(
            MetadataPrivateTrialPolicy.CutPoint.AFTER_FIRST_CHUNK),
            lab.run(t,true,MetadataPrivateTrialPolicy.CutPoint.AFTER_FIRST_CHUNK))
        assertEquals(MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN,lab.inspect(t,true))
        assertEquals(MetadataPrivateRestoreTrial.Outcome.TrialFinished(
            MetadataPrivateTrialPolicy.State.ORIGINAL_PRIVATE_INTACT),lab.restorePrivate(t,true,true))
        assertEquals(original.toList(),File(trialDir,"trial-${t.transactionId}.bin").readBytes().toList())
    }
    @Test fun crashBeforeTruncateLeavesOriginalPrivateBytes() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        lab.run(t,true,MetadataPrivateTrialPolicy.CutPoint.BEFORE_TRUNCATE)
        assertEquals(MetadataPrivateTrialPolicy.State.ORIGINAL_PRIVATE_INTACT,lab.inspect(t,true))
    }
    @Test fun afterFsyncCutLeavesCompleteCandidateButNotCertified() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        lab.run(t,true,MetadataPrivateTrialPolicy.CutPoint.AFTER_FSYNC)
        assertEquals(MetadataPrivateTrialPolicy.State.CANDIDATE_PRIVATE_INTACT,lab.inspect(t,true))
        assertFalse(MetadataPrivateTrialPolicy.providerCommitCertifiedByPrivateTrial(lab.inspect(t,true)))
    }
    @Test fun noSecondConfirmationNeverRestoresPrivateData() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        lab.run(t,true)
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.RESTORE_REQUIRES_CONFIRMATION),
            lab.restorePrivate(t,true,false))
        assertEquals(candidate.toList(),File(trialDir,"trial-${t.transactionId}.bin").readBytes().toList())
    }
    @Test fun foreignOwnerAndUriCannotInspectOrRestore() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        lab.run(t,true)
        val foreign=t.copy(sourceId="foreign",uri=uri+"/other")
        assertEquals(MetadataPrivateTrialPolicy.State.WRONG_TARGET,lab.inspect(foreign,true))
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.NOT_EXACT_TARGET),lab.restorePrivate(foreign,true,true))
    }
    @Test fun duplicateTrialRefusedWithoutOverwritingOldTrial() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        lab.run(t,true)
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.TRIAL_ALREADY_EXISTS),lab.run(t,true))
        assertEquals(candidate.toList(),File(trialDir,"trial-${t.transactionId}.bin").readBytes().toList())
    }
    @Test fun corruptedBackupCannotRunOrRestore() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        lab.run(t,true)
        File(journalDir,"backup-${t.transactionId}.bin").writeBytes("tampered".toByteArray())
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.PRIVATE_BACKUP_INVALID),lab.restorePrivate(t,true,true))
        assertEquals(MetadataPrivateTrialPolicy.State.BACKUP_INVALID,lab.inspect(t,true))
    }
    @Test fun preStageJournalCannotRunTrial() = runBlocking {
        val t=staged(); val j=MetadataRecoveryJournalV1(journalDir)
        j.exclusive {
            assertTrue(j.discardPrepared(t.transactionId))
            j.start(MetadataRecoveryJournalV1.Entry(t.transactionId,MetadataRecoveryPhase.SNAPSHOTTING,
                sourceId,trackId,uri,original.size.toLong(),777))
        }
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.NOT_STAGE_READY),
            MetadataPrivateRestoreTrial(context).run(t,true))
    }
    @Test fun journalStageAndBackupRetainedAfterPrivateRestore() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        lab.run(t,true); lab.restorePrivate(t,true,true)
        val j=MetadataRecoveryJournalV1(journalDir)
        j.exclusive { assertTrue(j.inspect() is MetadataRecoveryJournalV1.Inspection.Pending)
            val entry=(j.inspect() as MetadataRecoveryJournalV1.Inspection.Pending).entry
            assertTrue(j.privateArtifactsIntact(entry)) }
    }
    @Test fun privateCleanupRequiresSecondConfirmationAndKeepsJournal() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        lab.run(t,true)
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.RESTORE_REQUIRES_CONFIRMATION),
            lab.discardPrivateTrial(t,true,false))
        assertTrue(File(trialDir,"trial-${t.transactionId}.bin").exists())
        assertEquals(MetadataPrivateRestoreTrial.Outcome.PrivateTrialDiscarded,
            lab.discardPrivateTrial(t,true,true))
        assertFalse(File(trialDir,"trial-${t.transactionId}.bin").exists())
        assertTrue(File(journalDir,"backup-${t.transactionId}.bin").exists())
        assertTrue(File(journalDir,"journal-v1.bin").exists())
    }
    @Test fun foreignTrackCannotDiscardPrivateTrial() = runBlocking {
        val t=staged();val lab=MetadataPrivateRestoreTrial(context)
        lab.run(t,true)
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.NOT_EXACT_TARGET),
            lab.discardPrivateTrial(t.copy(trackId="foreign"),true,true))
        assertTrue(File(trialDir,"trial-${t.transactionId}.bin").exists())
    }

    @Test fun stagedCandidateCorruptionBlocksTrialAndPreservesBackup() = runBlocking {
        val t=staged(); val lab=MetadataPrivateRestoreTrial(context)
        File(journalDir,"stage-${t.transactionId}.bin").writeText("corrupt private candidate")
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.PRIVATE_BACKUP_INVALID),lab.run(t,true))
        assertEquals(MetadataPrivateTrialPolicy.State.BACKUP_INVALID,lab.inspect(t,true))
        assertEquals(original.toList(),File(journalDir,"backup-${t.transactionId}.bin").readBytes().toList())
    }
    @Test fun corruptJournalNeverOpensTrial() = runBlocking {
        val t=staged(); val lab=MetadataPrivateRestoreTrial(context)
        File(journalDir,"journal-v1.bin").writeText("invalid")
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.JOURNAL_UNREADABLE),lab.run(t,true))
        assertEquals(MetadataPrivateTrialPolicy.State.JOURNAL_UNAVAILABLE,lab.inspect(t,true))
    }
    @Test fun reconstructionAfterProcessLikeReopenRetainsClassification() = runBlocking {
        val t=staged()
        MetadataPrivateRestoreTrial(context).run(t,true,
            MetadataPrivateTrialPolicy.CutPoint.AFTER_FIRST_CHUNK)
        val reopened=MetadataPrivateRestoreTrial(context)
        assertEquals(MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN,reopened.inspect(t,true))
        assertEquals(MetadataPrivateRestoreTrial.Outcome.TrialFinished(
            MetadataPrivateTrialPolicy.State.ORIGINAL_PRIVATE_INTACT),reopened.restorePrivate(t,true,true))
    }
    @Test fun noPrivateTrialCannotBeFabricatedAsRestored() = runBlocking {
        val t=staged(); val lab=MetadataPrivateRestoreTrial(context)
        assertEquals(MetadataPrivateRestoreTrial.Outcome.Refused(
            MetadataPrivateRestoreTrial.Reason.NO_TRIAL),lab.restorePrivate(t,true,true))
    }

}
