package org.michimusic.data.metadata

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataArtworkAdditionPreview
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataArtworkReplacementPreview
import org.michimusic.core.metadata.MetadataDescribedTextWorkbench
import org.michimusic.core.metadata.MetadataId3DescribedTagCodec
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataResourceOperation
import org.michimusic.core.metadata.MetadataSelectiveAssetStage
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorColdRead
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.data.repository.customRootSourceId
import org.michimusic.data.repository.treeTrackId

/**
 * Gated evidence using Android's real ExternalStorageProvider and a persisted
 * DocumentsUI grant. The synthetic test DocumentsProvider is never involved.
 */
@RunWith(AndroidJUnit4::class)
class MetadataSaveSafDocumentsUiE2EInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: MichiDatabase
    private var treeUri: Uri? = null
    private var documentUri: Uri? = null
    private var folderSeedUri: Uri? = null

    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        folderSeedUri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ".michi-saf-seed")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Download/MichiMetadataSafE2E/",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )
        checkNotNull(folderSeedUri) { "SAF_TEST_FOLDER_SEED_FAILED" }
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        folderSeedUri?.let { runCatching {
            context.contentResolver.delete(it, null, null)
        } }
        documentUri?.let { runCatching { DocumentsContract.deleteDocument(
            context.contentResolver, it,
        ) } }
        treeUri?.let { tree ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    @Test fun persistedSystemDocumentGrantSavesAndRestoresExactPreimage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Explicitly opt in because this legacy fixture drives system DocumentsUI",
            InstrumentationRegistry.getArguments().getString("ME_SAF_E2E") ==
                "I_ACCEPT_DOCUMENTS_UI",
        )
        val tree = grantPersistedTree()

        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val uri = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            "audio/mpeg",
            "michi-saf-${UUID.randomUUID()}.mp3",
        )
        assertNotNull("ExternalStorageProvider refused the disposable document", uri)
        documentUri = uri

        val audio = instrumentation.context.assets.open("me13-silence.mp3")
            .use { it.readBytes() }
        val originalBytes = id3(
            title = "SAF Original",
            artist = "SAF Artist",
            album = "SAF Album",
            genre = "Rock",
            audio = audio,
        )
        context.contentResolver.openFileDescriptor(uri!!, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                output.write(originalBytes)
                output.flush()
                output.fd.sync()
            }
        }

        val documentId = DocumentsContract.getDocumentId(uri)
        val sourceId = customRootSourceId(tree)
        val trackId = treeTrackId(tree, documentId)
        val bytes = context.contentResolver.openFileDescriptor(uri, "r")!!.use {
            it.statSize
        }
        val originalSha = sha(originalBytes)
        SyncedTrackRepository(database.trackDao()).saveLibrary(
            listOf(
                TrackDto(
                    id = trackId,
                    title = "SAF Original",
                    artist = "SAF Artist",
                    album = "SAF Album",
                    size = bytes,
                    format = "mp3",
                    genre = "Rock",
                ),
            ),
            sourceId,
        )
        database.trackDao().markDownloadedVerified(
            sourceId = sourceId,
            id = trackId,
            filepath = uri.toString(),
            size = bytes,
            sha256 = originalSha,
            verifiedAt = 1_700_000_000_000L,
            algorithm = "sha256",
        )
        val beforeRevision = database.trackDao()
            .getTrackBySource(sourceId, trackId)!!.metadataRevision
        val reader = ExtendedTrackMetadataRepository(database.trackDao(), context)
        val original = when (
            val read = reader.loadEditorColdRead(
                sourceId, trackId, uri.toString(), bytes,
            )
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("Production cold read rejected real SAF document: $read")
        }
        val review = MetadataEditPreview.review(
            original,
            listOf(
                MetadataChange.Replace(
                    MetadataField.TITLE, listOf("SAF Renombrada"),
                ),
                MetadataChange.Replace(
                    MetadataField.COMPOSER, listOf("SAF Compositora"),
                ),
                MetadataChange.Replace(
                    MetadataField.RELEASE_DATE, listOf("2025"),
                ),
                MetadataChange.Replace(
                    MetadataField.TRACK_NUMBER, listOf("2"),
                ),
                MetadataChange.Replace(
                    MetadataField.TRACK_TOTAL, listOf("5"),
                ),
            ),
        )
        assertTrue(review is MetadataReview.Ready)

        val outcome = MetadataSaveService(
            context, database.trackDao(), database,
        ).save(
            sourceId = sourceId,
            trackId = trackId,
            uriString = uri.toString(),
            expectedBytes = bytes,
            reviewInput = review as MetadataReview.Ready,
            ownerCurrent = { true },
            playbackClosed = { true },
        )

        assertTrue("Real SAF save failed: $outcome", outcome is MetadataSaveService.Outcome.Saved)
        val saved = outcome as MetadataSaveService.Outcome.Saved
        assertTrue(saved.indexPublished)
        val committed = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(saved.committedSha256, sha(committed))
        assertEquals(saved.committedBytes, committed.size.toLong())
        assertArrayEquals(audio, committed.copyOfRange(committed.size - audio.size, committed.size))

        val row = database.trackDao().getTrackBySource(sourceId, trackId)!!
        assertEquals("SAF Renombrada", row.title)
        assertEquals(2025, row.year)
        assertEquals(2, row.trackNumber)
        assertEquals(committed.size.toLong(), row.size)
        assertTrue(row.metadataRevision > beforeRevision)
        val change = checkNotNull(saved.libraryChange)
        assertEquals(sourceId, change.sourceId)
        assertEquals(trackId, change.trackId)
        assertEquals(uri.toString(), change.uri)
        assertEquals(setOf(MetadataField.TITLE, MetadataField.COMPOSER,
            MetadataField.RELEASE_DATE, MetadataField.TRACK_NUMBER,
            MetadataField.TRACK_TOTAL), change.changedFields)
        assertEquals(saved.committedSha256, change.committedSha256)

        val marker = database.trackDao()
            .getAppliedMetadataTransaction(change.transactionId)
        assertNotNull(marker)
        assertEquals(beforeRevision, marker!!.originalRevision)
        assertEquals(row.metadataRevision, marker.committedRevision)
        assertEquals(saved.committedSha256, marker.candidateSha256)
        assertEquals(saved.committedBytes, marker.candidateBytes)

        val reopened = when (
            val read = reader.loadEditorColdRead(
                sourceId, trackId, uri.toString(), committed.size.toLong(),
            )
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("Production cold reopen rejected saved SAF document: $read")
        }
        assertEquals(listOf("SAF Renombrada"), reopened.known[MetadataField.TITLE])
        assertEquals(listOf("SAF Compositora"),
            reopened.known[MetadataField.COMPOSER])
        assertEquals(listOf("2025"), reopened.known[MetadataField.RELEASE_DATE])
        assertEquals(listOf("2"), reopened.known[MetadataField.TRACK_NUMBER])
        assertEquals(listOf("5"), reopened.known[MetadataField.TRACK_TOTAL])
        val evidence = database.trackDao()
            .getCurrentExtendedTagEvidence(sourceId, trackId)
            ?: throw AssertionError("SAF extended evidence must be published")
        assertEquals("SAF Compositora", evidence.composer)
        assertEquals(2, evidence.trackNumber)
        assertEquals(5, evidence.trackTotal)
        assertEquals("2025", evidence.date)
        assertTrue(
            MetadataRecoveryJournalV1(
                File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
            ).inspect() is MetadataRecoveryJournalV1.Inspection.Clear,
        )

        val secondReview = MetadataEditPreview.review(
            reopened,
            listOf(MetadataChange.Replace(
                MetadataField.TITLE, listOf("SAF Temporal"),
            )),
        )
        assertTrue(secondReview is MetadataReview.Ready)
        val interrupted = MetadataSaveService(
            context,
            database.trackDao(),
            database,
            MetadataSaveCheckpointHook { point ->
                if (point == MetadataSaveCheckpoint.AFTER_PROVIDER_CLOSE) {
                    throw IllegalStateException("SAF_RESTORE_E2E_INTERRUPT")
                }
            },
        ).save(
            sourceId = sourceId,
            trackId = trackId,
            uriString = uri.toString(),
            expectedBytes = committed.size.toLong(),
            reviewInput = secondReview as MetadataReview.Ready,
            ownerCurrent = { true },
            playbackClosed = { true },
        )
        assertTrue(
            "The injected post-provider interruption must retain recovery: $interrupted",
            interrupted is MetadataSaveService.Outcome.Failed &&
                interrupted.needsRecovery,
        )
        val journal = MetadataRecoveryJournalV1(
            File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
        )
        val pending = journal.inspect() as MetadataRecoveryJournalV1.Inspection.Pending
        assertEquals(MetadataRecoveryPhase.MUTATION_UNKNOWN, pending.entry.phase)

        context.contentResolver.openFileDescriptor(uri, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                output.write(byteArrayOf(1, 2, 3, 4, 5))
                output.flush()
                output.fd.sync()
            }
        }
        val restored = MetadataRecoveryConsole(context, database).restoreOriginal(
            sourceId = sourceId,
            trackId = trackId,
            uri = uri.toString(),
            explicitlyConfirmed = true,
            ownerStillCurrent = { true },
            playbackClosed = { true },
        )
        assertEquals(
            MetadataRecoveryConsole.RestoreResult.RestoredVerified,
            restored,
        )
        val restoredBytes = context.contentResolver.openInputStream(uri)!!
            .use { it.readBytes() }
        assertArrayEquals(committed, restoredBytes)
        assertEquals(saved.committedSha256, sha(restoredBytes))
        assertTrue(journal.inspect() is MetadataRecoveryJournalV1.Inspection.Clear)

        val extraDocuments = mutableListOf<Uri>()
        try {
            extraDocuments += saveSafAsset(
                parent = parent,
                assetName = "me19-sine.flac",
                mime = "audio/flac",
                displayName = "michi-saf-${UUID.randomUUID()}.flac",
                format = "flac",
                seedTitle = "ME19 FLAC Original",
                seedArtist = "Artista FLAC",
                seedAlbum = "Album FLAC",
                seedGenre = "Rock",
                changes = listOf(
                    MetadataChange.Replace(
                        MetadataField.COMPOSER, listOf("SAF FLAC Compositora")),
                    MetadataChange.Replace(
                        MetadataField.RELEASE_DATE, listOf("2025-10-01")),
                    MetadataChange.Replace(
                        MetadataField.TRACK_NUMBER, listOf("3")),
                    MetadataChange.Replace(
                        MetadataField.TRACK_TOTAL, listOf("9")),
                    MetadataChange.Replace(
                        MetadataField.DISC_NUMBER, listOf("1")),
                    MetadataChange.Replace(
                        MetadataField.DISC_TOTAL, listOf("2")),
                ),
            )
            extraDocuments += saveSafAsset(
                parent = parent,
                assetName = "me14-audio-only.m4a",
                mime = "audio/mp4",
                displayName = "michi-saf-${UUID.randomUUID()}.m4a",
                format = "m4a",
                seedTitle = "ME14 M4A",
                seedArtist = "Artista M4A",
                seedAlbum = "Album M4A",
                seedGenre = "Rock",
                changes = listOf(
                    MetadataChange.Replace(
                        MetadataField.TITLE, listOf("SAF M4A Nueva")),
                    MetadataChange.Replace(
                        MetadataField.COMPOSER, listOf("SAF M4A Compositor")),
                    MetadataChange.Replace(
                        MetadataField.RELEASE_DATE, listOf("2025-10-02")),
                    MetadataChange.Replace(
                        MetadataField.TRACK_NUMBER, listOf("2")),
                    MetadataChange.Replace(
                        MetadataField.TRACK_TOTAL, listOf("7")),
                    MetadataChange.Replace(
                        MetadataField.DISC_NUMBER, listOf("1")),
                    MetadataChange.Replace(
                        MetadataField.DISC_TOTAL, listOf("3")),
                ),
            )
        } finally {
            extraDocuments.forEach {
                runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, it)
                }
            }
        }
    }

    /**
     * Every format gate below drives the real system `ExternalStorageProvider`
     * through DocumentsUI, persists the exact tree grant, and mutates a
     * disposable document only. Resource operations are authorized by the
     * versioned SAF profiles; no MediaStore descriptor and no synthetic
     * DocumentsProvider is involved.
     */
    @Test fun mp3ResourcesPersistedGrantRoundTrip(): Unit = runBlocking {
        val audio = InstrumentationRegistry.getInstrumentation().context.assets
            .open("me13-silence.mp3").use { it.readBytes() }
        resourceRoundTrip(
            FormatSpec(
                mime = "audio/mpeg",
                displayName = "michi-saf-${UUID.randomUUID()}.mp3",
                formatLabel = "mp3",
                extension = "mp3",
                seedTitle = "SAF MP3 Original",
                seedArtist = "SAF Artista MP3",
                seedAlbum = "SAF Album MP3",
                seedGenre = "Rock",
                seed = {
                    id3(
                        title = "SAF MP3 Original",
                        artist = "SAF Artista MP3",
                        album = "SAF Album MP3",
                        genre = "Rock",
                        audio = audio,
                    )
                },
                audioTail = audio,
                artworkAdd = MetadataResourceOperation.ID3_APIC_ADD,
                artworkReplace = MetadataResourceOperation.ID3_APIC_REPLACE,
                artworkRemove = MetadataResourceOperation.ID3_APIC_REMOVE,
                lyrics = describedLyricsDriver(),
            ),
        )
    }

    @Test fun flacResourcesPersistedGrantRoundTrip(): Unit = runBlocking {
        val audio = ByteArray(4096) { ((it * 61) % 251).toByte() }
        resourceRoundTrip(
            FormatSpec(
                mime = "audio/flac",
                displayName = "michi-saf-${UUID.randomUUID()}.flac",
                formatLabel = "flac",
                extension = "flac",
                seedTitle = "SAF FLAC Original",
                seedArtist = "SAF Artista FLAC",
                seedAlbum = "SAF Album FLAC",
                seedGenre = "Rock",
                seed = {
                    flac(
                        comments = listOf(
                            "TITLE=SAF FLAC Original",
                            "ARTIST=SAF Artista FLAC",
                            "ALBUM=SAF Album FLAC",
                            "GENRE=Rock",
                        ),
                        audio = audio,
                    )
                },
                audioTail = audio,
                artworkAdd = MetadataResourceOperation.FLAC_PICTURE_ADD,
                artworkReplace = MetadataResourceOperation.FLAC_PICTURE_REPLACE,
                artworkRemove = MetadataResourceOperation.FLAC_PICTURE_REMOVE,
                lyrics = scalarLyricsDriver(),
            ),
        )
    }

    @Test fun m4aResourcesPersistedGrantRoundTrip(): Unit = runBlocking {
        val audio = InstrumentationRegistry.getInstrumentation().context.assets
            .open("me14-audio-only.m4a").use { it.readBytes() }
        resourceRoundTrip(
            FormatSpec(
                mime = "audio/mp4",
                displayName = "michi-saf-${UUID.randomUUID()}.m4a",
                formatLabel = "m4a",
                extension = "m4a",
                seedTitle = "ME14 M4A",
                seedArtist = "Artista M4A",
                seedAlbum = "Album M4A",
                seedGenre = "Rock",
                seed = { audio },
                audioTail = null,
                artworkAdd = MetadataResourceOperation.MP4_COVR_ADD,
                artworkReplace = MetadataResourceOperation.MP4_COVR_REPLACE,
                artworkRemove = MetadataResourceOperation.MP4_COVR_REMOVE,
                lyrics = scalarLyricsDriver(),
            ),
        )
    }

    private class FormatSpec(
        val mime: String,
        val displayName: String,
        val formatLabel: String,
        val extension: String,
        val seedTitle: String,
        val seedArtist: String,
        val seedAlbum: String,
        val seedGenre: String,
        val seed: () -> ByteArray,
        val audioTail: ByteArray?,
        val artworkAdd: MetadataResourceOperation,
        val artworkReplace: MetadataResourceOperation,
        val artworkRemove: MetadataResourceOperation,
        val lyrics: LyricsDriver,
    )

    private class LyricsDriver(
        val scalarLyrics: Boolean,
        val addEdits: (MetadataSnapshot) -> List<MetadataSelectiveAssetStage.Edit>,
        val replaceEdits: (MetadataSnapshot) -> List<MetadataSelectiveAssetStage.Edit>,
        val clearEdits: (MetadataSnapshot) -> List<MetadataSelectiveAssetStage.Edit>,
        val addReview: (MetadataSnapshot) -> MetadataReview.Ready?,
        val replaceReview: (MetadataSnapshot) -> MetadataReview.Ready?,
        val clearReview: (MetadataSnapshot) -> MetadataReview.Ready?,
        val addOps: Set<MetadataResourceOperation>,
        val replaceOps: Set<MetadataResourceOperation>,
        val clearOps: Set<MetadataResourceOperation>,
        val verifyAdd: (MetadataSnapshot, MetadataArtworkLyricsProbe.Snapshot) -> Unit,
        val verifyReplace: (MetadataSnapshot, MetadataArtworkLyricsProbe.Snapshot) -> Unit,
        val verifyClear: (MetadataSnapshot, MetadataArtworkLyricsProbe.Snapshot) -> Unit,
    )

    private class SafTrack(
        val uri: Uri,
        val sourceId: String,
        val trackId: String,
        val reader: ExtendedTrackMetadataRepository,
        val service: MetadataSaveService,
    )

    private suspend fun resourceRoundTrip(spec: FormatSpec) {
        val tree = grantPersistedTree()
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        val seed = spec.seed()
        val uri = requireNotNull(
            DocumentsContract.createDocument(
                context.contentResolver, parent, spec.mime, spec.displayName,
            ),
        ) { "ExternalStorageProvider refused the disposable ${spec.formatLabel} document" }
        documentUri = uri
        writeDocument(uri, seed)

        val sourceId = customRootSourceId(tree)
        val trackId = treeTrackId(tree, DocumentsContract.getDocumentId(uri))
        val repository = SyncedTrackRepository(trackDao = database.trackDao())
        val size = context.contentResolver.openFileDescriptor(uri, "r")!!
            .use { it.statSize }
        repository.saveLibrary(
            listOf(
                TrackDto(
                    id = trackId, title = spec.seedTitle, artist = spec.seedArtist,
                    album = spec.seedAlbum, duration = 100, size = size,
                    format = spec.formatLabel, genre = spec.seedGenre,
                ),
            ),
            sourceId,
        )
        repository.markDownloadedVerified(
            sourceId = sourceId, id = trackId, filepath = uri.toString(),
            size = size, sha256 = sha(seed), verifiedAt = 1_700_000_000_000L,
            algorithm = "sha256",
        )
        val track = SafTrack(
            uri = uri, sourceId = sourceId, trackId = trackId,
            reader = ExtendedTrackMetadataRepository(database.trackDao(), context),
            service = MetadataSaveService(context, database.trackDao(), database),
        )

        var current = readBytes(uri)
        assertArrayEquals("The provider must store the exact seed", seed, current)
        assertEquals(size, current.size.toLong())
        var snapshot = coldRead(track, current.size.toLong())
        val originalTitle = snapshot.known[MetadataField.TITLE]
        val initialArtworks = observe(current, spec.extension).artworks.size

        // 1. letra: add
        var saved = commit(
            spec, track, "letra add", snapshot, current.size.toLong(),
            spec.lyrics.addEdits(snapshot), spec.lyrics.addReview(snapshot),
            spec.lyrics.addOps, spec.lyrics.scalarLyrics,
        )
        current = verifyCommitted(saved, uri)
        assertAudioTail(spec, current)
        spec.lyrics.verifyAdd(
            coldRead(track, current.size.toLong()), observe(current, spec.extension),
        )
        snapshot = coldRead(track, current.size.toLong())

        // 2. imagen: add
        val firstImage = png(21)
        val secondImage = png(22)
        val beforeArtwork = observe(current, spec.extension)
        val addition = (
            MetadataArtworkAdditionPreview.review(beforeArtwork, firstImage, "image/png")
                as MetadataArtworkAdditionPreview.Result.Ready
            ).proposal
        assertEquals(initialArtworks, addition.ordinal)
        saved = commit(
            spec, track, "imagen add", snapshot, current.size.toLong(),
            listOf(MetadataSelectiveAssetStage.Edit.PictureAdd(addition)), null,
            setOf(spec.artworkAdd), false,
        )
        current = verifyCommitted(saved, uri)
        assertAudioTail(spec, current)
        var resources = observe(current, spec.extension)
        assertEquals(initialArtworks + 1, resources.artworks.size)
        assertArrayEquals(firstImage, resources.artworks[initialArtworks].image)
        snapshot = coldRead(track, current.size.toLong())

        // 3. imagen: replace
        val replacement = (
            MetadataArtworkReplacementPreview.review(
                resources, initialArtworks, secondImage, "image/png",
            ) as MetadataArtworkReplacementPreview.Result.Ready
            ).proposal
        saved = commit(
            spec, track, "imagen replace", snapshot, current.size.toLong(),
            listOf(MetadataSelectiveAssetStage.Edit.Picture(replacement)), null,
            setOf(spec.artworkReplace), false,
        )
        current = verifyCommitted(saved, uri)
        assertAudioTail(spec, current)
        resources = observe(current, spec.extension)
        assertEquals(initialArtworks + 1, resources.artworks.size)
        assertArrayEquals(secondImage, resources.artworks[initialArtworks].image)
        snapshot = coldRead(track, current.size.toLong())

        // 4. letra: replace
        saved = commit(
            spec, track, "letra replace", snapshot, current.size.toLong(),
            spec.lyrics.replaceEdits(snapshot), spec.lyrics.replaceReview(snapshot),
            spec.lyrics.replaceOps, spec.lyrics.scalarLyrics,
        )
        current = verifyCommitted(saved, uri)
        assertAudioTail(spec, current)
        spec.lyrics.verifyReplace(
            coldRead(track, current.size.toLong()), observe(current, spec.extension),
        )
        snapshot = coldRead(track, current.size.toLong())

        // 5. imagen: remove, exact ordinal and exact preimage digest
        saved = commit(
            spec, track, "imagen remove", snapshot, current.size.toLong(),
            listOf(
                MetadataSelectiveAssetStage.Edit.PictureRemove(
                    initialArtworks, sha(secondImage),
                ),
            ),
            null, setOf(spec.artworkRemove), false,
        )
        current = verifyCommitted(saved, uri)
        assertAudioTail(spec, current)
        assertEquals(initialArtworks, observe(current, spec.extension).artworks.size)
        snapshot = coldRead(track, current.size.toLong())

        // 6. letra: clear
        saved = commit(
            spec, track, "letra clear", snapshot, current.size.toLong(),
            spec.lyrics.clearEdits(snapshot), spec.lyrics.clearReview(snapshot),
            spec.lyrics.clearOps, spec.lyrics.scalarLyrics,
        )
        current = verifyCommitted(saved, uri)
        assertAudioTail(spec, current)
        spec.lyrics.verifyClear(
            coldRead(track, current.size.toLong()), observe(current, spec.extension),
        )
        snapshot = coldRead(track, current.size.toLong())

        // M4A carries no appended audio tail, so resource-only rewrites are
        // checked against the provider-neutral sample evidence instead.
        if (spec.extension == "m4a") {
            val baselineAudio = m4aEvidence(seed)
            val committedAudio = m4aEvidence(current)
            assertEquals(
                "The M4A media payload must survive every resource rewrite",
                baselineAudio.mediaPayloadSha256, committedAudio.mediaPayloadSha256,
            )
            assertEquals(
                "Every M4A sample table reference must survive every resource rewrite",
                baselineAudio.samples,
                committedAudio.samples,
            )
            assertEquals(
                "The M4A sample fingerprint must survive every resource rewrite",
                baselineAudio.samples.fingerprintSha256,
                committedAudio.samples.fingerprintSha256,
            )
        }

        // Reopen: unrelated scalar tags survive the whole resource round trip.
        val reopened = coldRead(track, current.size.toLong())
        assertEquals(originalTitle, reopened.known[MetadataField.TITLE])
        assertEquals(initialArtworks, observe(current, spec.extension).artworks.size)
        val row = database.trackDao().getTrackBySource(sourceId, trackId)!!
        assertEquals(current.size.toLong(), row.size)
        assertEquals(checkNotNull(originalTitle).single(), row.title)
        val change = checkNotNull(saved.libraryChange)
        assertEquals(current.size.toLong(), change.committedBytes)
        assertEquals(sha(current), change.committedSha256)
        assertEquals(row.metadataRevision, change.committedRevision)
        val marker = database.trackDao().getAppliedMetadataTransaction(
            change.transactionId,
        )
        assertNotNull("The applied transaction marker must exist", marker)
        assertEquals(change.committedRevision, marker!!.committedRevision)
        assertEquals(sha(current), marker.candidateSha256)
        assertEquals(current.size.toLong(), marker.candidateBytes)
        assertJournalClear()

        // Exact restore: an interrupted resource mutation must be reversible to
        // the last committed preimage, byte for byte.
        val committedBytes = current
        val interrupted = MetadataSaveService(
            context, database.trackDao(), database,
            MetadataSaveCheckpointHook { point ->
                if (point == MetadataSaveCheckpoint.AFTER_PROVIDER_CLOSE) {
                    throw IllegalStateException("SAF_RESTORE_E2E_INTERRUPT")
                }
            },
        ).saveBundle(
            sourceId = sourceId, trackId = trackId, uriString = uri.toString(),
            expectedBytes = committedBytes.size.toLong(),
            targetInput = snapshot.target, reviewInput = null,
            resourceEdits = listOf(
                MetadataSelectiveAssetStage.Edit.PictureAdd(
                    (
                        MetadataArtworkAdditionPreview.review(
                            observe(committedBytes, spec.extension), png(31), "image/png",
                        ) as MetadataArtworkAdditionPreview.Result.Ready
                        ).proposal,
                ),
            ),
            ownerCurrent = { true }, playbackClosed = { true },
        )
        assertTrue(
            "The injected post-provider interruption must retain recovery: $interrupted",
            interrupted is MetadataSaveService.Outcome.Failed && interrupted.needsRecovery,
        )
        val pending = MetadataRecoveryJournalV1(
            File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
        ).inspect() as MetadataRecoveryJournalV1.Inspection.Pending
        assertEquals(MetadataRecoveryPhase.MUTATION_UNKNOWN, pending.entry.phase)

        writeDocument(uri, byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(
            MetadataRecoveryConsole.RestoreResult.RestoredVerified,
            MetadataRecoveryConsole(context, database).restoreOriginal(
                sourceId = sourceId, trackId = trackId, uri = uri.toString(),
                explicitlyConfirmed = true, ownerStillCurrent = { true },
                playbackClosed = { true },
            ),
        )
        assertArrayEquals(committedBytes, readBytes(uri))
        assertEquals(sha(committedBytes), sha(readBytes(uri)))
        assertJournalClear()

        // An unclassified provider must never receive a resource mutation.
        val untrusted = DocumentsContract.buildDocumentUri(
            "org.michimusic.untrusted.documents",
            "primary:Download/michi-untrusted-${UUID.randomUUID()}.${spec.extension}",
        )
        val revisionBefore = database.trackDao()
            .getTrackBySource(sourceId, trackId)!!.metadataRevision
        val markersBefore = appliedTransactionCount(sourceId, trackId)
        val probe = track.service.saveBundle(
            sourceId = sourceId, trackId = trackId, uriString = untrusted.toString(),
            expectedBytes = committedBytes.size.toLong(),
            targetInput = snapshot.target, reviewInput = null,
            resourceEdits = listOf(
                MetadataSelectiveAssetStage.Edit.PictureAdd(
                    (
                        MetadataArtworkAdditionPreview.review(
                            observe(committedBytes, spec.extension), png(32), "image/png",
                        ) as MetadataArtworkAdditionPreview.Result.Ready
                        ).proposal,
                ),
            ),
            ownerCurrent = { true }, playbackClosed = { true },
        )
        assertTrue(
            "An insecure provider must be refused, got $probe",
            probe is MetadataSaveService.Outcome.Blocked,
        )
        assertEquals(
            "Perfil de escritura no certificado",
            (probe as MetadataSaveService.Outcome.Blocked).reason,
        )
        assertEquals(
            revisionBefore,
            database.trackDao().getTrackBySource(sourceId, trackId)!!.metadataRevision,
        )
        assertEquals(markersBefore, appliedTransactionCount(sourceId, trackId))
        assertArrayEquals(committedBytes, readBytes(uri))
        assertJournalClear()

        // Revocation: without the persisted grant nothing may be published.
        context.contentResolver.releasePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        assertTrue(
            "The persisted tree grant must be gone after revocation",
            context.contentResolver.persistedUriPermissions.none { it.uri == tree },
        )
        val revoked = track.service.saveBundle(
            sourceId = sourceId, trackId = trackId, uriString = uri.toString(),
            expectedBytes = committedBytes.size.toLong(),
            targetInput = snapshot.target, reviewInput = null,
            resourceEdits = listOf(
                MetadataSelectiveAssetStage.Edit.PictureAdd(
                    (
                        MetadataArtworkAdditionPreview.review(
                            observe(committedBytes, spec.extension), png(33), "image/png",
                        ) as MetadataArtworkAdditionPreview.Result.Ready
                        ).proposal,
                ),
            ),
            ownerCurrent = { true }, playbackClosed = { true },
        )
        assertTrue(
            "A revoked grant must block the resource save, got $revoked",
            revoked is MetadataSaveService.Outcome.Blocked,
        )
        assertEquals(
            revisionBefore,
            database.trackDao().getTrackBySource(sourceId, trackId)!!.metadataRevision,
        )
        assertEquals(markersBefore, appliedTransactionCount(sourceId, trackId))
        assertJournalClear()
        // Cleanup is intentionally best-effort: the grant is already revoked.
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    private fun describedLyricsDriver(): LyricsDriver {
        val identity = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.LYRICS, "eng", "",
        )
        fun edit(change: MetadataDescribedTextWorkbench.Change) =
            listOf(MetadataSelectiveAssetStage.Edit.DescribedText(change))
        return LyricsDriver(
            scalarLyrics = false,
            addEdits = {
                edit(MetadataDescribedTextWorkbench.Change.Add(identity, "Letra SAF uno"))
            },
            replaceEdits = {
                edit(MetadataDescribedTextWorkbench.Change.Replace(identity, "Letra SAF dos"))
            },
            clearEdits = {
                edit(MetadataDescribedTextWorkbench.Change.Clear(identity))
            },
            addReview = { null },
            replaceReview = { null },
            clearReview = { null },
            addOps = setOf(MetadataResourceOperation.ID3_DESCRIBED_TEXT_ADD),
            replaceOps = setOf(MetadataResourceOperation.ID3_DESCRIBED_TEXT_REPLACE),
            clearOps = setOf(MetadataResourceOperation.ID3_DESCRIBED_TEXT_CLEAR),
            verifyAdd = { _, resources ->
                assertEquals(
                    listOf(MetadataDescribedTextWorkbench.Item(identity, "Letra SAF uno")),
                    resources.texts,
                )
            },
            verifyReplace = { _, resources ->
                assertEquals(
                    listOf(MetadataDescribedTextWorkbench.Item(identity, "Letra SAF dos")),
                    resources.texts,
                )
            },
            verifyClear = { _, resources -> assertTrue(resources.texts.isEmpty()) },
        )
    }

    private fun scalarLyricsDriver(): LyricsDriver {
        fun review(
            snapshot: MetadataSnapshot,
            values: List<String>?,
        ): MetadataReview.Ready {
            val change = if (values == null) {
                MetadataChange.Clear(MetadataField.LYRICS)
            } else {
                MetadataChange.Replace(MetadataField.LYRICS, values)
            }
            val result = MetadataEditPreview.review(snapshot, listOf(change))
            assertTrue("The scalar lyrics review must be accepted: $result",
                result is MetadataReview.Ready)
            return result as MetadataReview.Ready
        }
        return LyricsDriver(
            scalarLyrics = true,
            addEdits = { emptyList() },
            replaceEdits = { emptyList() },
            clearEdits = { emptyList() },
            addReview = { review(it, listOf("Letra SAF uno")) },
            replaceReview = { review(it, listOf("Letra SAF dos")) },
            clearReview = { review(it, null) },
            addOps = emptySet(),
            replaceOps = emptySet(),
            clearOps = emptySet(),
            verifyAdd = { snapshot, _ ->
                assertEquals(listOf("Letra SAF uno"), snapshot.known[MetadataField.LYRICS])
            },
            verifyReplace = { snapshot, _ ->
                assertEquals(listOf("Letra SAF dos"), snapshot.known[MetadataField.LYRICS])
            },
            verifyClear = { snapshot, _ ->
                assertTrue(
                    "A cleared lyrics field must be absent, not empty text",
                    snapshot.known[MetadataField.LYRICS].orEmpty().isEmpty(),
                )
            },
        )
    }

    private suspend fun commit(
        spec: FormatSpec,
        track: SafTrack,
        step: String,
        snapshot: MetadataSnapshot,
        expectedBytes: Long,
        edits: List<MetadataSelectiveAssetStage.Edit>,
        review: MetadataReview.Ready?,
        expectedOps: Set<MetadataResourceOperation>,
        scalarLyrics: Boolean,
    ): MetadataSaveService.Outcome.Saved {
        val outcome = track.service.saveBundle(
            sourceId = track.sourceId, trackId = track.trackId,
            uriString = track.uri.toString(), expectedBytes = expectedBytes,
            targetInput = snapshot.target, reviewInput = review,
            resourceEdits = edits, ownerCurrent = { true }, playbackClosed = { true },
        )
        assertTrue(
            "SAF ${spec.formatLabel} step '$step' must verify and publish, got $outcome",
            outcome is MetadataSaveService.Outcome.Saved && outcome.indexPublished,
        )
        val saved = outcome as MetadataSaveService.Outcome.Saved
        val change = checkNotNull(saved.libraryChange) {
            "SAF ${spec.formatLabel} step '$step' must publish a library change"
        }
        assertEquals(
            "SAF ${spec.formatLabel} step '$step' resource operations",
            expectedOps, change.resourceOperations,
        )
        if (scalarLyrics) {
            assertEquals(
                "SAF ${spec.formatLabel} step '$step' scalar fields",
                setOf(MetadataField.LYRICS), change.changedFields,
            )
        } else {
            assertTrue(
                "SAF ${spec.formatLabel} step '$step' must not claim a scalar " +
                    "change: ${change.changedFields}",
                change.changedFields.isEmpty(),
            )
        }
        return saved
    }

    private fun verifyCommitted(saved: MetadataSaveService.Outcome.Saved, uri: Uri): ByteArray {
        val after = readBytes(uri)
        assertEquals(saved.committedSha256, sha(after))
        assertEquals(saved.committedBytes, after.size.toLong())
        return after
    }

    private fun assertAudioTail(spec: FormatSpec, bytes: ByteArray) {
        val tail = spec.audioTail ?: return
        assertArrayEquals(
            "SAF ${spec.formatLabel} audio payload must stay byte-identical",
            tail, bytes.copyOfRange(bytes.size - tail.size, bytes.size),
        )
    }

    private suspend fun grantPersistedTree(): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val grant = CompletableDeferred<Result<Uri>>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                resultData?.getString(
                    MetadataSafGrantTestActivity.EXTRA_ERROR,
                )?.let {
                    grant.complete(Result.failure(IllegalStateException(it)))
                    return
                }
                resultData?.getString(
                    MetadataSafGrantTestActivity.EXTRA_URI,
                )?.let { grant.complete(Result.success(Uri.parse(it))) }
            }
        }
        instrumentation.startActivitySync(
            Intent(context, MetadataSafGrantTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MetadataSafGrantTestActivity.EXTRA_RECEIVER, receiver),
        )
        approveTreeWithDocumentsUi(UiDevice.getInstance(instrumentation))
        val tree = withTimeout(60_000) { grant.await().getOrThrow() }
        treeUri = tree
        assertEquals("com.android.externalstorage.documents", tree.authority)
        val persisted = context.contentResolver.persistedUriPermissions
            .singleOrNull { it.uri == tree }
        assertNotNull("The exact system tree grant must be persisted", persisted)
        assertTrue(persisted!!.isReadPermission && persisted.isWritePermission)
        return tree
    }

    private suspend fun coldRead(track: SafTrack, size: Long): MetadataSnapshot =
        when (
            val read = track.reader.loadEditorColdRead(
                track.sourceId, track.trackId, track.uri.toString(), size,
            )
        ) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("Production cold read rejected the SAF document: $read")
        }

    private fun observe(
        bytes: ByteArray,
        extension: String,
    ): MetadataArtworkLyricsProbe.Snapshot {
        val temp = File(context.cacheDir, "saf-resource-${UUID.randomUUID()}.$extension")
        return try {
            temp.writeBytes(bytes)
            MetadataPrivateBundleStage.observeFile(temp)
        } finally {
            temp.delete()
        }
    }

    private fun readBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun m4aEvidence(bytes: ByteArray): MetadataM4aAudioOnlyInspector.Evidence {
        val file = File(context.cacheDir, "saf-m4a-evidence-${UUID.randomUUID()}.m4a")
        try {
            file.writeBytes(bytes)
            val inspected = file.inputStream().use {
                MetadataM4aAudioOnlyInspector.inspect(it.channel)
            }
            return (inspected as MetadataM4aAudioOnlyInspector.Result.AudioOnly).evidence
        } finally {
            file.delete()
        }
    }

    private fun writeDocument(uri: Uri, bytes: ByteArray) {
        context.contentResolver.openFileDescriptor(uri, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun appliedTransactionCount(sourceId: String, trackId: String): Int =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM metadata_applied_transaction " +
                "WHERE sourceId=? AND trackId=?",
            arrayOf(sourceId, trackId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun assertJournalClear() {
        assertTrue(
            "The metadata journal must be clear",
            MetadataRecoveryJournalV1(
                File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
            ).inspect() is MetadataRecoveryJournalV1.Inspection.Clear,
        )
    }

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
            block(0, false, streamInfo) + block(4, true, vorbis) + audio
    }

    private suspend fun saveSafAsset(
        parent: Uri,
        assetName: String,
        mime: String,
        displayName: String,
        format: String,
        seedTitle: String,
        seedArtist: String,
        seedAlbum: String,
        seedGenre: String,
        changes: List<MetadataChange>,
    ): Uri {
        val tree = checkNotNull(treeUri)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(assetName).use { it.readBytes() }
        val uri = requireNotNull(DocumentsContract.createDocument(
            context.contentResolver, parent, mime, displayName,
        ))
        try {
            context.contentResolver.openFileDescriptor(uri, "rwt")!!.use { pfd ->
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                    it.write(bytes); it.flush(); it.fd.sync()
                }
            }
            val size = context.contentResolver.openFileDescriptor(uri, "r")!!
                .use { it.statSize }
            val sourceId = customRootSourceId(tree)
            val trackId = treeTrackId(tree, DocumentsContract.getDocumentId(uri))
            val repository = SyncedTrackRepository(trackDao = database.trackDao())
            repository.saveLibrary(listOf(TrackDto(
                id = trackId, title = seedTitle, artist = seedArtist,
                album = seedAlbum, duration = 100, size = size,
                format = format, genre = seedGenre,
            )), sourceId)
            repository.markDownloadedVerified(
                sourceId = sourceId, id = trackId, filepath = uri.toString(),
                size = size, sha256 = sha(bytes),
                verifiedAt = 1_700_000_000_000L, algorithm = "sha256",
            )
            val reader = ExtendedTrackMetadataRepository(database.trackDao(), context)
            val snapshot = when (val read = reader.loadEditorColdRead(
                sourceId, trackId, uri.toString(), size,
            )) {
                is MetadataEditorColdRead.Verified -> read.snapshot
                is MetadataEditorColdRead.Partial -> read.snapshot
                else -> error("SAF $format cold read rejected: $read")
            }
            val review = MetadataEditPreview.review(snapshot, changes)
            assertTrue("SAF $format review rejected: $review",
                review is MetadataReview.Ready)
            val outcome = MetadataSaveService(
                context, database.trackDao(), database,
            ).save(
                sourceId = sourceId, trackId = trackId, uriString = uri.toString(),
                expectedBytes = size, reviewInput = review as MetadataReview.Ready,
                ownerCurrent = { true }, playbackClosed = { true },
            )
            assertTrue("SAF $format save failed: $outcome",
                outcome is MetadataSaveService.Outcome.Saved &&
                    outcome.indexPublished)
            val saved = outcome as MetadataSaveService.Outcome.Saved
            val after = context.contentResolver.openInputStream(uri)!!
                .use { it.readBytes() }
            assertEquals(saved.committedSha256, sha(after))
            assertEquals(saved.committedBytes, after.size.toLong())
            val reopened = when (val read = reader.loadEditorColdRead(
                sourceId, trackId, uri.toString(), after.size.toLong(),
            )) {
                is MetadataEditorColdRead.Verified -> read.snapshot
                is MetadataEditorColdRead.Partial -> read.snapshot
                else -> error("SAF $format reopen rejected: $read")
            }
            changes.filterIsInstance<MetadataChange.Replace>().forEach {
                assertEquals("$format ${it.field} readback", it.values,
                    reopened.known[it.field])
            }
            val evidence = database.trackDao()
                .getCurrentExtendedTagEvidence(sourceId, trackId)
                ?: throw AssertionError("SAF $format evidence missing")
            changes.filterIsInstance<MetadataChange.Replace>()
                .singleOrNull { it.field == MetadataField.COMPOSER }
                ?.let { assertEquals(it.values.single(), evidence.composer) }
            changes.filterIsInstance<MetadataChange.Replace>()
                .singleOrNull { it.field == MetadataField.TRACK_TOTAL }
                ?.let { assertEquals(it.values.single().toInt(), evidence.trackTotal) }
            changes.filterIsInstance<MetadataChange.Replace>()
                .singleOrNull { it.field == MetadataField.DISC_NUMBER }
                ?.let { assertEquals(it.values.single().toInt(), evidence.discNumber) }
            changes.filterIsInstance<MetadataChange.Replace>()
                .singleOrNull { it.field == MetadataField.DISC_TOTAL }
                ?.let { assertEquals(it.values.single().toInt(), evidence.discTotal) }
            changes.filterIsInstance<MetadataChange.Replace>()
                .singleOrNull { it.field == MetadataField.RELEASE_DATE }
                ?.let { assertEquals(it.values.single(), evidence.date) }
            assertTrue(MetadataRecoveryJournalV1(
                File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
            ).inspect() is MetadataRecoveryJournalV1.Inspection.Clear)
            return uri
        } catch (e: Throwable) {
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
            throw e
        }
    }

    private fun approveTreeWithDocumentsUi(device: UiDevice) {
        var choose = device.wait(
            Until.findObject(By.text(Pattern.compile(
                "Use this folder|Usar esta carpeta",
                Pattern.CASE_INSENSITIVE,
            ))),
            15_000,
        ) ?: device.wait(
            Until.findObject(By.res(Pattern.compile(
                ".*:id/action_menu_select",
            ))),
            5_000,
        )
        checkNotNull(choose) { "DOCUMENTS_UI_TREE_ACTION_NOT_FOUND" }
        if (!choose.isEnabled) {
            val downloads = device.wait(
                Until.findObject(By.text(Pattern.compile(
                    "MichiMetadataSafE2E|Downloads?|Descargas",
                    Pattern.CASE_INSENSITIVE,
                ))),
                5_000,
            )
            checkNotNull(downloads) { "DOCUMENTS_UI_DOWNLOADS_NOT_FOUND" }
            downloads.click()
            choose = device.wait(
                Until.findObject(By.text(Pattern.compile(
                    "Use this folder|Usar esta carpeta",
                    Pattern.CASE_INSENSITIVE,
                ))),
                5_000,
            )
            checkNotNull(choose) { "DOCUMENTS_UI_TREE_ACTION_NOT_FOUND_AFTER_NAVIGATION" }
            check(choose.isEnabled) { "DOCUMENTS_UI_TREE_ACTION_DISABLED" }
        }
        choose.click()

        val allow = device.wait(
            Until.findObject(By.text(Pattern.compile(
                "Allow|Permitir",
                Pattern.CASE_INSENSITIVE,
            ))),
            10_000,
        ) ?: device.wait(
            Until.findObject(By.res("android", "button1")),
            2_000,
        )
        if (allow == null) {
            val hierarchy = File(context.cacheDir, "documents-ui-confirmation.xml")
            device.dumpWindowHierarchy(hierarchy)
            val controls = Regex("""<node\b[^>]*(?:text="[^"]+"|clickable="true")[^>]*/?>""")
                .findAll(hierarchy.readText())
                .joinToString("\n") { it.value }
            error(
                "DOCUMENTS_UI_CONFIRMATION_NOT_FOUND " +
                    "package=${device.currentPackageName} " +
                    controls.takeLast(12_000),
            )
        }
        allow.click()
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun id3(
        title: String,
        artist: String,
        album: String,
        genre: String,
        audio: ByteArray,
    ): ByteArray {
        fun frame(id: String, value: String): ByteArray {
            val text = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
            return id.toByteArray(Charsets.US_ASCII) + byteArrayOf(
                ((text.size shr 24) and 0xff).toByte(),
                ((text.size shr 16) and 0xff).toByte(),
                ((text.size shr 8) and 0xff).toByte(),
                (text.size and 0xff).toByte(),
                0, 0,
            ) + text
        }
        val frames = frame("TIT2", title) + frame("TPE1", artist) +
            frame("TALB", album) + frame("TCON", genre)
        val header = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(3, 0, 0))
            write(byteArrayOf(
                ((frames.size shr 21) and 0x7f).toByte(),
                ((frames.size shr 14) and 0x7f).toByte(),
                ((frames.size shr 7) and 0x7f).toByte(),
                (frames.size and 0x7f).toByte(),
            ))
        }.toByteArray()
        return header + frames + audio
    }
}
