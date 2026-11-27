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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MediaStoreM4aHistoricalProfileV1
import org.michimusic.core.metadata.MediaStoreM4aTextProfile
import org.michimusic.core.metadata.MetadataArtworkAdditionPreview
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSelectiveAssetStage
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.metadata.MetadataWriteProfileRegistry
import org.michimusic.data.cache.MichiDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/**
 * KILLCRITIC P1: old MediaStore M4A journals must retain their exact executable
 * recovery scope, and a persisted STAGE_READY candidate must prove actual sample
 * equivalence before a resumed save can write the source.
 */
@RunWith(RobolectricTestRunner::class)
class MetadataM4aRecoveryResumeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val journalDir get() = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
    private lateinit var journal: MetadataRecoveryJournalV1
    private lateinit var intents: MetadataCommitIntentStore
    private lateinit var provider: SyntheticM4aProvider
    private lateinit var database: MichiDatabase
    private lateinit var service: MetadataSaveService

    private val sourceId = "lib_device_local"
    private val trackId = "local_m4a"
    private val uri = "content://media/external_primary/audio/media/9"
    private val token = "mf-p1:m4a-token"
    private val target = MetadataTarget(sourceId, trackId, uri, token)

    @Before fun setUp() {
        journalDir.deleteRecursively()
        journal = MetadataRecoveryJournalV1(journalDir)
        intents = MetadataCommitIntentStore(journal)
        provider = SyntheticM4aProvider(
            File(context.cacheDir, "mf-p1-m4a-${UUID.randomUUID()}.m4a"),
        )
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "media"
        })
        ShadowContentResolver.registerProviderInternal("media", provider)
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries().build()
        service = MetadataSaveService(context, database.trackDao(), database)
    }

    @After fun tearDown() {
        database.close()
        journalDir.deleteRecursively()
        provider.file.delete()
    }

    @Test
    fun legacyMediaStoreM4aAdvancedCovrJournalRestoresAndUnblocksSave() = runBlocking {
        val work = Files.createTempDirectory("mf-p1-m4a-recovery-").toFile()
        try {
            val originalFile = factory(work)
            val original = originalFile.readBytes()
            val originalSha = sha(original)
            val resources = MetadataPrivateBundleStage.observeFile(originalFile)
            val review = review(originalFile, MetadataField.ARTIST, "Artista P1")
            val addition = MetadataArtworkAdditionPreview.review(
                resources, png(1), "image/png",
            ) as MetadataArtworkAdditionPreview.Result.Ready
            val coverEdit = MetadataSelectiveAssetStage.Edit.PictureAdd(
                addition.proposal,
            )
            val candidateFile = File(work, "candidate.m4a")
            MetadataM4aPrivateStage.stage(
                originalFile,
                candidateFile,
                MetadataM4aPrivateStage.Request(
                    sourceSha256 = originalSha,
                    expectedBytes = original.size.toLong(),
                    review = review,
                    resourceEdits = listOf(coverEdit),
                    resources = resources,
                ),
            )
            val candidate = candidateFile.readBytes()
            val intent = MetadataCommitIntentManifest.createBundle(
                transactionId = TX,
                target = target,
                review = review,
                resources = resources,
                edits = listOf(coverEdit),
                provider = MetadataDocumentProvider.MEDIASTORE,
                container = MetadataAudioContainer.MP4_M4A,
                originalBytes = original.size.toLong(),
                originalSha256 = originalSha,
                candidateBytes = candidate.size.toLong(),
                candidateSha256 = sha(candidate),
            )
            val entry = journalEntry(
                original, candidate, intent,
                terminalPhase = MetadataRecoveryPhase.MUTATION_UNKNOWN,
            )

            // The legacy unbound journal resolves the exact broad MediaStore M4A
            // contract, never the active V2 profile.
            val permit = MetadataRestorePermit.fromDurable(entry, intent, target, true)
            assertNotNull(permit)
            assertEquals(MediaStoreM4aHistoricalProfileV1.ID, permit!!.profileId)
            assertEquals(
                MediaStoreM4aHistoricalProfileV1.EVIDENCE_ID,
                permit.evidenceId,
            )
            assertNotEquals(MediaStoreM4aTextProfile.ID, permit.profileId)
            assertTrue(
                MediaStoreM4aHistoricalProfileV1.profile.supports(intent.resources),
            )

            provider.file.writeBytes(candidate.copyOf(candidate.size / 2))
            val restored = MetadataRecoveryConsole(context).restoreOriginal(
                sourceId, trackId, uri,
                explicitlyConfirmed = true,
                ownerStillCurrent = { true },
                playbackClosed = { true },
            )
            assertEquals(
                MetadataRecoveryConsole.RestoreResult.RestoredVerified,
                restored,
            )
            assertArrayEquals(original, provider.file.readBytes())
            assertEquals(
                MetadataRecoveryJournalV1.Inspection.Clear,
                journal.inspect(),
            )
            assertEquals(
                MetadataSaveService.Eligibility.Eligible,
                service.eligibility(sourceId, uri),
            )
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun persistedStageReadyM4aWithReorderedSamplesIsRefusedBeforeWrite() = runBlocking {
        val work = Files.createTempDirectory("mf-p1-m4a-refuse-").toFile()
        try {
            val originalFile = factory(work)
            val original = originalFile.readBytes()
            val review = review(originalFile, MetadataField.TITLE, "Título P1")
            val candidateFile = File(work, "valid-candidate.m4a")
            MetadataM4aPrivateStage.stage(
                originalFile,
                candidateFile,
                MetadataM4aPrivateStage.Request(
                    sourceSha256 = sha(original),
                    expectedBytes = original.size.toLong(),
                    review = review,
                    resourceEdits = emptyList(),
                    resources = null,
                ),
            )
            val reordered = swapSampleSizes(candidateFile.readBytes())
            val reorderedFile = File(work, "reordered.m4a").also { it.writeBytes(reordered) }
            // Metadata-only readback is unchanged; only the per-sample bytes differ.
            assertEquals(
                snapshot(candidateFile).known[MetadataField.TITLE],
                snapshot(reorderedFile).known[MetadataField.TITLE],
            )
            assertEquals(
                snapshot(candidateFile).known,
                snapshot(reorderedFile).known,
            )
            val originalEvidence = evidence(originalFile)
            val reorderedEvidence = evidence(reorderedFile)
            assertEquals(
                originalEvidence.mediaPayloadSha256,
                reorderedEvidence.mediaPayloadSha256,
            )
            assertFalse(
                MetadataM4aAudioOnlyInspector.equivalent(
                    originalEvidence, reorderedEvidence,
                ),
            )
            val intent = MetadataCommitIntentManifest.create(
                transactionId = TX,
                review = review,
                provider = MetadataDocumentProvider.MEDIASTORE,
                container = MetadataAudioContainer.MP4_M4A,
                originalBytes = original.size.toLong(),
                originalSha256 = sha(original),
                candidateBytes = reordered.size.toLong(),
                candidateSha256 = sha(reordered),
            )
            journalEntry(
                original, reordered, intent,
                terminalPhase = MetadataRecoveryPhase.STAGE_READY,
            )
            provider.file.writeBytes(original)

            val outcome = service.save(
                sourceId, trackId, uri, original.size.toLong(), review,
                ownerCurrent = { true }, playbackClosed = { true },
            )

            assertEquals(
                MetadataSaveService.Outcome.Blocked("M4A_SAMPLE_EVIDENCE_MISMATCH"),
                outcome,
            )
            assertArrayEquals(original, provider.file.readBytes())
            val pending = journal.inspect() as MetadataRecoveryJournalV1.Inspection.Pending
            assertEquals(MetadataRecoveryPhase.STAGE_READY, pending.entry.phase)
            assertTrue(journal.backup(pending.entry).exists())
            assertTrue(journal.stage(pending.entry).exists())
            assertTrue(journal.intent(pending.entry).exists())
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun persistedStageReadyM4aWithEquivalentSamplesStillCommits() = runBlocking {
        val work = Files.createTempDirectory("mf-p1-m4a-commit-").toFile()
        try {
            val originalFile = factory(work)
            val original = originalFile.readBytes()
            val review = review(originalFile, MetadataField.TITLE, "Título P1")
            val candidateFile = File(work, "valid-candidate.m4a")
            MetadataM4aPrivateStage.stage(
                originalFile,
                candidateFile,
                MetadataM4aPrivateStage.Request(
                    sourceSha256 = sha(original),
                    expectedBytes = original.size.toLong(),
                    review = review,
                    resourceEdits = emptyList(),
                    resources = null,
                ),
            )
            val candidate = candidateFile.readBytes()
            val intent = MetadataCommitIntentManifest.create(
                transactionId = TX,
                review = review,
                provider = MetadataDocumentProvider.MEDIASTORE,
                container = MetadataAudioContainer.MP4_M4A,
                originalBytes = original.size.toLong(),
                originalSha256 = sha(original),
                candidateBytes = candidate.size.toLong(),
                candidateSha256 = sha(candidate),
            )
            journalEntry(
                original, candidate, intent,
                terminalPhase = MetadataRecoveryPhase.STAGE_READY,
            )
            provider.file.writeBytes(original)

            val outcome = service.save(
                sourceId, trackId, uri, original.size.toLong(), review,
                ownerCurrent = { true }, playbackClosed = { true },
            )

            assertTrue("valid persisted candidate must still commit: $outcome",
                outcome is MetadataSaveService.Outcome.Saved)
            assertArrayEquals(candidate, provider.file.readBytes())
            assertNotEquals(
                MetadataRecoveryPhase.STAGE_READY,
                (journal.inspect() as MetadataRecoveryJournalV1.Inspection.Pending)
                    .entry.phase,
            )
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun journalEntry(
        original: ByteArray,
        candidate: ByteArray,
        intent: MetadataCommitIntentManifest.Receipt,
        terminalPhase: MetadataRecoveryPhase,
    ): MetadataRecoveryJournalV1.Entry = journal.exclusive {
            val initial = MetadataRecoveryJournalV1.Entry(
                TX, MetadataRecoveryPhase.SNAPSHOTTING, sourceId, trackId, uri,
                original.size.toLong(), 777L,
            )
            journal.start(initial)
            journal.backup(initial).writeBytes(original)
            val backed = initial.copy(
                phase = MetadataRecoveryPhase.BACKUP_DURABLE,
                originalSha256 = sha(original),
            )
            journal.advance(initial, backed)
            journal.stage(backed).writeBytes(candidate)
            val staged = backed.copy(
                phase = MetadataRecoveryPhase.STAGE_READY,
                stageSha256 = sha(candidate),
                stageBytes = candidate.size.toLong(),
                format = MetadataAudioContainer.MP4_M4A.name,
                ownerEvidenceToken = token,
            )
            journal.advance(backed, staged)
            intents.write(staged, intent)
            if (terminalPhase == MetadataRecoveryPhase.MUTATING ||
                terminalPhase == MetadataRecoveryPhase.MUTATION_UNKNOWN
            ) {
                val mutating = staged.copy(phase = MetadataRecoveryPhase.MUTATING)
                journal.advance(staged, mutating, writePermit(staged, intent))
                if (terminalPhase == MetadataRecoveryPhase.MUTATION_UNKNOWN) {
                    journal.advance(
                        mutating,
                        mutating.copy(phase = MetadataRecoveryPhase.MUTATION_UNKNOWN),
                    )
                }
                (journal.inspect() as MetadataRecoveryJournalV1.Inspection.Pending).entry
            } else {
                staged
            }
    }

    private fun writePermit(
        entry: MetadataRecoveryJournalV1.Entry,
        intent: MetadataCommitIntentManifest.Receipt,
    ): MetadataWritePermit {
        val profile = requireNotNull(MetadataWriteProfileRegistry.resolveForIntent(intent))
        return MetadataWritePermit(
            transactionId = entry.id,
            sourceId = entry.sourceId,
            trackId = entry.trackId,
            uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256,
            stageBytes = entry.stageBytes,
            profileId = profile.id,
            evidenceId = profile.capabilityEvidenceId,
            container = entry.format,
            revision = 1,
            fields = intent.changes.mapTo(linkedSetOf()) { it.field },
            multiValueFields = MetadataWritePermit.declaredMultiValueFields(intent),
            explicitUserAction = true,
            resources = intent.resources,
        )
    }

    private fun factory(work: File): File {
        val source = listOf(
            File("src/androidTest/assets/me14-audio-only.m4a"),
            File("data/src/androidTest/assets/me14-audio-only.m4a"),
        ).firstOrNull { it.isFile }
            ?: error("the real AAC fixture is not available")
        return File(work, "original.m4a").also {
            source.copyTo(it, overwrite = true)
            it.setLastModified(1_700_000_000_000L)
        }
    }

    private fun review(
        file: File,
        field: MetadataField,
        value: String,
    ): MetadataReview.Ready {
        val snapshot = snapshot(file)
        val result = MetadataEditPreview.review(
            snapshot,
            listOf(MetadataChange.Replace(field, listOf(value))),
        )
        assertTrue("review must be ready: $result", result is MetadataReview.Ready)
        return result as MetadataReview.Ready
    }

    private fun snapshot(file: File): MetadataSnapshot =
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            MetadataColdFieldParser.observe(channel).toSnapshot(target)
        }

    private fun evidence(file: File): MetadataM4aAudioOnlyInspector.Evidence {
        val result = FileChannel.open(
            file.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use(MetadataM4aAudioOnlyInspector::inspect)
        assertTrue("fixture must be audio-only: $result",
            result is MetadataM4aAudioOnlyInspector.Result.AudioOnly)
        return (result as MetadataM4aAudioOnlyInspector.Result.AudioOnly).evidence
    }

    /** Flips two sample sizes while leaving metadata and the mdat bytes untouched. */
    private fun swapSampleSizes(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        val typeAt = (0..copy.size - 4).first {
            copy[it] == 's'.code.toByte() &&
                copy[it + 1] == 't'.code.toByte() &&
                copy[it + 2] == 's'.code.toByte() &&
                copy[it + 3] == 'z'.code.toByte()
        }
        val start = typeAt - 4
        val sampleSize = ByteBuffer.wrap(copy, start + 12, 4)
            .order(ByteOrder.BIG_ENDIAN).int
        val count = ByteBuffer.wrap(copy, start + 16, 4)
            .order(ByteOrder.BIG_ENDIAN).int
        require(sampleSize == 0 && count >= 2) { "fixture must use per-sample sizes" }
        val table = start + 20
        val first = ByteBuffer.wrap(copy, table, 4).order(ByteOrder.BIG_ENDIAN).int
        var swapped = false
        for (index in 1 until count) {
            val at = table + index * 4
            val other = ByteBuffer.wrap(copy, at, 4).order(ByteOrder.BIG_ENDIAN).int
            if (other != first) {
                ByteBuffer.wrap(copy, table, 4)
                    .order(ByteOrder.BIG_ENDIAN).putInt(other)
                ByteBuffer.wrap(copy, at, 4)
                    .order(ByteOrder.BIG_ENDIAN).putInt(first)
                swapped = true
                break
            }
        }
        check(swapped) { "fixture must contain two distinct sample sizes" }
        return copy
    }

    private fun png(mark: Int): ByteArray =
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        ) + ByteArray(8) { mark.toByte() } + ByteArray(8) { (mark + 1).toByte() }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun sha(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }

    private class SyntheticM4aProvider(val file: File) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?,
        ): Cursor {
            val columns = projection ?: arrayOf(
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
            )
            val row = arrayOfNulls<Any?>(columns.size)
            columns.forEachIndexed { index, column ->
                row[index] = when (column) {
                    MediaStore.MediaColumns.SIZE -> file.length()
                    MediaStore.MediaColumns.DATE_MODIFIED -> file.lastModified() / 1000L
                    MediaStore.MediaColumns.DISPLAY_NAME -> "mf-p1.m4a"
                    else -> null
                }
            }
            return MatrixCursor(columns).also { it.addRow(row) }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            when {
                mode.contains("t") -> ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                )
                mode.contains("w") -> ParcelFileDescriptor.open(
                    file, ParcelFileDescriptor.MODE_READ_WRITE,
                )
                else -> ParcelFileDescriptor.open(
                    file, ParcelFileDescriptor.MODE_READ_ONLY,
                )
            }

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? =
            openFile(uri, mode).let {
                AssetFileDescriptor(it, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }

        override fun getType(uri: Uri): String = "audio/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(
            uri: Uri, selection: String?, selectionArgs: Array<String>?,
        ) = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<String>?,
        ) = 0
    }

    private companion object {
        const val TX = "a1b2c3d4-1234-4abc-8abc-123456789abc"
    }
}
