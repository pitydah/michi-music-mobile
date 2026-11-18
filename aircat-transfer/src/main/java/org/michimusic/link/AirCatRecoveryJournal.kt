package org.michimusic.link

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * A20: on-demand, fail-closed, per-target crash journal for AirCat's file/Room boundary.
 *
 * Only the transfer owner holding the AirCat cross-process lease may mutate it.
 * The journal never starts a service, timer, scan, thread or network request.
 * Explicit recovery callers must corroborate Room and checkpoint externally.
 * Directory metadata fsync/FS crash-durability still requires Android validation.
 */
internal class AirCatRecoveryJournal(private val directory: File) {
    enum class Phase { PREPARED, PROMOTED, LIBRARY_COMMITTED, CHECKPOINT_COMMITTED }
    enum class Assessment {
        CLEAN, NEEDS_REPAIR, ORPHAN_FINAL, FINAL_MISSING, HASH_MISMATCH,
        READY_FOR_EXPLICIT_COMPLETION, CORRUPT_JOURNAL,
    }

    data class Entry(
        val transferKey: String,
        val finalPath: String,
        val expectedSha256: String,
        val operationId: String,
        val hadOldFile: Boolean,
        val phase: Phase,
    )

    private val shaPattern = Regex("[a-fA-F0-9]{64}")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    private fun regular(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())

    private fun occupied(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun shaBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun journalPath(final: File): File {
        val path = final.canonicalPath
        return File(directory, "${shaBytes(path.toByteArray(Charsets.UTF_8))}.aircat-journal")
    }

    private fun encode(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String) = String(decoder.decode(value), Charsets.UTF_8)

    private fun serialize(e: Entry): String = listOf(
        "AIRCJ1", encode(e.transferKey), encode(e.finalPath), e.expectedSha256,
        e.operationId, if (e.hadOldFile) "1" else "0", e.phase.name,
    ).joinToString("\n", postfix = "\n")

    private fun parse(text: String): Entry? = runCatching {
        val p = text.trimEnd('\n').split('\n')
        if (p.size != 7 || p[0] != "AIRCJ1" || !shaPattern.matches(p[3]) ||
            p[4].length != 36 || p[5] !in listOf("0", "1")) return@runCatching null
        val key = decode(p[1])
        val path = decode(p[2])
        if (key.isBlank() || path.isBlank() || !File(path).isAbsolute) return@runCatching null
        Entry(key, path, p[3].lowercase(), p[4], p[5] == "1", Phase.valueOf(p[6]))
    }.getOrNull()

    private fun ensureDirectory(): Boolean {
        return try {
            (directory.exists() || directory.mkdirs()) && directory.isDirectory &&
                !Files.isSymbolicLink(directory.toPath())
        } catch (_: Exception) { false }
    }

    /**
     * CREATE_NEW is a real exclusive reservation on supported local filesystems.
     * An ATOMIC_MOVE without REPLACE_EXISTING is NOT a no-clobber guarantee:
     * on some providers ATOMIC_MOVE can replace a competing destination.
     * A crash during initial reservation leaves a malformed journal that must
     * block future transfers until an explicit repair; never auto-delete it.
     */
    private fun persist(entry: Entry, replacing: Boolean): Boolean {
        if (!ensureDirectory()) return false
        val target = journalPath(File(entry.finalPath))
        if (replacing && !regular(target)) return false
        if (!replacing) {
            return runCatching {
                val bytes = serialize(entry).toByteArray(Charsets.UTF_8)
                FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                true
            }.getOrDefault(false)
        }
        val scratch = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        var scratchOwned = false
        return try {
            // A45: a UUID scratch path is not sufficient to prove exclusivity.
            // A preexisting file or symlink must never be truncated/overwritten.
            FileChannel.open(scratch.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(serialize(entry).toByteArray(Charsets.UTF_8))
                scratchOwned = true
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (!regular(target)) return false
            Files.move(scratch.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) { false }
        finally { if (scratchOwned && occupied(scratch)) scratch.delete() }
    }

    private fun readExact(final: File): Entry? {
        val path = journalPath(final)
        if (Files.isSymbolicLink(directory.toPath()) || !regular(path) ||
            path.length() !in 1..8192) return null
        // A43: bounded, NOFOLLOW read from the exact opened inode. A length
        // preflight alone is racy (replacement could force readText() to allocate
        // unbounded memory or follow a symlink after the regular-file check).
        return runCatching {
            FileChannel.open(path.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val b = ByteBuffer.allocate(8193)
                while (b.hasRemaining()) {
                    val n = channel.read(b)
                    if (n < 0) break
                    if (n == 0) return@use null
                }
                val size = b.position()
                if (size !in 1..8192 || channel.size() != size.toLong()) return@use null
                parse(String(b.array(), 0, size, Charsets.UTF_8))?.takeIf {
                    it.finalPath == final.canonicalPath
                }
            }
        }.getOrNull()
    }

    @Synchronized
    fun pending(final: File): Entry? = readExact(final)

    @Synchronized
    fun hasJournal(final: File): Boolean =
        Files.exists(journalPath(final).toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun advance(entry: Entry, from: Phase, to: Phase): Entry? {
        val current = readExact(File(entry.finalPath)) ?: return null
        if (current.transferKey != entry.transferKey || current.operationId != entry.operationId ||
            current.expectedSha256 != entry.expectedSha256) return null
        if (current.phase == to) return current // idempotent retry
        if (current.phase != from) return null
        val updated = current.copy(phase = to)
        return if (persist(updated, replacing = true)) updated else null
    }

    /** PREPARED is durable before the first final-file rename. */
    @Synchronized
    fun promote(part: File, final: File, transferKey: String, expectedSha256: String): Boolean {
        if (transferKey.isBlank() || !shaPattern.matches(expectedSha256) ||
            !regular(part) || part.length() <= 0L || hasJournal(final)) return false
        // Never journal an operation targeting a different physical directory or symlink.
        if (part.canonicalFile.parentFile != final.canonicalFile.parentFile ||
            Files.isSymbolicLink(final.toPath()) ||
            Files.isSymbolicLink(final.parentFile.toPath())) return false
        if (occupied(final) && !regular(final)) return false
        if (!ensureDirectory()) return false
        val entry = Entry(transferKey, final.canonicalPath, expectedSha256.lowercase(),
            UUID.randomUUID().toString(), occupied(final), Phase.PREPARED)
        if (!persist(entry, replacing = false)) return false
        // If promotion fails at ANY point, keep PREPARED for explicit repair.
        if (!AirCatFilePromotion.promote(part, final)) return false
        return advance(entry, Phase.PREPARED, Phase.PROMOTED) != null
    }

    /**
     * Must be invoked only after successful library registration AND durable checkpoint update.
     * Failure preserves both journal and any backup; no background retries.
     */
    @Synchronized
    fun finishCommitted(
        final: File,
        transferKey: String,
        expectedSha256: String,
        libraryCommitted: Boolean,
        checkpointCommitted: Boolean,
    ): Boolean {
        if (!libraryCommitted || !checkpointCommitted || !shaPattern.matches(expectedSha256)) return false
        if (!regular(final) || final.length() == 0L || !matchesHash(final, expectedSha256)) return false
        val current = readExact(final)
        if (current == null) {
            // Legitimate prior verified file reuse is allowed only with no pending journal/backup.
            if (hasJournal(final) || occupied(File(final.parentFile, "${final.name}.bak")) ||
                occupied(File(final.parentFile, "${final.name}.bak.staged"))) return false
            return true
        }
        if (current.transferKey != transferKey ||
            !current.expectedSha256.equals(expectedSha256, ignoreCase = true)) return false
        val library = when (current.phase) {
            Phase.PROMOTED -> advance(current, Phase.PROMOTED, Phase.LIBRARY_COMMITTED)
            Phase.LIBRARY_COMMITTED, Phase.CHECKPOINT_COMMITTED -> current
            else -> null
        } ?: return false
        val checkpoint = when (library.phase) {
            Phase.LIBRARY_COMMITTED -> advance(library, Phase.LIBRARY_COMMITTED, Phase.CHECKPOINT_COMMITTED)
            Phase.CHECKPOINT_COMMITTED -> library
            else -> null
        } ?: return false
        if (checkpoint.phase != Phase.CHECKPOINT_COMMITTED) return false
        val backup = File(final.parentFile, "${final.name}.bak")
        val pendingBackup = File(final.parentFile, "${final.name}.bak.staged")
        if (occupied(pendingBackup)) return false
        if (occupied(backup) && !AirCatFilePromotion.discardAfterCommit(final)) return false
        return runCatching { Files.deleteIfExists(journalPath(final).toPath()) }.getOrDefault(false)
    }

    /** Hash is calculated only during explicit completion/recovery, never by a resident worker. */
    private fun matchesHash(final: File, expected: String): Boolean = runCatching {
        if (!regular(final)) return@runCatching false
        val digest = MessageDigest.getInstance("SHA-256")
        // A45: open the exact file descriptor with NOFOLLOW, as journal reads do.
        // Pre-checking isRegularFile and then calling File.inputStream() could
        // follow a symlink swapped into the path before the open.
        FileChannel.open(final.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val fileSize = channel.size()
            if (fileSize <= 0L) return@runCatching false
            val buffer = ByteBuffer.allocate(64 * 1024)
            var consumed = 0L
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) return@runCatching false
                consumed = Math.addExact(consumed, count.toLong())
                if (consumed > fileSize) return@runCatching false
                digest.update(buffer.array(), 0, count)
            }
            if (consumed != fileSize || channel.size() != fileSize || !regular(final))
                return@runCatching false
        }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }.getOrDefault(false)

    /** Read-only, user-triggered diagnosis of a known destination. Never deletes anything. */
    @Synchronized
    fun assess(final: File, roomRegistered: Boolean?, checkpointCompleted: Boolean?): Assessment {
        if (!hasJournal(final)) return Assessment.CLEAN
        val entry = readExact(final) ?: return Assessment.CORRUPT_JOURNAL
        if (!regular(final) || final.length() == 0L) return Assessment.FINAL_MISSING
        if (!matchesHash(final, entry.expectedSha256)) return Assessment.HASH_MISMATCH
        if (roomRegistered != true) return if (!entry.hadOldFile) Assessment.ORPHAN_FINAL else Assessment.NEEDS_REPAIR
        return if (checkpointCompleted == true) Assessment.READY_FOR_EXPLICIT_COMPLETION
            else Assessment.NEEDS_REPAIR
    }
}
