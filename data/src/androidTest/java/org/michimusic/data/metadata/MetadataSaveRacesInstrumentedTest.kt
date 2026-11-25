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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorColdRead
import org.michimusic.data.repository.SyncedTrackRepository

/** ME-13 Block D: source races, live playback denial and duplicate saves. */
@RunWith(AndroidJUnit4::class)
class MetadataSaveRacesInstrumentedTest {
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

    @Test fun t12SourceChangedAtTheMutationBoundaryNeverReachesTransport() = runBlocking {
        val fixture = prepare()
        val changed = fixture.original + byteArrayOf(9, 9, 9)
        val hook = MetadataSaveCheckpointHook { point ->
            if (point == MetadataSaveCheckpoint.BEFORE_MUTATING) writeSource(fixture.uri, changed)
        }
        val outcome = service(hook).save(
            sourceId, trackId, fixture.uri.toString(), fixture.original.size.toLong(),
            fixture.review, ownerCurrent = { true }, playbackClosed = { true },
        )
        assertTrue("outcome=$outcome", outcome is MetadataSaveService.Outcome.Blocked)
        assertEquals(sha(changed), sha(readSource(fixture.uri)))
        assertTrue(MetadataRecoveryJournalV1(journalRoot).inspect() is
            MetadataRecoveryJournalV1.Inspection.Clear)
        assertEquals(fixture.originalTitle,
            database.trackDao().getTrackBySource(sourceId, trackId)!!.title)
    }

    @Test fun t13PlaybackOpenRefusesBeforeTheJournalEntersMutating() = runBlocking {
        val fixture = prepare()
        val outcome = service().save(
            sourceId, trackId, fixture.uri.toString(), fixture.original.size.toLong(),
            fixture.review, ownerCurrent = { true }, playbackClosed = { false },
        )
        assertTrue("outcome=$outcome", outcome is MetadataSaveService.Outcome.Blocked)
        assertTrue(MetadataRecoveryJournalV1(journalRoot).inspect() is
            MetadataRecoveryJournalV1.Inspection.Clear)
        assertEquals(sha(fixture.original), sha(readSource(fixture.uri)))
    }

    @Test fun t13PlaybackOpeningAfterTheDurableMutationGateRefusesTransport() = runBlocking {
        val fixture = prepare()
        var closed = true
        val hook = MetadataSaveCheckpointHook { point ->
            if (point == MetadataSaveCheckpoint.AFTER_MUTATING_DURABLE) closed = false
        }
        val outcome = service(hook).save(
            sourceId, trackId, fixture.uri.toString(), fixture.original.size.toLong(),
            fixture.review, ownerCurrent = { true }, playbackClosed = { closed },
        )
        assertEquals(MetadataSaveService.Outcome.RecoveryRequired(
            "OWNER_OR_PLAYBACK_CHANGED"), outcome)
        val pending = MetadataRecoveryJournalV1(journalRoot).inspect() as
            MetadataRecoveryJournalV1.Inspection.Pending
        assertEquals(MetadataRecoveryPhase.MUTATING, pending.entry.phase)
        assertEquals(sha(fixture.original), sha(readSource(fixture.uri)))
    }

    @Test fun t15SecondServiceCannotEnterTheSameSourceMutation() = runBlocking {
        val fixture = prepare()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hook = MetadataSaveCheckpointHook { point ->
            if (point == MetadataSaveCheckpoint.BEFORE_MUTATING) {
                entered.complete(Unit)
                release.await()
            }
        }
        val first = async(Dispatchers.IO) {
            service(hook).save(
                sourceId, trackId, fixture.uri.toString(), fixture.original.size.toLong(),
                fixture.review, ownerCurrent = { true }, playbackClosed = { true },
            )
        }
        entered.await()
        val second = service().save(
            sourceId, trackId, fixture.uri.toString(), fixture.original.size.toLong(),
            fixture.review, ownerCurrent = { true }, playbackClosed = { true },
        )
        assertEquals(MetadataSaveService.Outcome.RecoveryRequired("IN_PROGRESS"), second)
        release.complete(Unit)
        val firstOutcome = first.await()
        assertTrue("first=$firstOutcome", firstOutcome is MetadataSaveService.Outcome.Saved &&
            firstOutcome.indexPublished)
        assertEquals("ME13 Race Nuevo",
            database.trackDao().getTrackBySource(sourceId, trackId)!!.title)
        assertTrue(MetadataRecoveryJournalV1(journalRoot).inspect() is
            MetadataRecoveryJournalV1.Inspection.Clear)
    }

    @Test fun freshSaveCrossesEveryDestructiveCheckpointExactlyOnce() = runBlocking {
        val fixture = prepare()
        val reached = mutableListOf<MetadataSaveCheckpoint>()
        val outcome = service(MetadataSaveCheckpointHook { reached += it }).save(
            sourceId, trackId, fixture.uri.toString(), fixture.original.size.toLong(),
            fixture.review, ownerCurrent = { true }, playbackClosed = { true },
        )

        assertTrue("outcome=$outcome", outcome is MetadataSaveService.Outcome.Saved)
        assertEquals(
            listOf(
                MetadataSaveCheckpoint.AFTER_BACKUP_DURABLE,
                MetadataSaveCheckpoint.AFTER_STAGE_READY,
                MetadataSaveCheckpoint.BEFORE_MUTATING,
                MetadataSaveCheckpoint.AFTER_MUTATING_DURABLE,
                MetadataSaveCheckpoint.AFTER_PROVIDER_CLOSE,
                MetadataSaveCheckpoint.BEFORE_INDEX_CAS,
                MetadataSaveCheckpoint.AFTER_INDEX_CAS,
                MetadataSaveCheckpoint.BEFORE_TERMINAL_CLEANUP,
            ),
            reached,
        )
    }

    private fun service(
        hook: MetadataSaveCheckpointHook = NoMetadataSaveCheckpoint,
    ) = MetadataSaveService(context, database.trackDao(), database, hook)

    private data class Fixture(
        val uri: Uri,
        val original: ByteArray,
        val review: MetadataReview.Ready,
        val originalTitle: String,
    )

    private suspend fun prepare(): Fixture {
        val original = Mp3FixtureTags.build(
            4, "ME13 Race Original", "Artista ME13", "Album ME13", "Rock", audioPayload)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "me13-race-${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ME13Races")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        created = uri
        writeSource(uri, original)
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        trackId = "local_${uri.lastPathSegment!!.toLong()}"
        val repository = SyncedTrackRepository(trackDao = database.trackDao())
        repository.saveLibrary(listOf(TrackDto(
            id = trackId, title = "ME13 Race Original", artist = "Artista ME13",
            album = "Album ME13", duration = 100, size = original.size.toLong(),
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
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("ME13 Race Nuevo"))))
            as MetadataReview.Ready
        return Fixture(uri, original, review, "ME13 Race Original")
    }

    private fun writeSource(uri: Uri, bytes: ByteArray) {
        context.contentResolver.openFileDescriptor(uri, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                it.write(bytes); it.flush(); it.fd.sync()
            }
        }
    }

    private fun readSource(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
