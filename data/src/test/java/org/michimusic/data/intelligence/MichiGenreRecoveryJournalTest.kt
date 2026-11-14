package org.michimusic.data.intelligence

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiGenreRecoveryJournalTest {
    private fun inDirectory(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("mi24-journal-").toFile()
        try { block(File(dir, "no-backup")) }
        finally { dir.deleteRecursively() }
    }
    private val uri = "content://media/external/audio/media/17"

    @Test fun unopenedJournalDoesNotCreateDiskArtifacts() = inDirectory { dir ->
        assertEquals(MichiGenreRecoveryJournal.Inspection.Clear,
            MichiGenreRecoveryJournal(dir).inspect())
        assertFalse(dir.exists())
    }

    @Test fun newlyStartedSnapshotSurvivesProcessRecreation() = inDirectory { dir ->
        val entry = MichiGenreRecoveryJournal(dir).begin("owner-A", "17", uri)
        val newInstance = MichiGenreRecoveryJournal(dir)
        assertEquals(MichiGenreRecoveryJournal.Inspection.Pending(entry), newInstance.inspect())
        assertTrue(runCatching { newInstance.begin("owner-B", "17", uri) }.isFailure)
        newInstance.clearVerified(entry) // SNAPSHOTTING cannot have touched remote media.
        assertEquals(MichiGenreRecoveryJournal.Inspection.Clear, newInstance.inspect())
    }

    @Test fun backupAndMutatingStateDurablyRetainRecoveryAfterRecreation() = inDirectory { dir ->
        val journal = MichiGenreRecoveryJournal(dir)
        var entry = journal.begin("owner-A", "17", uri)
        val original = "ORIGINAL_AUDIO_AND_TAGS".toByteArray()
        journal.backupFile.writeBytes(original)
        val originalHash = MichiGenreRecoveryJournal.digest(journal.backupFile)
        entry = journal.backupReady(entry, original.size.toLong(), originalHash)
        val stage = File(dir.parentFile, "stage.flac").apply { writeText("EDITED_GENRE_AUDIO_SAME") }
        try {
            entry = journal.mutationStarted(entry, stage, MichiGenreRecoveryJournal.digest(stage))
            val afterCrash = MichiGenreRecoveryJournal(dir)
            assertEquals(MichiGenreRecoveryJournal.Inspection.Pending(entry), afterCrash.inspect())
            assertTrue(afterCrash.backupIntegrity(entry))
            assertTrue(runCatching { afterCrash.begin("owner-A", "18", uri) }.isFailure)
            entry = afterCrash.verified(entry)
            assertEquals(MichiGenreRecoveryJournal.Inspection.Pending(entry), afterCrash.inspect())
            afterCrash.clearVerified(entry)
            assertEquals(MichiGenreRecoveryJournal.Inspection.Clear, afterCrash.inspect())
            assertFalse(afterCrash.backupFile.exists())
        } finally { stage.delete() }
    }

    @Test fun tamperedBackupIsRejectedAndReceiptNotDeleted() = inDirectory { dir ->
        val journal = MichiGenreRecoveryJournal(dir)
        val started = journal.begin("owner-A", "17", uri)
        journal.backupFile.writeText("ORIGINAL")
        val ready = journal.backupReady(started, 8,
            MichiGenreRecoveryJournal.digest(journal.backupFile))
        journal.backupFile.writeText("MODIFIED")
        assertFalse(journal.backupIntegrity(ready))
        assertEquals(MichiGenreRecoveryJournal.Inspection.Pending(ready), journal.inspect())
        assertTrue(runCatching { journal.begin("owner-A", "17", uri) }.isFailure)
    }

    @Test fun wrongStagedHashCannotAdvanceToMutating() = inDirectory { dir ->
        val journal = MichiGenreRecoveryJournal(dir)
        val started = journal.begin("", "17", uri)
        journal.backupFile.writeText("SOURCE")
        val ready = journal.backupReady(started, 6,
            MichiGenreRecoveryJournal.digest(journal.backupFile))
        val stage = File(dir.parentFile, "stage-invalid.flac").apply { writeText("STAGE") }
        try {
            assertTrue(runCatching {
                journal.mutationStarted(ready, stage, "0".repeat(64))
            }.isFailure)
            assertEquals(MichiGenreRecoveryJournal.Inspection.Pending(ready), journal.inspect())
        } finally { stage.delete() }
    }

    @Test fun damagedOrOrphanedReceiptFailsClosed() = inDirectory { dir ->
        dir.mkdirs()
        File(dir, "mutation-v1.receipt").writeText("not a valid receipt")
        assertTrue(MichiGenreRecoveryJournal(dir).inspect() is MichiGenreRecoveryJournal.Inspection.Blocked)
        assertTrue(runCatching { MichiGenreRecoveryJournal(dir).begin("", "17", uri) }.isFailure)
        File(dir, "mutation-v1.receipt").delete()
        MichiGenreRecoveryJournal(dir).backupFile.writeText("orphaned backup")
        assertTrue(MichiGenreRecoveryJournal(dir).inspect() is MichiGenreRecoveryJournal.Inspection.Blocked)
    }
    @Test fun interruptedCleanupCanFinishWithoutBackupOrMediaWrite() = inDirectory { dir ->
        val journal = MichiGenreRecoveryJournal(dir)
        val initial = journal.begin("owner-A", "17", uri)
        journal.backupFile.writeText("ORIGINAL")
        val ready = journal.backupReady(initial, 8,
            MichiGenreRecoveryJournal.digest(journal.backupFile))
        // Simulate a process death after the atomic CLEANUP receipt is published
        // and after the backup is deleted, but before the receipt itself is removed.
        val receipt = File(dir, "mutation-v1.receipt")
        val bytes = receipt.readBytes()
        // A state transition is exactly an atomic rewrite; use a copy of the valid
        // READY receipt changing the enum ordinal to CLEANUP (ordinal = 4).
        val phaseOffset = java.io.DataInputStream(bytes.inputStream()).use { input ->
            input.readInt(); input.readUTF(); input.readUTF(); input.readUTF(); input.readUTF()
            bytes.size - input.available()
        }
        bytes[phaseOffset] = 0;bytes[phaseOffset+1] = 0
        bytes[phaseOffset+2] = 0;bytes[phaseOffset+3] = 4
        receipt.writeBytes(bytes)
        journal.backupFile.delete()
        val restarted = MichiGenreRecoveryJournal(dir)
        val pending = restarted.inspect() as MichiGenreRecoveryJournal.Inspection.Pending
        assertEquals(MichiGenreRecoveryJournal.Phase.CLEANUP,pending.entry.phase)
        restarted.clearVerified(pending.entry)
        assertEquals(MichiGenreRecoveryJournal.Inspection.Clear,restarted.inspect())
    }
}
