package org.michimusic.data.intelligence

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiGenreRecoverySafetyPolicyTest {
    private val uri = "content://media/external/audio/media/17"
    private fun fixture(block: (MichiGenreRecoveryJournal, MichiGenreRecoveryJournal.Entry) -> Unit) {
        val dir = Files.createTempDirectory("mi25-recovery-safety-").toFile()
        try {
            val journal = MichiGenreRecoveryJournal(File(dir, "no-backup"))
            var entry = journal.begin("saf-A", "17", uri)
            journal.backupFile.writeText("ORIGINAL_AUDIO")
            entry = journal.backupReady(entry, journal.backupFile.length(),
                MichiGenreRecoveryJournal.digest(journal.backupFile))
            val stage = File(dir, "stage").apply { writeText("STAGED_AUDIO_NEW_GENRE") }
            entry = journal.mutationStarted(entry, stage, MichiGenreRecoveryJournal.digest(stage))
            block(journal, entry)
        } finally { dir.deleteRecursively() }
    }

    @Test fun unchangedOriginalDoesNotRequireAnySourceWrite() = fixture { _, e ->
        assertEquals(MichiGenreRecoverySafetyPolicy.CurrentBytes.ORIGINAL,
            MichiGenreRecoverySafetyPolicy.classify(e, e.originalBytes to e.originalSha256))
    }
    @Test fun recognizedStagedResultCanRestoreOnlyFromExactHash() = fixture { _, e ->
        assertEquals(MichiGenreRecoverySafetyPolicy.CurrentBytes.STAGED,
            MichiGenreRecoverySafetyPolicy.classify(e, e.stagedBytes to e.stagedSha256))
        assertEquals(MichiGenreRecoverySafetyPolicy.CurrentBytes.UNKNOWN,
            MichiGenreRecoverySafetyPolicy.classify(e, e.stagedBytes to "0".repeat(64)))
    }
    @Test fun partialFileNeverLooksLikeKnownStagedOrOriginal() = fixture { _, e ->
        assertEquals(MichiGenreRecoverySafetyPolicy.CurrentBytes.UNKNOWN,
            MichiGenreRecoverySafetyPolicy.classify(e, 5L to "f".repeat(64)))
    }
    @Test fun unreadableProviderNeverAuthorizesDestructiveRecovery() = fixture { _, e ->
        assertEquals(MichiGenreRecoverySafetyPolicy.CurrentBytes.UNREADABLE,
            MichiGenreRecoverySafetyPolicy.classify(e, null))
    }
    @Test fun destinationChangedByAnotherEditorIsUnknownEvenAtSameLength() = fixture { _, e ->
        assertEquals(MichiGenreRecoverySafetyPolicy.CurrentBytes.UNKNOWN,
            MichiGenreRecoverySafetyPolicy.classify(e, e.originalBytes to "a".repeat(64)))
    }
    @Test fun processLockRejectsSecondProcessAndReleasesAfterFirstReturns() = fixture { journal, _ ->
        kotlinx.coroutines.runBlocking {
            journal.withExclusiveProcessLock {
                assertTrue(runCatching {
                    journal.withExclusiveProcessLock { "MUST_NOT_EXECUTE" }
                }.isFailure)
            }
            assertEquals("REACQUIRED",journal.withExclusiveProcessLock { "REACQUIRED" })
        }
    }
    @Test fun processLockReleasesAfterException() = fixture { journal, _ ->
        kotlinx.coroutines.runBlocking {
            assertTrue(runCatching {
                journal.withExclusiveProcessLock { throw IllegalStateException("injected failure") }
            }.isFailure)
            assertEquals(7, journal.withExclusiveProcessLock { 7 })
        }
    }
    @Test fun unknownOverwriteRequiresDistinctPositiveExactFingerprintApproval() = fixture { _, e ->
        val current = 10L to "b".repeat(64)
        val gate = MichiGenreRecoverySafetyPolicy
        assertTrue(gate.permitsUnknownOverwrite(e,current,true,10L,"b".repeat(64)))
        assertEquals(false,gate.permitsUnknownOverwrite(e,current,false,10L,"b".repeat(64)))
        assertEquals(false,gate.permitsUnknownOverwrite(e,current,true,10L,null))
        assertEquals(false,gate.permitsUnknownOverwrite(e,current,true,null,"b".repeat(64)))
        assertEquals(false,gate.permitsUnknownOverwrite(e,current,true,10L,"B".repeat(64)))
    }
    @Test fun changingBytesAfterPreviewInvalidatesTheApproval() = fixture { _, e ->
        val originalPreview = 10L to "c".repeat(64)
        val modifiedAfterPreview = 10L to "d".repeat(64)
        assertEquals(false,MichiGenreRecoverySafetyPolicy.permitsUnknownOverwrite(
            e,modifiedAfterPreview,true,originalPreview.first,originalPreview.second))
    }
    @Test fun approvalForUnknownBytesCannotAuthorizeARecognizedStagedVersion() = fixture { _, e ->
        assertEquals(false,MichiGenreRecoverySafetyPolicy.permitsUnknownOverwrite(
            e,e.stagedBytes to e.stagedSha256,true,e.stagedBytes,e.stagedSha256))
        assertEquals(false,MichiGenreRecoverySafetyPolicy.permitsUnknownOverwrite(
            e,e.originalBytes to e.originalSha256,true,e.originalBytes,e.originalSha256))
    }
}
