package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataRecoveryPhase

class MetadataRecoveryJournalV1Test {
    private fun fresh()=Files.createTempDirectory("michi-me05-journal-test-").toFile()
    private fun entry(id:String="a999a999-1234-4abc-8abc-999a999a9999")=
        MetadataRecoveryJournalV1.Entry(id,MetadataRecoveryPhase.SNAPSHOTTING,"","track-1",
            "content://media/external/audio/media/7",4096,98765)
    private val shaA="a".repeat(64)
    private val shaB="b".repeat(64)

    @Test fun initiallyClearAndPersistsSnapshottingAfterReopen()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            journal.exclusive { assertEquals(MetadataRecoveryJournalV1.Inspection.Clear,journal.inspect());journal.start(entry()) }
            val reopened=MetadataRecoveryJournalV1(dir)
            reopened.exclusive { assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(entry()),reopened.inspect()) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun durablePhaseTransitionAndReadback()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            val started=entry();val backed=started.copy(phase=MetadataRecoveryPhase.BACKUP_DURABLE,originalSha256=shaA)
            val staged=backed.copy(phase=MetadataRecoveryPhase.STAGE_READY,stageSha256=shaB,
                stageBytes=4100,format="FLAC")
            journal.exclusive {
                journal.start(started)
                journal.advance(started,backed)
                journal.advance(backed,staged)
                assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(staged),journal.inspect())
            }
            MetadataRecoveryJournalV1(dir).exclusive {
                assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(staged),
                    MetadataRecoveryJournalV1(dir).inspect())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun doubleStartBlockedAndFirstEntryNeverReplaced()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            journal.exclusive {
                val old=entry()
                journal.start(old)
                var blocked=false
                try { journal.start(entry("b888b888-1234-4abc-8abc-888b888b8888")) }
                catch (_: IllegalStateException) { blocked=true }
                assertTrue(blocked)
                assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(old),journal.inspect())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun refusesIllegalDirectJumpToMutation()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            journal.exclusive {
                val initial=entry();journal.start(initial)
                var blocked=false
                try { journal.advance(initial,initial.copy(phase=MetadataRecoveryPhase.MUTATING)) }
                catch (_: IllegalStateException) { blocked=true }
                assertTrue(blocked)
                assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(initial),journal.inspect())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun alteredJournalNeverBecomesClear()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            journal.exclusive { journal.start(entry()) }
            val record=File(dir,"journal-v1.bin")
            record.writeBytes(record.readBytes().also { it[6]=(it[6].toInt() xor 3).toByte() })
            journal.exclusive { assertTrue(journal.inspect() is MetadataRecoveryJournalV1.Inspection.Blocked) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun privateBackupDigestMismatchBlocksRecoveryEvidence()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            journal.exclusive {
                val initial=entry()
                journal.start(initial)
                journal.backup(initial).writeText("not the expected file")
                val recorded=initial.copy(phase=MetadataRecoveryPhase.BACKUP_DURABLE,originalSha256=shaA)
                journal.advance(initial,recorded)
                assertFalse(journal.privateArtifactsIntact(recorded))
                assertTrue(journal.inspect() is MetadataRecoveryJournalV1.Inspection.Pending)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun stageCannotBeDeclaredWithEmptyDigest()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            journal.exclusive {
                val first=entry();journal.start(first)
                val second=first.copy(phase=MetadataRecoveryPhase.BACKUP_DURABLE,originalSha256=shaA)
                journal.advance(first,second)
                var blocked=false
                try {journal.advance(second,second.copy(phase=MetadataRecoveryPhase.STAGE_READY,stageBytes=4))}
                catch (_: IllegalStateException){blocked=true}
                assertTrue(blocked)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun noUserIdCanDiscardAnotherTransaction()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            journal.exclusive {
                journal.start(entry())
                assertFalse(journal.discardPrepared("b888b888-1234-4abc-8abc-888b888b8888"))
                assertTrue(journal.inspect() is MetadataRecoveryJournalV1.Inspection.Pending)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun explicitDiscardDeletesOnlyPrivateArtifacts()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            val start=entry()
            journal.exclusive {
                journal.start(start)
                journal.backup(start).writeText("Private original bytes")
                journal.stage(start).writeText("Private stage bytes")
                assertTrue(journal.discardPrepared(start.id))
                assertEquals(MetadataRecoveryJournalV1.Inspection.Clear,journal.inspect())
                assertFalse(journal.backup(start).exists())
                assertFalse(journal.stage(start).exists())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun unsafeFilenameRejectedBeforeFileAccess()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            journal.exclusive {
                var rejected=false
                try { journal.backup(entry("../../outside")) }
                catch (_: IllegalArgumentException) { rejected=true }
                assertTrue(rejected)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun stableLocalOwnerEmptyAndVersionOneRoundTrips()=runBlocking {
        val dir=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(dir)
            val started=entry()
            journal.exclusive { journal.start(started) }
            journal.exclusive {
                val read=journal.inspect() as MetadataRecoveryJournalV1.Inspection.Pending
                assertEquals("",read.entry.sourceId)
                assertEquals(started.uri,read.entry.uri)
                assertEquals(MetadataRecoveryPhase.SNAPSHOTTING,read.entry.phase)
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun privateTrialScratchBlocksDiscardUntilSeparatelyRemoved() = runBlocking {
        val dir=fresh()
        try {
            val j=MetadataRecoveryJournalV1(File(dir,"michi-metadata-editor-v1"))
            val pending=entry()
            j.exclusive { j.start(pending) }
            val trials=File(dir,"michi-metadata-private-trials-v1")
            trials.mkdirs()
            val scratch=File(trials,"trial-${pending.id}.bin")
            try {
                scratch.writeText("private cut evidence")
                j.exclusive {
                    assertTrue(j.privateTrialPresent(pending))
                    assertFalse(j.discardPrepared(pending.id))
                    assertTrue(j.inspect() is MetadataRecoveryJournalV1.Inspection.Pending)
                }
            } finally { scratch.delete(); trials.delete() }
        } finally { dir.deleteRecursively() }
    }

}
