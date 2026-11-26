package org.michimusic.data.metadata

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorColdRead
import org.michimusic.data.repository.SyncedTrackRepository

/**
 * ME-13 Block B: real MediaStore saves for all four certified scalar fields,
 * ID3v2.3 and ID3v2.4, opaque-frame preservation and a stale-preimage refusal.
 */
@RunWith(AndroidJUnit4::class)
class MetadataSaveMediaStoreFieldsInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: MichiDatabase
    private lateinit var audioPayload: ByteArray
    private lateinit var journalRoot: File
    private var created: Uri? = null
    private var trackId = ""
    private val sourceId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID

    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        audioPayload = instrumentation.context.assets.open("me13-silence.mp3")
            .use { it.readBytes() }
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries().build()
        journalRoot = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
        journalRoot.deleteRecursively()
    }

    @After fun cleanup() {
        created?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        database.close()
        journalRoot.deleteRecursively()
    }

    @Test fun t02V24TitleAndUtf8ValueSurvive() = runBlocking {
        val original = Mp3FixtureTags.build(
            4, "ME13 Ñandú Original", "Artista ME13", "Album ME13", "Rock", audioPayload)
        val result = save(original, MetadataField.TITLE, "ME13 Ñandú Nuevo")
        assertSaved(result, MetadataField.TITLE, "ME13 Ñandú Nuevo")
        assertCommon(result, original, "ME13 Ñandú Nuevo", "Artista ME13",
            "Album ME13", "Rock")
    }

    @Test fun t03V23ArtistIsSaved() = runBlocking {
        val original = Mp3FixtureTags.build(
            3, "ME13 Title", "Artista Original", "Album ME13", "Rock", audioPayload)
        val result = save(original, MetadataField.ARTIST, "Artista Nuevo")
        assertCommon(result, original, "ME13 Title", "Artista Nuevo", "Album ME13", "Rock")
    }

    @Test fun t04V23AlbumIsSaved() = runBlocking {
        val original = Mp3FixtureTags.build(
            3, "ME13 Title", "Artista ME13", "Album Original", "Rock", audioPayload)
        val result = save(original, MetadataField.ALBUM, "Album Nuevo")
        assertCommon(result, original, "ME13 Title", "Artista ME13", "Album Nuevo", "Rock")
    }

    @Test fun t05V24GenreIsSavedWithoutInventedNormalization() = runBlocking {
        val original = Mp3FixtureTags.build(
            4, "ME13 Title", "Artista ME13", "Album ME13", "Rock", audioPayload)
        val result = save(original, MetadataField.GENRE, "Rock Alternativo")
        assertCommon(result, original, "ME13 Title", "Artista ME13",
            "Album ME13", "Rock Alternativo")
    }

    @Test fun t06FourScalarFieldsSaveInOneTransaction() = runBlocking {
        val original = Mp3FixtureTags.build(
            4, "ME13 Title", "Artista ME13", "Album ME13", "Rock", audioPayload)
        val changes = listOf(
            MetadataChange.Replace(MetadataField.TITLE, listOf("ME13 T06 Title")),
            MetadataChange.Replace(MetadataField.ARTIST, listOf("ME13 T06 Artist")),
            MetadataChange.Replace(MetadataField.ALBUM, listOf("ME13 T06 Album")),
            MetadataChange.Replace(MetadataField.GENRE, listOf("ME13 T06 Genre")),
        )
        val result = save(original, changes)
        assertCommon(result, original, "ME13 T06 Title", "ME13 T06 Artist",
            "ME13 T06 Album", "ME13 T06 Genre")
    }

    @Test fun v23Id3v1FooterIsPreservedByteForByte() = runBlocking {
        val footer = Mp3FixtureTags.id3v1("ME13 Title", "Artista ME13", "Album ME13")
        val original = Mp3FixtureTags.build(
            3, "ME13 Title", "Artista ME13", "Album ME13", "Rock", audioPayload,
            id3v1 = true)
        val result = save(original, MetadataField.TITLE, "ME13 Footer Nuevo")
        val after = result.after
        assertArrayEquals(footer, after.copyOfRange(after.size - 128, after.size))
        assertTrue("audio must stay before the ID3v1 footer",
            after.copyOfRange(after.size - 128 - audioPayload.size, after.size - 128)
                .contentEquals(audioPayload))
        assertEquals("ME13 Footer Nuevo", result.row.title)
    }

    @Test fun v24TxxxAndPrivFramesSurviveTheRewrite() = runBlocking {
        val txxxPayload = byteArrayOf(3) + "ME13_OPAQUE".toByteArray() +
            byteArrayOf(0) + "valor opaco".toByteArray()
        val privPayload = "ME13owner".toByteArray() + byteArrayOf(0) +
            byteArrayOf(1, 2, 3, 4, 5)
        val extra = Mp3FixtureTags.rawFrame(4, "TXXX", txxxPayload) +
            Mp3FixtureTags.rawFrame(4, "PRIV", privPayload)
        val original = Mp3FixtureTags.build(
            4, "ME13 Opaque", "Artista ME13", "Album ME13", "Rock", audioPayload,
            extraFrames = extra)
        val result = save(original, MetadataField.TITLE, "ME13 Opaque Nuevo")
        assertTrue("TXXX must survive", result.after.indexOfSubsequence(extra.copyOfRange(0, 10)) >= 0)
        assertTrue("PRIV must survive", result.after.indexOfSubsequence(privPayload) >= 0)
        assertEquals("ME13 Opaque Nuevo", result.row.title)
    }

    @Test fun t07AdvancedTextAndPairsPublishExactRoomEvidence() = runBlocking {
        val original = Mp3FixtureTags.build(
            4, "ME13 T07", "Artista ME13", "Album ME13", "Rock", audioPayload)
        val changes = listOf(
            MetadataChange.Replace(MetadataField.COMPOSER, listOf("Compositora ME19")),
            MetadataChange.Replace(MetadataField.RELEASE_DATE, listOf("2025-11-25")),
            MetadataChange.Replace(MetadataField.TRACK_NUMBER, listOf("4")),
            MetadataChange.Replace(MetadataField.TRACK_TOTAL, listOf("11")),
            MetadataChange.Replace(MetadataField.DISC_NUMBER, listOf("1")),
            MetadataChange.Replace(MetadataField.DISC_TOTAL, listOf("2")),
        )
        val result = save(original, changes)
        assertEquals(listOf("Compositora ME19"),
            result.reopened.known[MetadataField.COMPOSER])
        assertEquals(listOf("2025-11-25"),
            result.reopened.known[MetadataField.RELEASE_DATE])
        assertEquals(listOf("4"), result.reopened.known[MetadataField.TRACK_NUMBER])
        assertEquals(listOf("11"), result.reopened.known[MetadataField.TRACK_TOTAL])
        assertEquals(listOf("1"), result.reopened.known[MetadataField.DISC_NUMBER])
        assertEquals(listOf("2"), result.reopened.known[MetadataField.DISC_TOTAL])
        assertEquals(2025, result.row.year)
        assertEquals(4, result.row.trackNumber)
        val evidence = database.trackDao()
            .getCurrentExtendedTagEvidence(sourceId, trackId)
            ?: throw AssertionError("extended tag evidence must be published")
        assertEquals("Compositora ME19", evidence.composer)
        assertEquals(4, evidence.trackNumber)
        assertEquals(11, evidence.trackTotal)
        assertEquals(1, evidence.discNumber)
        assertEquals(2, evidence.discTotal)
        assertEquals("2025-11-25", evidence.date)
        assertTrue(evidence.modifiedAt > 0L)
    }

    @Test fun t08MultiValueArtistAndGenrePublishStructuredEvidence() = runBlocking {
        val original = Mp3FixtureTags.build(
            4, "ME19 Multi", "Artista Original", "Album ME19", "Rock", audioPayload)
        val artists = listOf("Artista Uno", "Artista Dos", "Artista Tres")
        val genres = listOf("Rock", "Jazz")
        val result = save(original, listOf(
            MetadataChange.Replace(MetadataField.ARTIST, artists),
            MetadataChange.Replace(MetadataField.GENRE, genres),
        ))
        assertEquals(artists, result.reopened.known[MetadataField.ARTIST])
        assertEquals(genres, result.reopened.known[MetadataField.GENRE])
        assertEquals(artists.first(), result.row.artist)
        assertEquals(genres.first(), result.row.genre)
        assertArrayEquals(
            audioPayload,
            result.after.copyOfRange(
                result.after.size - audioPayload.size, result.after.size,
            ),
        )
        val people = database.metadataPersonEvidenceDao()
            .currentForTrack(sourceId, trackId)
            ?: throw AssertionError("person evidence must be published")
        assertEquals(artists, people.artists())
        val evidence = database.trackDao()
            .getCurrentExtendedTagEvidence(sourceId, trackId)
            ?: throw AssertionError("genre evidence must be published")
        assertEquals("Rock\u0000Jazz", evidence.genreValues)
        assertTrue(MetadataRecoveryJournalV1(journalRoot).inspect() is
            MetadataRecoveryJournalV1.Inspection.Clear)
    }

    @Test fun stalePreimageKeepsTheRowAndWritesActionableV2() = runBlocking {
        val original = Mp3FixtureTags.build(
            4, "ME13 Stale", "Artista ME13", "Album ME13", "Rock", audioPayload)
        val uri = insert(original)
        seedRow(original, verifiedSizeOverride = 0L)
        val before = database.trackDao().getTrackBySource(sourceId, trackId)!!
        val originalSnapshot = coldRead(uri, original.size.toLong())
        val review = MetadataEditPreview.review(originalSnapshot,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("ME13 Stale Nuevo"))))
            as MetadataReview.Ready
        val outcome = MetadataSaveService(context, database.trackDao(), database).save(
            sourceId, trackId, uri.toString(), original.size.toLong(), review,
            ownerCurrent = { true }, playbackClosed = { true },
        )
        assertTrue("outcome=$outcome", outcome is MetadataSaveService.Outcome.Saved &&
            !outcome.indexPublished)
        val row = database.trackDao().getTrackBySource(sourceId, trackId)!!
        assertEquals(before.title, row.title)
        assertEquals(before.size, row.size)
        val receipt = MetadataIndexReconciliationReceipt.list(journalRoot).single()
        assertTrue(MetadataIndexReconciliationReceipt.read(receipt)!!.actionable)
        assertEquals(MetadataRecoveryPhase.POSTCHECK_VERIFIED,
            (MetadataRecoveryJournalV1(journalRoot).inspect() as
                MetadataRecoveryJournalV1.Inspection.Pending).entry.phase)
    }

    private suspend fun save(
        original: ByteArray,
        field: MetadataField,
        value: String,
    ) = save(original, listOf(MetadataChange.Replace(field, listOf(value))))

    private suspend fun save(
        original: ByteArray,
        changes: List<MetadataChange>,
    ): SaveResult {
        val uri = insert(original)
        seedRow(original)
        val snapshot = coldRead(uri, original.size.toLong())
        val review = MetadataEditPreview.review(snapshot, changes)
        assertTrue("review=$review", review is MetadataReview.Ready)
        val outcome = MetadataSaveService(context, database.trackDao(), database).save(
            sourceId, trackId, uri.toString(), original.size.toLong(),
            review as MetadataReview.Ready,
            ownerCurrent = { true }, playbackClosed = { true },
        )
        assertTrue("outcome=$outcome", outcome is MetadataSaveService.Outcome.Saved &&
            outcome.indexPublished)
        val saved = outcome as MetadataSaveService.Outcome.Saved
        val after = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        for (change in changes) {
            if (change is MetadataChange.Replace) {
                assertEquals("candidate snapshot must describe the committed bytes",
                    change.values, saved.snapshot.known[change.field])
            }
        }
        val row = database.trackDao().getTrackBySource(sourceId, trackId)!!
        assertEquals(after.size.toLong(), row.size)
        assertEquals(0L, row.verifiedAt)
        assertEquals("", row.verifiedSha256)
        assertTrue(MetadataRecoveryJournalV1(journalRoot).inspect() is
            MetadataRecoveryJournalV1.Inspection.Clear)
        val reopened = coldRead(uri, after.size.toLong())
        return SaveResult(uri, original, after, row, reopened)
    }

    private fun assertCommon(
        result: SaveResult,
        original: ByteArray,
        title: String,
        artist: String,
        album: String,
        genre: String,
    ) {
        assertEquals(listOf(title), result.reopened.known[MetadataField.TITLE])
        assertEquals(listOf(artist), result.reopened.known[MetadataField.ARTIST])
        assertEquals(listOf(album), result.reopened.known[MetadataField.ALBUM])
        assertEquals(listOf(genre), result.reopened.known[MetadataField.GENRE])
        assertEquals(title, result.row.title)
        assertEquals(artist, result.row.artist)
        assertEquals(album, result.row.album)
        assertEquals(genre, result.row.genre)
        assertTrue("audio payload must be byte-identical",
            result.after.size > audioPayload.size &&
                result.after.copyOfRange(result.after.size - audioPayload.size,
                    result.after.size).contentEquals(audioPayload))
    }

    private fun assertSaved(result: SaveResult, field: MetadataField, value: String) {
        assertEquals(listOf(value), result.reopened.known[field])
    }

    private suspend fun insert(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "me13-fields-${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ME13Fields")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        created = uri
        context.contentResolver.openFileDescriptor(uri, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                it.write(bytes); it.flush(); it.fd.sync()
            }
        }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        trackId = "local_${uri.lastPathSegment!!.toLong()}"
        return uri
    }

    private suspend fun seedRow(bytes: ByteArray, verifiedSizeOverride: Long? = null) {
        val repository = SyncedTrackRepository(trackDao = database.trackDao())
        repository.saveLibrary(listOf(TrackDto(
            id = trackId, title = "seed", artist = "", album = "",
            duration = 100, size = bytes.size.toLong(), format = "mp3", genre = "",
        )), sourceId)
        repository.markDownloadedVerified(
            sourceId = sourceId, id = trackId, filepath = created.toString(),
            size = verifiedSizeOverride ?: bytes.size.toLong(), sha256 = sha(bytes),
            verifiedAt = 1_700_000_000_000, algorithm = "sha256")
    }

    private suspend fun coldRead(uri: Uri, size: Long) =
        when (val read = ExtendedTrackMetadataRepository(database.trackDao(), context)
            .loadEditorColdRead("", trackId, uri.toString(), size)) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("cold read failed: $read")
        }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        for (i in 0..size - needle.size) {
            if (copyOfRange(i, i + needle.size).contentEquals(needle)) return i
        }
        return -1
    }

    private data class SaveResult(
        val uri: Uri,
        val original: ByteArray,
        val after: ByteArray,
        val row: CachedTrack,
        val reopened: org.michimusic.core.metadata.MetadataSnapshot,
    )
}
