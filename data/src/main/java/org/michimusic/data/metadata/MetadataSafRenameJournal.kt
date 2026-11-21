package org.michimusic.data.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.michimusic.core.metadata.MetadataSafRenameRecoveryPolicy

/**
 * ME-11O. A separate, fail-closed, durable SAF rename INTENT journal.
 * This class never calls renameDocument, deletes documents or overwrites media.
 * All public operations acquire an OS file lock and preserve uncertain states.
 * Store only inside the application's private noBackup directory.
 */
internal class MetadataSafRenameJournal(private val root: File) {
    enum class Phase {
        PREPARED, INVOKING, PROVIDER_RETURNED, UNKNOWN,
        ORIGINAL_VERIFIED, RENAMED_VERIFIED,
    }

    data class Entry(
        val intent: MetadataSafRenameRecoveryPolicy.Intent,
        val phase: Phase,
        val returnedDocumentUri: String? = null,
    )

    sealed interface State {
        data object Clear : State
        data class Pending(val entry: Entry) : State
        data class Blocked(val reason: String) : State
    }

    private val record get() = File(root, "metadata-saf-rename-v1.bin")
    private val lock get() = File(root, "metadata-saf-rename-v1.lock")
    private val shaRegex = Regex("[a-f0-9]{64}")
    private val uuidRegex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val magic = 0x4d45524e // MERN
    private val version = 1
    private val maxRecord = 20 * 1024

    private fun ensureRoot() {
        if (Files.isSymbolicLink(root.toPath())) error("RENAME_JOURNAL_ROOT_SYMLINK")
        Files.createDirectories(root.toPath())
        check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    private fun <T> exclusive(operation: () -> T): T {
        ensureRoot()
        FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { return operation() }
        }
    }

    fun inspect(): State = exclusive { inspectLocked() }

    private fun inspectLocked(): State {
        if (!Files.exists(record.toPath(), LinkOption.NOFOLLOW_LINKS)) return State.Clear
        if (!Files.isRegularFile(record.toPath(), LinkOption.NOFOLLOW_LINKS))
            return State.Blocked("RENAME_JOURNAL_UNSAFE_FILE")
        return try {
            val size = Files.size(record.toPath())
            if (size !in 40L..maxRecord.toLong()) State.Blocked("RENAME_JOURNAL_BAD_SIZE")
            else State.Pending(decode(Files.readAllBytes(record.toPath())))
        } catch (_: Exception) { State.Blocked("RENAME_JOURNAL_CORRUPT_PRESERVE") }
    }

    /** Must be called only after an independent current preflight; no source mutation. */
    fun prepare(intent: MetadataSafRenameRecoveryPolicy.Intent, mainJournalClear: Boolean): Entry =
        exclusive {
            require(mainJournalClear) { "OTHER_METADATA_RECOVERY_PENDING" }
            require(validIntent(intent)) { "INVALID_RENAME_INTENT" }
            check(inspectLocked() == State.Clear) { "RENAME_RECOVERY_ALREADY_PENDING" }
            Entry(intent, Phase.PREPARED).also { persist(it, initial = true) }
        }

    /** fsync INVOKING before the first potentially physical provider operation. */
    fun markInvoking(before: Entry): Entry = advance(before, Phase.INVOKING)

    /**
     * A provider return is not proof. Persist the EXACT returned URI before
     * starting a cold readback. If an exception happens, mark UNKNOWN instead.
     */
    fun markProviderReturned(before: Entry, uri: String?): Entry =
        exclusive {
            check(before.phase == Phase.INVOKING) { "NOT_INVOKING" }
            val next = if (uri.isNullOrBlank() || !uri.startsWith("content://"))
                before.copy(phase = Phase.UNKNOWN)
                else before.copy(phase = Phase.PROVIDER_RETURNED, returnedDocumentUri = uri)
            replaceLocked(before, next)
            next
        }

    fun markUncertain(before: Entry): Entry = advance(before, Phase.UNKNOWN)

    /**
     * Read-only recovery after restart. The listing and full SHA must be
     * independently collected by the caller; no blind rename retry or rollback.
     * A changed ID is accepted only if the provider URI was durably recorded.
     */
    fun reconcile(
        observation: MetadataSafRenameRecoveryPolicy.Observation,
    ): MetadataSafRenameRecoveryPolicy.Verdict = exclusive {
        val current = inspectLocked() as? State.Pending
            ?: error("RENAME_JOURNAL_UNAVAILABLE")
        val entry = current.entry
        check(entry.phase == Phase.INVOKING || entry.phase == Phase.PROVIDER_RETURNED ||
            entry.phase == Phase.UNKNOWN) { "RENAME_RECEIPT_ALREADY_CLASSIFIED_OR_NOT_INVOKED" }
        val evidence = observation.copy(returnedDocumentUri = entry.returnedDocumentUri)
        val verdict = MetadataSafRenameRecoveryPolicy.reconcile(entry.intent, evidence)
        val next = when (verdict) {
            is MetadataSafRenameRecoveryPolicy.Verdict.OriginalVerified ->
                entry.copy(phase = Phase.ORIGINAL_VERIFIED)
            is MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified ->
                entry.copy(phase = Phase.RENAMED_VERIFIED)
            is MetadataSafRenameRecoveryPolicy.Verdict.Uncertain ->
                entry.copy(phase = Phase.UNKNOWN)
        }
        if (entry != next) replaceLocked(entry, next)
        verdict
    }

    private fun advance(before: Entry, phase: Phase): Entry = exclusive {
        val permitted = when (before.phase) {
            Phase.PREPARED -> phase == Phase.INVOKING
            Phase.INVOKING, Phase.PROVIDER_RETURNED -> phase == Phase.UNKNOWN
            else -> false
        }
        check(permitted) { "ILLEGAL_RENAME_TRANSITION" }
        before.copy(phase = phase).also { replaceLocked(before, it) }
    }

    /** Only PREPARED is definitely non-mutating; never clean UNKNOWN automatically. */
    fun discardNeverInvoked(transactionId: String, userConfirmed: Boolean): Boolean = exclusive {
        val pending = (inspectLocked() as? State.Pending)?.entry ?: return@exclusive false
        if (!userConfirmed || pending.intent.transactionId != transactionId ||
            pending.phase != Phase.PREPARED) return@exclusive false
        !Files.isSymbolicLink(record.toPath()) && Files.deleteIfExists(record.toPath())
    }

    private fun replaceLocked(before: Entry, after: Entry) {
        val live = (inspectLocked() as? State.Pending)?.entry ?: error("RENAME_JOURNAL_LOST")
        check(live == before) { "RENAME_JOURNAL_CHANGED" }
        check(before.intent == after.intent) { "RENAME_INTENT_CHANGED" }
        check(after.returnedDocumentUri == before.returnedDocumentUri ||
            (before.returnedDocumentUri == null && before.phase == Phase.INVOKING &&
                after.phase == Phase.PROVIDER_RETURNED)) { "RETURNED_URI_CHANGED" }
        persist(after, initial = false)
    }

    private fun validIntent(i: MetadataSafRenameRecoveryPolicy.Intent): Boolean =
        uuidRegex.matches(i.transactionId) && i.trackId.isNotBlank() &&
            i.beforeUri.startsWith("content://") && i.parentUri.startsWith("content://") &&
            i.beforeDocumentId.isNotBlank() && i.beforeName.isNotBlank() &&
            i.requestedName.isNotBlank() && i.originalBytes > 0 &&
            shaRegex.matches(i.originalSha256)

    private fun persist(entry: Entry, initial: Boolean) {
        val bytes = encode(entry)
        val tmp = File(root, "rename-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { ch ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) ch.write(buffer)
                ch.force(true)
            }
            val options = if (initial) arrayOf(StandardCopyOption.ATOMIC_MOVE)
                else arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp.toPath(), record.toPath(), *options)
            // A durable intent is required before invoking a provider. If a
            // filesystem cannot sync its directory, FAIL CLOSED and keep record.
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(tmp.toPath()) }
    }

    private fun encode(entry: Entry): ByteArray {
        check(validIntent(entry.intent)) { "INVALID_RENAME_INTENT" }
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            val i = entry.intent
            out.writeInt(magic); out.writeInt(version)
            out.writeUTF(i.transactionId); out.writeUTF(entry.phase.name)
            out.writeUTF(i.sourceId); out.writeUTF(i.trackId)
            out.writeUTF(i.beforeUri); out.writeUTF(i.beforeDocumentId)
            out.writeUTF(i.parentUri); out.writeUTF(i.beforeName)
            out.writeUTF(i.requestedName); out.writeUTF(i.originalSha256)
            out.writeLong(i.originalBytes)
            out.writeBoolean(entry.returnedDocumentUri != null)
            if (entry.returnedDocumentUri != null) out.writeUTF(entry.returnedDocumentUri)
        }
        val data = payload.toByteArray()
        require(data.size in 1..(maxRecord - 32)) { "RENAME_RECORD_TOO_LARGE" }
        return data + MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun decode(bytes: ByteArray): Entry {
        require(bytes.size in 40..maxRecord)
        val data = bytes.copyOf(bytes.size - 32)
        val actual = bytes.copyOfRange(data.size, bytes.size)
        require(MessageDigest.isEqual(actual, MessageDigest.getInstance("SHA-256").digest(data)))
        val input = DataInputStream(ByteArrayInputStream(data))
        require(input.readInt() == magic && input.readInt() == version)
        val transaction = input.readUTF()
        val phase = Phase.valueOf(input.readUTF())
        val source = input.readUTF(); val track = input.readUTF()
        val uri = input.readUTF(); val docId = input.readUTF()
        val parent = input.readUTF(); val beforeName = input.readUTF()
        val requested = input.readUTF(); val hash = input.readUTF()
        val size = input.readLong()
        val returned = if (input.readBoolean()) input.readUTF() else null
        require(input.available() == 0)
        val intent = MetadataSafRenameRecoveryPolicy.Intent(
            transaction, source, track, uri, docId, parent, beforeName, requested, hash, size)
        require(validIntent(intent))
        require(returned == null || returned.startsWith("content://"))
        return Entry(intent, phase, returned)
    }
}
