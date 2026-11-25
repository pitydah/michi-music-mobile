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
import org.michimusic.core.metadata.MetadataArtworkReplacementPreview
import org.michimusic.core.metadata.MetadataDescribedTextWorkbench
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataId3DescribedTagCodec
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSelectiveAssetStage
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

    @Test fun replaceExistingApicCommitsExactResourceAndPublishesRoom() = runBlocking {
        val resolver = context.contentResolver
        val beforeImage = png(1)
        val afterImage = png(2)
        val before = id3v24WithApic(
            title = "Artwork Original",
            artist = "Synthetic Artist",
            album = "Synthetic Album",
            genre = "Rock",
            image = beforeImage,
            audio = audioPayload,
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "me-art-${UUID.randomUUID()}.mp3")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/MichiMEFinalE2E/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("Could not create disposable artwork MediaStore item", uri)
        created = uri
        resolver.openFileDescriptor(uri!!, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                output.write(before)
                output.flush()
                output.fd.sync()
            }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        val artworkTrackId = "local_${uri.lastPathSegment!!.substringAfterLast('/')}"
        val observedSize = resolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
        SyncedTrackRepository(database.trackDao()).saveLibrary(
            listOf(
                TrackDto(
                    id = artworkTrackId,
                    title = "Artwork Original",
                    artist = "Synthetic Artist",
                    album = "Synthetic Album",
                    size = observedSize,
                    format = "mp3",
                    genre = "Rock",
                ),
            ),
            sourceId,
        )
        database.trackDao().markDownloadedVerified(
            sourceId, artworkTrackId, uri.toString(), observedSize, sha(before),
            1_700_000_000_000L, "sha256",
        )
        val originalRevision = database.trackDao()
            .getTrackBySource(sourceId, artworkTrackId)!!.metadataRevision
        val reader = ExtendedTrackMetadataRepository(database.trackDao(), context)
        val original = when (
            val read = reader.loadEditorColdRead(
                readSourceId, artworkTrackId, uri.toString(), observedSize,
            )
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("The production cold reader rejected artwork fixture: $read")
        }
        val privateSource = File(context.cacheDir, "me-art-${UUID.randomUUID()}.mp3")
        val observedResources = try {
            privateSource.writeBytes(before)
            MetadataPrivateBundleStage.observeFile(privateSource)
        } finally {
            privateSource.delete()
        }
        val proposal = (
            MetadataArtworkReplacementPreview.review(
                observedResources, 0, afterImage, "image/png",
            ) as MetadataArtworkReplacementPreview.Result.Ready
            ).proposal
        val outcome = MetadataSaveService(context, database.trackDao(), database).saveBundle(
            sourceId = sourceId,
            trackId = artworkTrackId,
            uriString = uri.toString(),
            expectedBytes = observedSize,
            targetInput = original.target,
            reviewInput = null,
            resourceEdits = listOf(MetadataSelectiveAssetStage.Edit.Picture(proposal)),
            ownerCurrent = { true },
            playbackClosed = { true },
        )

        assertTrue(
            "Exact APIC replacement must commit and publish, got $outcome",
            outcome is MetadataSaveService.Outcome.Saved && outcome.indexPublished,
        )
        val after = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val physical = File(context.cacheDir, "me-art-after-${UUID.randomUUID()}.mp3")
        val resources = try {
            physical.writeBytes(after)
            MetadataPrivateBundleStage.observeFile(physical)
        } finally {
            physical.delete()
        }
        assertArrayEquals(afterImage, resources.artworks.single().image)
        assertArrayEquals(audioPayload, after.copyOfRange(after.size - audioPayload.size, after.size))
        val row = database.trackDao().getTrackBySource(sourceId, artworkTrackId)!!
        assertEquals("Artwork Original", row.title)
        assertEquals(after.size.toLong(), row.size)
        assertTrue(row.metadataRevision > originalRevision)
        database.openHelper.writableDatabase.query(
            "SELECT originalRevision,committedRevision,candidateSha256,candidateBytes " +
                "FROM metadata_applied_transaction WHERE sourceId=? AND trackId=?",
            arrayOf(sourceId, artworkTrackId),
        ).use { cursor ->
            assertTrue("Exact applied transaction marker is missing", cursor.moveToFirst())
            assertEquals(originalRevision, cursor.getLong(0))
            assertEquals(row.metadataRevision, cursor.getLong(1))
            assertEquals(sha(after), cursor.getString(2))
            assertEquals(after.size.toLong(), cursor.getLong(3))
            assertTrue("There must be exactly one marker", !cursor.moveToNext())
        }
        assertTrue(
            MetadataRecoveryJournalV1(
                File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
            ).inspect() is MetadataRecoveryJournalV1.Inspection.Clear,
        )
    }

    @Test fun replaceExistingFlacPictureCommitsExactResourceAndPublishesRoom() = runBlocking {
        val resolver = context.contentResolver
        val beforeImage = png(3)
        val afterImage = png(4)
        val flacAudio = ByteArray(4096) { ((it * 61) % 251).toByte() }
        val before = flacWithPicture(
            comments = listOf(
                "TITLE=FLAC Artwork",
                "ARTIST=Synthetic FLAC Artist",
                "ALBUM=Synthetic FLAC Album",
                "GENRE=Rock",
            ),
            image = beforeImage,
            audio = flacAudio,
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "me-flac-art-${UUID.randomUUID()}.flac")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/flac")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/MichiMEFinalE2E/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("Could not create disposable FLAC artwork item", uri)
        created = uri
        resolver.openFileDescriptor(uri!!, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                output.write(before)
                output.flush()
                output.fd.sync()
            }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        val pictureTrackId = "local_${uri.lastPathSegment!!.substringAfterLast('/')}"
        val observedSize = resolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
        SyncedTrackRepository(database.trackDao()).saveLibrary(
            listOf(
                TrackDto(
                    id = pictureTrackId,
                    title = "FLAC Artwork",
                    artist = "Synthetic FLAC Artist",
                    album = "Synthetic FLAC Album",
                    size = observedSize,
                    format = "flac",
                    genre = "Rock",
                ),
            ),
            sourceId,
        )
        database.trackDao().markDownloadedVerified(
            sourceId, pictureTrackId, uri.toString(), observedSize, sha(before),
            1_700_000_000_000L, "sha256",
        )
        val originalRevision = database.trackDao()
            .getTrackBySource(sourceId, pictureTrackId)!!.metadataRevision
        val reader = ExtendedTrackMetadataRepository(database.trackDao(), context)
        val original = when (
            val read = reader.loadEditorColdRead(
                readSourceId, pictureTrackId, uri.toString(), observedSize,
            )
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("The production cold reader rejected FLAC artwork: $read")
        }
        val privateSource = File(context.cacheDir, "me-flac-art-${UUID.randomUUID()}.flac")
        val observedResources = try {
            privateSource.writeBytes(before)
            MetadataPrivateBundleStage.observeFile(privateSource)
        } finally {
            privateSource.delete()
        }
        val proposal = (
            MetadataArtworkReplacementPreview.review(
                observedResources, 0, afterImage, "image/png",
            ) as MetadataArtworkReplacementPreview.Result.Ready
            ).proposal
        val outcome = MetadataSaveService(context, database.trackDao(), database).saveBundle(
            sourceId = sourceId,
            trackId = pictureTrackId,
            uriString = uri.toString(),
            expectedBytes = observedSize,
            targetInput = original.target,
            reviewInput = null,
            resourceEdits = listOf(MetadataSelectiveAssetStage.Edit.Picture(proposal)),
            ownerCurrent = { true },
            playbackClosed = { true },
        )

        assertTrue(
            "Exact FLAC PICTURE replacement must commit and publish, got $outcome",
            outcome is MetadataSaveService.Outcome.Saved && outcome.indexPublished,
        )
        val after = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val physical = File(context.cacheDir, "me-flac-art-after-${UUID.randomUUID()}.flac")
        val resources = try {
            physical.writeBytes(after)
            MetadataPrivateBundleStage.observeFile(physical)
        } finally {
            physical.delete()
        }
        assertArrayEquals(afterImage, resources.artworks.single().image)
        assertArrayEquals(flacAudio, after.copyOfRange(after.size - flacAudio.size, after.size))
        val row = database.trackDao().getTrackBySource(sourceId, pictureTrackId)!!
        assertEquals("FLAC Artwork", row.title)
        assertEquals(after.size.toLong(), row.size)
        assertTrue(row.metadataRevision > originalRevision)
        database.openHelper.writableDatabase.query(
            "SELECT originalRevision,committedRevision,candidateSha256,candidateBytes " +
                "FROM metadata_applied_transaction WHERE sourceId=? AND trackId=?",
            arrayOf(sourceId, pictureTrackId),
        ).use { cursor ->
            assertTrue("Exact FLAC applied marker is missing", cursor.moveToFirst())
            assertEquals(originalRevision, cursor.getLong(0))
            assertEquals(row.metadataRevision, cursor.getLong(1))
            assertEquals(sha(after), cursor.getString(2))
            assertEquals(after.size.toLong(), cursor.getLong(3))
            assertTrue("There must be exactly one FLAC marker", !cursor.moveToNext())
        }
        assertTrue(
            MetadataRecoveryJournalV1(
                File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
            ).inspect() is MetadataRecoveryJournalV1.Inspection.Clear,
        )
    }

    @Test fun replaceCommentAndClearLyricsCommitExactResourcesAndPublishRoom() = runBlocking {
        val resolver = context.contentResolver
        val commentIdentity = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.COMMENT, "spa", "principal",
        )
        val lyricsIdentity = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.LYRICS, "spa", "letra",
        )
        val before = id3v24WithDescribedText(
            title = "Texto descrito",
            artist = "Synthetic Artist",
            album = "Synthetic Album",
            genre = "Rock",
            commentLanguage = commentIdentity.language,
            commentDescription = commentIdentity.description,
            comment = "Comentario original",
            lyricsLanguage = lyricsIdentity.language,
            lyricsDescription = lyricsIdentity.description,
            lyrics = "Letra que se eliminará",
            audio = audioPayload,
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "me-text-${UUID.randomUUID()}.mp3")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/MichiMEFinalE2E/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("Could not create disposable described-text item", uri)
        created = uri
        resolver.openFileDescriptor(uri!!, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                output.write(before)
                output.flush()
                output.fd.sync()
            }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        val textTrackId = "local_${uri.lastPathSegment!!.substringAfterLast('/')}"
        val observedSize = resolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
        SyncedTrackRepository(database.trackDao()).saveLibrary(
            listOf(
                TrackDto(
                    id = textTrackId,
                    title = "Texto descrito",
                    artist = "Synthetic Artist",
                    album = "Synthetic Album",
                    size = observedSize,
                    format = "mp3",
                    genre = "Rock",
                ),
            ),
            sourceId,
        )
        database.trackDao().markDownloadedVerified(
            sourceId, textTrackId, uri.toString(), observedSize, sha(before),
            1_700_000_000_000L, "sha256",
        )
        val originalRevision = database.trackDao()
            .getTrackBySource(sourceId, textTrackId)!!.metadataRevision
        val reader = ExtendedTrackMetadataRepository(database.trackDao(), context)
        val original = when (
            val read = reader.loadEditorColdRead(
                readSourceId, textTrackId, uri.toString(), observedSize,
            )
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("The production cold reader rejected described text: $read")
        }
        val outcome = MetadataSaveService(context, database.trackDao(), database).saveBundle(
            sourceId = sourceId,
            trackId = textTrackId,
            uriString = uri.toString(),
            expectedBytes = observedSize,
            targetInput = original.target,
            reviewInput = null,
            resourceEdits = listOf(
                MetadataSelectiveAssetStage.Edit.DescribedText(
                    MetadataDescribedTextWorkbench.Change.Replace(
                        commentIdentity, "Comentario corregido",
                    ),
                ),
                MetadataSelectiveAssetStage.Edit.DescribedText(
                    MetadataDescribedTextWorkbench.Change.Clear(lyricsIdentity),
                ),
            ),
            ownerCurrent = { true },
            playbackClosed = { true },
        )

        assertTrue(
            "Exact COMM replacement and USLT clear must commit and publish, got $outcome",
            outcome is MetadataSaveService.Outcome.Saved && outcome.indexPublished,
        )
        val after = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val physical = File(context.cacheDir, "me-text-after-${UUID.randomUUID()}.mp3")
        val resources = try {
            physical.writeBytes(after)
            MetadataPrivateBundleStage.observeFile(physical)
        } finally {
            physical.delete()
        }
        assertEquals(
            listOf(
                MetadataDescribedTextWorkbench.Item(
                    commentIdentity, "Comentario corregido",
                ),
            ),
            resources.texts,
        )
        assertArrayEquals(audioPayload, after.copyOfRange(after.size - audioPayload.size, after.size))
        val row = database.trackDao().getTrackBySource(sourceId, textTrackId)!!
        assertEquals("Texto descrito", row.title)
        assertEquals(after.size.toLong(), row.size)
        assertTrue(row.metadataRevision > originalRevision)
        database.openHelper.writableDatabase.query(
            "SELECT originalRevision,committedRevision,candidateSha256,candidateBytes " +
                "FROM metadata_applied_transaction WHERE sourceId=? AND trackId=?",
            arrayOf(sourceId, textTrackId),
        ).use { cursor ->
            assertTrue("Exact described-text marker is missing", cursor.moveToFirst())
            assertEquals(originalRevision, cursor.getLong(0))
            assertEquals(row.metadataRevision, cursor.getLong(1))
            assertEquals(sha(after), cursor.getString(2))
            assertEquals(after.size.toLong(), cursor.getLong(3))
            assertTrue("There must be exactly one described-text marker", !cursor.moveToNext())
        }
        assertTrue(
            MetadataRecoveryJournalV1(
                File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
            ).inspect() is MetadataRecoveryJournalV1.Inspection.Clear,
        )
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun png(mark: Int): ByteArray {
        fun be(value: Int) = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte(),
        )
        return byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10) +
            be(13) + "IHDR".toByteArray() + be(16) + be(20) +
            byteArrayOf(8, 2, 0, 0, 0) + ByteArray(4) { mark.toByte() } +
            be(0) + "IEND".toByteArray() + ByteArray(4) { mark.toByte() }
    }

    private fun id3v24WithApic(
        title: String,
        artist: String,
        album: String,
        genre: String,
        image: ByteArray,
        audio: ByteArray,
    ): ByteArray {
        fun sync(n: Int) = byteArrayOf(
            ((n ushr 21) and 127).toByte(), ((n ushr 14) and 127).toByte(),
            ((n ushr 7) and 127).toByte(), (n and 127).toByte(),
        )
        fun frame(id: String, payload: ByteArray) =
            id.toByteArray(Charsets.US_ASCII) + sync(payload.size) +
                byteArrayOf(0, 0) + payload
        fun text(id: String, value: String) =
            frame(id, byteArrayOf(3) + value.toByteArray(Charsets.UTF_8))
        val apic = byteArrayOf(0) + "image/png".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 3, 0) + image
        val body = text("TIT2", title) + text("TPE1", artist) +
            text("TALB", album) + text("TCON", genre) + frame("APIC", apic)
        return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0) +
            sync(body.size) + body + audio
    }

    private fun id3v24WithDescribedText(
        title: String,
        artist: String,
        album: String,
        genre: String,
        commentLanguage: String,
        commentDescription: String,
        comment: String,
        lyricsLanguage: String,
        lyricsDescription: String,
        lyrics: String,
        audio: ByteArray,
    ): ByteArray {
        fun sync(n: Int) = byteArrayOf(
            ((n ushr 21) and 127).toByte(), ((n ushr 14) and 127).toByte(),
            ((n ushr 7) and 127).toByte(), (n and 127).toByte(),
        )
        fun frame(id: String, payload: ByteArray) =
            id.toByteArray(Charsets.US_ASCII) + sync(payload.size) +
                byteArrayOf(0, 0) + payload
        fun text(id: String, value: String) =
            frame(id, byteArrayOf(3) + value.toByteArray(Charsets.UTF_8))
        fun described(language: String, description: String, value: String) =
            byteArrayOf(3) + language.toByteArray(Charsets.US_ASCII) +
                description.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
                value.toByteArray(Charsets.UTF_8)
        val body = text("TIT2", title) + text("TPE1", artist) +
            text("TALB", album) + text("TCON", genre) +
            frame("COMM", described(commentLanguage, commentDescription, comment)) +
            frame("USLT", described(lyricsLanguage, lyricsDescription, lyrics))
        return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0) +
            sync(body.size) + body + audio
    }

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

    private fun flacWithPicture(
        comments: List<String>,
        image: ByteArray,
        audio: ByteArray,
    ): ByteArray {
        fun be(value: Int) = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte(),
        )
        fun block(kind: Int, last: Boolean, payload: ByteArray) =
            byteArrayOf(
                (kind or if (last) 0x80 else 0).toByte(),
                (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            ) + payload
        val base = flac(comments, ByteArray(0))
        val commentEnd = base.size
        val commentHeader = base[42].toInt() and 0x7f
        check(commentHeader == 4)
        base[42] = commentHeader.toByte()
        val mime = "image/png".toByteArray(Charsets.US_ASCII)
        val description = "Front cover".toByteArray(Charsets.UTF_8)
        val picture = be(3) + be(mime.size) + mime +
            be(description.size) + description +
            be(16) + be(20) + be(24) + be(0) + be(image.size) + image
        return base.copyOf(commentEnd) + block(6, true, picture) + audio
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
