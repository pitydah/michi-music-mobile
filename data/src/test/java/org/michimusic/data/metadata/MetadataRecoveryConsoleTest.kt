package org.michimusic.data.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.michimusic.core.metadata.MetadataRecoveryPhase

/** OpenCode: execute on Robolectric; authored here, not executed in Drive. */
@RunWith(RobolectricTestRunner::class)
class MetadataRecoveryConsoleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dir get() = File(context.noBackupFilesDir,"michi-metadata-editor-v1")
    private val uri="content://media/external/audio/media/202"
    private val id get() = UUID.randomUUID().toString()
    private val owner=""
    private val track="202"
    @Before fun cleanStart() { dir.deleteRecursively() }
    @After fun cleanEnd() { dir.deleteRecursively() }

    private fun begin(): MetadataRecoveryJournalV1.Entry {
        val initial=MetadataRecoveryJournalV1.Entry(id,MetadataRecoveryPhase.SNAPSHOTTING,
            owner,track,uri,4096,777)
        val journal = MetadataRecoveryJournalV1(dir)
        runBlocking { journal.exclusive { journal.start(initial) } }
        return initial
    }
    @Test fun noPendingReturnsClear() = runBlocking {
        assertEquals(MetadataRecoveryConsole.Status.NoPending,
            MetadataRecoveryConsole(context).inspect(owner,track,uri,true))
    }
    @Test fun inspectionCannotHappenWithoutClick() = runBlocking {
        begin()
        assertEquals(MetadataRecoveryConsole.Status.ActionMustBeExplicit,
            MetadataRecoveryConsole(context).inspect(owner,track,uri,false))
    }
    @Test fun exactOwnerTrackAndUriAreRequired() = runBlocking {
        begin()
        val console=MetadataRecoveryConsole(context)
        assertEquals(MetadataRecoveryConsole.Status.OtherTrackHasPendingWork,
            console.inspect("other",track,uri,true))
        assertEquals(MetadataRecoveryConsole.Status.OtherTrackHasPendingWork,
            console.inspect(owner,"another",uri,true))
        assertEquals(MetadataRecoveryConsole.Status.OtherTrackHasPendingWork,
            console.inspect(owner,track,uri+"2",true))
    }
    @Test fun snapshottingCanBeDiscardedButOnlyAfterSecondClick() = runBlocking {
        val entry=begin()
        val console=MetadataRecoveryConsole(context)
        assertEquals(MetadataRecoveryConsole.Status.DiscardablePrivateStage(entry.id,entry.phase),
            console.inspect(owner,track,uri,true))
        assertEquals(MetadataRecoveryConsole.DiscardResult.NO_EXPLICIT_CONFIRMATION,
            console.discardPrivatePreparation(owner,track,uri,entry.id,false))
        assertEquals(MetadataRecoveryConsole.DiscardResult.DISCARDED_PRIVATE_ONLY,
            console.discardPrivatePreparation(owner,track,uri,entry.id,true))
        assertEquals(MetadataRecoveryConsole.Status.NoPending,console.inspect(owner,track,uri,true))
    }
    @Test fun mismatchedTransactionCannotDiscardSomeOtherPreparation() = runBlocking {
        begin()
        val console=MetadataRecoveryConsole(context)
        assertEquals(MetadataRecoveryConsole.DiscardResult.NOT_EXACT_TRACK_OR_CHANGED,
            console.discardPrivatePreparation(owner,track,uri,id,true))
        assertTrue(console.inspect(owner,track,uri,true) is MetadataRecoveryConsole.Status.DiscardablePrivateStage)
    }
    @Test fun corruptJournalRetainsPrivateFiles() = runBlocking {
        begin()
        File(dir,"journal-v1.bin").writeText("tampered")
        val console=MetadataRecoveryConsole(context)
        assertEquals(MetadataRecoveryConsole.Status.CorruptOrInaccessibleKeepBackup,
            console.inspect(owner,track,uri,true))
    }
    @Test fun futureSourceMutationIsNotImplicitlyRestored() = runBlocking {
        val entry=begin()
        val console=MetadataRecoveryConsole(context)
        // Cannot transition to MUTATING while the certification gate is false.
        val j=MetadataRecoveryJournalV1(dir)
        assertTrue(j.exclusive { j.inspect() } is MetadataRecoveryJournalV1.Inspection.Pending)
        assertEquals(MetadataRecoveryConsole.Status.DiscardablePrivateStage(entry.id,entry.phase),
            console.inspect(owner,track,uri,true))
    }
    @Test fun trialScratchPreventsRecoveryPanelFromOfferingDestructivePrivateCleanup() = runBlocking {
        val entry=begin()
        val trials=File(context.noBackupFilesDir,"michi-metadata-private-trials-v1")
        trials.mkdirs()
        val scratch=File(trials,"trial-${entry.id}.bin")
        try {
            scratch.writeText("cut in private sandbox")
            val console=MetadataRecoveryConsole(context)
            assertEquals(MetadataRecoveryConsole.Status.ProtectedKeepBackup(entry.phase, entry.id),
                console.inspect(owner,track,uri,true))
            assertEquals(MetadataRecoveryConsole.DiscardResult.PRIVATE_CLEANUP_INCOMPLETE,
                console.discardPrivatePreparation(owner,track,uri,entry.id,true))
            assertTrue(File(dir,"journal-v1.bin").exists())
        } finally { scratch.delete(); trials.delete() }
    }

}
