package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataPhysicalOutcomePolicy
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.TrackDao

/**
 * ME-13 S4: on-demand consumer of durable [MetadataIndexReconciliationReceipt]
 * obligations. It never scans the library, never uses WorkManager and never
 * writes source bytes. Each activation lists only private, bounded receipts and
 * revalidates the exact journal, intent, private artifacts, provider source and
 * cached row before a single Room CAS.
 *
 * Idempotency is durable, not in-memory: a CAS followed by a kill before cleanup
 * is recognized as `AlreadyAppliedAndCleaned` on the next activation, because the
 * row carries the candidate size and its stale verification fields were cleared.
 * A missing row waits, a concurrently changed row is kept as a conflict, and a
 * corrupt or non-actionable receipt is never deleted.
 */
class MetadataIndexReconciler internal constructor(
    private val context: Context,
    private val database: MichiDatabase,
    private val sourceGate: SourceGate,
    private val publisher: Publisher,
) {
    constructor(context: Context, database: MichiDatabase) : this(
        context, database,
        AndroidSourceGate(context),
        CoordinatorPublisher(context, database),
    )

    sealed interface Outcome {
        data class Indexed(val transactionId: String) : Outcome
        data class AlreadyAppliedAndCleaned(val transactionId: String) : Outcome
        data class WaitingForIndexedRow(val transactionId: String) : Outcome
        data class ConflictKept(val transactionId: String) : Outcome
        data class RecoveryRequired(val transactionId: String, val code: String) : Outcome
        data class JournalUnavailableKept(val transactionId: String) : Outcome
        data class CorruptReceiptKept(val fileName: String) : Outcome
    }

    data class Report(val outcomes: List<Outcome>) {
        val changedIndex: Boolean
            get() = outcomes.any {
                it is Outcome.Indexed || it is Outcome.AlreadyAppliedAndCleaned
            }
    }

    internal interface SourceGate {
        fun exactCandidate(entry: MetadataRecoveryJournalV1.Entry): Boolean
        fun parseSource(entry: MetadataRecoveryJournalV1.Entry): MetadataFieldReadReport?
        fun snapshot(backup: File, target: MetadataTarget): MetadataSnapshot?
    }

    internal fun interface Publisher {
        suspend fun publish(request: PublishRequest): Boolean
    }

    internal data class PublishRequest(
        val entry: MetadataRecoveryJournalV1.Entry,
        val intent: MetadataCommitIntentManifest.Receipt,
        val original: MetadataSnapshot,
        val expectedIndex: MetadataIndexPublicationPolicy.IndexIdentity,
        val permit: MetadataWritePermit,
        val ownerStillCurrent: () -> Boolean,
        val playbackDescriptorClosed: () -> Boolean,
    )

    private val journal = MetadataRecoveryJournalV1(
        File(context.noBackupFilesDir, "michi-metadata-editor-v1"))
    private val intents = MetadataCommitIntentStore(journal)
    private val dao: TrackDao = database.trackDao()

    suspend fun reconcileAll(
        ownerStillCurrent: () -> Boolean,
        playbackDescriptorClosed: () -> Boolean,
    ): Report = withContext(Dispatchers.IO) {
        if (!ownerStillCurrent() || !playbackDescriptorClosed())
            return@withContext Report(emptyList())
        val root = journalRoot
        Report(MetadataIndexReconciliationReceipt.list(root).map { file ->
            reconcileFile(file, ownerStillCurrent, playbackDescriptorClosed)
        })
    }

    suspend fun reconcileForTrack(
        sourceId: String,
        trackId: String,
        ownerStillCurrent: () -> Boolean,
        playbackDescriptorClosed: () -> Boolean,
    ): Report = withContext(Dispatchers.IO) {
        if (!ownerStillCurrent() || !playbackDescriptorClosed())
            return@withContext Report(emptyList())
        val root = journalRoot
        val matches = MetadataIndexReconciliationReceipt.list(root).mapNotNull { file ->
            val receipt = MetadataIndexReconciliationReceipt.read(file) ?: return@mapNotNull null
            if (receipt.sourceId == sourceId && receipt.trackId == trackId) file else null
        }
        Report(matches.map { reconcileFile(it, ownerStillCurrent, playbackDescriptorClosed) })
    }

    suspend fun reconcile(
        transactionId: String,
        ownerStillCurrent: () -> Boolean,
        playbackDescriptorClosed: () -> Boolean,
    ): Outcome? = withContext(Dispatchers.IO) {
        if (!ownerStillCurrent() || !playbackDescriptorClosed()) return@withContext null
        val root = journalRoot
        val file = MetadataIndexReconciliationReceipt.file(root, transactionId)
        when {
            !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) -> null
            Files.isSymbolicLink(file.toPath()) -> Outcome.CorruptReceiptKept(file.name)
            else -> reconcileFile(file, ownerStillCurrent, playbackDescriptorClosed)
        }
    }

    private val journalRoot: File get() = File(
        context.noBackupFilesDir, "michi-metadata-editor-v1")

    private suspend fun reconcileFile(
        file: File,
        ownerStillCurrent: () -> Boolean,
        playbackDescriptorClosed: () -> Boolean,
    ): Outcome {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile ||
            file.length() !in 1..MetadataIndexReconciliationReceipt.MAX_FILE_BYTES)
            return Outcome.CorruptReceiptKept(file.name)
        val receipt = MetadataIndexReconciliationReceipt.read(file)
            ?: return Outcome.CorruptReceiptKept(file.name)
        val transactionId = receipt.transactionId
        // V1 is readable but cannot authorize CAS: it has no exact owner token or
        // pre-publication row identity. The file is retained, never deleted.
        if (!receipt.actionable)
            return Outcome.RecoveryRequired(transactionId, "RECEIPT_V1_NOT_ACTIONABLE")
        val expectedIndex = receipt.expectedIndex()
            ?: return Outcome.RecoveryRequired(transactionId, "INVALID_INDEX_IDENTITY")

        lateinit var entry: MetadataRecoveryJournalV1.Entry
        lateinit var intent: MetadataCommitIntentManifest.Receipt
        lateinit var original: MetadataSnapshot
        lateinit var permit: MetadataWritePermit
        val durable = try {
            journal.withExclusiveSnapshot { inspection ->
                val pending = (inspection as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withExclusiveSnapshot null
                if (pending.id != transactionId ||
                    pending.sourceId != receipt.sourceId ||
                    pending.trackId != receipt.trackId ||
                    pending.uri != receipt.uri ||
                    pending.originalSha256 != receipt.originalSha256 ||
                    pending.stageSha256 != receipt.candidateSha256 ||
                    pending.stageBytes != receipt.candidateBytes ||
                    pending.format != receipt.format ||
                    pending.expectedBytes != receipt.previousSize)
                    return@withExclusiveSnapshot null
                if (pending.phase != MetadataRecoveryPhase.POSTCHECK_VERIFIED)
                    return@withExclusiveSnapshot null
                if (!journal.privateArtifactsIntact(pending))
                    return@withExclusiveSnapshot null
                val present = (intents.inspect(pending) as?
                    MetadataCommitIntentStore.Inspection.Present)?.receipt
                    ?: return@withExclusiveSnapshot null
                if (!MetadataCommitIntentManifest.valid(present) ||
                    present.transactionId != pending.id ||
                    present.originalSha256 != pending.originalSha256 ||
                    present.candidateSha256 != pending.stageSha256 ||
                    present.originalBytes != pending.expectedBytes ||
                    present.candidateBytes != pending.stageBytes ||
                    present.container.name != pending.format)
                    return@withExclusiveSnapshot null
                if (present.ownerSha256 !=
                    MetadataPrivateSessionIdentity.digest(MetadataTarget(
                        pending.sourceId, pending.trackId, pending.uri,
                        receipt.ownerEvidenceToken)))
                    return@withExclusiveSnapshot null
                // The backup is parsed and cross-checked against the durable
                // "before" digests before any Room decision.
                val target = MetadataTarget(pending.sourceId, pending.trackId,
                    pending.uri, receipt.ownerEvidenceToken)
                val snapshot = sourceGate.snapshot(journal.backup(pending), target)
                    ?: return@withExclusiveSnapshot null
                for (change in present.changes) {
                    val values = when (val observation = snapshot.fieldObservations[change.field]) {
                        is MetadataFieldObservation.Present -> observation.values
                        MetadataFieldObservation.VerifiedAbsent -> emptyList()
                        else -> return@withExclusiveSnapshot null
                    }
                    if (MetadataCommitIntentManifest.valuesDigest(values) !=
                        change.beforeSha256)
                        return@withExclusiveSnapshot null
                }
                val chosenPermit = permitFor(pending, present)
                    ?: return@withExclusiveSnapshot null
                entry = pending
                intent = present
                original = snapshot
                permit = chosenPermit
                true
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { null }
        if (durable?.value != true)
            return Outcome.JournalUnavailableKept(transactionId)

        // Reopen the provider source after the lock. The receipt and the journal
        // are never enough on their own.
        val exact = try { sourceGate.exactCandidate(entry) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { false }
        if (!exact) return Outcome.RecoveryRequired(transactionId, "SOURCE_NOT_CANDIDATE")

        val row = try { dao.getTrackBySource(receipt.sourceId, receipt.trackId) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return Outcome.WaitingForIndexedRow(transactionId) }
            ?: return Outcome.WaitingForIndexedRow(transactionId)

        if (matchesPreWriteIdentity(row, receipt, expectedIndex)) {
            val applied = try {
                publisher.publish(PublishRequest(entry, intent, original, expectedIndex,
                    permit, ownerStillCurrent, playbackDescriptorClosed))
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { false }
            if (applied) return finishAfterIndexed(entry, file, durable.generation)
            // The CAS refused. Reclassify the exact live row before deciding.
            val after = try { dao.getTrackBySource(receipt.sourceId, receipt.trackId) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                ?: return Outcome.WaitingForIndexedRow(transactionId)
            if (matchesApplied(after, receipt, intent, sourceGate.parseSource(entry)
                    ?: return Outcome.RecoveryRequired(transactionId, "SOURCE_PARSE_UNAVAILABLE")))
                return finishApplied(entry, file, durable.generation)
            if (matchesPreWriteIdentity(after, receipt, expectedIndex))
                return Outcome.RecoveryRequired(transactionId, "CAS_REFUSED")
            return Outcome.ConflictKept(transactionId)
        }

        val parsed = sourceGate.parseSource(entry)
            ?: return Outcome.RecoveryRequired(transactionId, "SOURCE_PARSE_UNAVAILABLE")
        if (matchesApplied(row, receipt, intent, parsed)) {
            if (!journal.isUnchanged(durable.generation, entry))
                return Outcome.ConflictKept(transactionId)
            return finishApplied(entry, file, durable.generation)
        }
        return Outcome.ConflictKept(transactionId)
    }

    private suspend fun finishAfterIndexed(
        entry: MetadataRecoveryJournalV1.Entry,
        receiptFile: File,
        generation: MetadataRecoveryJournalV1.Generation,
    ): Outcome {
        if (!journal.isUnchanged(generation, entry))
            return Outcome.ConflictKept(entry.id)
        if (!journal.completeVerified(entry.id))
            return Outcome.RecoveryRequired(entry.id, "INDEXED_CLEANUP_PENDING")
        return if (!Files.isSymbolicLink(receiptFile.toPath()) && receiptFile.delete())
            Outcome.Indexed(entry.id)
        else Outcome.RecoveryRequired(entry.id, "RECEIPT_CLEANUP_PENDING")
    }

    private suspend fun finishApplied(
        entry: MetadataRecoveryJournalV1.Entry,
        receiptFile: File,
        generation: MetadataRecoveryJournalV1.Generation,
    ): Outcome {
        if (!journal.isUnchanged(generation, entry))
            return Outcome.ConflictKept(entry.id)
        if (!journal.completeVerified(entry.id))
            return Outcome.RecoveryRequired(entry.id, "INDEXED_CLEANUP_PENDING")
        return if (!Files.isSymbolicLink(receiptFile.toPath()) && receiptFile.delete())
            Outcome.AlreadyAppliedAndCleaned(entry.id)
        else Outcome.RecoveryRequired(entry.id, "RECEIPT_CLEANUP_PENDING")
    }

    private fun permitFor(
        entry: MetadataRecoveryJournalV1.Entry,
        intent: MetadataCommitIntentManifest.Receipt,
    ): MetadataWritePermit? {
        val container = try { MetadataAudioContainer.valueOf(entry.format) }
            catch (_: Exception) { return null }
        val revision = when (container) {
            MetadataAudioContainer.ID3_V23 -> 3
            MetadataAudioContainer.ID3_V24 -> 4
            else -> return null
        }
        if (intent.container != container) return null
        val fields = intent.changes.mapTo(linkedSetOf()) { it.field }
        return MetadataWritePermit(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
            profileId = MediaStoreMp3TextProfile.ID,
            evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
            container = entry.format, revision = revision, fields = fields,
            explicitUserAction = true,
        ).takeIf { it.matches(entry) }
    }

    private fun matchesPreWriteIdentity(
        row: CachedTrack,
        receipt: MetadataIndexReconciliationReceipt,
        expected: MetadataIndexPublicationPolicy.IndexIdentity,
    ): Boolean {
        if (row.sourceId != receipt.sourceId || row.id != receipt.trackId ||
            row.filepath != receipt.uri || row.size != receipt.previousSize)
            return false
        if (expected.version > 0L)
            return row.verifiedAt == expected.version &&
                row.verifiedSha256 == expected.observedSourceSha256 &&
                row.verifiedSize == row.size && row.verifiedAlgorithm.lowercase() == "sha256" &&
                HEX.matches(row.verifiedSha256)
        return row.verifiedAt == 0L && row.verifiedSha256.isEmpty() &&
            row.verifiedSize == 0L && row.verifiedAlgorithm.isEmpty()
    }

    private fun matchesApplied(
        row: CachedTrack,
        receipt: MetadataIndexReconciliationReceipt,
        intent: MetadataCommitIntentManifest.Receipt,
        parsed: MetadataFieldReadReport,
    ): Boolean {
        if (row.sourceId != receipt.sourceId || row.id != receipt.trackId ||
            row.filepath != receipt.uri || row.size != receipt.candidateBytes)
            return false
        if (row.verifiedAt != 0L || row.verifiedSha256.isNotEmpty() ||
            row.verifiedSize != 0L || row.verifiedAlgorithm.isNotEmpty())
            return false
        return intent.changes.all { change ->
            val after = when (val observation = parsed.observation(change.field)) {
                is MetadataFieldObservation.Present -> observation.values
                MetadataFieldObservation.VerifiedAbsent -> emptyList()
                else -> return false
            }
            rowValues(row, change.field) == after
        }
    }

    private fun rowValues(row: CachedTrack, field: MetadataField): List<String>? =
        when (field) {
            MetadataField.TITLE -> row.title.asValues()
            MetadataField.ARTIST -> row.artist.asValues()
            MetadataField.ALBUM -> row.album.asValues()
            MetadataField.ALBUM_ARTIST -> row.albumArtist.asValues()
            MetadataField.GENRE -> row.genre.asValues()
            MetadataField.TRACK_NUMBER ->
                if (row.trackNumber == 0) emptyList()
                else listOf(row.trackNumber.toString())
            MetadataField.RELEASE_DATE -> if (row.year == 0) emptyList()
                else listOf(row.year.toString())
            else -> null
        }

    private fun String.asValues(): List<String> = if (isBlank()) emptyList() else listOf(this)

    private companion object {
        val HEX = Regex("[a-f0-9]{64}")
    }

    private class AndroidSourceGate(context: Context) : SourceGate {
        private val bytes = MetadataCurrentBytesReader(context)
        private val resolver = context.contentResolver

        override fun exactCandidate(entry: MetadataRecoveryJournalV1.Entry): Boolean =
            bytes.readCommittedCandidate(entry.sourceId, entry.uri,
                entry.stageBytes, entry.stageSha256) is
                MetadataCurrentBytesReader.PostCommitRead.Exact

        override fun parseSource(
            entry: MetadataRecoveryJournalV1.Entry,
        ): MetadataFieldReadReport? = try {
            val descriptor = resolver.openFileDescriptor(Uri.parse(entry.uri), "r")
                ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                MetadataColdFieldParser.observe(stream.channel)
            }
        } catch (_: Exception) { null }

        override fun snapshot(backup: File, target: MetadataTarget): MetadataSnapshot? = try {
            if (Files.isSymbolicLink(backup.toPath()) ||
                !Files.isRegularFile(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) null
            else {
                val report = FileChannel.open(backup.toPath(), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS).use(MetadataColdFieldParser::observe)
                report.toSnapshot(target)
            }
        } catch (_: Exception) { null }
    }

    private class CoordinatorPublisher(
        context: Context,
        database: MichiDatabase,
    ) : Publisher {
        private val coordinator = MetadataFinalizedIndexPublicationCoordinator(context, database)
        override suspend fun publish(request: PublishRequest): Boolean =
            when (coordinator.reconcile(
                transactionId = request.entry.id,
                original = request.original,
                expectedIndex = request.expectedIndex,
                explicitUserAction = true,
                ownerStillCurrent = request.ownerStillCurrent,
                playbackDescriptorClosed = request.playbackDescriptorClosed,
                permit = request.permit,
            )) {
                is MetadataFinalizedIndexPublicationCoordinator.Result.IndexedOnly -> true
                is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredGenreIndexed -> true
                is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleIndexed -> true
                is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleAndGenreIndexed -> true
                else -> false
            }
    }
}

/**
 * Build a snapshot only from fields the cold parser proved present or absent.
 * An unverifiable field is never promoted to an empty value.
 */
internal fun MetadataFieldReadReport.toSnapshot(target: MetadataTarget): MetadataSnapshot {
    val known = linkedMapOf<MetadataField, List<String>>()
    val verified = linkedSetOf<MetadataField>()
    for ((field, observation) in observations) {
        when (observation) {
            is MetadataFieldObservation.Present -> {
                known[field] = observation.values.toList()
                verified += field
            }
            MetadataFieldObservation.VerifiedAbsent -> verified += field
            else -> Unit
        }
    }
    require(verified.isNotEmpty()) { "NO_VERIFIED_FIELDS" }
    return MetadataSnapshot(
        target = target,
        tagsVerified = true,
        known = known,
        verifiedFields = verified,
        fieldObservations = observations,
    )
}
