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
        assumeTrue(
            "Explicitly opt in because this test drives system DocumentsUI",
            InstrumentationRegistry.getArguments().getString("ME_SAF_E2E") ==
                "I_ACCEPT_DOCUMENTS_UI",
        )
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
        val tree = withTimeout(30_000) { grant.await().getOrThrow() }
        treeUri = tree
        assertEquals("com.android.externalstorage.documents", tree.authority)
        val persisted = context.contentResolver.persistedUriPermissions
            .singleOrNull { it.uri == tree }
        assertNotNull("The exact system tree grant must be persisted", persisted)
        assertTrue(persisted!!.isReadPermission && persisted.isWritePermission)

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
            listOf(MetadataChange.Replace(
                MetadataField.TITLE, listOf("SAF Renombrada"),
            )),
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
        assertEquals(committed.size.toLong(), row.size)
        assertTrue(row.metadataRevision > beforeRevision)
        val change = checkNotNull(saved.libraryChange)
        assertEquals(sourceId, change.sourceId)
        assertEquals(trackId, change.trackId)
        assertEquals(uri.toString(), change.uri)
        assertEquals(setOf(MetadataField.TITLE), change.changedFields)
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
