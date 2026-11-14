package org.michimusic.data.intelligence

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.intelligence.MichiGenreTagRewriter
import org.michimusic.data.repository.EmbeddedTagFields
import org.michimusic.data.repository.EmbeddedTagFieldsReader
import org.michimusic.data.repository.EmbeddedTagReadPolicy
import org.michimusic.data.repository.UriEmbeddedTags
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * MI-24: guarded single-track write with crash-persistent, non-backed-up recovery journal.
 * The UI must first obtain user/Android write approval and an owner-scoped ticket.
 * This is NOT a general SAF transaction: provider atomicity is unproven, hence UI writes
 * stay globally disabled until MediaStore/SAF device certification. No scheduled work.
 */
class MichiGenreTagWriteGateway(private val context: Context) {
    data class Ticket(
        val sourceId: String, val trackId: String, val uri: String,
        val expectedGenres: List<String>, val expectedTitle: String,
        val expectedArtist: String, val expectedAlbum: String,
        val expectedSize: Long = 0L,
    )
    sealed interface Result {
        data class SavedVerified(val values: List<String>, val format: String) : Result
        data class Rejected(val code: String) : Result
        data class Failed(val code: String, val restorationAttempted: Boolean) : Result
    }
    sealed interface RecoveryStatus {
        data object Clear : RecoveryStatus
        data class Pending(val sourceId: String, val trackId: String,
            val uri: String, val phase: String) : RecoveryStatus
        data class Blocked(val code: String) : RecoveryStatus
    }
    sealed interface RecoveryResult {
        data object RestoredAndVerified : RecoveryResult
        data object OriginalAlreadyVerified : RecoveryResult
        data object CleanupCompleted : RecoveryResult
        data object NoOriginalMutation : RecoveryResult
        data class Rejected(
            val code: String,
            /** Only for user-visible second confirmation; never a permanent permission. */
            val currentBytes: Long? = null,
            val currentSha256: String? = null,
        ) : RecoveryResult
        data class Failed(val code: String) : RecoveryResult
    }

    private val journal by lazy {
        MichiGenreRecoveryJournal(File(context.noBackupFilesDir, "michi-mi23-genre-recovery"))
    }
    private val maxCopy = 1024L * 1024 * 1024

    suspend fun pendingRecovery(): RecoveryStatus = withContext(Dispatchers.IO) {
        when (val result = journal.inspect()) {
            MichiGenreRecoveryJournal.Inspection.Clear -> RecoveryStatus.Clear
            is MichiGenreRecoveryJournal.Inspection.Pending -> RecoveryStatus.Pending(
                result.entry.sourceId, result.entry.trackId, result.entry.uri, result.entry.phase.name)
            is MichiGenreRecoveryJournal.Inspection.Blocked -> RecoveryStatus.Blocked(result.reason)
        }
    }

    /**
     * Never called automatically at app start. Requires a SEPARATE explicit user action,
     * the SAME owner/path and a current write grant; verifies original backup digest
     * before restoring and verifies provider bytes after restoring.
     */
    suspend fun restorePending(
        ticket: Ticket, userConfirmed: Boolean, writeGrantConfirmed: Boolean,
        stillOwns: () -> Boolean, unknownBytesOverwriteConfirmed: Boolean = false,
        expectedUnknownBytes: Long? = null, expectedUnknownSha256: String? = null,
    ): RecoveryResult = withContext(Dispatchers.IO) {
        if (!userConfirmed || !writeGrantConfirmed || ticket.trackId.isBlank() ||
            ticket.uri.isBlank() || !stillOwns())
            return@withContext RecoveryResult.Rejected("RECOVERY_NOT_AUTHORIZED")
        writeMutex.withLock {
            try { journal.withExclusiveProcessLock {
            val current = journal.inspect()
            val entry = (current as? MichiGenreRecoveryJournal.Inspection.Pending)?.entry
                ?: return@withExclusiveProcessLock RecoveryResult.Rejected("NO_READABLE_RECOVERY")
            if (!userConfirmed || !writeGrantConfirmed || !stillOwns() ||
                ticket.sourceId != entry.sourceId || ticket.trackId != entry.trackId ||
                ticket.uri != entry.uri)
                return@withExclusiveProcessLock RecoveryResult.Rejected("RECOVERY_NOT_AUTHORIZED_OR_OWNER_CHANGED")
            if (entry.phase == MichiGenreRecoveryJournal.Phase.CLEANUP) {
                // The prior operation verified its outcome before entering CLEANUP.
                // No source write or restoration is permitted from this phase.
                return@withExclusiveProcessLock try {
                    journal.clearVerified(entry)
                    RecoveryResult.CleanupCompleted
                } catch (_: Exception) { RecoveryResult.Failed("RECOVERY_CLEANUP_NOT_COMPLETED") }
            }
            if (entry.phase == MichiGenreRecoveryJournal.Phase.SNAPSHOTTING ||
                entry.phase == MichiGenreRecoveryJournal.Phase.BACKUP_READY) {
                // Mutation was never entered: no original bytes have been truncated.
                return@withExclusiveProcessLock try {
                    journal.clearVerified(entry)
                    RecoveryResult.NoOriginalMutation
                } catch (_: Exception) { RecoveryResult.Failed("RECOVERY_RECEIPT_NOT_CLEARED") }
            }
            if (!journal.backupIntegrity(entry))
                return@withExclusiveProcessLock RecoveryResult.Rejected("RECOVERY_BACKUP_HASH_MISMATCH")
            val uri = Uri.parse(entry.uri)
            val liveFingerprint = digestProvider(uri)
            val actual = MichiGenreRecoverySafetyPolicy.classify(entry, liveFingerprint)
            if (actual == MichiGenreRecoverySafetyPolicy.CurrentBytes.UNREADABLE)
                return@withExclusiveProcessLock RecoveryResult.Rejected("CURRENT_FILE_UNREADABLE_KEEP_BACKUP")
            if (unknownBytesOverwriteConfirmed &&
                !MichiGenreRecoverySafetyPolicy.permitsUnknownOverwrite(
                    entry, liveFingerprint, unknownBytesOverwriteConfirmed,
                    expectedUnknownBytes, expectedUnknownSha256))
                return@withExclusiveProcessLock RecoveryResult.Rejected("CURRENT_BYTES_CHANGED_SINCE_CONFIRMATION")
            if (actual == MichiGenreRecoverySafetyPolicy.CurrentBytes.UNKNOWN &&
                !unknownBytesOverwriteConfirmed)
                return@withExclusiveProcessLock RecoveryResult.Rejected(
                    "UNKNOWN_CURRENT_BYTES_REQUIRES_SECOND_CONFIRMATION",
                    liveFingerprint?.first, liveFingerprint?.second)
            try {
                // A previous rollback may already have restored the source before crashing.
                // Do not truncate it twice. Retain evidence until cleanup is durable.
                if (actual == MichiGenreRecoverySafetyPolicy.CurrentBytes.ORIGINAL) {
                    journal.clearVerified(entry)
                    return@withExclusiveProcessLock RecoveryResult.OriginalAlreadyVerified
                }
                if (!stillOwns())
                    return@withExclusiveProcessLock RecoveryResult.Rejected("OWNER_CHANGED_BEFORE_RESTORE")
                restoreOrFail(uri, journal.backupFile, liveFingerprint ?: return@withExclusiveProcessLock RecoveryResult.Rejected("CURRENT_FILE_UNREADABLE_KEEP_BACKUP"))
                val currentDigest = digestProvider(uri)
                if (currentDigest != (entry.originalBytes to entry.originalSha256))
                    return@withExclusiveProcessLock RecoveryResult.Failed("RESTORED_BYTES_NOT_VERIFIED_KEEP_BACKUP")
                journal.clearVerified(entry)
                RecoveryResult.RestoredAndVerified
            } catch (_: Exception) {
                RecoveryResult.Failed("RECOVERY_FAILED_KEEP_BACKUP")
            }
        } } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { RecoveryResult.Failed("RECOVERY_PROCESS_LOCK_UNAVAILABLE") }
        }
    }

    suspend fun write(
        ticket: Ticket, proposed: List<String>, userConfirmed: Boolean,
        replacementConfirmed: Boolean, writeGrantConfirmed: Boolean,
        stillOwns: () -> Boolean,
    ): Result = withContext(Dispatchers.IO) {
        // Denied requests must not even create a private lock file.
        if (!userConfirmed || !writeGrantConfirmed || ticket.trackId.isBlank() ||
            ticket.uri.isBlank() || !stillOwns())
            return@withContext Result.Rejected("NOT_AUTHORIZED")
        writeMutex.withLock {
            try {
                journal.withExclusiveProcessLock {
                    writeLocked(ticket, proposed, userConfirmed, replacementConfirmed,
                        writeGrantConfirmed, stillOwns)
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { Result.Rejected("GENRE_WRITE_PROCESS_LOCK_UNAVAILABLE") }
        }
    }

    private suspend fun writeLocked(
        ticket: Ticket, proposed: List<String>, userConfirmed: Boolean,
        replacementConfirmed: Boolean, writeGrantConfirmed: Boolean,
        stillOwns: () -> Boolean,
    ): Result {
        if (!userConfirmed || !writeGrantConfirmed || ticket.trackId.isBlank() ||
            ticket.uri.isBlank() || !stillOwns()) return Result.Rejected("NOT_AUTHORIZED")
        if (journal.inspect() != MichiGenreRecoveryJournal.Inspection.Clear)
            return Result.Rejected("UNRESOLVED_RECOVERY_REQUIRED")
        val uri = Uri.parse(ticket.uri)
        if (uri.scheme != "content") return Result.Rejected("CONTENT_URI_REQUIRED")
        val reportedSize = reportedSize(uri)
        if (reportedSize == null || reportedSize < 12 || reportedSize > maxCopy ||
            (ticket.expectedSize > 0 && ticket.expectedSize != reportedSize) ||
            context.noBackupFilesDir.usableSpace < reportedSize * 2 + 32L * 1024 * 1024)
            return Result.Rejected("UNKNOWN_OR_CHANGED_SIZE_OR_INSUFFICIENT_SPACE")
        val before = readVerified(uri) ?: return Result.Rejected("CANNOT_VERIFY_TAGS")
        if (!sameIdentity(before, ticket) || !stillOwns())
            return Result.Rejected("SOURCE_OR_TAGS_CHANGED")
        val decision = MichiGenreNormalizationPolicy.decideWrite(
            before.genres, proposed, userConfirmed, replacementConfirmed, stillOwns(), writeGrantConfirmed)
        if (decision !is MichiGenreNormalizationPolicy.Decision.Allowed)
            return Result.Rejected((decision as MichiGenreNormalizationPolicy.Decision.Denied).reason)
        if (before.genres == decision.values)
            return Result.SavedVerified(decision.values, "UNCHANGED")

        var entry: MichiGenreRecoveryJournal.Entry? = null
        var stage: File? = null
        var mutationStarted = false
        var verifiedAndCommitted = false
        try {
            entry = journal.begin(ticket.sourceId, ticket.trackId, ticket.uri)
            val copied = copyUriToBackup(uri, journal.backupFile)
            if (copied != reportedSize || !stillOwns())
                return Result.Rejected("FILE_CHANGED_DURING_SNAPSHOT")
            val originalSha = MichiGenreRecoveryJournal.digest(journal.backupFile)
            entry = journal.backupReady(entry, copied, originalSha)
            // Exact bytes may change without a size or visible tag change: reject it.
            if (reportedSize(uri) != copied || digestProvider(uri) != (copied to originalSha) ||
                !sameIdentity(readVerified(uri), ticket) || !stillOwns())
                return Result.Rejected("PROVIDER_CHANGED_AFTER_SNAPSHOT")

            stage = File.createTempFile("mi23-staged-", ".tmp", context.cacheDir)
            if (!stage.delete()) return Result.Rejected("CANNOT_CREATE_STAGING")
            val rewritten = MichiGenreTagRewriter.rewrite(journal.backupFile, stage, decision.values)
            val stagedFields = FileInputStream(stage).use { EmbeddedTagFieldsReader.read(it) }
            if (stagedFields == null || stagedFields.genres != decision.values ||
                stagedFields.copy(genres = before.genres) != before)
                return Result.Rejected("STAGING_TAGS_NOT_EQUIVALENT")
            val stageSha = MichiGenreRecoveryJournal.digest(stage)
            if (reportedSize(uri) != copied || digestProvider(uri) != (copied to originalSha) ||
                !sameIdentity(readVerified(uri), ticket) || !stillOwns())
                return Result.Rejected("SOURCE_CHANGED_BEFORE_MUTATION")
            entry = journal.mutationStarted(entry, stage, stageSha)
            // Once MUTATING is committed to noBackup, a crash must leave a recovery receipt.
            mutationStarted = true
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                if (!stillOwns() || digestProvider(uri) != (copied to originalSha))
                    throw IOException("SOURCE_CHANGED_AFTER_WRITE_DESCRIPTOR_OPEN")
                replaceDescriptor(descriptor, stage, expectedCurrent = copied to originalSha)
            } ?: throw IOException("NOT_WRITABLE")
            val after = readVerified(uri)
            val providerHash = digestProvider(uri)
            if (after == null || after.genres != decision.values ||
                after.copy(genres = before.genres) != before ||
                providerHash != (stage.length() to stageSha) || !stillOwns())
                throw IOException("POST_WRITE_VERIFICATION_FAILED")
            entry = journal.verified(entry)
            verifiedAndCommitted = true
            journal.clearVerified(entry)
            entry = null
            return Result.SavedVerified(decision.values, rewritten.format)
        } catch (cancel: CancellationException) {
            // Cancellation cannot strand a mutation without trying an uncancellable restore.
            if (mutationStarted && !verifiedAndCommitted && entry != null) {
                withContext(NonCancellable) { restoreAndVerify(entry, uri) }
            }
            throw cancel
        } catch (_: Exception) {
            val current = entry
            if (mutationStarted && !verifiedAndCommitted && current != null) {
                return when (withContext(NonCancellable) { restoreAndVerify(current, uri) }) {
                    RollbackStatus.ORIGINAL_INTACT -> Result.Failed("WRITE_FAILED_ORIGINAL_INTACT", false)
                    RollbackStatus.RESTORED_VERIFIED ->
                        Result.Failed("WRITE_FAILED_RESTORED_AND_HASH_VERIFIED", true)
                    RollbackStatus.RESTORE_UNVERIFIED ->
                        Result.Failed("WRITE_FAILED_RESTORATION_UNVERIFIED_KEEP_BACKUP", true)
                    RollbackStatus.PENDING_NO_RESTORE ->
                        Result.Failed("WRITE_FAILED_PENDING_EXPLICIT_RECOVERY", false)
                }
            }
            return Result.Failed(if (verifiedAndCommitted) "WRITE_VERIFIED_RECEIPT_CLEANUP_PENDING"
                else "WRITER_REJECTED_BEFORE_MUTATION", false)
        } finally {
            stage?.delete()
            // If never MUTATING, no remote bytes were modified: clean journal if possible.
            val pending = entry
            if (!mutationStarted && pending != null) {
                try { journal.clearVerified(pending) } catch (_: Exception) { /* pending blocks next write */ }
            }
            // During/after MUTATING, never delete the backup unless digest-verified.
        }
    }

    private enum class RollbackStatus {
        ORIGINAL_INTACT, RESTORED_VERIFIED, RESTORE_UNVERIFIED, PENDING_NO_RESTORE,
    }

    /** No false claim of restoration when the source was already unchanged. */
    private fun restoreAndVerify(entry: MichiGenreRecoveryJournal.Entry, uri: Uri): RollbackStatus {
        if (!journal.matches(entry) || !journal.backupIntegrity(entry))
            return RollbackStatus.PENDING_NO_RESTORE
        val currentFingerprint = digestProvider(uri)
        return when (MichiGenreRecoverySafetyPolicy.classify(entry, currentFingerprint)) {
            MichiGenreRecoverySafetyPolicy.CurrentBytes.ORIGINAL -> {
                try { journal.clearVerified(entry); RollbackStatus.ORIGINAL_INTACT }
                catch (_: Exception) { RollbackStatus.PENDING_NO_RESTORE }
            }
            MichiGenreRecoverySafetyPolicy.CurrentBytes.STAGED -> {
                try {
                    restoreOrFail(uri, journal.backupFile,
                        currentFingerprint ?: return RollbackStatus.PENDING_NO_RESTORE)
                    if (digestProvider(uri) != (entry.originalBytes to entry.originalSha256))
                        RollbackStatus.RESTORE_UNVERIFIED
                    else {
                        try { journal.clearVerified(entry); RollbackStatus.RESTORED_VERIFIED }
                        catch (_: Exception) { RollbackStatus.RESTORE_UNVERIFIED }
                    }
                } catch (_: Exception) { RollbackStatus.RESTORE_UNVERIFIED }
            }
            // Unknown may represent another app's modifications or a partial write.
            // Do not silently overwrite; preserve backup for a second informed consent.
            MichiGenreRecoverySafetyPolicy.CurrentBytes.UNKNOWN,
            MichiGenreRecoverySafetyPolicy.CurrentBytes.UNREADABLE -> RollbackStatus.PENDING_NO_RESTORE
        }
    }

    private fun readVerified(uri: Uri): EmbeddedTagFields? =
        EmbeddedTagReadPolicy.fieldsOrNull(UriEmbeddedTags.readDetailed(context, uri))

    private fun sameIdentity(fields: EmbeddedTagFields?, ticket: Ticket): Boolean =
        fields != null && fields.genres == ticket.expectedGenres &&
            fields.title.orEmpty() == ticket.expectedTitle &&
            fields.artist.orEmpty() == ticket.expectedArtist &&
            fields.album.orEmpty() == ticket.expectedAlbum

    private fun reportedSize(uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
        }
    } catch (_: Exception) { null }

    private fun copyUriToBackup(uri: Uri, backup: File): Long {
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(backup).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxCopy) throw IOException("BACKUP_LIMIT_EXCEEDED")
                    if (n > 0) output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
        } ?: return 0L
        return total
    }

    private fun digestProvider(uri: Uri): Pair<Long, String>? {
        return try {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(block)
                if (count < 0) break
                size += count
                if (size > maxCopy + 16 * 1024 * 1024) return null
                if (count > 0) md.update(block, 0, count)
            }
        } ?: return null
        size to md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: Exception) { null }
    }

    private fun replaceDescriptor(
        pfd: ParcelFileDescriptor, stage: File, expectedCurrent: Pair<Long, String>,
    ) {
        // Do not trust a separate ContentResolver.openInputStream(): a provider
        // could serve distinct snapshots through different open descriptors.
        // Both streams deliberately share this already opened read/write PFD.
        val read = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val channel = output.channel
        val statBytes = pfd.statSize
        val channelBytes = channel.size() // pipe / nonseekable rejected
        val permitted = if (expectedCurrent.first == 0L)
            MichiGenreDescriptorPolicy.permitsRecovery(statBytes, channelBytes) && channelBytes == 0L
        else MichiGenreDescriptorPolicy.permitsMutation(
            statBytes, channelBytes, expectedCurrent.first)
        if (!permitted) throw IOException("NONSEEKABLE_OR_CHANGED_PROVIDER_DESCRIPTOR")
        if (!MichiGenreDescriptorFingerprint.matchesOpenedDescriptor(
                read.channel, expectedCurrent, maxCopy + 16L * 1024 * 1024))
            throw IOException("OPEN_DESCRIPTOR_CONTENT_CHANGED_BEFORE_TRUNCATE")
        channel.position(0)
        channel.truncate(0)
        FileInputStream(stage).use { input -> input.copyTo(output, 64 * 1024) }
        output.fd.sync()
        // Do NOT close either stream independently: pfd owns the shared FD.
    }

    private fun restoreOrFail(uri: Uri, backup: File, expectedCurrent: Pair<Long, String>) {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            replaceDescriptor(descriptor, backup, expectedCurrent)
        } ?: throw IOException("RESTORE_PERMISSION_REVOKED")
    }

    private companion object {
        // Same-process serialization for all Compose gateways. Inter-process writing is NOT
        // certified: the production release gate must check Android process boundaries.
        val writeMutex = Mutex()
    }
}
