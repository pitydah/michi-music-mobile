package org.michimusic.data.metadata

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorColdRead
import org.michimusic.data.repository.SyncedTrackRepository

/**
 * F5/N27: real process death at AFTER_PROVIDER_CLOSE. Phase one is run in its
 * own instrumentation command, kills the process after the candidate is durable,
 * and leaves an exact private record. Phase two is a new process that resumes
 * through the production coordinator. Normal suites skip both phases.
 */
@RunWith(AndroidJUnit4::class)
class MetadataSaveKillInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val packageName = "ME14_TEST_"
    private val recordRoot get() = File(context.noBackupFilesDir, "me14-kill-only")
    private val record get() = File(recordRoot, "phase-one.bin")

    @Test fun phaseOneKillAfterProviderClose(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("ME14_KILL_SAVE") == "I_ACCEPT_PROCESS_DEATH")
        check(recordRoot.deleteRecursively())
        check(recordRoot.mkdirs())
        // This instrumentation package owns a disposable journal root; never the
        // production app's journal.
        File(context.noBackupFilesDir, "michi-metadata-editor-v1").deleteRecursively()
        val databaseName = "me14-kill-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, MichiDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        val audio = InstrumentationRegistry.getInstrumentation().context.assets
            .open("me13-silence.mp3").use { it.readBytes() }
        val original = Mp3FixtureTags.build(
            3, "ME13 Kill Original", "Artista Kill", "Album Kill", "Rock", audio)
        val candidate = Mp3FixtureTags.build(
            3, "ME13 Kill Candidate", "Artista Kill", "Album Kill", "Rock", audio)
        val uri = insert(original)
        val trackId = "local_${uri.lastPathSegment!!.toLong()}"
        val sourceId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
        val repository = SyncedTrackRepository(trackDao = database.trackDao())
        repository.saveLibrary(listOf(TrackDto(
            id = trackId, title = "ME13 Kill Original", artist = "Artista Kill",
            album = "Album Kill", duration = 100, size = original.size.toLong(),
            format = "mp3", genre = "Rock",
        )), sourceId)
        repository.markDownloadedVerified(
            sourceId = sourceId, id = trackId, filepath = uri.toString(),
            size = original.size.toLong(), sha256 = sha(original),
            verifiedAt = 1_700_000_000_000, algorithm = "sha256")
        val snapshot = when (val read = ExtendedTrackMetadataRepository(
            database.trackDao(), context,
        ).loadEditorColdRead("", trackId, uri.toString(), original.size.toLong())) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("cold read failed: $read")
        }
        val review = MetadataEditPreview.review(snapshot,
            listOf(MetadataChange.Replace(MetadataField.TITLE,
                listOf("ME13 Kill Candidate")))) as MetadataReview.Ready

        val hook = MetadataSaveCheckpointHook { point ->
            if (point == MetadataSaveCheckpoint.AFTER_PROVIDER_CLOSE) {
                val entry = (MetadataRecoveryJournalV1(
                    File(context.noBackupFilesDir, "michi-metadata-editor-v1")).inspect() as
                    MetadataRecoveryJournalV1.Inspection.Pending).entry
                writeRecord(Record(
                    transactionId = entry.id, dbName = databaseName,
                    sourceId = sourceId, trackId = trackId, uri = uri.toString(),
                    originalSha = sha(original), candidateSha = sha(candidate),
                    candidateBytes = candidate.size.toLong(), pid = Process.myPid(),
                ))
                Process.killProcess(Process.myPid())
                error("KILL_DID_NOT_OCCUR")
            }
        }
        val outcome = MetadataSaveService(context, database.trackDao(), database, hook).save(
            sourceId = sourceId, trackId = trackId, uriString = uri.toString(),
            expectedBytes = original.size.toLong(), reviewInput = review,
            ownerCurrent = { true }, playbackClosed = { true },
        )
        error("SAVE_RETURNED_BEFORE_KILL: $outcome")
    }

    @Test fun phaseTwoResumeAfterRealProcessDeath() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("ME14_RESUME_SAVE") == "I_ACCEPT_PROCESS_DEATH")
        val record = readRecord() ?: error("PHASE_ONE_RECORD_MISSING")
        assertNotEquals(record.pid, Process.myPid())
        val database = Room.databaseBuilder(context, MichiDatabase::class.java, record.dbName)
            .allowMainThreadQueries().build()
        try {
            val outcome = MetadataRecoveryCoordinator(context, database).resume(
                MetadataRecoveryCoordinator.RecoveryRequest(
                    transactionId = record.transactionId, sourceId = record.sourceId,
                    trackId = record.trackId, uri = record.uri, userRequested = true,
                    ownerStillCurrent = { true }, playbackClosed = { true },
                ))
            assertTrue("outcome=$outcome",
                outcome is MetadataRecoveryCoordinator.Outcome.Indexed)
            val row = database.trackDao().getTrackBySource(record.sourceId, record.trackId)!!
            assertEquals("ME13 Kill Candidate", row.title)
            assertEquals(record.candidateBytes, row.size)
            assertEquals(0L, row.verifiedAt)
            assertEquals("", row.verifiedSha256)
            val bytes = context.contentResolver.openInputStream(Uri.parse(record.uri))!!
                .use { it.readBytes() }
            assertEquals(record.candidateSha, sha(bytes))
            assertEquals(MetadataRecoveryJournalV1.Inspection.Clear,
                MetadataRecoveryJournalV1(File(context.noBackupFilesDir,
                    "michi-metadata-editor-v1")).inspect())
        } finally {
            database.close()
            context.deleteDatabase(record.dbName)
            context.contentResolver.delete(Uri.parse(record.uri), null, null)
            recordRoot.deleteRecursively()
        }
    }

    private fun insert(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,
                "${packageName}${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ME14Kill")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        context.contentResolver.openFileDescriptor(uri, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                it.write(bytes); it.flush(); it.fd.sync()
            }
        }
        values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    }

    private data class Record(
        val transactionId: String, val dbName: String, val sourceId: String,
        val trackId: String, val uri: String, val originalSha: String,
        val candidateSha: String, val candidateBytes: Long, val pid: Int,
    )

    private fun writeRecord(record: Record) {
        val file = this.record
        FileOutputStream(file).use { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(MAGIC); data.writeInt(1)
                data.writeUTF(record.transactionId); data.writeUTF(record.dbName)
                data.writeUTF(record.sourceId); data.writeUTF(record.trackId)
                data.writeUTF(record.uri); data.writeUTF(record.originalSha)
                data.writeUTF(record.candidateSha); data.writeLong(record.candidateBytes)
                data.writeInt(record.pid)
            }
            out.fd.sync()
        }
        check(file.isFile && file.length() in 1..4096)
    }

    private fun readRecord(): Record? = try {
        val bytes = record.readBytes()
        DataInputStream(bytes.inputStream()).use { data ->
            if (data.readInt() != MAGIC || data.readInt() != 1) null else Record(
                data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF(),
                data.readUTF(), data.readUTF(), data.readUTF(), data.readLong(),
                data.readInt(),
            )
        }
    } catch (_: Exception) { null }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object { const val MAGIC = 0x4D453134 }
}
