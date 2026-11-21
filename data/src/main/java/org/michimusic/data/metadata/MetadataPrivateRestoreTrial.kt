package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataPhysicalOutcomePolicy
import org.michimusic.core.metadata.MetadataPrivateTrialPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase

/**
 * ME-07B. Exercise real truncate/write/fsync/restore on a PRIVATE disposable file.
 * Input bytes are obtained from ME-05's immutable backup/stage. There are no
 * ContentResolver write APIs, and NO method accepts a provider URI as an output.
 * This is not a production source writer and cannot certify MediaStore or SAF.
 * All public actions require explicit initiation; none is scheduled or polled.
 */
internal class MetadataPrivateRestoreTrial(context: Context) {
    private val privateDir = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
    private val trialDir = File(context.noBackupFilesDir, "michi-metadata-private-trials-v1")
    private val journal = MetadataRecoveryJournalV1(privateDir)
    private val digestPattern = Regex("[a-f0-9]{64}")

    data class Target(val sourceId: String, val trackId: String, val uri: String,
        val transactionId: String)

    sealed interface Outcome {
        data object PrivateTrialDiscarded : Outcome
        data class TrialFinished(val state: MetadataPrivateTrialPolicy.State) : Outcome
        data class SimulatedCrashPoint(val point: MetadataPrivateTrialPolicy.CutPoint) : Outcome
        data class Refused(val reason: Reason) : Outcome
    }
    enum class Reason {
        NOT_USER_REQUESTED, NOT_EXACT_TARGET, JOURNAL_UNREADABLE, NOT_STAGE_READY,
        PRIVATE_BACKUP_INVALID, TRIAL_ALREADY_EXISTS, NO_TRIAL, PRIVATE_PATH_UNSAFE,
        TRIAL_TOO_LARGE, PRIVATE_STORAGE_INSUFFICIENT, PRIVATE_WRITE_FAILED,
        RESTORE_REQUIRES_CONFIRMATION, PRIVATE_READBACK_FAILED,
    }

    private class InjectedCut(val point: MetadataPrivateTrialPolicy.CutPoint) : Exception()

    private fun matches(entry: MetadataRecoveryJournalV1.Entry, target: Target): Boolean =
        entry.id == target.transactionId && entry.sourceId == target.sourceId &&
            entry.trackId == target.trackId && entry.uri == target.uri &&
            target.trackId.isNotBlank() && target.uri.startsWith("content://")

    private fun directoryReady(): Boolean = try {
        val parent = trialDir.parentFile
        if (Files.isSymbolicLink(trialDir.toPath()) ||
            (parent != null && Files.isSymbolicLink(parent.toPath()))) false
        else {
            Files.createDirectories(trialDir.toPath())
            Files.isDirectory(trialDir.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
    } catch (_: Exception) { false }

    private fun destination(entry: MetadataRecoveryJournalV1.Entry): File =
        File(trialDir, "trial-${entry.id}.bin") // ID validated by journal decoder

    private fun safePrivateFile(file: File): Boolean =
        !Files.isSymbolicLink(file.toPath()) &&
            (!file.exists() || Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))

    private fun readObserved(file: File, maximumBytes: Long): MetadataPhysicalOutcomePolicy.Observed? {
        if (!safePrivateFile(file) || !file.isFile || file.length() !in 1..maximumBytes) return null
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (true) {
                buffer.clear()
                val n = ch.read(buffer)
                if (n < 0) break
                if (n == 0) return null
                size += n
                if (size > maximumBytes) return null
                digest.update(buffer.array(), 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return MetadataPhysicalOutcomePolicy.Observed(size, hex)
    }

    private fun classify(entry: MetadataRecoveryJournalV1.Entry, trial: File): MetadataPrivateTrialPolicy.State {
        if (!trial.exists()) return MetadataPrivateTrialPolicy.State.NOT_STARTED
        if (!safePrivateFile(trial) || !trial.isFile)
            return MetadataPrivateTrialPolicy.State.PRIVATE_UNREADABLE
        // A crash immediately after truncation is an UNKNOWN private result,
        // not a reason to refuse restoring this disposable copy from backup.
        if (trial.length() == 0L) return MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN
        val expected = MetadataPhysicalOutcomePolicy.Expected(entry.expectedBytes,
            entry.originalSha256, entry.stageBytes, entry.stageSha256)
        val observed = readObserved(trial, maxOf(entry.expectedBytes, entry.stageBytes) + 65536)
        return when (MetadataPhysicalOutcomePolicy.classify(expected, observed)) {
            MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT ->
                MetadataPrivateTrialPolicy.State.ORIGINAL_PRIVATE_INTACT
            MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED ->
                MetadataPrivateTrialPolicy.State.CANDIDATE_PRIVATE_INTACT
            MetadataPhysicalOutcomePolicy.State.UNKNOWN_BYTES ->
                MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN
            else -> MetadataPrivateTrialPolicy.State.PRIVATE_UNREADABLE
        }
    }

    private fun copyIntoNew(source: File, dest: File, expectedBytes: Long, expectedSha: String): Boolean {
        if (!safePrivateFile(source) || !source.isFile || !safePrivateFile(dest) || dest.exists() ||
            !digestPattern.matches(expectedSha) || expectedBytes <= 0L) return false
        FileChannel.open(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileChannel.open(dest.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW,
                LinkOption.NOFOLLOW_LINKS).use { output ->
                val chunk = ByteBuffer.allocate(64 * 1024)
                while (true) {
                    chunk.clear()
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n == 0) return false
                    chunk.flip()
                    while (chunk.hasRemaining()) output.write(chunk)
                }
                output.force(true)
            }
        }
        return readObserved(dest, expectedBytes) ==
            MetadataPhysicalOutcomePolicy.Observed(expectedBytes, expectedSha)
    }

    private fun overwriteTrial(source: File, target: File, cut: MetadataPrivateTrialPolicy.CutPoint) {
        if (cut == MetadataPrivateTrialPolicy.CutPoint.BEFORE_TRUNCATE) throw InjectedCut(cut)
        FileChannel.open(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileChannel.open(target.toPath(), StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { output ->
                if (cut == MetadataPrivateTrialPolicy.CutPoint.AFTER_TRUNCATE) throw InjectedCut(cut)
                val chunk = ByteBuffer.allocate(64 * 1024)
                var first = true
                while (true) {
                    chunk.clear()
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n == 0) error("PRIVATE_STAGE_STALLED")
                    chunk.flip()
                    while (chunk.hasRemaining()) output.write(chunk)
                    if (first && cut == MetadataPrivateTrialPolicy.CutPoint.AFTER_FIRST_CHUNK)
                        throw InjectedCut(cut)
                    first = false
                }
                output.force(true)
            }
        }
        if (cut == MetadataPrivateTrialPolicy.CutPoint.AFTER_FSYNC) throw InjectedCut(cut)
    }

    /**
     * Re-enters the exclusion without mutating the journal and reports whether the
     * durable record is still exactly [entry] at [generation]. A verdict derived from
     * private bytes is withheld when the transaction moved underneath it.
     */
    private suspend fun stillCurrent(generation: MetadataRecoveryJournalV1.Generation,
        entry: MetadataRecoveryJournalV1.Entry): Boolean = journal.isUnchanged(generation, entry)

    /** Exactly one private trial per staged transaction. A surviving trial requires inspection. */
    suspend fun run(target: Target, explicitUserAction: Boolean,
        cut: MetadataPrivateTrialPolicy.CutPoint = MetadataPrivateTrialPolicy.CutPoint.NONE,
    ): Outcome = withContext(Dispatchers.IO) {
        if (!explicitUserAction) return@withContext Outcome.Refused(Reason.NOT_USER_REQUESTED)
        try {
            // Phase 1: brief exclusion, durable identity only. Full-file hashing and
            // copies below run without the journal lock.
            lateinit var entry: MetadataRecoveryJournalV1.Entry
            val snapshot = journal.withExclusiveSnapshot { inspection ->
                val pending = (inspection as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withExclusiveSnapshot Outcome.Refused(Reason.JOURNAL_UNREADABLE)
                if (!matches(pending, target))
                    return@withExclusiveSnapshot Outcome.Refused(Reason.NOT_EXACT_TARGET)
                if (pending.phase != MetadataRecoveryPhase.STAGE_READY)
                    return@withExclusiveSnapshot Outcome.Refused(Reason.NOT_STAGE_READY)
                entry = pending
                null
            }
            snapshot.value?.let { return@withContext it }
            if (!journal.privateArtifactsIntact(entry))
                return@withContext Outcome.Refused(Reason.PRIVATE_BACKUP_INVALID)
            if (!directoryReady()) return@withContext Outcome.Refused(Reason.PRIVATE_PATH_UNSAFE)
            val trial = destination(entry)
            if (!safePrivateFile(trial)) return@withContext Outcome.Refused(Reason.PRIVATE_PATH_UNSAFE)
            if (trial.exists()) return@withContext Outcome.Refused(Reason.TRIAL_ALREADY_EXISTS)
            if (entry.expectedBytes !in 1..(1024L*1024*1024) ||
                entry.stageBytes !in 1..(1024L*1024*1024+128L*1024*1024))
                return@withContext Outcome.Refused(Reason.TRIAL_TOO_LARGE)
            if (trialDir.usableSpace < maxOf(entry.expectedBytes,entry.stageBytes) + 16L * 1024 * 1024)
                return@withContext Outcome.Refused(Reason.PRIVATE_STORAGE_INSUFFICIENT)
            if (!copyIntoNew(journal.backup(entry), trial, entry.expectedBytes, entry.originalSha256))
                return@withContext Outcome.Refused(Reason.PRIVATE_WRITE_FAILED)
            val simulated = try {
                overwriteTrial(journal.stage(entry), trial, cut)
                null
            } catch (cutPoint: InjectedCut) { cutPoint.point }
            // The trial bytes are private diagnostics, not a write grant: do not report
            // them for a transaction that was discarded or advanced meanwhile.
            if (!stillCurrent(snapshot.generation, entry))
                return@withContext Outcome.Refused(Reason.JOURNAL_UNREADABLE)
            if (simulated != null) Outcome.SimulatedCrashPoint(simulated)
            else Outcome.TrialFinished(classify(entry, trial))
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Outcome.Refused(Reason.PRIVATE_WRITE_FAILED) }
    }

    suspend fun inspect(target: Target, explicitUserAction: Boolean): MetadataPrivateTrialPolicy.State =
        withContext(Dispatchers.IO) {
            if (!explicitUserAction) return@withContext MetadataPrivateTrialPolicy.State.JOURNAL_UNAVAILABLE
            try {
                lateinit var entry: MetadataRecoveryJournalV1.Entry
                val snapshot = journal.withExclusiveSnapshot { inspection ->
                    val pending = (inspection as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                        ?: return@withExclusiveSnapshot MetadataPrivateTrialPolicy.State.JOURNAL_UNAVAILABLE
                    if (!matches(pending, target))
                        return@withExclusiveSnapshot MetadataPrivateTrialPolicy.State.WRONG_TARGET
                    if (pending.phase != MetadataRecoveryPhase.STAGE_READY)
                        return@withExclusiveSnapshot MetadataPrivateTrialPolicy.State.NOT_PREPARED
                    entry = pending
                    null
                }
                snapshot.value?.let { return@withContext it }
                if (!journal.privateArtifactsIntact(entry))
                    return@withContext MetadataPrivateTrialPolicy.State.BACKUP_INVALID
                if (!directoryReady())
                    return@withContext MetadataPrivateTrialPolicy.State.PRIVATE_UNREADABLE
                val state = classify(entry, destination(entry))
                if (!stillCurrent(snapshot.generation, entry))
                    return@withContext MetadataPrivateTrialPolicy.State.JOURNAL_UNAVAILABLE
                state
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { MetadataPrivateTrialPolicy.State.PRIVATE_UNREADABLE }
        }

    /** Restore only the PRIVATE trial file through a verified temporary copy. */
    suspend fun restorePrivate(target: Target, explicitUserAction: Boolean,
        secondConfirmation: Boolean): Outcome = withContext(Dispatchers.IO) {
        if (!explicitUserAction || !secondConfirmation)
            return@withContext Outcome.Refused(Reason.RESTORE_REQUIRES_CONFIRMATION)
        try {
            lateinit var entry: MetadataRecoveryJournalV1.Entry
            val snapshot = journal.withExclusiveSnapshot { inspection ->
                val pending = (inspection as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withExclusiveSnapshot Outcome.Refused(Reason.JOURNAL_UNREADABLE)
                if (!matches(pending,target))
                    return@withExclusiveSnapshot Outcome.Refused(Reason.NOT_EXACT_TARGET)
                if (pending.phase != MetadataRecoveryPhase.STAGE_READY)
                    return@withExclusiveSnapshot Outcome.Refused(Reason.NOT_STAGE_READY)
                entry = pending
                null
            }
            snapshot.value?.let { return@withContext it }
            if (!journal.privateArtifactsIntact(entry))
                return@withContext Outcome.Refused(Reason.PRIVATE_BACKUP_INVALID)
            if (!directoryReady()) return@withContext Outcome.Refused(Reason.PRIVATE_PATH_UNSAFE)
            val trial=destination(entry)
            if (!safePrivateFile(trial)) return@withContext Outcome.Refused(Reason.PRIVATE_PATH_UNSAFE)
            val state=classify(entry,trial)
            val decision=MetadataPrivateTrialPolicy.mayRestorePrivateTrial(state,
                explicitUserAction,secondConfirmation,true,true)
            if (decision.restoration == MetadataPrivateTrialPolicy.Restoration.ALREADY_PRIVATE_ORIGINAL)
                return@withContext if (stillCurrent(snapshot.generation,entry))
                    Outcome.TrialFinished(state) else Outcome.Refused(Reason.JOURNAL_UNREADABLE)
            if (decision.restoration != MetadataPrivateTrialPolicy.Restoration.READY_FOR_PRIVATE_RESTORE)
                return@withContext Outcome.Refused(Reason.NO_TRIAL)
            val tmp=File(trialDir,"restore-${entry.id}-${UUID.randomUUID()}.tmp")
            try {
                if (!copyIntoNew(journal.backup(entry),tmp,entry.expectedBytes,entry.originalSha256))
                    return@withContext Outcome.Refused(Reason.PRIVATE_WRITE_FAILED)
                // Destructive step: the atomic move publishes only if the durable
                // record is still the observed generation and entry.
                val moved=journal.publishIfUnchanged(snapshot.generation,entry) {
                    Files.move(tmp.toPath(),trial.toPath(),StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                    true
                }
                if (moved is MetadataRecoveryJournalV1.Publication.Stale)
                    return@withContext Outcome.Refused(Reason.JOURNAL_UNREADABLE)
                val result=classify(entry,trial)
                if (result != MetadataPrivateTrialPolicy.State.ORIGINAL_PRIVATE_INTACT)
                    return@withContext Outcome.Refused(Reason.PRIVATE_READBACK_FAILED)
                if (!stillCurrent(snapshot.generation,entry))
                    return@withContext Outcome.Refused(Reason.JOURNAL_UNREADABLE)
                Outcome.TrialFinished(result)
            } finally { tmp.delete() }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Outcome.Refused(Reason.PRIVATE_WRITE_FAILED) }
    }
    /** Delete ONLY disposable private-trial bytes after exact-track second confirmation. */
    suspend fun discardPrivateTrial(target: Target, explicitUserAction: Boolean,
        secondConfirmation: Boolean): Outcome = withContext(Dispatchers.IO) {
        if (!explicitUserAction || !secondConfirmation)
            return@withContext Outcome.Refused(Reason.RESTORE_REQUIRES_CONFIRMATION)
        try {
            lateinit var entry: MetadataRecoveryJournalV1.Entry
            val snapshot = journal.withExclusiveSnapshot { inspection ->
                val pending=(inspection as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withExclusiveSnapshot Outcome.Refused(Reason.JOURNAL_UNREADABLE)
                if (!matches(pending,target))
                    return@withExclusiveSnapshot Outcome.Refused(Reason.NOT_EXACT_TARGET)
                if (pending.phase != MetadataRecoveryPhase.STAGE_READY)
                    return@withExclusiveSnapshot Outcome.Refused(Reason.NOT_STAGE_READY)
                entry = pending
                null
            }
            snapshot.value?.let { return@withContext it }
            if (!journal.privateArtifactsIntact(entry))
                return@withContext Outcome.Refused(Reason.PRIVATE_BACKUP_INVALID)
            if (!directoryReady()) return@withContext Outcome.Refused(Reason.PRIVATE_PATH_UNSAFE)
            val trial=destination(entry)
            if (!safePrivateFile(trial)) return@withContext Outcome.Refused(Reason.PRIVATE_PATH_UNSAFE)
            if (!trial.exists()) return@withContext Outcome.Refused(Reason.NO_TRIAL)
            // The delete is published only while the durable record is unchanged.
            val deleted=journal.publishIfUnchanged(snapshot.generation,entry) { trial.delete() }
            when (deleted) {
                is MetadataRecoveryJournalV1.Publication.Stale ->
                    Outcome.Refused(Reason.JOURNAL_UNREADABLE)
                is MetadataRecoveryJournalV1.Publication.Published ->
                    if (deleted.value) Outcome.PrivateTrialDiscarded
                    else Outcome.Refused(Reason.PRIVATE_WRITE_FAILED)
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Outcome.Refused(Reason.PRIVATE_WRITE_FAILED) }
    }

}
