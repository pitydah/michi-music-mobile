package org.michimusic.link

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * A19: fail-closed filesystem boundary for AirCat's .part -> final promotion.
 *
 * This is intentionally not a Room transaction. A caller must keep its per-device
 * lease, verify SHA-256 before promotion, commit Room and only then discard .bak.
 * The methods do not scan in background or acquire network resources.
 */
object AirCatFilePromotion {
    private fun backup(final: File): File = File(final.parentFile, "${final.name}.bak")
    private fun staged(final: File): File = File(final.parentFile, "${final.name}.bak.staged")

    private fun regular(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())

    // File.exists() follows symlinks: a dangling link is still an occupied path.
    // NoFollow is required on every sidecar used by promotion/rollback.
    private fun occupied(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun atomicallyMove(from: File, to: File, replace: Boolean = false): Boolean = try {
        if (replace) Files.move(from.toPath(), to.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: Exception) {
        // Do not silently fall back to copy+delete or a non-atomic move.
        false
    }

    @Synchronized
    fun promote(part: File, final: File): Boolean {
        if (part.absolutePath == final.absolutePath || !regular(part) || part.length() <= 0L) return false
        if (part.canonicalFile.parentFile != final.canonicalFile.parentFile ||
            Files.isSymbolicLink(final.toPath()) ||
            Files.isSymbolicLink(part.parentFile.toPath()) ||
            Files.isSymbolicLink(final.parentFile.toPath())) return false
        if (occupied(final) && !regular(final)) return false
        val bak = backup(final)
        val pending = staged(final)
        // A36: even dangling symlinks occupy a rollback path. Never overwrite
        // an unresolved backup or silently replace an alias during an atomic move.
        if (occupied(bak) || occupied(pending)) return false
        // A45: syncing via RandomAccessFile would follow a swapped symlink.
        if (!runCatching {
                FileChannel.open(part.toPath(), StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            }.isSuccess || !regular(part)) return false

        val hadOld = occupied(final)
        if (hadOld && !atomicallyMove(final, bak)) return false
        if (atomicallyMove(part, final)) return true
        // The old copy stays on disk even if rollback itself fails.
        if (hadOld) atomicallyMove(bak, final)
        return false
    }

    @Synchronized
    fun restore(final: File): Boolean {
        val bak = backup(final)
        val pending = staged(final)
        // Two candidates are ambiguous. Choosing one by file length or timestamp is unsafe.
        if (occupied(bak) && occupied(pending)) return false
        val candidate = when {
            occupied(bak) -> bak
            occupied(pending) -> pending
            else -> return false
        }
        if (!regular(candidate) || candidate.length() <= 0L) return false
        if (Files.isSymbolicLink(final.toPath()) ||
            Files.isSymbolicLink(final.parentFile.toPath()) ||
            (occupied(final) && !regular(final))) return false
        // Atomic replace: never delete final before attempting the restore.
        return atomicallyMove(candidate, final, replace = true)
    }

    @Synchronized
    fun discardAfterCommit(final: File): Boolean {
        val bak = backup(final)
        val pending = staged(final)
        // Caller confirms successful Room+checkpoint commit. Do not discard without a final file.
        if (!regular(final) || final.length() <= 0L || occupied(pending)) return false
        return occupied(bak) && regular(bak) && bak.delete()
    }

    /**
     * Crash recovery never purges a backup on the strength of a Room row alone.
     * A registered path is not proof that the bytes have the expected SHA-256.
     * The caller can later remove the backup with discardAfterCommit after checking
     * the final file against the trusted checkpoint AND confirming its Room row.
     */
    suspend fun reconcile(
        targetDir: File,
        isRegisteredInDatabase: (suspend (File) -> Boolean)? = null,
    ): Int {
        // The caller must never follow an aliased directory to restore backups.
        if (!targetDir.isDirectory || Files.isSymbolicLink(targetDir.toPath())) return 0
        val names = targetDir.listFiles().orEmpty()
            .filter { regular(it) && (it.name.endsWith(".bak") || it.name.endsWith(".bak.staged")) }
            .map { it.name.removeSuffix(".bak.staged").removeSuffix(".bak") }
            .distinct().sorted()
        var restored = 0
        for (name in names) {
            val final = File(targetDir, name)
            // A20/A21: a durable journal owns this destination. A legacy backup
            // scanner is not allowed to roll it back using only a Room Boolean.
            // Only an explicit, lease-guarded recovery may reconcile this file.
            if (AirCatRecoveryJournal(File(targetDir, ".aircat-journal")).hasJournal(final)) continue
            // A42: a dangling symlink is an occupied sidecar too; legacy scan
            // must not ignore the second candidate or restore over an alias.
            if (occupied(backup(final)) && occupied(staged(final))) continue
            if (occupied(final) && !regular(final)) continue
            val needsRestore = !occupied(final) || final.length() == 0L ||
                isRegisteredInDatabase?.invoke(final) == false
            if (needsRestore && restore(final)) restored++
            // Registered and unregistered-unknown targets both retain their backup.
        }
        return restored
    }
}
