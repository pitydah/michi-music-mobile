package org.michimusic.data.intelligence

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * MI-24: one explicit metadata mutation at a time, with a durable, private recovery receipt.
 * The caller MUST supply a directory inside Context.noBackupFilesDir; never cacheDir.
 * No background recovery, scheduled work, automatic overwrites or network calls.
 *
 * ATOMIC_MOVE is mandatory. If this filesystem cannot atomically replace the receipt,
 * writing is refused; we do not silently downgrade to an unsafe move.
 */
internal class MichiGenreRecoveryJournal(private val directory: File) {
    enum class Phase { SNAPSHOTTING, BACKUP_READY, MUTATING, VERIFIED, CLEANUP }
    data class Entry(
        val id: String,
        val sourceId: String,
        val trackId: String,
        val uri: String,
        val phase: Phase,
        val originalBytes: Long = 0,
        val originalSha256: String = "",
        val stagedBytes: Long = 0,
        val stagedSha256: String = "",
    )
    sealed interface Inspection {
        data object Clear : Inspection
        data class Pending(val entry: Entry) : Inspection
        data class Blocked(val reason: String) : Inspection
    }

    private val receipt get() = File(directory, "mutation-v1.receipt")
    private val draft get() = File(directory, "mutation-v1.receipt.tmp")
    val backupFile: File get() = File(directory, "original-v1.backup")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    /**
     * Serializes edits across distinct Android processes, not just Mutex instances.
     * Never wait for another process with an audio descriptor open: fail closed.
     * The lock file is private, inert at rest, and is created only on user action.
     */
    suspend fun <T> withExclusiveProcessLock(block: suspend () -> T): T {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("RECOVERY_DIRECTORY_UNAVAILABLE")
        val lockFile = File(directory, "writer-v1.lock")
        val handle = RandomAccessFile(lockFile, "rw")
        try {
            val lock = try { handle.channel.tryLock() }
                catch (_: OverlappingFileLockException) { null }
            if (lock == null) throw IOException("GENRE_WRITER_PROCESS_LOCK_UNAVAILABLE")
            try { return block() }
            finally { lock.release() }
        } finally { handle.close() }
    }

    fun inspect(): Inspection {
        if (!directory.exists()) return Inspection.Clear
        if (!receipt.exists()) return if (backupFile.exists() || draft.exists())
            Inspection.Blocked("ORPHANED_RECOVERY_FILE") else Inspection.Clear
        return try {
            if (receipt.length() > 8192) return Inspection.Blocked("RECEIPT_TOO_LARGE")
            val raw = receipt.readBytes()
            val entry = DataInputStream(raw.inputStream()).use { input ->
                if (input.readInt() != 0x4D493234) throw IOException("WRONG_RECOVERY_VERSION")
                val id = input.readUTF()
                val sourceId = input.readUTF()
                val trackId = input.readUTF()
                val uri = input.readUTF()
                val phase = Phase.entries.getOrNull(input.readInt()) ?: throw IOException("BAD_PHASE")
                val originalSize = input.readLong()
                val originalHash = input.readUTF()
                val stagedSize = input.readLong()
                val stagedHash = input.readUTF()
                if (input.read() != -1) throw IOException("TRAILING_RECOVERY_DATA")
                Entry(id, sourceId, trackId, uri, phase, originalSize, originalHash, stagedSize, stagedHash)
            }
            if (!valid(entry)) Inspection.Blocked("INVALID_RECOVERY_RECEIPT")
            else if (entry.phase != Phase.SNAPSHOTTING && entry.phase != Phase.CLEANUP &&
                !backupFile.isFile) Inspection.Blocked("RECOVERY_BACKUP_MISSING")
            else Inspection.Pending(entry)
        } catch (_: Exception) { Inspection.Blocked("UNREADABLE_RECOVERY_RECEIPT") }
    }

    /** The entry is durably persisted BEFORE reading/copying the provider source. */
    fun begin(sourceId: String, trackId: String, uri: String): Entry {
        require(sourceId.length <= 256 && trackId.isNotBlank() && trackId.length <= 256)
        require(uri.startsWith("content://") && uri.length <= 2048)
        check(inspect() == Inspection.Clear) { "RECOVERY_PENDING" }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("RECOVERY_DIRECTORY_UNAVAILABLE")
        if (backupFile.exists() || draft.exists()) throw IOException("RECOVERY_ORPHAN_EXISTS")
        val entry = Entry(UUID.randomUUID().toString(), sourceId, trackId, uri, Phase.SNAPSHOTTING)
        store(entry)
        return entry
    }

    fun backupReady(entry: Entry, size: Long, sha256: String): Entry {
        check(entry.phase == Phase.SNAPSHOTTING)
        check(size > 0 && sha256Pattern.matches(sha256) && backupFile.isFile &&
            backupFile.length() == size && digest(backupFile) == sha256) { "BACKUP_NOT_VERIFIED" }
        return transition(entry, entry.copy(phase = Phase.BACKUP_READY,
            originalBytes = size, originalSha256 = sha256))
    }

    /** This receipt MUST be synced before the provider is truncated. */
    fun mutationStarted(entry: Entry, stage: File, stageSha256: String): Entry {
        check(entry.phase == Phase.BACKUP_READY)
        check(stage.isFile && stage.length() > 0 && sha256Pattern.matches(stageSha256) &&
            digest(stage) == stageSha256) { "STAGING_NOT_VERIFIED" }
        return transition(entry, entry.copy(phase = Phase.MUTATING,
            stagedBytes = stage.length(), stagedSha256 = stageSha256))
    }

    fun verified(entry: Entry): Entry {
        check(entry.phase == Phase.MUTATING)
        return transition(entry, entry.copy(phase = Phase.VERIFIED))
    }

    /** Call ONLY after byte-verified success or byte-verified restoration. */
    fun clearVerified(entry: Entry) {
        check(inspect() == Inspection.Pending(entry)) { "RECOVERY_RECEIPT_CHANGED" }
        // Durable CLEANUP receipt BEFORE deleting the only backup. If process death
        // occurs after backup deletion, the next user action can finish cleanup
        // without trying to restore bytes from a now-missing file.
        if (entry.phase != Phase.CLEANUP) transition(entry, entry.copy(phase = Phase.CLEANUP))
        if (backupFile.exists() && !backupFile.delete()) throw IOException("RECOVERY_BACKUP_CANNOT_DELETE")
        if (!receipt.delete()) throw IOException("RECOVERY_RECEIPT_CANNOT_DELETE")
        if (draft.exists() && !draft.delete()) throw IOException("RECOVERY_DRAFT_CANNOT_DELETE")
    }

    fun matches(entry: Entry): Boolean = inspect() == Inspection.Pending(entry)
    fun backupIntegrity(entry: Entry): Boolean = entry.originalBytes > 0 &&
        sha256Pattern.matches(entry.originalSha256) && backupFile.isFile &&
        backupFile.length() == entry.originalBytes && digest(backupFile) == entry.originalSha256

    private fun transition(before: Entry, after: Entry): Entry {
        check(inspect() == Inspection.Pending(before)) { "RECOVERY_RECEIPT_CHANGED" }
        store(after)
        return after
    }

    private fun valid(e: Entry): Boolean {
        if (e.id.length !in 1..64 || e.trackId.length !in 1..256 || e.sourceId.length > 256 ||
            e.uri.length !in 10..2048 || !e.uri.startsWith("content://")) return false
        return when (e.phase) {
            Phase.SNAPSHOTTING -> e.originalBytes == 0L && e.originalSha256.isEmpty() &&
                e.stagedBytes == 0L && e.stagedSha256.isEmpty()
            Phase.BACKUP_READY -> e.originalBytes > 0 && sha256Pattern.matches(e.originalSha256) &&
                e.stagedBytes == 0L && e.stagedSha256.isEmpty()
            Phase.MUTATING, Phase.VERIFIED -> e.originalBytes > 0 &&
                sha256Pattern.matches(e.originalSha256) && e.stagedBytes > 0 &&
                sha256Pattern.matches(e.stagedSha256)
            Phase.CLEANUP -> (e.originalBytes == 0L && e.originalSha256.isEmpty() ||
                e.originalBytes > 0 && sha256Pattern.matches(e.originalSha256)) &&
                (e.stagedBytes == 0L && e.stagedSha256.isEmpty() ||
                e.stagedBytes > 0 && sha256Pattern.matches(e.stagedSha256))
        }
    }

    private fun store(entry: Entry) {
        check(valid(entry)) { "BAD_RECOVERY_ENTRY" }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("RECOVERY_DIRECTORY_UNAVAILABLE")
        FileOutputStream(draft).use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x4D493234)
                data.writeUTF(entry.id)
                data.writeUTF(entry.sourceId)
                data.writeUTF(entry.trackId)
                data.writeUTF(entry.uri)
                data.writeInt(entry.phase.ordinal)
                data.writeLong(entry.originalBytes)
                data.writeUTF(entry.originalSha256)
                data.writeLong(entry.stagedBytes)
                data.writeUTF(entry.stagedSha256)
                data.flush()
                output.fd.sync()
            }
        }
        Files.move(draft.toPath(), receipt.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        fun digest(file: File): String {
            val hasher = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val block = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(block)
                    if (n < 0) break
                    if (n > 0) hasher.update(block, 0, n)
                }
            }
            return hasher.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
