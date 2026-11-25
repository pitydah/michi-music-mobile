package org.michimusic.data.metadata

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorColdRead
import org.michimusic.data.repository.SyncedTrackRepository

/**
 * ME-13 P0 E2E on a disposable, app-owned MediaStore MP3 and a real Room
 * `cached_tracks` row inserted through the production sync repository API.
 *
 * This is NOT a mock provider: the production Save path rewrites a real
 * MediaStore descriptor, performs its own independent postcheck, and applies
 * the exact Room CAS. The item is always deleted by its exact URI.
 */
@RunWith(AndroidJUnit4::class)
class MetadataSaveMediaStoreE2EInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: MichiDatabase
    private var created: Uri? = null
    private var sourceId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
    private val readSourceId = ""
    private var trackId = ""

    private lateinit var audioPayload: ByteArray
    private lateinit var originalBytes: ByteArray

    @Before fun setup(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        audioPayload = instrumentation.context.assets.open("me13-silence.mp3")
            .use { it.readBytes() }
        originalBytes = id3(
            title = "ME13 Original",
            artist = "Synthetic Artist",
            album = "Synthetic Album",
            genre = "Rock",
            audio = audioPayload,
        )
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun cleanup(): Unit = runBlocking {
        database.close()
        created?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    @Test fun saveRewritesMediaStoreVerifiesAndPublishesTheExactCachedRow() = runBlocking {
        val resolver = context.contentResolver
        val label = "me13-e2e-${UUID.randomUUID()}.mp3"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, label)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/MichiME13E2E/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val inserted = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("Could not create the disposable MediaStore item", inserted)
        val uri = inserted!!
        created = uri

        resolver.openFileDescriptor(uri, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                output.write(originalBytes)
                output.flush()
                output.fd.sync()
            }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        val mediaId = uri.lastPathSegment!!.substringAfterLast('/')
        trackId = "local_$mediaId"
        val observedSize = resolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
        assertTrue("MediaStore fixture size must be positive", observedSize > 0)
        val originalSha = sha(originalBytes)
        println("ME13_E2E fixture uri=$uri trackId=$trackId bytes=$observedSize sha=$originalSha")

        val repository = SyncedTrackRepository(trackDao = database.trackDao())
        repository.saveLibrary(
            listOf(
                TrackDto(
                    id = trackId,
                    title = "ME13 Original",
                    artist = "Synthetic Artist",
                    album = "Synthetic Album",
                    size = observedSize,
                    format = "mp3",
                    genre = "Rock",
                ),
            ),
            sourceId = sourceId,
        )
        val seeded = database.trackDao().getTrackBySource(sourceId, trackId)
        assertNotNull("The production repository API must create the cached row", seeded)
        database.trackDao().markDownloadedVerified(
            sourceId = sourceId,
            id = trackId,
            filepath = uri.toString(),
            size = observedSize,
            sha256 = originalSha,
            verifiedAt = 1_700_000_000_000L,
            algorithm = "sha256",
        )
        val indexedBefore = database.trackDao().getTrackBySource(sourceId, trackId)!!
        assertEquals(uri.toString(), indexedBefore.filepath)
        assertEquals(originalSha, indexedBefore.verifiedSha256)

        val reader = ExtendedTrackMetadataRepository(
            trackDao = database.trackDao(),
            context = context,
        )
        val original = when (
            val read = reader.loadEditorColdRead(readSourceId, trackId, uri.toString(), observedSize)
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("The production cold reader rejected the synthetic MP3: $read")
        }
        assertEquals(listOf("ME13 Original"), original.known[MetadataField.TITLE])

        val review = MetadataEditPreview.review(
            original,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("ME13 Renombrada"))),
        )
        assertTrue("Review must accept the scalar MP3 title edit: $review", review is MetadataReview.Ready)

        val service = MetadataSaveService(context, database.trackDao(), database)
        val outcome = service.save(
            sourceId = sourceId,
            trackId = trackId,
            uriString = uri.toString(),
            expectedBytes = observedSize,
            reviewInput = review as MetadataReview.Ready,
            ownerCurrent = { true },
            playbackClosed = { true },
        )
        println("ME13_E2E outcome=$outcome")
        assertTrue(
            "The unified Save path must verify and index the exact row, got $outcome",
            outcome is MetadataSaveService.Outcome.Saved && outcome.indexPublished,
        )
        val saved = outcome as MetadataSaveService.Outcome.Saved
        assertEquals(listOf("ME13 Renombrada"), saved.snapshot.known[MetadataField.TITLE])
        assertEquals(
            org.michimusic.core.metadata.MetadataFieldObservation.Present(
                listOf("ME13 Renombrada")),
            saved.snapshot.fieldObservations[MetadataField.TITLE],
        )

        val after = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val afterSha = sha(after)
        assertTrue(
            "The synthetic audio payload must be byte-identical after the tag",
            after.size > audioPayload.size &&
                after.copyOfRange(after.size - audioPayload.size, after.size)
                    .contentEquals(audioPayload),
        )

        val row = database.trackDao().getTrackBySource(sourceId, trackId)!!
        assertEquals("ME13 Renombrada", row.title)
        assertEquals(after.size.toLong(), row.size)
        assertEquals(0L, row.verifiedAt)
        assertEquals("", row.verifiedSha256)

        val reopened = when (
            val read = reader.loadEditorColdRead(readSourceId, trackId, uri.toString(), after.size.toLong())
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("Reopening the saved MP3 failed: $read")
        }
        assertEquals(listOf("ME13 Renombrada"), reopened.known[MetadataField.TITLE])

        val journal = MetadataRecoveryJournalV1(
            java.io.File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
        )
        assertTrue(
            "A verified, published save must leave no pending recovery journal",
            journal.inspect() is MetadataRecoveryJournalV1.Inspection.Clear,
        )
        println(
            "ME13_E2E PASS originalSha=$originalSha candidateSha=$afterSha " +
                "rowTitle=${row.title} rowSize=${row.size} journal=Clear",
        )

        val receiptRoot = java.io.File(context.noBackupFilesDir, "me13-receipt-android-only")
        val receipt = MetadataIndexReconciliationReceipt(
            transactionId = java.util.UUID.randomUUID().toString(),
            sourceId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID,
            trackId = trackId,
            uri = uri.toString(),
            originalSha256 = sha(originalBytes),
            candidateSha256 = sha(after),
            candidateBytes = after.size.toLong(),
            format = "ID3_V23",
            ownerEvidenceToken = "me13:android-e2e-owner",
            previousVerifiedAt = 1L,
            previousVerifiedSha256 = sha(originalBytes),
            previousSize = originalBytes.size.toLong(),
        )
        assertTrue("The Android receipt must be actionable", receipt.actionable)
        assertTrue(
            "The Android no-backup receipt must be durable",
            MetadataIndexReconciliationReceipt.write(receiptRoot, receipt),
        )
        assertEquals(
            receipt,
            MetadataIndexReconciliationReceipt.read(receiptRoot, receipt.transactionId),
        )
        receiptRoot.deleteRecursively()
        Unit
    }

    @Test fun saveFlacTitleRewritesMediaStorePreservesAudioAndPublishesRoom() = runBlocking {
        val resolver = context.contentResolver
        val flacAudio = ByteArray(4096) { ((it * 47) % 251).toByte() }
        val flacOriginal = flac(
            comments = listOf(
                "TITLE=FLAC Original",
                "ARTIST=Synthetic FLAC Artist",
                "ALBUM=Synthetic FLAC Album",
                "GENRE=Rock",
                "X-CUSTOM=preserve-me",
            ),
            audio = flacAudio,
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "me-final-${UUID.randomUUID()}.flac")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/flac")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/MichiMEFinalE2E/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("Could not create the disposable FLAC MediaStore item", uri)
        created = uri
        resolver.openFileDescriptor(uri!!, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                output.write(flacOriginal)
                output.flush()
                output.fd.sync()
            }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        val mediaId = uri.lastPathSegment!!.substringAfterLast('/')
        val flacTrackId = "local_$mediaId"
        val observedSize = resolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
        val originalSha = sha(flacOriginal)
        SyncedTrackRepository(trackDao = database.trackDao()).saveLibrary(
            listOf(
                TrackDto(
                    id = flacTrackId,
                    title = "FLAC Original",
                    artist = "Synthetic FLAC Artist",
                    album = "Synthetic FLAC Album",
                    size = observedSize,
                    format = "flac",
                    genre = "Rock",
                ),
            ),
            sourceId = sourceId,
        )
        database.trackDao().markDownloadedVerified(
            sourceId = sourceId,
            id = flacTrackId,
            filepath = uri.toString(),
            size = observedSize,
            sha256 = originalSha,
            verifiedAt = 1_700_000_000_000L,
            algorithm = "sha256",
        )
        val reader = ExtendedTrackMetadataRepository(
            trackDao = database.trackDao(),
            context = context,
        )
        val original = when (
            val read = reader.loadEditorColdRead(
                readSourceId, flacTrackId, uri.toString(), observedSize,
            )
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("The production cold reader rejected the synthetic FLAC: $read")
        }
        assertEquals(listOf("FLAC Original"), original.known[MetadataField.TITLE])
        val review = MetadataEditPreview.review(
            original,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("FLAC Renombrada"))),
        )
        assertTrue("Review must accept the FLAC title edit: $review", review is MetadataReview.Ready)

        val outcome = MetadataSaveService(context, database.trackDao(), database).save(
            sourceId = sourceId,
            trackId = flacTrackId,
            uriString = uri.toString(),
            expectedBytes = observedSize,
            reviewInput = review as MetadataReview.Ready,
            ownerCurrent = { true },
            playbackClosed = { true },
        )

        assertTrue(
            "The unified Save path must verify and index FLAC, got $outcome",
            outcome is MetadataSaveService.Outcome.Saved && outcome.indexPublished,
        )
        val after = resolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(flacAudio, after.copyOfRange(after.size - flacAudio.size, after.size))
        assertTrue(String(after, Charsets.ISO_8859_1).contains("X-CUSTOM=preserve-me"))
        val row = database.trackDao().getTrackBySource(sourceId, flacTrackId)!!
        assertEquals("FLAC Renombrada", row.title)
        assertEquals(after.size.toLong(), row.size)
        val reopened = when (
            val read = reader.loadEditorColdRead(
                readSourceId, flacTrackId, uri.toString(), after.size.toLong(),
            )
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("Reopening the saved FLAC failed: $read")
        }
        assertEquals(listOf("FLAC Renombrada"), reopened.known[MetadataField.TITLE])
        assertTrue(
            MetadataRecoveryJournalV1(
                java.io.File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
            ).inspect() is MetadataRecoveryJournalV1.Inspection.Clear,
        )
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun flac(comments: List<String>, audio: ByteArray): ByteArray {
        fun littleEndian32(out: ByteArrayOutputStream, value: Int) {
            repeat(4) { out.write(value ushr (8 * it) and 0xff) }
        }
        fun block(kind: Int, last: Boolean, payload: ByteArray): ByteArray =
            ByteArrayOutputStream().apply {
                write(kind or if (last) 0x80 else 0)
                write(payload.size ushr 16 and 0xff)
                write(payload.size ushr 8 and 0xff)
                write(payload.size and 0xff)
                write(payload)
            }.toByteArray()
        val streamInfo = ByteArray(34).apply {
            this[0] = 0x10
            this[2] = 0x10
            this[10] = 0x0a
            this[11] = 0xc4.toByte()
            this[12] = 0x40
        }
        val vendor = "Michi E2E".toByteArray(Charsets.UTF_8)
        val vorbis = ByteArrayOutputStream().apply {
            littleEndian32(this, vendor.size)
            write(vendor)
            littleEndian32(this, comments.size)
            comments.forEach { comment ->
                val encoded = comment.toByteArray(Charsets.UTF_8)
                littleEndian32(this, encoded.size)
                write(encoded)
            }
        }.toByteArray()
        return "fLaC".toByteArray(Charsets.US_ASCII) +
            block(0, false, streamInfo) +
            block(4, true, vorbis) +
            audio
    }


    /** Minimal valid ID3v2.3 tag with four scalar frames plus synthetic audio. */
    private fun id3(
        title: String,
        artist: String,
        album: String,
        genre: String,
        audio: ByteArray,
    ): ByteArray {
        fun frame(id: String, value: String): ByteArray {
            val text = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
            val size = text.size
            return id.toByteArray(Charsets.US_ASCII) + byteArrayOf(
                ((size shr 24) and 0xFF).toByte(),
                ((size shr 16) and 0xFF).toByte(),
                ((size shr 8) and 0xFF).toByte(),
                (size and 0xFF).toByte(),
                0, 0,
            ) + text
        }
        val frames = frame("TIT2", title) + frame("TPE1", artist) +
            frame("TALB", album) + frame("TCON", genre)
        val header = "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            3, 0, 0,
            ((frames.size shr 21) and 0x7F).toByte(),
            ((frames.size shr 14) and 0x7F).toByte(),
            ((frames.size shr 7) and 0x7F).toByte(),
            (frames.size and 0x7F).toByte(),
        )
        return header + frames + audio
    }
}
