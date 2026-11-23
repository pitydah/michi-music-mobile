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
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.core.models.TrackDto
import org.michimusic.data.repository.SyncedTrackRepository

/**
 * ME-13 T21/T22: after a process stop, a clean editor recovers an already
 * opened MP3 write from durable journal + intent + owner receipt. No
 * `MetadataReview` is passed, no source write is repeated, and the exact Room
 * CAS is still required.
 */
@RunWith(AndroidJUnit4::class)
class MetadataRecoveryCoordinatorInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: MichiDatabase
    private var created: Uri? = null
    private val sourceId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
    private val ownerToken = "me13:recovery-owner-token"
    private lateinit var trackId: String
    private lateinit var audioPayload: ByteArray
    private lateinit var originalBytes: ByteArray
    private lateinit var journalRoot: File

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries().build()
        journalRoot = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
        journalRoot.deleteRecursively()
        audioPayload = readAsset("me13-silence.mp3")
        originalBytes = id3("ME13 Antes", "Artista ME13", "Album ME13", "Rock", audioPayload)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "me13-recovery-${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ME13Recovery")
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
        val id = uri.lastPathSegment!!.toLong()
        trackId = "local_$id"
    }

    @After fun tearDown() {
        created?.let { context.contentResolver.delete(it, null, null) }
        database.close()
        journalRoot.deleteRecursively()
    }

    @Test fun recoversCommittedCandidateWithoutReviewAndPublishesExactCas() = runBlocking {
        val uri = requireNotNull(created)
        val original = originalSnapshot(originalBytes, uri)
        val review = review(original, "ME13 Recuperada")
        val candidate = stage(originalBytes, review)
        seedVerifiedRow(originalBytes)
        seedPostMutationJournal(uri, original, review, candidate)
        // The killed process already wrote candidate bytes. Recovery must only
        // reread them, never call the writing transport again.
        writeSource(uri, candidate)

        val outcome = MetadataRecoveryCoordinator(context, database).resume(
            MetadataRecoveryCoordinator.RecoveryRequest(
                transactionId = TX, sourceId = sourceId, trackId = trackId,
                uri = uri.toString(), userRequested = true,
                ownerStillCurrent = { true }, playbackClosed = { true },
            ))

        assertTrue("outcome=$outcome", outcome is MetadataRecoveryCoordinator.Outcome.Indexed)
        val row = database.trackDao().getTrackBySource(sourceId, trackId)!!
        assertEquals("ME13 Recuperada", row.title)
        assertEquals(candidate.size.toLong(), row.size)
        assertEquals(0L, row.verifiedAt)
        assertEquals("", row.verifiedSha256)
        assertEquals(MetadataRecoveryJournalV1.Inspection.Clear,
            MetadataRecoveryJournalV1(journalRoot).inspect())
        val readBack = readSource(uri)
        assertEquals(sha(candidate), sha(readBack))
        assertTrue(readBack.copyOfRange(readBack.size - audioPayload.size, readBack.size)
            .contentEquals(audioPayload))
    }

    @Test fun missingRowLeavesActionableReceiptAndNeverRollsBackCandidate() = runBlocking {
        val uri = requireNotNull(created)
        val original = originalSnapshot(originalBytes, uri)
        val review = review(original, "ME13 Sin Fila")
        val candidate = stage(originalBytes, review)
        seedPostMutationJournal(uri, original, review, candidate)
        writeSource(uri, candidate)

        val outcome = MetadataRecoveryCoordinator(context, database).resume(
            MetadataRecoveryCoordinator.RecoveryRequest(
                transactionId = TX, sourceId = sourceId, trackId = trackId,
                uri = uri.toString(), userRequested = true,
                ownerStillCurrent = { true }, playbackClosed = { true },
            ))

        assertTrue("outcome=$outcome",
            outcome is MetadataRecoveryCoordinator.Outcome.IndexPending)
        assertEquals("", sha(readSource(uri)).let { if (it == sha(candidate)) "" else it })
        assertEquals(MetadataRecoveryPhase.POSTCHECK_VERIFIED,
            (MetadataRecoveryJournalV1(journalRoot).inspect() as
                MetadataRecoveryJournalV1.Inspection.Pending).entry.phase)
        val receipt = MetadataIndexReconciliationReceipt.read(journalRoot, TX)
        assertTrue("receipt=$receipt", receipt != null && receipt.actionable)
    }

    @Test fun missingOwnerReceiptFailsClosedWithoutCasOrSourceWrite() = runBlocking {
        val uri = requireNotNull(created)
        val original = originalSnapshot(originalBytes, uri)
        val review = review(original, "ME13 Sin Propietario")
        val candidate = stage(originalBytes, review)
        seedPostMutationJournal(uri, original, review, candidate)
        writeSource(uri, candidate)
        val entry = (MetadataRecoveryJournalV1(journalRoot).inspect() as
            MetadataRecoveryJournalV1.Inspection.Pending).entry
        assertTrue(MetadataRecoveryOwnerStore(journalRoot).delete(entry))
        val before = sha(readSource(uri))

        val outcome = MetadataRecoveryCoordinator(context, database).resume(
            MetadataRecoveryCoordinator.RecoveryRequest(
                transactionId = TX, sourceId = sourceId, trackId = trackId,
                uri = uri.toString(), userRequested = true,
                ownerStillCurrent = { true }, playbackClosed = { true },
            ))

        assertEquals(MetadataRecoveryCoordinator.Outcome.RecoveryRequired(
            TX, "OWNER_RECEIPT_MISSING_OR_CORRUPT"), outcome)
        assertEquals(before, sha(readSource(uri)))
        assertEquals(MetadataRecoveryPhase.MUTATION_UNKNOWN,
            (MetadataRecoveryJournalV1(journalRoot).inspect() as
                MetadataRecoveryJournalV1.Inspection.Pending).entry.phase)
        assertEquals(null, MetadataIndexReconciliationReceipt.read(journalRoot, TX))
    }

    private fun originalSnapshot(bytes: ByteArray, uri: Uri): MetadataSnapshot {
        val file = File(context.cacheDir, "me13-recovery-original-${UUID.randomUUID()}.mp3")
        return try {
            file.writeBytes(bytes)
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                MetadataColdFieldParser.observe(channel).toSnapshot(
                    MetadataTarget(sourceId, trackId, uri.toString(), ownerToken))
            }
        } finally { file.delete() }
    }

    private fun stage(bytes: ByteArray, review: MetadataReview.Ready): ByteArray {
        val dir = File(context.cacheDir, "me13-recovery-stage-${UUID.randomUUID()}")
        return try {
            dir.mkdirs()
            val source = File(dir, "source.mp3").also { it.writeBytes(bytes) }
            val staged = File(dir, "stage.mp3")
            MetadataId3StageRewriter.stage(source, staged, review, bytes.size.toLong())
            staged.readBytes()
        } finally { dir.deleteRecursively() }
    }

    private fun review(snapshot: MetadataSnapshot, title: String): MetadataReview.Ready =
        MetadataEditPreview.review(snapshot,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf(title))))
            as MetadataReview.Ready

    private fun seedVerifiedRow(bytes: ByteArray) = runBlocking {
        val repository = SyncedTrackRepository(trackDao = database.trackDao())
        repository.saveLibrary(listOf(TrackDto(
            id = trackId, title = "ME13 Antes", artist = "Artista ME13",
            album = "Album ME13", duration = 100, size = bytes.size.toLong(),
            format = "mp3", genre = "Rock",
        )), sourceId)
        repository.markDownloadedVerified(
            sourceId = sourceId, id = trackId, filepath = created.toString(),
            size = bytes.size.toLong(), sha256 = sha(bytes),
            verifiedAt = 1_700_000_000_000, algorithm = "sha256")
    }

    private fun seedPostMutationJournal(
        uri: Uri,
        original: MetadataSnapshot,
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
                    mutating.copy(phase = MetadataRecoveryPhase.MUTATION_UNKNOWN))
            }
        }
        check(original.target.uri == uri.toString())
    }

    private fun writeSource(uri: Uri, bytes: ByteArray) {
        context.contentResolver.openFileDescriptor(uri, "rwt")!!.use {
            it.fileDescriptor.writeAll(bytes)
        }
    }

    private fun readSource(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun readAsset(name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

    private fun java.io.FileDescriptor.writeAll(bytes: ByteArray) {
        java.io.FileOutputStream(this).use { it.write(bytes); it.fd.sync() }
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
        const val TX = "b7527d1a-20fa-498f-a66c-b22c3bb58ad4"
    }
}
