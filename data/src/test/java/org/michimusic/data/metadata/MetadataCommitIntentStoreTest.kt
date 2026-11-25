package org.michimusic.data.metadata

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
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
import org.michimusic.core.metadata.MetadataArtworkEvidence

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
    private fun resourceReceipt() = MetadataCommitIntentManifest.Receipt(
        transactionId = id,
        ownerSha256 = "c".repeat(64),
        provider = MetadataDocumentProvider.MEDIASTORE,
        container = MetadataAudioContainer.ID3_V24,
        originalBytes = 2048,
        originalSha256 = "a".repeat(64),
        candidateBytes = 4096,
        candidateSha256 = "b".repeat(64),
        changes = emptyList(),
        resources = listOf(
            MetadataCommitIntentManifest.ResourceChange.PictureReplacement(
                ordinal = 0,
                format = MetadataArtworkEvidence.Format.ID3_APIC,
                type = 3,
                description = "Cover",
                beforeMime = "image/jpeg",
                beforeBytes = 32,
                beforeSha256 = "d".repeat(64),
                afterMime = "image/png",
                afterBytes = 48,
                afterSha256 = "e".repeat(64),
            ),
        ),
    )
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
    @Test fun writesAndReadsAssetOnlyV2Receipt() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                val store=MetadataCommitIntentStore(journal)
                store.write(entry(),resourceReceipt())
                assertEquals(
                    MetadataCommitIntentStore.Inspection.Present(resourceReceipt()),
                    store.inspect(entry()),
                )
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun writesAndReadsArtworkAddAndRemoveReceipt() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                val store=MetadataCommitIntentStore(journal)
                val receipt=resourceReceipt().copy(resources=listOf(
                    MetadataCommitIntentManifest.ResourceChange.PictureAdd(
                        format=MetadataArtworkEvidence.Format.ID3_APIC,
                        ordinal=1, type=3, description="Nueva",
                        afterMime="image/png", afterBytes=48,
                        afterSha256="f".repeat(64),
                    ),
                    MetadataCommitIntentManifest.ResourceChange.PictureRemove(
                        format=MetadataArtworkEvidence.Format.ID3_APIC,
                        ordinal=0, type=3, description="Cover",
                        beforeMime="image/jpeg", beforeBytes=32,
                        beforeSha256="d".repeat(64),
                    ),
                ))
                assertTrue(MetadataCommitIntentManifest.valid(receipt))
                store.write(entry(),receipt)
                assertEquals(
                    MetadataCommitIntentStore.Inspection.Present(receipt),
                    store.inspect(entry()),
                )
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun readsLegacyV1AsScalarOnly() = runBlocking {
        val root=fresh()
        try {
            val journal=MetadataRecoveryJournalV1(root)
            journal.exclusive {
                journal.intent(entry()).writeBytes(legacyV1(receipt()))
                val inspected=MetadataCommitIntentStore(journal).inspect(entry())
                assertEquals(MetadataCommitIntentStore.Inspection.Present(receipt()), inspected)
                val decoded=(inspected as MetadataCommitIntentStore.Inspection.Present).receipt
                assertTrue(decoded.resources.isEmpty())
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

    private fun legacyV1(r: MetadataCommitIntentManifest.Receipt): ByteArray {
        val body=ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(0x4d45314e)
                out.writeInt(1)
                out.writeUTF(r.transactionId)
                out.writeUTF(r.ownerSha256)
                out.writeUTF(r.provider.name)
                out.writeUTF(r.container.name)
                out.writeLong(r.originalBytes)
                out.writeUTF(r.originalSha256)
                out.writeLong(r.candidateBytes)
                out.writeUTF(r.candidateSha256)
                out.writeInt(r.changes.size)
                r.changes.forEach { change ->
                    out.writeUTF(change.field.name)
                    out.writeInt(change.beforeCount)
                    out.writeInt(change.afterCount)
                    out.writeUTF(change.beforeSha256)
                    out.writeUTF(change.afterSha256)
                }
            }
        }.toByteArray()
        val digest=MessageDigest.getInstance("SHA-256").digest(body)
        return ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(body.size)
                out.write(body)
                out.write(digest)
            }
        }.toByteArray()
    }
}
