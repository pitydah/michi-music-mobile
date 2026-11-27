package org.michimusic.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataId3StageRewriter
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.MetadataAppliedTransaction
import org.michimusic.data.cache.MichiDatabase

/**
 * ME-13 S4: the durable receipt consumer is proved with synthetic MP3 bytes and
 * an in-memory Room database. No personal audio, provider or network is used.
 */
@RunWith(RobolectricTestRunner::class)
class MetadataIndexReconcilerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val journalDir get() = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
    private lateinit var database: MichiDatabase
    private lateinit var journal: MetadataRecoveryJournalV1
    private lateinit var intents: MetadataCommitIntentStore

    private val sourceId = "lib_device_local"
    private val trackId = "local_1"
    private val uri = "content://media/external_primary/audio/media/1"
    private val token = "me04:fixture-token"
    private val target = MetadataTarget(sourceId, trackId, uri, token)
    private val mp3Payload = ByteArray(2700) { (it % 239).toByte() }

    private val original: ByteArray = sample(tagVersion = 4, title = "Original")
    private val candidate: ByteArray = stage(original, "Edited")
    private val originalSha = sha(original)
    private val candidateSha = sha(candidate)

    private val originalReport: MetadataFieldReadReport = report(original)
    private val candidateReport: MetadataFieldReadReport = report(candidate)
    private val review = review("Original", "Edited")
    private val intent = MetadataCommitIntentManifest.create(
        transactionId = TX,
        review = review,
        provider = MetadataDocumentProvider.MEDIASTORE,
        container = MetadataAudioContainer.ID3_V24,
        originalBytes = original.size.toLong(),
        originalSha256 = originalSha,
        candidateBytes = candidate.size.toLong(),
        candidateSha256 = candidateSha,
    )

    @Before fun setUp() {
        journalDir.deleteRecursively()
        journal = MetadataRecoveryJournalV1(journalDir)
        intents = MetadataCommitIntentStore(journal)
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        database.close()
        journalDir.deleteRecursively()
    }

    private fun reconciler(
        source: MetadataIndexReconciler.SourceGate = FakeSource(original, candidate,
            originalReport, candidateReport),
        publisher: MetadataIndexReconciler.Publisher,
    ) = MetadataIndexReconciler(context, database, source, publisher)

    private fun stagedJournal(): MetadataRecoveryJournalV1.Entry = runBlocking {
        val initial = MetadataRecoveryJournalV1.Entry(
            TX, MetadataRecoveryPhase.SNAPSHOTTING, sourceId, trackId, uri,
            original.size.toLong(), 777L)
        journal.exclusive {
            journal.start(initial)
            journal.backup(initial).writeBytes(original)
            val backed = initial.copy(phase = MetadataRecoveryPhase.BACKUP_DURABLE,
                originalSha256 = originalSha)
            journal.advance(initial, backed)
            journal.stage(backed).writeBytes(candidate)
            val staged = backed.copy(phase = MetadataRecoveryPhase.STAGE_READY,
                stageSha256 = candidateSha, stageBytes = candidate.size.toLong(),
                format = MetadataAudioContainer.ID3_V24.name)
            journal.advance(backed, staged)
            intents.write(staged, intent)
            val mutating = staged.copy(phase = MetadataRecoveryPhase.MUTATING)
            journal.advance(staged, mutating, writePermit(staged))
            val post = mutating.copy(phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED)
            journal.advance(mutating, post)
            post
        }
    }

    private fun writeReceipt(
        entry: MetadataRecoveryJournalV1.Entry = stagedJournal(),
        previousVerifiedAt: Long = 123L,
        previousVerifiedSha256: String = originalSha,
        previousSize: Long = original.size.toLong(),
    ): File {
        val receipt = MetadataIndexReconciliationReceipt(
            transactionId = entry.id, sourceId = entry.sourceId, trackId = entry.trackId,
            uri = entry.uri, originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256, candidateBytes = entry.stageBytes,
            format = entry.format, ownerEvidenceToken = token,
            previousVerifiedAt = previousVerifiedAt,
            previousVerifiedSha256 = previousVerifiedSha256,
            previousSize = previousSize,
        )
        assertTrue(receipt.actionable)
        assertTrue(MetadataIndexReconciliationReceipt.write(journalDir, receipt))
        return MetadataIndexReconciliationReceipt.file(journalDir, entry.id)
    }

    private fun preWriteRow(entry: MetadataRecoveryJournalV1.Entry) = CachedTrack(
        id = entry.trackId, title = "Original", sourceId = entry.sourceId,
        filepath = entry.uri, size = entry.expectedBytes,
        verifiedSize = entry.expectedBytes, verifiedSha256 = entry.originalSha256,
        verifiedAt = 123L, verifiedAlgorithm = "sha256",
    )

    private fun appliedRow(entry: MetadataRecoveryJournalV1.Entry) = CachedTrack(
        id = entry.trackId, title = "Edited", sourceId = entry.sourceId,
        filepath = entry.uri, size = entry.stageBytes,
        verifiedSize = 0L, verifiedSha256 = "", verifiedAt = 0L, verifiedAlgorithm = "",
    )

    private suspend fun insertAppliedMarker(
        entry: MetadataRecoveryJournalV1.Entry,
        candidateDigest: String = entry.stageSha256,
    ) {
        database.trackDao().insertAppliedMetadataTransaction(
            MetadataAppliedTransaction(
                transactionId = entry.id,
                sourceId = entry.sourceId,
                trackId = entry.trackId,
                uri = entry.uri,
                originalRevision = 0L,
                committedRevision = 1L,
                candidateSha256 = candidateDigest,
                candidateBytes = entry.stageBytes,
                appliedAt = 99L,
            ),
        )
    }

    @Test fun corruptReceiptIsRetainedAndNeverPublished() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        val bytes = file.readBytes().also { it[it.lastIndex - 3] = (it[it.lastIndex - 3] + 1).toByte() }
        file.writeBytes(bytes)
        val publisher = RecordingPublisher(true)
        val result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.CorruptReceiptKept(file.name)),
            result.outcomes)
        assertEquals(0, publisher.calls)
        assertTrue(file.exists())
        assertTrue(File(journalDir, "journal-v1.bin").exists())
    }

    @Test fun missingExactRowWaitsAndRetainsEveryObligation() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        val publisher = RecordingPublisher(true)
        val result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.WaitingForIndexedRow(TX)),
            result.outcomes)
        assertEquals(0, publisher.calls)
        assertTrue(file.exists())
        assertTrue(File(journalDir, "backup-${TX}.bin").exists())
        assertTrue(File(journalDir, "stage-${TX}.bin").exists())
        assertTrue(File(journalDir, "intent-${TX}.bin").exists())
    }

    @Test fun concurrentRowChangeIsKeptWithoutOverwriteOrPublisherCall() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        database.trackDao().insertAll(listOf(
            preWriteRow(entry).copy(verifiedAt = 999L, verifiedSha256 = "c".repeat(64))))
        val publisher = RecordingPublisher(true)
        val result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.ConflictKept(TX)), result.outcomes)
        assertEquals(0, publisher.calls)
        assertTrue(file.exists())
        assertEquals("Original",
            database.trackDao().getTrackBySource(sourceId, trackId)!!.title)
    }

    @Test fun exactAppliedMarkerCleansWithoutSecondCasAndSurvivesRestart() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        database.trackDao().insertAll(listOf(appliedRow(entry)))
        insertAppliedMarker(entry)
        val publisher = RecordingPublisher(true)
        val first = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.AlreadyAppliedAndCleaned(TX)),
            first.outcomes)
        assertEquals(0, publisher.calls)
        assertFalse(file.exists())
        assertFalse(File(journalDir, "journal-v1.bin").exists())
        assertFalse(File(journalDir, "backup-${TX}.bin").exists())
        assertFalse(MetadataIndexAppliedReceipt.file(journalDir, TX).exists())

        // A new instance after a process restart observes no remaining obligation.
        val second = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertTrue(second.outcomes.isEmpty())
        assertFalse(second.changedIndex)
        assertEquals(0, publisher.calls)
        assertEquals("Edited",
            database.trackDao().getTrackBySource(sourceId, trackId)!!.title)
    }

    @Test fun valueEqualAppliedRowWithoutExactMarkerIsNeverCommitAuthority() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        database.trackDao().insertAll(listOf(appliedRow(entry)))
        val publisher = RecordingPublisher(true)

        val result = reconciler(publisher = publisher).reconcileAll({ true }, { true })

        assertEquals(
            listOf(MetadataIndexReconciler.Outcome.ConflictKept(TX)),
            result.outcomes,
        )
        assertEquals(0, publisher.calls)
        assertTrue(file.exists())
        assertTrue(File(journalDir, "journal-v1.bin").exists())
    }

    @Test fun preWriteRowPublishesOnceAndRepeatedOpensDoNothing() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        database.trackDao().insertAll(listOf(preWriteRow(entry)))
        val publisher = RecordingPublisher(true)
        val first = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.Indexed(TX)), first.outcomes)
        assertEquals(1, publisher.calls)
        assertFalse(file.exists())
        assertFalse(File(journalDir, "journal-v1.bin").exists())

        repeat(2) {
            val again = reconciler(publisher = publisher).reconcileAll({ true }, { true })
            assertTrue(again.outcomes.isEmpty())
            assertFalse(again.changedIndex)
        }
        assertEquals(1, publisher.calls)
    }

    @Test fun casRefusalOnLivePrestateKeepsObligation() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        database.trackDao().insertAll(listOf(preWriteRow(entry)))
        val publisher = RecordingPublisher(false)
        val result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.RecoveryRequired(TX, "CAS_REFUSED")),
            result.outcomes)
        assertEquals(1, publisher.calls)
        assertTrue(file.exists())
        assertTrue(File(journalDir, "journal-v1.bin").exists())
    }

    @Test fun v1ReceiptIsReadableButNeverActionable() = runBlocking {
        val entry = stagedJournal()
        val v1 = MetadataIndexReconciliationReceipt(
            transactionId = entry.id, sourceId = entry.sourceId, trackId = entry.trackId,
            uri = entry.uri, originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256, candidateBytes = entry.stageBytes,
            format = entry.format, version = 1,
        )
        assertTrue(MetadataIndexReconciliationReceipt.write(journalDir, v1))
        val file = MetadataIndexReconciliationReceipt.file(journalDir, entry.id)
        val publisher = RecordingPublisher(true)
        val result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.RecoveryRequired(
            TX, "RECEIPT_V1_NOT_ACTIONABLE")), result.outcomes)
        assertEquals(0, publisher.calls)
        assertTrue(file.exists())
    }

    @Test fun journalMismatchKeepsReceipt() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        File(journalDir, "stage-${TX}.bin").writeText("tampered")
        val publisher = RecordingPublisher(true)
        val result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.JournalUnavailableKept(TX)),
            result.outcomes)
        assertEquals(0, publisher.calls)
        assertTrue(file.exists())
    }

    @Test fun exactMarkerResolvesAnOrphanReceiptWithoutSecondCas() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        // Simulate a kill after the CAS and after the journal/private cleanup,
        // but before the reconciliation receipt and applied proof were removed.
        assertTrue(File(journalDir, "journal-v1.bin").delete())
        assertTrue(File(journalDir, "backup-${TX}.bin").delete())
        assertTrue(File(journalDir, "stage-${TX}.bin").delete())
        assertTrue(File(journalDir, "intent-${TX}.bin").delete())
        database.trackDao().insertAll(listOf(appliedRow(entry)))
        insertAppliedMarker(entry)
        val publisher = RecordingPublisher(true)
        val result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.AlreadyAppliedAndCleaned(TX)),
            result.outcomes)
        assertEquals(0, publisher.calls)
        assertFalse(file.exists())
        assertFalse(MetadataIndexAppliedReceipt.file(journalDir, TX).exists())
        assertEquals("Edited",
            database.trackDao().getTrackBySource(sourceId, trackId)!!.title)
    }

    @Test fun absentOrMismatchedAppliedMarkerNeverDeletesAnOrphanReceipt() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        assertTrue(File(journalDir, "journal-v1.bin").delete())
        database.trackDao().insertAll(listOf(appliedRow(entry)))
        val publisher = RecordingPublisher(true)
        var result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.JournalUnavailableKept(TX)),
            result.outcomes)
        assertTrue(file.exists())

        insertAppliedMarker(entry, candidateDigest = "d".repeat(64))
        result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.JournalUnavailableKept(TX)),
            result.outcomes)
        assertTrue(file.exists())
        assertEquals(0, publisher.calls)
    }

    @Test fun failedAppliedProofKeepsTheJournalAndReceiptObligation() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        database.trackDao().insertAll(listOf(preWriteRow(entry)))
        // The proof path is blocked, so the terminal proof cannot be written.
        val blocked = MetadataIndexAppliedReceipt.file(journalDir, TX)
        assertTrue(blocked.mkdir())
        File(blocked, "child").writeText("x")
        val publisher = RecordingPublisher(true)
        val result = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.RecoveryRequired(TX,
            "INDEX_APPLIED_PROOF_NOT_DURABLE")), result.outcomes)
        assertEquals(1, publisher.calls)
        assertTrue(file.exists())
        assertTrue(File(journalDir, "journal-v1.bin").exists())
        assertTrue(File(journalDir, "backup-${TX}.bin").exists())
    }

    @Test fun receiptDeleteFailureAfterCasLeavesProofAndNeverRepeatsTheCas() = runBlocking {
        val entry = stagedJournal()
        val file = writeReceipt(entry)
        database.trackDao().insertAll(listOf(preWriteRow(entry)))
        val publisher = object : MetadataIndexReconciler.Publisher {
            var calls = 0
            override suspend fun publish(
                request: MetadataIndexReconciler.PublishRequest,
            ): Boolean {
                calls++
                // Replace the reconciliation receipt with a non-empty directory so
                // the post-CAS cleanup cannot delete it.
                assertTrue(file.delete())
                assertTrue(file.mkdir())
                File(file, "child").writeText("x")
                return true
            }
        }
        val first = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.RecoveryRequired(TX,
            "RECEIPT_CLEANUP_PENDING")), first.outcomes)
        assertEquals(1, publisher.calls)
        assertFalse(File(journalDir, "journal-v1.bin").exists())
        assertTrue(MetadataIndexAppliedReceipt.file(journalDir, TX).exists())

        // The directory is not a valid candidate, so the next open has no
        // actionable receipt and must never attempt the CAS again.
        val second = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertTrue(second.outcomes.isEmpty())
        assertEquals(1, publisher.calls)
    }

    private inner class RecordingPublisher(private val result: Boolean) :
        MetadataIndexReconciler.Publisher {
        var calls = 0
        override suspend fun publish(
            request: MetadataIndexReconciler.PublishRequest,
        ): Boolean {
            calls++
            assertEquals(TX, request.entry.id)
            return result
        }
    }

    private inner class FakeSource(
        private val original: ByteArray,
        private val candidate: ByteArray,
        private val originalReport: MetadataFieldReadReport,
        private val candidateReport: MetadataFieldReadReport,
    ) : MetadataIndexReconciler.SourceGate {
        override fun exactCandidate(entry: MetadataRecoveryJournalV1.Entry): Boolean =
            sha(candidate) == entry.stageSha256 && candidate.size.toLong() == entry.stageBytes

        override fun parseSource(
            entry: MetadataRecoveryJournalV1.Entry,
        ): MetadataFieldReadReport? = candidateReport

        override fun snapshot(backup: File, target: MetadataTarget): MetadataSnapshot? =
            if (backup.readBytes().contentEquals(original))
                originalReport.toSnapshot(target) else null
    }

    private fun writePermit(entry: MetadataRecoveryJournalV1.Entry) = MetadataWritePermit(
        transactionId = entry.id, sourceId = entry.sourceId, trackId = entry.trackId,
        uri = entry.uri, originalSha256 = entry.originalSha256,
        candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
        profileId = MediaStoreMp3TextProfile.ID,
        evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
        container = entry.format, revision = 4, fields = setOf(MetadataField.TITLE),
        explicitUserAction = true,
    )

    private fun review(before: String, after: String): org.michimusic.core.metadata.MetadataReview.Ready {
        val snapshot = MetadataSnapshot(
            target = target, tagsVerified = true,
            known = mapOf(MetadataField.TITLE to listOf(before)),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        return MetadataEditPreview.review(snapshot,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf(after))))
            as org.michimusic.core.metadata.MetadataReview.Ready
    }

    private fun stage(source: ByteArray, title: String): ByteArray {
        val dir = Files.createTempDirectory("me13-reconcile-stage").toFile()
        try {
            val sourceFile = File(dir, "source.mp3").also { it.writeBytes(source) }
            val stageFile = File(dir, "stage.mp3")
            val snapshot = MetadataSnapshot(
                target = target, tagsVerified = true,
                known = mapOf(MetadataField.TITLE to listOf("Original")),
                verifiedFields = setOf(MetadataField.TITLE),
            )
            val proposal = MetadataEditPreview.review(snapshot,
                listOf(MetadataChange.Replace(MetadataField.TITLE, listOf(title))))
            MetadataId3StageRewriter.stage(sourceFile, stageFile, proposal, source.size.toLong())
            return stageFile.readBytes()
        } finally { dir.deleteRecursively() }
    }

    private fun report(bytes: ByteArray): MetadataFieldReadReport {
        val dir = Files.createTempDirectory("me13-reconcile-report").toFile()
        try {
            val file = File(dir, "fixture.mp3").also { it.writeBytes(bytes) }
            return FileChannel.open(file.toPath(), StandardOpenOption.READ)
                .use(MetadataColdFieldParser::observe)
        } finally { dir.deleteRecursively() }
    }

    private fun sample(tagVersion: Int, title: String): ByteArray {
        fun sync(n: Int) = byteArrayOf(
            ((n ushr 21) and 127).toByte(), ((n ushr 14) and 127).toByte(),
            ((n ushr 7) and 127).toByte(), (n and 127).toByte())
        fun frame(id: String, value: String): ByteArray {
            val payload = byteArrayOf(if (tagVersion == 3) 1 else 3) + value.toByteArray(
                if (tagVersion == 3) StandardCharsets.UTF_16 else StandardCharsets.UTF_8)
            val head = ByteArrayOutputStream()
            head.write(id.toByteArray(StandardCharsets.US_ASCII))
            head.write(if (tagVersion == 3) byteArrayOf(
                (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(), payload.size.toByte())
            else sync(payload.size))
            head.write(0); head.write(0); head.write(payload)
            return head.toByteArray()
        }
        val body = ByteArrayOutputStream().also {
            it.write(frame("TIT2", title))
        }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            out.write("ID3".toByteArray(StandardCharsets.US_ASCII))
            out.write(tagVersion); out.write(0); out.write(0); out.write(sync(body.size))
            out.write(body); out.write(mp3Payload)
        }.toByteArray()
    }

    @Test fun reconcileAllAndReconcileAreBusyAndSideEffectFreeUnderSaveLease() = runBlocking {
        val publisher = MetadataIndexReconciler.Publisher {
            error("publisher must not run while the lease is held")
        }
        MetadataSaveLocks.tryRun(onBusy = { error("outer lock must be acquired") }) {
            val report = reconciler(publisher = publisher).reconcileAll({ true }, { true })
            assertTrue(report.busy)
            assertTrue(report.outcomes.isEmpty())
            assertFalse(report.changedIndex)
            assertEquals(null, reconciler(publisher = publisher)
                .reconcile(TX, { true }, { true }))
        }
        val after = reconciler(publisher = publisher).reconcileAll({ true }, { true })
        assertFalse(after.busy)
        assertTrue(after.outcomes.isEmpty())
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private companion object {
        val TX: String = UUID.randomUUID().toString()
    }
}
