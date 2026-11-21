package org.michimusic.data.metadata

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataRecoveryPhase

/** Journal remains usable for old ME-05 records; no original is modified. */
class MetadataRecoveryTransitionJournalTest {
    private val id="12345678-1234-4234-8234-123456789abc"
    @Test fun v1SnapshotCanStillBeReadAfterME06()=runBlocking {
        val dir=Files.createTempDirectory("me06-journal-v1-").toFile()
        try {
            val j=MetadataRecoveryJournalV1(dir)
            val entry=MetadataRecoveryJournalV1.Entry(id,MetadataRecoveryPhase.SNAPSHOTTING,
                "","t1","content://media/external/audio/media/9",400,1200)
            j.exclusive {j.start(entry)}
            assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(entry),
                MetadataRecoveryJournalV1(dir).exclusive { MetadataRecoveryJournalV1(dir).inspect() })
        } finally {dir.deleteRecursively()}
    }
    @Test fun pendingPreMutationEntryMayBeDiscardedButNeverStartsWrite()=runBlocking {
        val dir=Files.createTempDirectory("me06-prepared-").toFile()
        try {
            val j=MetadataRecoveryJournalV1(dir)
            val entry=MetadataRecoveryJournalV1.Entry(id,MetadataRecoveryPhase.SNAPSHOTTING,
                "","t1","content://media/external/audio/media/9",400,1200)
            j.exclusive {
                j.start(entry)
                assertTrue(j.discardPrepared(id))
                assertEquals(MetadataRecoveryJournalV1.Inspection.Clear,j.inspect())
            }
        } finally {dir.deleteRecursively()}
    }
    @Test fun stageCannotJumpIntoMutatingWithNoCertifiedCapability()=runBlocking {
        val dir=Files.createTempDirectory("me06-cert-").toFile()
        try {
            val j=MetadataRecoveryJournalV1(dir)
            val entry=MetadataRecoveryJournalV1.Entry(id,MetadataRecoveryPhase.SNAPSHOTTING,
                "","t1","content://media/external/audio/media/9",400,1200)
            j.exclusive {
                j.start(entry)
                var forbidden=false
                try {j.advance(entry,entry.copy(phase=MetadataRecoveryPhase.MUTATING))}
                catch (_:IllegalStateException) {forbidden=true}
                assertTrue(forbidden)
                assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(entry),j.inspect())
            }
        } finally {dir.deleteRecursively()}
    }
}
