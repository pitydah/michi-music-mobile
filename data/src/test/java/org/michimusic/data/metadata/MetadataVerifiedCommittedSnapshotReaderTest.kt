package org.michimusic.data.metadata

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataId3StageRewriter
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/** ME-13 85-A: the committed snapshot must match the installed source bytes. */
@RunWith(RobolectricTestRunner::class)
class MetadataVerifiedCommittedSnapshotReaderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val sourceId = "lib_device_local"
    private val trackId = "local_1"
    private val uri = "content://media/external_primary/audio/media/1"
    private val token = "me13:committed-token"
    private val target = MetadataTarget(sourceId, trackId, uri, token)
    private val audio = ByteArray(1200) { (it % 211).toByte() }
    private lateinit var provider: SyntheticProvider
    private lateinit var original: ByteArray
    private lateinit var candidate: ByteArray
    private lateinit var review: MetadataReview.Ready
    private lateinit var intent: MetadataCommitIntentManifest.Receipt
    private lateinit var entry: MetadataRecoveryJournalV1.Entry

    @Before fun setUp() {
        original = id3("Original", "Artista", "Album", audio)
        val originalFile = File.createTempFile("me13-a-original", ".mp3").also { it.writeBytes(original) }
        val originalSnapshot = parse(originalFile)
        review = MetadataEditPreview.review(originalSnapshot,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("Nueva"))))
            as MetadataReview.Ready
        val stageDir = Files.createTempDirectory("me13-a-stage").toFile()
        val stageFile = File(stageDir, "stage.mp3")
        MetadataId3StageRewriter.stage(originalFile, stageFile, review, original.size.toLong())
        candidate = stageFile.readBytes()
        intent = MetadataCommitIntentManifest.create(
            transactionId = TX, review = review,
            provider = org.michimusic.core.metadata.MetadataDocumentProvider.MEDIASTORE,
            container = MetadataAudioContainer.ID3_V23,
            originalBytes = original.size.toLong(), originalSha256 = sha(original),
            candidateBytes = candidate.size.toLong(), candidateSha256 = sha(candidate),
        )
        entry = MetadataRecoveryJournalV1.Entry(
            TX, org.michimusic.core.metadata.MetadataRecoveryPhase.POSTCHECK_VERIFIED,
            sourceId, trackId, uri, original.size.toLong(), 777L,
            originalSha256 = sha(original), stageSha256 = sha(candidate),
            stageBytes = candidate.size.toLong(), format = MetadataAudioContainer.ID3_V23.name,
            ownerEvidenceToken = token,
        )
        provider = SyntheticProvider(File(context.cacheDir, "me13-a-${UUID.randomUUID()}.mp3"))
        provider.file.writeBytes(candidate)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        originalFile.delete()
        stageDir.deleteRecursively()
    }

    @After fun tearDown() { provider.file.delete() }

    @Test fun committedSnapshotCarriesNewTitleAndMatchingObservation() = runBlocking {
        val outcome = reader().read(entry, target, intent)
        assertTrue(outcome is MetadataVerifiedCommittedSnapshotReader.Result.Verified)
        val snapshot = (outcome as MetadataVerifiedCommittedSnapshotReader.Result.Verified).snapshot
        assertEquals(listOf("Nueva"), snapshot.known[MetadataField.TITLE])
        assertEquals(MetadataFieldObservation.Present(listOf("Nueva")),
            snapshot.fieldObservations[MetadataField.TITLE])
        assertEquals("me13:sha256:${sha(candidate)}", snapshot.target.evidenceToken)
        assertTrue(MetadataField.ARTIST in snapshot.verifiedFields)
    }

    @Test fun differentCandidateShaIsRefusedBeforeAnySnapshot() = runBlocking {
        provider.file.writeBytes(candidate.copyOf(candidate.size - 1))
        val outcome = reader().read(entry, target, intent)
        assertEquals("SOURCE_SHA_OR_SIZE_MISMATCH",
            (outcome as MetadataVerifiedCommittedSnapshotReader.Result.Refused).code)
    }

    @Test fun sourceChangingBetweenReadsIsRefused() = runBlocking {
        var reads = 0
        provider.onRead = {
            reads++
            if (reads == 2) {
                provider.file.writeBytes(candidate.copyOf(candidate.size - 1))
            }
        }
        val outcome = reader().read(entry, target, intent)
        assertEquals("SOURCE_CHANGED_DURING_SNAPSHOT",
            (outcome as MetadataVerifiedCommittedSnapshotReader.Result.Refused).code)
    }

    @Test fun foreignTargetCannotReuseTheManifest() = runBlocking {
        val foreign = target.copy(trackId = "other")
        val outcome = reader().read(entry, foreign, intent)
        assertEquals("SNAPSHOT_WRONG_TRANSACTION",
            (outcome as MetadataVerifiedCommittedSnapshotReader.Result.Refused).code)
    }

    private fun reader() = MetadataVerifiedCommittedSnapshotReader(context)

    private fun parse(file: File): MetadataSnapshot {
        val report = java.io.FileInputStream(file).use { input ->
            org.michimusic.core.metadata.MetadataColdFieldParser.observe(input.channel)
        }
        return report.toSnapshot(target)
    }

    private fun id3(title: String, artist: String, album: String, audio: ByteArray): ByteArray {
        fun sync(n: Int) = byteArrayOf(
            ((n ushr 21) and 127).toByte(), ((n ushr 14) and 127).toByte(),
            ((n ushr 7) and 127).toByte(), (n and 127).toByte())
        fun frame(id: String, value: String): ByteArray {
            val payload = byteArrayOf(0) + value.toByteArray(StandardCharsets.ISO_8859_1)
            val head = ByteArrayOutputStream()
            head.write(id.toByteArray(StandardCharsets.US_ASCII))
            val size = payload.size
            head.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(),
                (size ushr 8).toByte(), size.toByte()))
            head.write(0); head.write(0); head.write(payload)
            return head.toByteArray()
        }
        val body = ByteArrayOutputStream().also {
            it.write(frame("TIT2", title)); it.write(frame("TPE1", artist))
            it.write(frame("TALB", album))
        }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            out.write("ID3".toByteArray(StandardCharsets.US_ASCII))
            out.write(3); out.write(0); out.write(0); out.write(sync(body.size))
            out.write(body); out.write(audio)
        }.toByteArray()
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private class SyntheticProvider(val file: File) : ContentProvider() {
        var onRead: () -> Unit = {}
        override fun onCreate(): Boolean = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            val row = columns.map {
                when (it) {
                    MediaStore.MediaColumns.SIZE -> file.length()
                    MediaStore.MediaColumns.DATE_MODIFIED -> file.lastModified() / 1000L
                    else -> null
                }
            }.toTypedArray()
            return MatrixCursor(columns).also { it.addRow(row) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            onRead()
            if (mode.contains("w")) {
                if (mode.contains("t")) throw FileNotFoundException("read-only fixture")
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? =
            openFile(uri, mode).let {
                AssetFileDescriptor(it, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
        override fun getType(uri: Uri): String = "audio/mpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<String>?) = 0
    }

    private companion object { val TX: String = UUID.randomUUID().toString() }
}
