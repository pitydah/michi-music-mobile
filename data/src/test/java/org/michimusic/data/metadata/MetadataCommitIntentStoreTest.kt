package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.metadata.MetadataRecoveryPhase

class MetadataCommitIntentStoreTest {
    private val id="12341234-1234-4abc-8abc-123412341234"
    private val target=MetadataTarget("","song","content://media/external/audio/media/6","ev-1")
    private fun entry() = MetadataRecoveryJournalV1.Entry(id,MetadataRecoveryPhase.SNAPSHOTTING,
        target.sourceId,target.trackId,target.uri,2048,123456)
    private fun receipt(): MetadataCommitIntentManifest.Receipt {
        val s=MetadataSnapshot(target,true,mapOf(MetadataField.TITLE to listOf("Before")),
            verifiedFields=setOf(MetadataField.TITLE))
        val review=MetadataEditPreview.review(s,listOf(MetadataChange.Replace(
            MetadataField.TITLE,listOf("After")))) as MetadataReview.Ready
        return MetadataCommitIntentManifest.create(id,review,MetadataDocumentProvider.MEDIASTORE,
            MetadataAudioContainer.FLAC,2048,"a".repeat(64),4096,"b".repeat(64))
    }
    private fun fresh() = Files.createTempDirectory("michi-intent-me10n-").toFile()

    @Test fun absentLegacyManifestIsNotInvented() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                assertEquals(MetadataCommitIntentStore.Inspection.Missing,
                    MetadataCommitIntentStore(journal).inspect(entry()))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun writesOnceAndReadsSameReceipt() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                val store=MetadataCommitIntentStore(journal)
                store.write(entry(),receipt())
                assertEquals(MetadataCommitIntentStore.Inspection.Present(receipt()),
                    store.inspect(entry()))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun refusesOverwriteOfExistingIntent() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                val store=MetadataCommitIntentStore(journal)
                store.write(entry(),receipt())
                var rejected=false
                try { store.write(entry(),receipt()) } catch (_: IllegalStateException) { rejected=true }
                assertTrue(rejected)
                assertEquals(MetadataCommitIntentStore.Inspection.Present(receipt()),store.inspect(entry()))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun checksumCorruptionFailsClosed() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                val store=MetadataCommitIntentStore(journal)
                store.write(entry(),receipt())
                val file=journal.intent(entry())
                file.writeBytes(file.readBytes().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() })
                assertEquals(MetadataCommitIntentStore.Inspection.CorruptOrUntrusted,
                    store.inspect(entry()))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun refusesTransactionIdMismatchInReadback() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                val store=MetadataCommitIntentStore(journal)
                store.write(entry(),receipt())
                val other=entry().copy(id="cccccccc-1234-4abc-8abc-123456789abc")
                assertEquals(MetadataCommitIntentStore.Inspection.Missing,store.inspect(other))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun explicitDiscardOfPreMutationJournalDeletesIntentToo() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                journal.start(entry())
                MetadataCommitIntentStore(journal).write(entry(),receipt())
                assertTrue(journal.intent(entry()).isFile)
                assertTrue(journal.discardPrepared(id))
                assertFalse(journal.intent(entry()).exists())
                assertEquals(MetadataRecoveryJournalV1.Inspection.Clear,journal.inspect())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsSymlinkedIntent() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                val targetFile=File(root,"external")
                targetFile.writeText("external")
                Files.createSymbolicLink(journal.intent(entry()).toPath(),targetFile.toPath())
                assertEquals(MetadataCommitIntentStore.Inspection.CorruptOrUntrusted,
                    MetadataCommitIntentStore(journal).inspect(entry()))
            }
        } finally { root.deleteRecursively() }
    }
}
