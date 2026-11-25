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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataRecoveryTransitionPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhasePolicy

/**
 * ME-05: single-entry PRIVATE journal. Not the legacy GENRE journal. A stage is NOT
 * a write grant. The only supported destructive operation here deletes PRIVATE
 * pre-mutation files after an explicit discard, never a provider document.
 *
 * Journal writes use CREATE_NEW -> fsync -> atomic rename and a SHA-256 envelope;
 * unreadable or altered records fail closed.
 *
 * ## Exclusion contract
 *
 * [exclusive] runs a NON-SUSPENDING critical section. Suspending inside it is a
 * compile error, so no caller can hold the process mutex or the OS file lock across
 * provider IPC, a Room round trip or a cancellable read. The single documented
 * exception is the SAF rename attempt (see below). Work that talks to the outside
 * world uses [withExclusiveSnapshot] and [publishIfUnchanged]:
 *
 * 1. [withExclusiveSnapshot] observes the durable record and returns its
 *    content-addressed [Generation] together with a non-suspending observation
 *    (entry identity, phase, private digests).
 * 2. The caller performs blocking or suspending provider work with no journal lock.
 * 3. [publishIfUnchanged] re-enters the exclusion, re-reads the record and runs the
 *    caller's non-suspending block ONLY when the generation and the observed entry
 *    are still identical. That block must re-verify the caller's own local
 *    preconditions (private artifacts, intent binding, ownership, playback) and then
 *    either persist a transition or derive a result. It must NOT perform provider
 *    IPC: re-hashing private artifacts is local file I/O and is allowed; a permission
 *    probe, a provider query or a document rename is not.
 *
 * [Publication.Stale] is a refusal, never a success. A result that is still uncertain
 * after a publish must stay in a durable uncertain phase.
 *
 * ## Why a content-addressed generation is sufficient here, and where it is not
 *
 * [Generation] is the SHA-256 of the exact persisted envelope. It survives process
 * restart and changes on every distinct record, so two journal instances, two
 * coroutines and two processes observe the same identity for the same durable state.
 *
 * It is NOT a fence against an external provider: Android providers can change the
 * audio bytes without touching this record. Publication therefore re-verifies local
 * evidence only. Source bytes are fenced by the durable phase reservation
 * (MUTATING and ROLLBACK_PENDING are persisted immediately before a source FD is
 * opened, and both are non-discardable), by re-hashing digest-pinned private
 * artifacts, and by cold readback after the write. Room publication is fenced by the
 * conditional UPDATE in MetadataTrackDaoCasPort, not by this generation.
 *
 * Generation ABA cannot hide a write: [MetadataRecoveryPhasePolicy.canDiscardPrivateOnly]
 * only allows discarding SNAPSHOTTING/BACKUP_DURABLE/STAGE_READY, [advance] never moves a
 * phase backwards, and any entry that may have touched the source (MUTATING onwards) can
 * never return to a discardable phase. Before the reservation the record carries no
 * source authority, so a delete-and-recreate cycle cannot mask a mutation.
 *
 * ## Sanctioned exception: the SAF rename attempt
 *
 * [MetadataSafRenameExecutionCoordinator.attempt] is the only caller allowed to hold
 * the non-suspending exclusion across provider IPC (`DocumentsContract.renameDocument`),
 * and it is reachable only if `MetadataSafRenameCertification.PHYSICAL_RENAME_CERTIFIED`
 * is flipped from its hard `false` constant.
 *
 * The reason is structural: this journal has no rename phase, so it cannot express
 * "a provider rename is in flight" as a durable reservation. Releasing the lock
 * between PREPARED and INVOKING would let a concurrent preparation observe `Clear`
 * and write private artifacts for a document that the provider is renaming. The
 * rename journal's PREPARED/INVOKING records fence other renames, but they do not
 * fence every reader of this journal. Continuous exclusion is therefore the chosen
 * fence. Any future exception needs the same two things: a hard-false runtime gate
 * and an explicit paragraph here. Do not add one silently.
 *
 * ## Reentrancy
 *
 * The mutex is not reentrant. Because every critical section is non-suspending, a
 * nested acquisition can only be introduced deliberately (for example with
 * `runBlocking`); that attempt is rejected with `RECOVERY_JOURNAL_REENTRANT_LOCK`
 * instead of deadlocking.
 */
internal class MetadataRecoveryJournalV1(private val root: File) {
    data class Entry(
        val id: String,
        val phase: MetadataRecoveryPhase,
        val sourceId: String,
        val trackId: String,
        val uri: String,
        val expectedBytes: Long,
        val expectedModifiedAt: Long,
        val originalSha256: String = "",
        val stageSha256: String = "",
        val stageBytes: Long = 0,
        val format: String = "",
        /**
         * V2: exact opaque evidence token bound into the durable intent owner
         * digest. It is persisted so a typed restore can be authorized after a
         * process restart without trusting a Compose draft. Empty for V1 records.
         */
        val ownerEvidenceToken: String = "",
    )

    sealed interface Inspection {
        data object Clear : Inspection
        data class Pending(val entry: Entry) : Inspection
        data class Blocked(val reason: String) : Inspection
    }

    /**
     * Content-addressed identity of the persisted record. [Generation.Clear] means the
     * record does not exist; the remaining sentinels are never publishable.
     */
    @JvmInline
    value class Generation(val digest: String) {
        companion object {
            val Clear = Generation("")
            val Symlink = Generation("!symlink")
            val Unreadable = Generation("!unreadable")
        }
    }

    /** A durable observation plus whatever the caller derived from it. */
    data class Snapshot<T>(
        val generation: Generation,
        val inspection: Inspection,
        val value: T,
    )

    sealed interface Publication<out T> {
        data class Published<T>(val value: T, val generation: Generation) : Publication<T>
        data class Stale(val expected: Generation, val observed: Generation) : Publication<Nothing>
    }

    private class Observed(val generation: Generation, val inspection: Inspection)

    private class RootLock {
        val mutex = Mutex()
        private val owner = AtomicReference<Thread?>(null)
        fun ownedByCurrentThread(): Boolean = owner.get() === Thread.currentThread()
        fun enter() { owner.set(Thread.currentThread()) }
        fun leave() { owner.set(null) }
    }

    private companion object {
        const val MAGIC = 0x4d453035 // ME05
        const val VERSION = 2
        const val VERSION_LEGACY = 1
        const val MAX_RECORD = 32 * 1024
        const val MAX_OWNER_TOKEN = 256
        val validId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val digestRegex = Regex("[a-f0-9]{64}")

        /**
         * One mutex per canonical journal directory, shared by every instance in this
         * process. Without it two instances over the same directory would each hold
         * their own mutex and `FileChannel.lock()` would throw
         * `OverlappingFileLockException` inside the same JVM instead of blocking.
         */
        val processLocks = ConcurrentHashMap<String, RootLock>()

        fun lockFor(root: File): RootLock {
            val key = try { root.canonicalPath } catch (_: Exception) { root.absolutePath }
            return processLocks.computeIfAbsent(key) { RootLock() }
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }
    }

    private val record get() = File(root, "journal-v1.bin")
    private val lockFile get() = File(root, "journal-v1.lock")

    /**
     * Runs [block] under a process-wide mutex and the cross-process file lock.
     *
     * The blocking acquisition and the whole critical section run inside
     * [runInterruptible] on the IO dispatcher: cancelling the caller interrupts a
     * parked acquisition and releases the mutex through the `finally` path. [block]
     * must not suspend; that is enforced by its type, so a persisted transition can
     * never be interleaved with an external suspension point.
     */
    suspend fun <T> exclusive(block: () -> T): T {
        val lock = lockFor(root)
        check(!lock.ownedByCurrentThread()) { "RECOVERY_JOURNAL_REENTRANT_LOCK" }
        lock.mutex.lock()
        try {
            return runInterruptible(Dispatchers.IO) {
                ensureDirectory()
                FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    channel.lock().use {
                        lock.enter()
                        try { block() } finally { lock.leave() }
                    }
                }
            }
        } finally {
            lock.mutex.unlock()
        }
    }

    /**
     * Briefly enters the exclusion and returns the durable [Generation] plus [block]'s
     * non-suspending observation. Release the lock before any provider or Room call.
     */
    suspend fun <T> withExclusiveSnapshot(block: (Inspection) -> T): Snapshot<T> = exclusive {
        val observed = observe()
        Snapshot(observed.generation, observed.inspection, block(observed.inspection))
    }

    /**
     * Re-enters the exclusion and runs [block] only when the record is still exactly the
     * observed generation and, when supplied, still holds [before].
     *
     * [block] is non-suspending and runs while the journal is frozen, so it must
     * re-verify every caller precondition that could have changed during the unlocked
     * window and either persist its transition or return a derived value. A stale
     * observation yields [Publication.Stale] and [block] never runs.
     */
    suspend fun <T> publishIfUnchanged(
        expected: Generation,
        before: Entry? = null,
        block: () -> T,
    ): Publication<T> = exclusive {
        val observed = observe()
        val live = observed.inspection
        val unchanged = observed.generation == expected &&
            (before == null || (live is Inspection.Pending && live.entry == before))
        if (!unchanged) {
            return@exclusive Publication.Stale(expected, observed.generation)
        }
        val value = block()
        Publication.Published(value, observe().generation)
    }

    /**
     * Non-mutating confirmation. Use before reporting a verdict that was derived
     * outside the exclusion, so a concurrent transition cannot be mistaken for success.
     */
    suspend fun isUnchanged(expected: Generation, entry: Entry): Boolean =
        when (val publication = publishIfUnchanged(expected, entry) { true }) {
            is Publication.Stale -> false
            is Publication.Published -> publication.value
        }

    fun backup(entry: Entry): File = File(root, "backup-${validatedId(entry.id)}.bin")
    fun stage(entry: Entry): File = File(root, "stage-${validatedId(entry.id)}.bin")
    /** ME-10N: an independent V1 journal intent, never the V2 preview receipt. */
    fun intent(entry: Entry): File = File(root, "intent-${validatedId(entry.id)}.bin")

    /** Durable owner evidence written before MUTATING; optional for legacy V2. */
    fun owner(entry: Entry): File = File(root, "owner-${validatedId(entry.id)}.bin")
    fun preimage(entry: Entry): File = File(root, "preimage-${validatedId(entry.id)}.bin")

    private fun validatedId(id: String): String {
        require(validId.matches(id)) { "INVALID_PRIVATE_TRANSACTION_ID" }
        return id
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(root.toPath())) error("JOURNAL_DIRECTORY_SYMLINK")
        Files.createDirectories(root.toPath())
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            error("JOURNAL_DIRECTORY_INVALID")
    }

    fun inspect(): Inspection = observe().inspection

    private fun observe(): Observed {
        ensureDirectory()
        if (!record.exists()) return Observed(Generation.Clear, Inspection.Clear)
        if (Files.isSymbolicLink(record.toPath()))
            return Observed(Generation.Symlink, Inspection.Blocked("JOURNAL_SYMLINK"))
        val bytes = try { Files.readAllBytes(record.toPath()) }
            catch (_: Exception) {
                return Observed(Generation.Unreadable,
                    Inspection.Blocked("JOURNAL_CORRUPT_KEEP_PRIVATE_FILES"))
            }
        val inspection = try { Inspection.Pending(decode(bytes)) }
            catch (_: Exception) { Inspection.Blocked("JOURNAL_CORRUPT_KEEP_PRIVATE_FILES") }
        return Observed(Generation(sha256Hex(bytes)), inspection)
    }

    /** Rehashes private backups on user-initiated recovery inspection, never in scroll. */
    fun privateArtifactsIntact(entry: Entry): Boolean {
        fun valid(file: File, size: Long, digest: String): Boolean {
            if (!digestRegex.matches(digest) || !file.isFile ||
                Files.isSymbolicLink(file.toPath()) || file.length() != size) return false
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n > 0) hash.update(chunk, 0, n)
                }
            }
            return hash.digest().joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2,'0')
            } == digest
        }
        return when(entry.phase) {
            MetadataRecoveryPhase.SNAPSHOTTING -> true // backup may be incomplete
            MetadataRecoveryPhase.BACKUP_DURABLE ->
                valid(backup(entry),entry.expectedBytes,entry.originalSha256)
            else -> valid(backup(entry),entry.expectedBytes,entry.originalSha256) &&
                valid(stage(entry),entry.stageBytes,entry.stageSha256)
        }
    }

    fun start(entry: Entry) {
        require(entry.phase == MetadataRecoveryPhase.SNAPSHOTTING)
        require(entry.expectedBytes > 0 && entry.expectedModifiedAt > 0 && entry.uri.startsWith("content://"))
        require(entry.trackId.isNotBlank())
        check(inspect() == Inspection.Clear) { "RECOVERY_ALREADY_PENDING" }
        check(!backup(entry).exists() && !stage(entry).exists() &&
            !Files.exists(intent(entry).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "PRIVATE_FILE_COLLISION"
        }
        persist(entry, initial = true)
    }

    fun advance(before: Entry, next: Entry, permit: MetadataWritePermit? = null) {
        require(before.id == next.id && before.sourceId == next.sourceId &&
            before.trackId == next.trackId && before.uri == next.uri &&
            before.expectedBytes == next.expectedBytes &&
            before.expectedModifiedAt == next.expectedModifiedAt)
        val transition = MetadataRecoveryTransitionPolicy.advance(before.phase, next.phase)
        check(transition != MetadataRecoveryTransitionPolicy.Decision.REFUSED) {
            "ILLEGAL_JOURNAL_TRANSITION"
        }
        val allowed = when(before.phase) {
            MetadataRecoveryPhase.SNAPSHOTTING -> next.phase == MetadataRecoveryPhase.BACKUP_DURABLE &&
                digestRegex.matches(next.originalSha256)
            MetadataRecoveryPhase.BACKUP_DURABLE -> next.phase == MetadataRecoveryPhase.STAGE_READY &&
                digestRegex.matches(next.originalSha256) &&
                digestRegex.matches(next.stageSha256) && next.stageBytes > 0
            else -> next.originalSha256 == before.originalSha256 &&
                next.stageSha256 == before.stageSha256 && next.stageBytes == before.stageBytes &&
                next.format == before.format && privateArtifactsIntact(before)
        }
        check(allowed) { "INVALID_RECOVERY_ARTIFACTS_OR_CHANGED_RECEIPT" }
        // The prepared stage exists, but no physical commit profile is certified
        // in ME-06. Explicit consent and a journal are not enough.
        if (next.phase == MetadataRecoveryPhase.MUTATING) {
            val permitted = permit?.matches(before) == true
            check(permitted || MetadataCommitCertification.mayMutateOriginal()) {
                "SOURCE_COMMIT_NOT_CERTIFIED"
            }
            check(privateArtifactsIntact(before)) { "PRIVATE_BACKUP_NOT_VERIFIED" }
        }
        val live = inspect() as? Inspection.Pending ?: error("JOURNAL_NOT_READABLE")
        check(live.entry == before) { "JOURNAL_CHANGED" }
        persist(next, initial = false)
    }

    /**
     * ME-07B: a still-existing private fault-injection trial may be the only
     * diagnostic evidence of an interrupted sandbox exercise. Do not remove its
     * source backup until the user explicitly discards the trial first.
     */
    fun privateTrialPresent(entry: Entry): Boolean {
        val trials = File(root.parentFile, "michi-metadata-private-trials-v1")
        val scratch = File(trials, "trial-${validatedId(entry.id)}.bin")
        return Files.isSymbolicLink(trials.toPath()) ||
            Files.exists(scratch.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    /**
     * Terminal cleanup after a verified physical write and coherent index CAS.
     * Never call after an uncertain postcheck or an index-pending outcome.
     */
    /**
     * Terminal cleanup, resumable after a process kill. The first call advances
     * POSTCHECK_VERIFIED to CLEANUP_SAFE and then deletes private artifacts
     * idempotently. A later call accepts CLEANUP_SAFE directly, so a kill after
     * the phase transition or after any single delete can continue without
     * re-running the provider postcheck or requiring both hashes again.
     */
    fun completeVerified(id: String): Boolean {
        val entry = (inspect() as? Inspection.Pending)?.entry ?: return false
        if (entry.id != id) return false
        val cleanup = when (entry.phase) {
            MetadataRecoveryPhase.POSTCHECK_VERIFIED ->
                entry.copy(phase = MetadataRecoveryPhase.CLEANUP_SAFE)
            MetadataRecoveryPhase.CLEANUP_SAFE -> entry
            else -> return false
        }
        return try {
            if (cleanup !== entry) advance(entry, cleanup)
            for (file in listOf(intent(entry), stage(entry), backup(entry), owner(entry), preimage(entry))) {
                if (Files.isSymbolicLink(file.toPath())) return false
                if (file.exists() && !file.delete()) return false
            }
            !record.exists() || record.delete()
        } catch (_: Exception) { false }
    }

    /** No mutation is ever attempted here. Keep journal if a private delete fails. */
    fun discardPrepared(id: String): Boolean {
        val entry = (inspect() as? Inspection.Pending)?.entry ?: return false
        if (entry.id != id || !MetadataRecoveryPhasePolicy.canDiscardPrivateOnly(entry.phase) ||
            privateTrialPresent(entry)) return false
        for (file in listOf(intent(entry), stage(entry), backup(entry), owner(entry), preimage(entry))) {
            if (Files.isSymbolicLink(file.toPath())) return false
            if (file.exists() && !file.delete()) return false
        }
        return !record.exists() || record.delete()
    }

    private fun persist(entry: Entry, initial: Boolean) {
        val bytes = encode(entry)
        ensureDirectory()
        val tmp = File(root, "journal-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (initial) {
                Files.move(tmp.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } else {
                Files.move(tmp.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            }
            // Directory fsync is not supported by every Android filesystem. Failure is
            // deliberately NOT converted into false permission to commit to source.
            try { FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) } }
            catch (_: Exception) { /* prepare remains non-mutating */ }
        } finally { tmp.delete() }
    }

    private fun encode(e: Entry): ByteArray {
        validatedId(e.id)
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeUTF(e.id); out.writeUTF(e.phase.name)
            out.writeUTF(e.sourceId); out.writeUTF(e.trackId); out.writeUTF(e.uri)
            out.writeLong(e.expectedBytes); out.writeLong(e.expectedModifiedAt)
            out.writeUTF(e.originalSha256); out.writeUTF(e.stageSha256)
            out.writeLong(e.stageBytes); out.writeUTF(e.format)
            out.writeUTF(e.ownerEvidenceToken)
        }
        val data = payload.toByteArray()
        require(data.size in 1..MAX_RECORD) { "RECORD_TOO_BIG" }
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(data.size); out.write(data); out.write(digest)
            }
        }.toByteArray()
    }

    private fun decode(raw: ByteArray): Entry {
        require(raw.size in 37..MAX_RECORD + 36)
        val stream = DataInputStream(ByteArrayInputStream(raw))
        val length = stream.readInt()
        require(length in 1..MAX_RECORD && raw.size == length + 36)
        val payload = ByteArray(length)
        stream.readFully(payload)
        val hash = ByteArray(32)
        stream.readFully(hash)
        require(MessageDigest.isEqual(hash,MessageDigest.getInstance("SHA-256").digest(payload)))
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC)
            val version = input.readInt()
            require(version == VERSION || version == VERSION_LEGACY)
            val id=validatedId(input.readUTF())
            val phase=MetadataRecoveryPhase.valueOf(input.readUTF())
            val sourceId=input.readUTF();val trackId=input.readUTF();val uri=input.readUTF()
            val expectedBytes=input.readLong(); val modifiedAt=input.readLong()
            val original=input.readUTF();val staged=input.readUTF()
            val stageBytes=input.readLong();val format=input.readUTF()
            val ownerToken = if (version >= VERSION) input.readUTF() else ""
            require(input.available() == 0 && trackId.isNotBlank() && uri.startsWith("content://") &&
                expectedBytes > 0 && modifiedAt > 0 && stageBytes >= 0)
            require(original.isEmpty() || digestRegex.matches(original))
            require(staged.isEmpty() || digestRegex.matches(staged))
            require(ownerToken.length <= MAX_OWNER_TOKEN &&
                ownerToken.none { it.isISOControl() })
            return Entry(id,phase,sourceId,trackId,uri,expectedBytes,modifiedAt,original,
                staged,stageBytes,format,ownerToken)
        }
    }
}
