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
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataRecoveryPhasePolicy
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorColdRead
import org.michimusic.data.repository.SyncedTrackRepository

/**
 * ME-13 resilience: a dead Room handle after the physical write, three concurrent
 * recovery operations on one frontier, ENOSPC/cancellation boundaries and an
 * interrupted restore whose cleanup stays conservative.
 *
 * Every case uses the production writers (MediaStore source, the real durable
 * private files and the real recovery coordinators). Only the IO fault probe of
 * [MetadataDurableFile] is injected, and it is removed in a `finally`.
 */
@RunWith(AndroidJUnit4::class)
class MetadataSaveResilienceInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: MichiDatabase
    private lateinit var audioPayload: ByteArray
    private lateinit var journalRoot: File
    private val createdUris = mutableListOf<Uri>()
    private val databaseNames = mutableListOf<String>()
    private val extraDatabases = mutableListOf<MichiDatabase>()
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
        MetadataDurableFile.ioFaultProbe = null
        createdUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        createdUris.clear()
        extraDatabases.forEach { db -> runCatching { db.close() } }
        extraDatabases.clear()
        runCatching { database.close() }
        databaseNames.forEach { name -> runCatching { context.deleteDatabase(name) } }
        databaseNames.clear()
        journalRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------ A23

    /**
     * A23: Room fails AFTER the physical postcheck of a durable mutation. The
     * save must persist the index obligation, report that the index was not
     * published and never write the source bytes again. A later resume with a
     * healthy Room completes the exact CAS and leaves the exact applied marker.
     */
    @Test fun roomUnavailableAfterWriteRecoversWithoutRewrite() = runBlocking {
        val databaseName = "me23-${UUID.randomUUID()}.db"
        databaseNames += databaseName
        // Room must own real files so the failure is a dead handle, not a lost DB.
        database.close()
        database = Room.databaseBuilder(context, MichiDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        val fixture = createFixture("A23")
        var closedInsideTheWrite = false
        val hook = MetadataSaveCheckpointHook { point ->
            if (point == MetadataSaveCheckpoint.BEFORE_INDEX_CAS) {
                // The physical source write already happened and is durable.
                database.close()
                closedInsideTheWrite = true
            }
        }

        val outcome = save(fixture, hook)

        assertTrue("checkpoint hook did not run", closedInsideTheWrite)
        assertTrue("outcome=$outcome", outcome is MetadataSaveService.Outcome.Saved)
        val saved = outcome as MetadataSaveService.Outcome.Saved
        assertTrue("the index must not be published: $saved", !saved.indexPublished)

        val journal = MetadataRecoveryJournalV1(journalRoot)
        val entry = pendingEntry(journal)
        assertEquals(MetadataRecoveryPhase.POSTCHECK_VERIFIED, entry.phase)
        val written = readSource(fixture.uri)
        assertEquals("exactly one physical write", entry.stageSha256, sha(written))
        assertTrue("committed size ${written.size} != ${entry.stageBytes}",
            written.size.toLong() == entry.stageBytes)
        assertNotEquals("the source must carry the candidate bytes",
            sha(fixture.original), sha(written))

        val obligation = MetadataIndexReconciliationReceipt.read(journalRoot, entry.id)
        assertTrue("the index obligation must be durable", obligation != null)
        val durable = requireNotNull(obligation)
        assertTrue("obligation is not actionable: $durable", durable.actionable)
        assertEquals(entry.id, durable.transactionId)
        assertEquals(entry.sourceId, durable.sourceId)
        assertEquals(entry.trackId, durable.trackId)
        assertEquals(entry.uri, durable.uri)
        assertEquals(entry.originalSha256, durable.originalSha256)
        assertEquals(entry.stageSha256, durable.candidateSha256)
        assertTrue("obligation candidate bytes mismatch",
            durable.candidateBytes == entry.stageBytes)
        assertEquals(entry.stageSha256, saved.committedSha256)
        assertTrue("saved committed bytes mismatch",
            saved.committedBytes == entry.stageBytes)

        val reopened = Room.databaseBuilder(context, MichiDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        extraDatabases += reopened
        val recovered = MetadataRecoveryCoordinator(context, reopened).resume(
            MetadataRecoveryCoordinator.RecoveryRequest(
                transactionId = entry.id,
                sourceId = entry.sourceId,
                trackId = entry.trackId,
                uri = entry.uri,
                userRequested = true,
                ownerStillCurrent = { true },
                playbackClosed = { true },
            ),
        )

        assertTrue("recovered=$recovered", recovered is MetadataRecoveryCoordinator.Outcome.Indexed)
        val afterRecovery = readSource(fixture.uri)
        assertEquals("recovery rewrote the source bytes", sha(written), sha(afterRecovery))
        assertTrue("recovery changed the source size",
            afterRecovery.size == written.size)
        assertTrue("journal must be clear after the exact CAS",
            journal.inspect() is MetadataRecoveryJournalV1.Inspection.Clear)

        val marker = reopened.trackDao().getAppliedMetadataTransaction(entry.id)
        assertTrue("the exact applied marker is missing", marker != null)
        val applied = requireNotNull(marker)
        assertEquals(entry.id, applied.transactionId)
        assertEquals(entry.sourceId, applied.sourceId)
        assertEquals(entry.trackId, applied.trackId)
        assertEquals(entry.uri, applied.uri)
        assertEquals(entry.stageSha256, applied.candidateSha256)
        assertTrue("marker candidate bytes mismatch",
            applied.candidateBytes == entry.stageBytes)
        assertTrue("the marker must advance exactly one revision",
            applied.committedRevision == applied.originalRevision + 1L)
        assertEquals(fixture.newTitle,
            reopened.trackDao().getTrackBySource(sourceId, fixture.trackId)!!.title)
    }

    // ------------------------------------------------------------------ A27

    /**
     * A27: save, restore and reconcile racing on the same frontier. Exactly one
     * of them may mutate; the journal lock returns a recoverable busy code and a
     * fully durable restore permit cannot enter a private phase. No deadlock.
     */
    @Test fun threeConcurrentOperationsOneMutation() = runBlocking {
        val fixture = createFixture("A27")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reached = mutableListOf<MetadataSaveCheckpoint>()
        val hook = MetadataSaveCheckpointHook { point ->
            reached += point
            if (point == MetadataSaveCheckpoint.BEFORE_MUTATING) {
                entered.complete(Unit)
                release.await()
            }
        }
        var transactionId = ""
        val first = async(Dispatchers.IO) { save(fixture, hook) }

        try {
            withTimeout(30_000) {
                entered.await()
                val journal = MetadataRecoveryJournalV1(journalRoot)
                val pending = pendingEntry(journal)
                transactionId = pending.id
                assertEquals(MetadataRecoveryPhase.STAGE_READY, pending.phase)

                // 1. A second save is refused by the single mutation frontier.
                val second = save(fixture)
                assertEquals(MetadataSaveService.Outcome.RecoveryRequired("IN_PROGRESS"), second)

                // 2. Every console entry point is refused by the same frontier.
                val console = MetadataRecoveryConsole(context, database)
                assertEquals(
                    MetadataRecoveryCoordinator.Outcome.Blocked("IN_PROGRESS"),
                    console.resumeExact(pending.id, pending.sourceId, pending.trackId,
                        pending.uri, true, { true }, { true }),
                )
                assertEquals(
                    MetadataRecoveryConsole.RestoreResult.Blocked("IN_PROGRESS"),
                    console.restoreOriginal(pending.sourceId, pending.trackId, pending.uri,
                        true, { true }, { true }),
                )
                assertEquals(
                    MetadataRecoveryConsole.DiscardResult.IN_PROGRESS,
                    console.discardPrivatePreparation(pending.sourceId, pending.trackId,
                        pending.uri, pending.id, true),
                )

                // 3. The reconcile path refuses a phase that never mutated.
                assertEquals(
                    MetadataRecoveryCoordinator.Outcome.Blocked("NOT_POST_MUTATION_PHASE"),
                    MetadataRecoveryCoordinator(context, database).resume(
                        MetadataRecoveryCoordinator.RecoveryRequest(
                            transactionId = pending.id,
                            sourceId = pending.sourceId,
                            trackId = pending.trackId,
                            uri = pending.uri,
                            userRequested = true,
                            ownerStillCurrent = { true },
                            playbackClosed = { true },
                        ),
                    ),
                )

                // 4. A restore attempt without a permit is not certified ...
                val target = MetadataTarget(pending.sourceId, pending.trackId,
                    pending.uri, pending.ownerEvidenceToken)
                assertEquals(
                    MetadataSourceRestoreCoordinator.Outcome.Blocked(
                        MetadataSourceRestoreCoordinator.Refusal.NOT_CERTIFIED),
                    MetadataSourceRestoreCoordinator(context).attempt(
                        MetadataSourceRestoreCoordinator.Request(
                            transactionId = pending.id,
                            target = target,
                            explicitlyConfirmedByUser = true,
                            ownerStillCurrent = { true },
                            playbackClosed = { true },
                        ),
                    ),
                )

                // ... and an exact, durable permit still cannot enter a private phase.
                val intents = MetadataCommitIntentStore(journal)
                val intent = (intents.inspect(pending) as
                    MetadataCommitIntentStore.Inspection.Present).receipt
                val permit =
                    requireNotNull(MetadataRestorePermit.fromDurable(pending, intent, true))
                assertEquals(
                    MetadataSourceRestoreCoordinator.Outcome.Blocked(
                        MetadataSourceRestoreCoordinator.Refusal.NOT_IN_UNKNOWN_PHASE),
                    MetadataSourceRestoreCoordinator(context).attempt(
                        MetadataSourceRestoreCoordinator.Request(
                            transactionId = pending.id,
                            target = target,
                            explicitlyConfirmedByUser = true,
                            ownerStillCurrent = { true },
                            playbackClosed = { true },
                            permit = permit,
                        ),
                    ),
                )
                // The contested source was not touched by any of the losers.
                assertEquals(sha(fixture.original), sha(readSource(fixture.uri)))
            }
        } finally {
            // A failing assertion must surface as a failure, not as a hung
            // instrumentation run: the gated save is always released.
            release.complete(Unit)
        }
        val outcome = first.await()
        assertTrue("outcome=$outcome", outcome is MetadataSaveService.Outcome.Saved)
        val saved = outcome as MetadataSaveService.Outcome.Saved
        assertTrue("the winner must publish exactly one index mutation: $saved",
            saved.indexPublished)
        val mutatingFrontierEntries =
            reached.count { it == MetadataSaveCheckpoint.BEFORE_MUTATING }
        assertTrue("the mutation frontier was crossed $mutatingFrontierEntries times",
            mutatingFrontierEntries == 1)
        val written = readSource(fixture.uri)
        assertNotEquals("the source must carry the candidate bytes",
            sha(fixture.original), sha(written))
        assertTrue("journal must be clear",
            MetadataRecoveryJournalV1(journalRoot).inspect() is
                MetadataRecoveryJournalV1.Inspection.Clear)
        val marker = database.trackDao().getAppliedMetadataTransaction(transactionId)
        assertTrue("expected exactly one applied mutation", marker != null)
        val applied = requireNotNull(marker)
        assertTrue("exactly one index revision",
            applied.committedRevision == applied.originalRevision + 1L)
        assertEquals(sha(written), applied.candidateSha256)
        assertEquals(fixture.newTitle,
            database.trackDao().getTrackBySource(sourceId, fixture.trackId)!!.title)
    }

    // ------------------------------------------------------------------ A28

    /**
     * A28: ENOSPC at the durable private-copy boundary and cancellation before
     * and after the provider mutation. The personal source is never damaged, the
     * private backup is preserved while it is the only remaining copy, and the
     * directory entry is really forced.
     */
    @Test fun enospcAndCancellationPreserveOriginalOrRecovery() = runBlocking {
        journalRoot.mkdirs()
        assertEquals(MetadataDurableFile.DirectorySyncResult.FORCED,
            MetadataDurableFile.syncDirectory(journalRoot))

        val enospcFixture = createFixture("A28E")
        // Real durable round trip of the same primitive the save path uses.
        val probeSource = File(context.cacheDir, "me28-source-${UUID.randomUUID()}.bin")
        val probeTarget = File(journalRoot, "me28-probe-${UUID.randomUUID()}.bin")
        try {
            probeSource.writeBytes(enospcFixture.original)
            assertTrue("copyExact must succeed for a real durable copy",
                MetadataDurableFile.copyExact(probeSource, probeTarget,
                    enospcFixture.original.size.toLong(), sha(enospcFixture.original)))
            assertEquals(sha(enospcFixture.original), sha(probeTarget.readBytes()))
            assertTrue("copyExact must never overwrite an existing durable file",
                !MetadataDurableFile.copyExact(probeSource, probeTarget,
                    enospcFixture.original.size.toLong(), sha(enospcFixture.original)))
        } finally {
            probeSource.delete()
            probeTarget.delete()
        }

        // (a) ENOSPC exactly at the private backup boundary.
        try {
            MetadataDurableFile.ioFaultProbe = { target ->
                if (target.name.startsWith("backup-"))
                    throw IOException("ENOSPC: injected durable-write failure")
            }
            val blocked = save(enospcFixture)
            assertTrue("outcome=$blocked", blocked is MetadataSaveService.Outcome.Blocked)
        } finally {
            MetadataDurableFile.ioFaultProbe = null
        }
        val journal = MetadataRecoveryJournalV1(journalRoot)
        assertTrue("a failed private copy must leave no pending record",
            journal.inspect() is MetadataRecoveryJournalV1.Inspection.Clear)
        assertEquals("the personal source must be byte-identical",
            sha(enospcFixture.original), sha(readSource(enospcFixture.uri)))
        assertTrue("no partial private artifact may survive: ${privateArtifactNames()}",
            privateArtifactNames().isEmpty())

        // (b) Cancellation BEFORE the mutation preserves source and backup.
        val beforeFixture = createFixture("A28B")
        val cancelBefore = MetadataSaveCheckpointHook { point ->
            if (point == MetadataSaveCheckpoint.AFTER_BACKUP_DURABLE)
                throw CancellationException("ME28 cancel before mutation")
        }
        val beforeFailure = runCatching { save(beforeFixture, cancelBefore) }.exceptionOrNull()
        assertTrue("expected CancellationException, was $beforeFailure",
            beforeFailure is CancellationException)
        val preserved = pendingEntry(journal)
        assertEquals(MetadataRecoveryPhase.BACKUP_DURABLE, preserved.phase)
        assertEquals("the private backup is the only remaining copy and must be exact",
            sha(beforeFixture.original), sha(journal.backup(preserved).readBytes()))
        assertTrue("the private stage must not exist yet", !journal.stage(preserved).exists())
        assertEquals("the source must not be touched",
            sha(beforeFixture.original), sha(readSource(beforeFixture.uri)))
        // While the private preparation is unresolved, a fresh edit of the same
        // file is refused: the recovery obligation outranks a new write.
        assertEquals(
            MetadataSaveService.Outcome.Blocked("Perfil de escritura no certificado"),
            save(beforeFixture),
        )
        assertEquals("the refused retry must not touch the source",
            sha(beforeFixture.original), sha(readSource(beforeFixture.uri)))
        // Nothing mutated, so only the private artifacts may be discarded, and
        // the discard is the explicit, user-confirmed one.
        val console = MetadataRecoveryConsole(context, database)
        assertEquals(MetadataRecoveryConsole.DiscardResult.DISCARDED_PRIVATE_ONLY,
            console.discardPrivatePreparation(preserved.sourceId, preserved.trackId,
                preserved.uri, preserved.id, true))
        assertTrue("journal must be clear after the explicit private discard",
            journal.inspect() is MetadataRecoveryJournalV1.Inspection.Clear)
        assertTrue("no private artifact may outlive the discard: ${privateArtifactNames()}",
            privateArtifactNames().isEmpty())
        assertEquals("the discard must not touch the source",
            sha(beforeFixture.original), sha(readSource(beforeFixture.uri)))
        // Only now can the same personal file be edited again, exactly once.
        val retried = save(beforeFixture)
        assertTrue("retried=$retried", retried is MetadataSaveService.Outcome.Saved)
        assertTrue("retried must publish: $retried",
            (retried as MetadataSaveService.Outcome.Saved).indexPublished)
        assertTrue("journal must be clear after the retry",
            journal.inspect() is MetadataRecoveryJournalV1.Inspection.Clear)
        assertNotEquals("the retry must install the candidate",
            sha(beforeFixture.original), sha(readSource(beforeFixture.uri)))

        // (c) Cancellation AFTER the mutation keeps the recovery obligation.
        val afterFixture = createFixture("A28C")
        val cancelAfter = MetadataSaveCheckpointHook { point ->
            if (point == MetadataSaveCheckpoint.AFTER_PROVIDER_CLOSE)
                throw CancellationException("ME28 cancel after mutation")
        }
        val afterFailure = runCatching { save(afterFixture, cancelAfter) }.exceptionOrNull()
        assertTrue("expected CancellationException, was $afterFailure",
            afterFailure is CancellationException)
        val mutating = pendingEntry(journal)
        assertEquals("the transport already sent the candidate bytes",
            MetadataRecoveryPhase.MUTATION_UNKNOWN, mutating.phase)
        assertEquals("the source must carry the exact candidate bytes",
            mutating.stageSha256, sha(readSource(afterFixture.uri)))
        assertEquals("the private backup must be preserved",
            mutating.originalSha256, sha(journal.backup(mutating).readBytes()))

        val resumed = MetadataRecoveryCoordinator(context, database).resume(
            MetadataRecoveryCoordinator.RecoveryRequest(
                transactionId = mutating.id,
                sourceId = mutating.sourceId,
                trackId = mutating.trackId,
                uri = mutating.uri,
                userRequested = true,
                ownerStillCurrent = { true },
                playbackClosed = { true },
            ),
        )
        assertTrue("resumed=$resumed", resumed is MetadataRecoveryCoordinator.Outcome.Indexed)
        assertEquals("recovery must not write the source again",
            mutating.stageSha256, sha(readSource(afterFixture.uri)))
        assertTrue("journal must be clear after the exact CAS",
            journal.inspect() is MetadataRecoveryJournalV1.Inspection.Clear)
        val applied = database.trackDao().getAppliedMetadataTransaction(mutating.id)
        assertTrue("the recovery must leave the exact marker", applied != null)
        assertEquals(mutating.stageSha256, requireNotNull(applied).candidateSha256)
    }

    // ------------------------------------------------------------------ A26

    /**
     * A26: an interrupted restore and its cleanup stay conservative. Case 1 runs
     * the full explicit restore plus cleanup. Case 2 cancels the restore while it
     * is already certified and proves the journal, the private backup and the
     * uninterested foreign track survive, and that a second attempt is either
     * refused without touching bytes or completes to the original bytes.
     */
    @Test fun interruptedRestoreAndCleanupRecoverConservatively() = runBlocking {
        val journal = MetadataRecoveryJournalV1(journalRoot)
        val console = MetadataRecoveryConsole(context, database)
        val cancelAfterMutation = MetadataSaveCheckpointHook { point ->
            if (point == MetadataSaveCheckpoint.AFTER_PROVIDER_CLOSE)
                throw CancellationException("ME26 interrupted")
        }
        val foreign = createFixture("A26F")
        val foreignSha = sha(readSource(foreign.uri))

        // Case 1: teardown of a durable mutation whose physical write was torn.
        val cleanupTarget = createFixture("A26A")
        assertTrue(runCatching { save(cleanupTarget, cancelAfterMutation) }
            .exceptionOrNull() is CancellationException)
        val pending = pendingEntry(journal)
        assertEquals(MetadataRecoveryPhase.MUTATION_UNKNOWN, pending.phase)
        writeSource(cleanupTarget.uri,
            readSource(cleanupTarget.uri).copyOfRange(0, (pending.stageBytes / 2).toInt()))
        val torn = sha(readSource(cleanupTarget.uri))
        assertNotEquals(pending.originalSha256, torn)
        assertNotEquals(pending.stageSha256, torn)

        assertEquals(MetadataRecoveryConsole.RestoreResult.RestoredVerified,
            console.restoreOriginal(pending.sourceId, pending.trackId, pending.uri,
                true, { true }, { true }))
        assertEquals("the restore must install the exact original bytes",
            pending.originalSha256, sha(readSource(cleanupTarget.uri)))
        assertTrue("the verified restore must clean the journal",
            journal.inspect() is MetadataRecoveryJournalV1.Inspection.Clear)
        assertPrivateArtifactsGone(pending.id)
        assertEquals(foreignSha, sha(readSource(foreign.uri)))

        // Case 2: the restore is cancelled after certification but before the
        // post-readback promotion, so no cleanup may claim success.
        val interrupted = createFixture("A26B")
        assertTrue(runCatching { save(interrupted, cancelAfterMutation) }
            .exceptionOrNull() is CancellationException)
        val reserved = pendingEntry(journal)
        assertEquals(MetadataRecoveryPhase.MUTATION_UNKNOWN, reserved.phase)
        writeSource(interrupted.uri,
            readSource(interrupted.uri).copyOfRange(0, (reserved.stageBytes / 2).toInt()))
        val tornBefore = sha(readSource(interrupted.uri))
        assertNotEquals(reserved.originalSha256, tornBefore)
        assertNotEquals(reserved.stageSha256, tornBefore)

        val intents = MetadataCommitIntentStore(journal)
        val intent = (intents.inspect(reserved) as
            MetadataCommitIntentStore.Inspection.Present).receipt
        val permit = requireNotNull(MetadataRestorePermit.fromDurable(reserved, intent, true))
        val holder = AtomicReference<Job?>(null)
        val coordinator = MetadataSourceRestoreCoordinator(
            context,
            permissionProbe = { _, _, _ ->
                // The write grant exists, but the caller is gone at this boundary.
                holder.get()?.cancel()
                MetadataExactWritePermission.Result.ExistingExactGrant
            },
        )
        val attempt = async(Dispatchers.IO, CoroutineStart.LAZY) {
            coordinator.attempt(MetadataSourceRestoreCoordinator.Request(
                transactionId = reserved.id,
                target = MetadataTarget(reserved.sourceId, reserved.trackId,
                    reserved.uri, reserved.ownerEvidenceToken),
                explicitlyConfirmedByUser = true,
                ownerStillCurrent = { true },
                playbackClosed = { true },
                permit = permit,
            ))
        }
        holder.set(attempt)
        attempt.start()
        val interruptedFailure =
            runCatching { withTimeout(30_000) { attempt.await() } }.exceptionOrNull()
        assertTrue("expected CancellationException, was $interruptedFailure",
            interruptedFailure is CancellationException)
        assertTrue("the attempt must be cancelled, not timed out", attempt.isCancelled)

        val afterInterruption = pendingEntry(journal)
        assertTrue("phase=${afterInterruption.phase}",
            afterInterruption.phase == MetadataRecoveryPhase.MUTATION_UNKNOWN ||
                afterInterruption.phase == MetadataRecoveryPhase.ROLLBACK_PENDING)
        assertTrue("an interrupted restore must stay recoverable",
            MetadataRecoveryPhasePolicy.sourceMayHaveChanged(afterInterruption.phase))
        assertTrue("the private backup must still be intact",
            journal.privateArtifactsIntact(afterInterruption))
        val afterSha = sha(readSource(interrupted.uri))
        // The cancellation is requested from the permission boundary, so the
        // interrupted attempt can only end in the reserved rollback phase or
        // exactly where it started; it can never claim the original bytes.
        assertTrue("an interrupted restore must not fabricate source bytes: $afterSha",
            afterSha == tornBefore ||
                afterSha == afterInterruption.originalSha256 ||
                afterSha == afterInterruption.stageSha256)
        assertEquals("a foreign track must never be part of this transaction",
            foreignSha, sha(readSource(foreign.uri)))

        val retry = console.restoreOriginal(reserved.sourceId, reserved.trackId,
            reserved.uri, true, { true }, { true })
        if (afterInterruption.phase == MetadataRecoveryPhase.ROLLBACK_PENDING) {
            assertEquals("a reserved rollback phase is never silently restarted",
                MetadataRecoveryConsole.RestoreResult.Blocked("PHASE_NOT_RECOVERABLE"), retry)
            assertEquals(afterSha, sha(readSource(interrupted.uri)))
            assertTrue("the backup must survive a refused restart",
                journal.privateArtifactsIntact(pendingEntry(journal)))
        } else {
            assertEquals("the exact explicit retry finishes the interrupted restore",
                MetadataRecoveryConsole.RestoreResult.RestoredVerified, retry)
            assertEquals("the retry must install the exact original bytes",
                afterInterruption.originalSha256, sha(readSource(interrupted.uri)))
            assertTrue("the verified retry must clean the journal",
                journal.inspect() is MetadataRecoveryJournalV1.Inspection.Clear)
        }
        assertEquals("a foreign track is never an exact pending restore",
            MetadataRecoveryConsole.RestoreResult.NoExactPendingRestore,
            console.restoreOriginal(reserved.sourceId, foreign.trackId,
                foreign.uri.toString(), true, { true }, { true }))
        assertEquals("the foreign source must remain byte-identical",
            foreignSha, sha(readSource(foreign.uri)))
    }

    // --------------------------------------------------------------- harness

    private data class Fixture(
        val uri: Uri,
        val trackId: String,
        val original: ByteArray,
        val review: MetadataReview.Ready,
        val originalTitle: String,
        val newTitle: String,
    )

    private fun service(
        hook: MetadataSaveCheckpointHook = NoMetadataSaveCheckpoint,
    ) = MetadataSaveService(context, database.trackDao(), database, hook)

    private suspend fun save(
        fixture: Fixture,
        hook: MetadataSaveCheckpointHook = NoMetadataSaveCheckpoint,
        ownerCurrent: () -> Boolean = { true },
        playbackClosed: () -> Boolean = { true },
    ) = service(hook).save(
        sourceId, fixture.trackId, fixture.uri.toString(),
        fixture.original.size.toLong(), fixture.review,
        ownerCurrent = ownerCurrent, playbackClosed = playbackClosed,
    )

    private suspend fun createFixture(tag: String): Fixture {
        val originalTitle = "ME13 $tag Original"
        val newTitle = "ME13 $tag Nuevo"
        val original = Mp3FixtureTags.build(
            4, originalTitle, "Artista $tag", "Album $tag", "Rock", audioPayload)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "me13-resilience-${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ME13Resilience")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        createdUris += uri
        writeSource(uri, original)
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        val trackId = "local_${uri.lastPathSegment!!.toLong()}"
        val repository = SyncedTrackRepository(trackDao = database.trackDao())
        repository.saveLibrary(listOf(TrackDto(
            id = trackId, title = originalTitle, artist = "Artista $tag",
            album = "Album $tag", duration = 100, size = original.size.toLong(),
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
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf(newTitle))))
            as MetadataReview.Ready
        return Fixture(uri, trackId, original, review, originalTitle, newTitle)
    }

    private fun pendingEntry(
        journal: MetadataRecoveryJournalV1,
    ): MetadataRecoveryJournalV1.Entry {
        val inspection = journal.inspect()
        assertTrue("expected a pending journal, was $inspection",
            inspection is MetadataRecoveryJournalV1.Inspection.Pending)
        return (inspection as MetadataRecoveryJournalV1.Inspection.Pending).entry
    }

    private fun privateArtifactNames(): List<String> =
        journalRoot.listFiles()?.map { it.name }.orEmpty().filter { name ->
            PRIVATE_PREFIXES.any { name.startsWith(it) } || name == RECORD_NAME
        }

    private fun assertPrivateArtifactsGone(transactionId: String) {
        val leftovers = journalRoot.listFiles()?.map { it.name }.orEmpty()
            .filter { transactionId in it && PRIVATE_PREFIXES.any { prefix -> prefix in it } }
        assertTrue("private artifacts left for $transactionId: $leftovers",
            leftovers.isEmpty())
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

    private companion object {
        val PRIVATE_PREFIXES = listOf(
            "backup-", "stage-", "intent-", "owner-", "preimage-",
            "index-reconciliation-", "index-applied-",
        )
        const val RECORD_NAME = "journal-v1.bin"
    }
}
