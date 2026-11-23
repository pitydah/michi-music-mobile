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
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataId3StageRewriter
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget

/** ME-13 S5: typed MP3 restore proof with a synthetic MediaStore provider. */
@RunWith(RobolectricTestRunner::class)
class MetadataSourceRestoreCoordinatorTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val journalDir get() = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
    private lateinit var journal: MetadataRecoveryJournalV1
    private lateinit var intents: MetadataCommitIntentStore
    private lateinit var provider: SyntheticMediaProvider

    private val sourceId = "lib_device_local"
    private val trackId = "local_1"
    private val uri = "content://media/external_primary/audio/media/1"
    private val token = "me04:restore-token"
    private val target = MetadataTarget(sourceId, trackId, uri, token)
    private val mp3Payload = ByteArray(2700) { (it % 239).toByte() }

    private val original: ByteArray = sample("Original")
    private val candidate: ByteArray = stage(original, "Edited")
    private val originalSha = sha(original)
    private val candidateSha = sha(candidate)
    private val review = review("Original", "Edited")
    private val intent = MetadataCommitIntentManifest.create(
        transactionId = TX, review = review,
        provider = MetadataDocumentProvider.MEDIASTORE,
        container = MetadataAudioContainer.ID3_V24,
        originalBytes = original.size.toLong(), originalSha256 = originalSha,
        candidateBytes = candidate.size.toLong(), candidateSha256 = candidateSha,
    )

    @Before fun setUp() {
        journalDir.deleteRecursively()
        journal = MetadataRecoveryJournalV1(journalDir)
        intents = MetadataCommitIntentStore(journal)
        provider = SyntheticMediaProvider(File(context.cacheDir, "me13-restore-${UUID.randomUUID()}.mp3"))
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "media"
        })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @After fun tearDown() {
        journalDir.deleteRecursively()
        provider.file.delete()
    }

    private fun unknownEntry(): MetadataRecoveryJournalV1.Entry = runBlocking {
        val initial = MetadataRecoveryJournalV1.Entry(
            TX, MetadataRecoveryPhase.SNAPSHOTTING, sourceId, trackId, uri,
            original.size.toLong(), 777L)
        journal.exclusive {
            journal.start(initial)
            journal.backup(initial).writeBytes(original)
            val backed = initial.copy(phase = MetadataRecoveryPhase.BACKUP_DURABLE,
                originalSha256 = originalSha)
            journal.advance(initial, backed)
            journal.stage(backed).writeBytes(candidate)
            val staged = backed.copy(phase = MetadataRecoveryPhase.STAGE_READY,
                stageSha256 = candidateSha, stageBytes = candidate.size.toLong(),
                format = MetadataAudioContainer.ID3_V24.name)
            journal.advance(backed, staged)
            intents.write(staged, intent)
            val mutating = staged.copy(phase = MetadataRecoveryPhase.MUTATING)
            journal.advance(staged, mutating, writePermit(staged))
            val unknown = mutating.copy(phase = MetadataRecoveryPhase.MUTATION_UNKNOWN)
            journal.advance(mutating, unknown)
            unknown
        }
    }

    private fun permit(entry: MetadataRecoveryJournalV1.Entry) =
        MetadataRestorePermit.fromDurable(entry, intent, target, true)!!

    private fun request(
        entry: MetadataRecoveryJournalV1.Entry,
        owner: () -> Boolean = { true },
        playback: () -> Boolean = { true },
        permission: (String, String, Boolean) -> MetadataExactWritePermission.Result =
            { _, _, _ -> MetadataExactWritePermission.Result.ExistingExactGrant },
    ) = MetadataSourceRestoreCoordinator.Request(
        transactionId = entry.id, target = target,
        explicitlyConfirmedByUser = true,
        ownerStillCurrent = owner, playbackClosed = playback,
        permit = permit(entry),
    )

    private fun coordinator(
        permission: (String, String, Boolean) -> MetadataExactWritePermission.Result,
    ) = MetadataSourceRestoreCoordinator(context, permission)

    private fun phase(): MetadataRecoveryPhase =
        (journal.inspect() as MetadataRecoveryJournalV1.Inspection.Pending).entry.phase

    @Test fun truncatedSourceIsRestoredAndIndependentlyReadBack() = runBlocking {
        val entry = unknownEntry()
        provider.file.writeBytes(candidate.copyOf(candidate.size / 2))
        val outcome = coordinator { _, _, _ ->
            MetadataExactWritePermission.Result.ExistingExactGrant
        }.attempt(request(entry))
        assertEquals(MetadataSourceRestoreCoordinator.Outcome.RestoredVerified(TX), outcome)
        assertArrayEquals(original, provider.file.readBytes())
        assertEquals(MetadataRecoveryPhase.RESTORED_VERIFIED, phase())
        assertTrue(File(journalDir, "backup-${TX}.bin").exists())
        assertTrue(File(journalDir, "stage-${TX}.bin").exists())
        assertTrue(File(journalDir, "intent-${TX}.bin").exists())
    }

    @Test fun alreadyInstalledCandidateIsFinalizedNotRestored() = runBlocking {
        val entry = unknownEntry()
        provider.file.writeBytes(candidate)
        val outcome = coordinator { _, _, _ ->
            MetadataExactWritePermission.Result.ExistingExactGrant
        }.attempt(request(entry))
        assertEquals(MetadataSourceRestoreCoordinator.Outcome
            .CandidateInstalledFinalizeRequired(TX), outcome)
        assertArrayEquals(candidate, provider.file.readBytes())
        assertEquals(MetadataRecoveryPhase.MUTATION_UNKNOWN, phase())
    }

    @Test fun alreadyRestoredCompletesTerminalStateWithoutWrite() = runBlocking {
        val entry = unknownEntry()
        provider.file.writeBytes(original)
        var openedWrites = 0
        provider.onWrite = { openedWrites++ }
        val outcome = coordinator { _, _, _ ->
            MetadataExactWritePermission.Result.ExistingExactGrant
        }.attempt(request(entry))
        assertEquals(MetadataSourceRestoreCoordinator.Outcome.RestoredVerified(TX), outcome)
        assertEquals(0, openedWrites)
        assertArrayEquals(original, provider.file.readBytes())
        assertEquals(MetadataRecoveryPhase.RESTORED_VERIFIED, phase())
    }

    @Test fun failedRestoreKeepsRollbackPendingAndBothPrivateCopies() = runBlocking {
        val entry = unknownEntry()
        provider.file.writeBytes(candidate.copyOf(candidate.size / 2))
        provider.failWrites = true
        val outcome = coordinator { _, _, _ ->
            MetadataExactWritePermission.Result.ExistingExactGrant
        }.attempt(request(entry))
        assertTrue(outcome is MetadataSourceRestoreCoordinator.Outcome.KeepBothPrivateCopies)
        assertEquals(MetadataRecoveryPhase.ROLLBACK_PENDING, phase())
        assertTrue(File(journalDir, "backup-${TX}.bin").exists())
        assertTrue(File(journalDir, "stage-${TX}.bin").exists())
        assertTrue(File(journalDir, "intent-${TX}.bin").exists())
        assertArrayEquals(candidate.copyOf(candidate.size / 2), provider.file.readBytes())
    }

    @Test fun revokedPermissionNeverOpensTheSource() = runBlocking {
        val entry = unknownEntry()
        provider.file.writeBytes(candidate.copyOf(candidate.size / 2))
        var openedWrites = 0
        provider.onWrite = { openedWrites++ }
        val outcome = coordinator { _, _, _ ->
            MetadataExactWritePermission.Result.NoGrant
        }.attempt(request(entry))
        assertEquals(MetadataSourceRestoreCoordinator.Outcome.Blocked(
            MetadataSourceRestoreCoordinator.Refusal.RESTORE_PRECONDITIONS_FAILED), outcome)
        assertEquals(0, openedWrites)
        assertEquals(MetadataRecoveryPhase.MUTATION_UNKNOWN, phase())
    }

    @Test fun ownerChangeRefusesBeforeAnyReservationOrWrite() = runBlocking {
        val entry = unknownEntry()
        provider.file.writeBytes(candidate.copyOf(candidate.size / 2))
        var openedWrites = 0
        provider.onWrite = { openedWrites++ }
        val outcome = coordinator { _, _, _ ->
            MetadataExactWritePermission.Result.ExistingExactGrant
        }.attempt(request(entry, owner = { false }))
        assertEquals(MetadataSourceRestoreCoordinator.Outcome.Blocked(
            MetadataSourceRestoreCoordinator.Refusal.SOURCE_CHANGED_OR_UNREADABLE), outcome)
        assertEquals(0, openedWrites)
        assertEquals(MetadataRecoveryPhase.MUTATION_UNKNOWN, phase())
    }

    @Test fun permitCannotBeReusedForAnotherTransactionOrOwner() = runBlocking {
        val entry = unknownEntry()
        provider.file.writeBytes(candidate.copyOf(candidate.size / 2))
        val foreignTarget = target.copy(trackId = "other")
        val forged = MetadataRestorePermit.fromDurable(entry, intent, foreignTarget, true)
        assertEquals(null, forged)
    }

    private fun writePermit(entry: MetadataRecoveryJournalV1.Entry) = MetadataWritePermit(
        transactionId = entry.id, sourceId = entry.sourceId, trackId = entry.trackId,
        uri = entry.uri, originalSha256 = entry.originalSha256,
        candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
        profileId = MediaStoreMp3TextProfile.ID,
        evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
        container = entry.format, revision = 4, fields = setOf(MetadataField.TITLE),
        explicitUserAction = true,
    )

    private fun review(before: String, after: String): org.michimusic.core.metadata.MetadataReview.Ready {
        val snapshot = MetadataSnapshot(
            target = target, tagsVerified = true,
            known = mapOf(MetadataField.TITLE to listOf(before)),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        return MetadataEditPreview.review(snapshot,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf(after))))
            as org.michimusic.core.metadata.MetadataReview.Ready
    }

    private fun stage(source: ByteArray, title: String): ByteArray {
        val dir = Files.createTempDirectory("me13-restore-stage").toFile()
        try {
            val sourceFile = File(dir, "source.mp3").also { it.writeBytes(source) }
            val stageFile = File(dir, "stage.mp3")
            val snapshot = MetadataSnapshot(
                target = target, tagsVerified = true,
                known = mapOf(MetadataField.TITLE to listOf("Original")),
                verifiedFields = setOf(MetadataField.TITLE),
            )
            val proposal = MetadataEditPreview.review(snapshot,
                listOf(MetadataChange.Replace(MetadataField.TITLE, listOf(title))))
            MetadataId3StageRewriter.stage(sourceFile, stageFile, proposal, source.size.toLong())
            return stageFile.readBytes()
        } finally { dir.deleteRecursively() }
    }

    private fun sample(title: String): ByteArray {
        fun sync(n: Int) = byteArrayOf(
            ((n ushr 21) and 127).toByte(), ((n ushr 14) and 127).toByte(),
            ((n ushr 7) and 127).toByte(), (n and 127).toByte())
        fun frame(id: String, value: String): ByteArray {
            val payload = byteArrayOf(3) + value.toByteArray(StandardCharsets.UTF_8)
            val head = ByteArrayOutputStream()
            head.write(id.toByteArray(StandardCharsets.US_ASCII))
            head.write(sync(payload.size))
            head.write(0); head.write(0); head.write(payload)
            return head.toByteArray()
        }
        val body = ByteArrayOutputStream().also { it.write(frame("TIT2", title)) }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            out.write("ID3".toByteArray(StandardCharsets.US_ASCII))
            out.write(4); out.write(0); out.write(0); out.write(sync(body.size))
            out.write(body); out.write(mp3Payload)
        }.toByteArray()
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private class SyntheticMediaProvider(val file: File) : ContentProvider() {
        var failWrites = false
        var onWrite: () -> Unit = {}

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?,
        ): Cursor {
            val columns = projection ?: arrayOf(
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            val row = columns.map { column ->
                when (column) {
                    MediaStore.MediaColumns.SIZE -> file.length()
                    MediaStore.MediaColumns.DATE_MODIFIED -> file.lastModified() / 1000L
                    else -> null
                }
            }.toTypedArray()
            return MatrixCursor(columns).also { it.addRow(row) }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode.contains("w")) {
                onWrite()
                if (failWrites) throw FileNotFoundException("write denied")
                return ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
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
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<String>?,
        ) = 0
    }

    private companion object {
        val TX: String = UUID.randomUUID().toString()
    }
}
