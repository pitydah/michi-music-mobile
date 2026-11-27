package org.michimusic.data.metadata

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
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
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataId3StageRewriter
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.SyncedTrackRepository

/**
 * ME-13 C: durable V2 obligations survive a real file-backed Room restart,
 * missing rows are repaired through the production repository API, and a
 * cleanup failure never repeats the CAS.
 */
@RunWith(AndroidJUnit4::class)
class MetadataIndexReconcilerInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var databaseName: String
    private lateinit var database: MichiDatabase
    private var created: Uri? = null
    private val sourceId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
    private val ownerToken = "me13:reconciler-owner-token"
    private lateinit var trackId: String
    private lateinit var audioPayload: ByteArray
    private lateinit var originalBytes: ByteArray
    private lateinit var journalRoot: File

    @Before fun setUp() {
        databaseName = "me13-reconciler-${UUID.randomUUID()}.db"
        database = openDatabase()
        journalRoot = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
        journalRoot.deleteRecursively()
        audioPayload = context.assets.open("me13-silence.mp3").use { it.readBytes() }
        originalBytes = id3("ME13 Original", "Artista ME13", "Album ME13", "Rock", audioPayload)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "me13-reconciler-${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ME13Reconciler")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        created = context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        val uri = requireNotNull(created)
        context.contentResolver.openFileDescriptor(uri, "rwt")!!.use {
            it.fileDescriptor.writeAll(originalBytes)
        }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        trackId = "local_${uri.lastPathSegment!!.toLong()}"
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        created?.let { context.contentResolver.delete(it, null, null) }
        journalRoot.deleteRecursively()
    }

    @Test fun t36FailedPublisherKeepsActionableReceiptAcrossRoomRestart() = runBlocking {
        val uri = requireNotNull(created)
        val (review, candidate) = prepare(uri, "ME13 T36")
        seedVerifiedRow(originalBytes.size.toLong())
        seedPostcheckJournal(uri, review, candidate)
        writeReceipt(candidate)

        val failed = MetadataIndexReconciler(context, database,
            MetadataIndexReconciler.AndroidSourceGate(context),
            MetadataIndexReconciler.Publisher { throw IllegalStateException("forced") })
            .reconcileAll({ true }, { true })
        assertTrue("outcome=${failed.outcomes}",
            failed.outcomes.single() is MetadataIndexReconciler.Outcome.RecoveryRequired)
        assertEquals(MetadataRecoveryPhase.POSTCHECK_VERIFIED,
            (MetadataRecoveryJournalV1(journalRoot).inspect() as
                MetadataRecoveryJournalV1.Inspection.Pending).entry.phase)
        assertTrue(MetadataIndexReconciliationReceipt.read(journalRoot, TX)!!.actionable)

        database.close()
        database = openDatabase()
        val retry = MetadataIndexReconciler(context, database)
            .reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.Indexed(TX)), retry.outcomes)
        assertEquals("ME13 T36",
            database.trackDao().getTrackBySource(sourceId, trackId)!!.title)
        assertEquals(MetadataRecoveryJournalV1.Inspection.Clear,
            MetadataRecoveryJournalV1(journalRoot).inspect())
        assertFalse(MetadataIndexReconciliationReceipt.file(journalRoot, TX).exists())
    }

    @Test fun x08MissingRowIsPublishedAfterTheExactOriginalRowAppears() = runBlocking {
        val uri = requireNotNull(created)
        val (review, candidate) = prepare(uri, "ME13 X08")
        seedPostcheckJournal(uri, review, candidate)
        writeReceipt(candidate)

        val waiting = MetadataIndexReconciler(context, database)
            .reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.WaitingForIndexedRow(TX)),
            waiting.outcomes)
        assertTrue(MetadataIndexReconciliationReceipt.read(journalRoot, TX)!!.actionable)

        seedVerifiedRow(originalBytes.size.toLong())
        val indexed = MetadataIndexReconciler(context, database)
            .reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.Indexed(TX)), indexed.outcomes)
        assertEquals("ME13 X08",
            database.trackDao().getTrackBySource(sourceId, trackId)!!.title)
        assertEquals(MetadataRecoveryJournalV1.Inspection.Clear,
            MetadataRecoveryJournalV1(journalRoot).inspect())
        assertFalse(MetadataIndexReconciliationReceipt.file(journalRoot, TX).exists())
    }

    @Test fun x12ReceiptCleanupFailureNeverRepeatsTheCas() = runBlocking {
        val uri = requireNotNull(created)
        val (review, candidate) = prepare(uri, "ME13 X12")
        seedVerifiedRow(originalBytes.size.toLong())
        seedPostcheckJournal(uri, review, candidate)
        writeReceipt(candidate)
        val real = MetadataIndexReconciler.CoordinatorPublisher(context, database)
        val sabotaging = MetadataIndexReconciler.Publisher { request ->
            val applied = real.publish(request)
            if (applied) {
                val file = MetadataIndexReconciliationReceipt.file(journalRoot, TX)
                file.delete()
                file.mkdirs()
                File(file, "blocker").writeText("x")
            }
            applied
        }
        val first = MetadataIndexReconciler(context, database,
            MetadataIndexReconciler.AndroidSourceGate(context), sabotaging)
            .reconcileAll({ true }, { true })
        assertEquals(listOf(MetadataIndexReconciler.Outcome.RecoveryRequired(
            TX, "RECEIPT_CLEANUP_PENDING")), first.outcomes)
        assertEquals("ME13 X12",
            database.trackDao().getTrackBySource(sourceId, trackId)!!.title)
        assertEquals(MetadataRecoveryJournalV1.Inspection.Clear,
            MetadataRecoveryJournalV1(journalRoot).inspect())
        assertTrue(MetadataIndexAppliedReceipt.file(journalRoot, TX).exists())

        val second = MetadataIndexReconciler(context, database)
            .reconcileAll({ true }, { true })
        assertTrue("outcome=${second.outcomes}", second.outcomes.isEmpty())
        assertEquals("ME13 X12",
            database.trackDao().getTrackBySource(sourceId, trackId)!!.title)
    }

    private fun openDatabase(): MichiDatabase =
        Room.databaseBuilder(context, MichiDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()

    private fun prepare(uri: Uri, title: String): Pair<MetadataReview.Ready, ByteArray> {
        val original = originalSnapshot(uri)
        val review = MetadataEditPreview.review(original,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf(title))))
            as MetadataReview.Ready
        val dir = File(context.cacheDir, "me13-reconciler-stage-${UUID.randomUUID()}")
        val candidate = try {
            dir.mkdirs()
            val source = File(dir, "source.mp3").also { it.writeBytes(originalBytes) }
            val staged = File(dir, "stage.mp3")
            MetadataId3StageRewriter.stage(source, staged, review, originalBytes.size.toLong())
            staged.readBytes()
        } finally { dir.deleteRecursively() }
        writeSource(uri, candidate)
        return review to candidate
    }

    private fun originalSnapshot(uri: Uri): MetadataSnapshot {
        val file = File(context.cacheDir, "me13-reconciler-original-${UUID.randomUUID()}.mp3")
        return try {
            file.writeBytes(originalBytes)
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                MetadataColdFieldParser.observe(channel).toSnapshot(
                    MetadataTarget(sourceId, trackId, uri.toString(), ownerToken))
            }
        } finally { file.delete() }
    }

    private suspend fun seedVerifiedRow(size: Long) {
        val repository = SyncedTrackRepository(trackDao = database.trackDao())
        repository.saveLibrary(listOf(TrackDto(
            id = trackId, title = "ME13 Original", artist = "Artista ME13",
            album = "Album ME13", duration = 100, size = size,
            format = "mp3", genre = "Rock",
        )), sourceId)
        repository.markDownloadedVerified(
            sourceId = sourceId, id = trackId, filepath = created.toString(),
            size = size, sha256 = sha(originalBytes),
            verifiedAt = 1_700_000_000_000, algorithm = "sha256")
    }

    private fun seedPostcheckJournal(
        uri: Uri,
        review: MetadataReview.Ready,
        candidate: ByteArray,
    ) {
        val journal = MetadataRecoveryJournalV1(journalRoot)
        val intents = MetadataCommitIntentStore(journal)
        val owners = MetadataRecoveryOwnerStore(journalRoot)
        val container = MetadataAudioContainer.ID3_V23
        val intent = MetadataCommitIntentManifest.create(
            transactionId = TX, review = review,
            provider = MetadataDocumentProvider.MEDIASTORE, container = container,
            originalBytes = originalBytes.size.toLong(), originalSha256 = sha(originalBytes),
            candidateBytes = candidate.size.toLong(), candidateSha256 = sha(candidate),
        )
        val initial = MetadataRecoveryJournalV1.Entry(
            TX, MetadataRecoveryPhase.SNAPSHOTTING, sourceId, trackId, uri.toString(),
            originalBytes.size.toLong(), 777L, ownerEvidenceToken = ownerToken)
        runBlocking {
            journal.exclusive {
                journal.start(initial)
                journal.backup(initial).writeBytes(originalBytes)
                val backed = initial.copy(phase = MetadataRecoveryPhase.BACKUP_DURABLE,
                    originalSha256 = sha(originalBytes))
                journal.advance(initial, backed)
                journal.stage(backed).writeBytes(candidate)
                val staged = backed.copy(phase = MetadataRecoveryPhase.STAGE_READY,
                    stageSha256 = sha(candidate), stageBytes = candidate.size.toLong(),
                    format = container.name)
                journal.advance(backed, staged)
                intents.write(staged, intent)
                check(owners.write(staged, review.original.target))
                val permit = MetadataWritePermit(
                    transactionId = TX, sourceId = sourceId, trackId = trackId,
                    uri = uri.toString(), originalSha256 = sha(originalBytes),
                    candidateSha256 = sha(candidate), stageBytes = candidate.size.toLong(),
                    profileId = MediaStoreMp3TextProfile.ID,
                    evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
                    container = container.name, revision = 3,
                    fields = setOf(MetadataField.TITLE), explicitUserAction = true,
                )
                val mutating = staged.copy(phase = MetadataRecoveryPhase.MUTATING)
                journal.advance(staged, mutating, permit)
                journal.advance(mutating,
                    mutating.copy(phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED))
            }
        }
    }

    private fun writeReceipt(candidate: ByteArray) {
        val receipt = MetadataIndexReconciliationReceipt(
            transactionId = TX, sourceId = sourceId, trackId = trackId,
            uri = created.toString(), originalSha256 = sha(originalBytes),
            candidateSha256 = sha(candidate), candidateBytes = candidate.size.toLong(),
            format = MetadataAudioContainer.ID3_V23.name, ownerEvidenceToken = ownerToken,
            previousVerifiedAt = 1_700_000_000_000L,
            previousVerifiedSha256 = sha(originalBytes),
            previousSize = originalBytes.size.toLong(),
        )
        assertTrue(receipt.actionable)
        assertTrue(MetadataIndexReconciliationReceipt.write(journalRoot, receipt))
    }

    private fun writeSource(uri: Uri, bytes: ByteArray) {
        context.contentResolver.openFileDescriptor(uri, "rwt")!!.use {
            it.fileDescriptor.writeAll(bytes)
        }
    }

    private fun FileDescriptor.writeAll(bytes: ByteArray) {
        FileOutputStream(this).use { it.write(bytes); it.fd.sync() }
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }

    private fun id3(title: String, artist: String, album: String, genre: String,
                    audio: ByteArray): ByteArray {
        val frames = listOf("TIT2" to title, "TPE1" to artist,
            "TALB" to album, "TCON" to genre).fold(ByteArrayOutputStream()) { out, (id, value) ->
            val text = byteArrayOf(0) + value.toByteArray(StandardCharsets.ISO_8859_1)
            out.write(id.toByteArray(StandardCharsets.US_ASCII))
            out.write(be32(text.size))
            out.write(byteArrayOf(0, 0))
            out.write(text)
            out
        }.toByteArray()
        val header = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(StandardCharsets.US_ASCII))
            write(byteArrayOf(3, 0, 0))
            write(synchsafe(frames.size))
        }.toByteArray()
        return header + frames + audio
    }

    private fun be32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte())

    private fun synchsafe(value: Int) = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(), ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(), (value and 0x7f).toByte())

    private companion object {
        const val TX = "c7527d1a-20fa-498f-a66c-b22c3bb58ad4"
    }
}
