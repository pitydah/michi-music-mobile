package org.michimusic.mobile.devices.aircat

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.michimusic.core.models.PersistentCheckpoint

/**
 * A33: source-bound, bounded, ALL-OR-NOTHING input preflight before repairing a
 * managed AirCat batch. Recovery never scans nonselected backups and never
 * writes Room/files until every selected checkpoint passes this policy.
 *
 * This is local validation, not Home authorization or a substitute for a
 * lease. The caller must additionally check the enrolled Home anchor and
 * hold its exclusive cross-process lease for the whole recovery operation.
 */
internal object AirCatRecoveryBatchPreflightPolicy {
    private val sha256 = Regex("[a-fA-F0-9]{64}")

    /** Returns a stable refusal code; null is an eligible selection. */
    fun refusal(
        selected: List<PersistentCheckpoint>,
        requestedSourceMichiId: String,
        managedDirectory: File,
    ): String? {
        if (requestedSourceMichiId.isBlank()) return "AIRC_RECOVERY_SOURCE_REQUIRED"
        val root = runCatching { managedDirectory.canonicalFile }.getOrNull()
            ?: return "AIRC_RECOVERY_INVALID_ROOT"
        if (!root.isDirectory || Files.isSymbolicLink(managedDirectory.toPath()))
            return "AIRC_RECOVERY_INVALID_ROOT"
        // Refuse oversized selections before allocating a size list or touching disk.
        if (selected.size > 10_000 ||
            AirCatByteTotalPolicy.exactTotal(selected.map { it.expectedSize }) == null)
            return "AIRC_RECOVERY_INVALID_TOTAL"
        val seenKeys = HashSet<String>()
        val seenTargets = HashSet<String>()
        val seenLogicalTargets = HashSet<Pair<String, String>>()
        // A43: one signed plan cannot use the same remote source object twice
        // under distinct logical IDs; that would make identity/receipt ambiguous.
        val seenRemoteWithinPlan = HashSet<Pair<String, String>>()
        // Reserve every path owned by a selected item, including its rollback
        // sidecars. A second checkpoint must not point at another track's .part
        // or .bak; these are real files during crash recovery.
        val occupiedPaths = HashSet<String>()
        for (cp in selected) {
            if (cp.remoteDeviceId != requestedSourceMichiId ||
                cp.transferKey.isBlank() || cp.transferId.isBlank() ||
                cp.trackId.isBlank() || cp.remoteTrackId.isBlank() ||
                cp.sourceLibraryId.isBlank() || cp.destinationLibraryId.isBlank() ||
                !sha256.matches(cp.expectedSha256) || cp.expectedSize <= 0L ||
                cp.receivedBytes < 0L || cp.verifiedBytes < 0L ||
                cp.receivedBytes > cp.expectedSize ||
                cp.verifiedBytes > cp.receivedBytes) {
                return "AIRC_RECOVERY_INVALID_CHECKPOINT"
            }
            if (!seenKeys.add(cp.transferKey)) return "AIRC_RECOVERY_DUPLICATE_KEY"
            if (!seenRemoteWithinPlan.add(cp.transferId to cp.remoteTrackId))
                return "AIRC_RECOVERY_DUPLICATE_REMOTE"
            if (!seenLogicalTargets.add(cp.destinationLibraryId to cp.trackId))
                return "AIRC_RECOVERY_DUPLICATE_LOGICAL_TARGET"
            val final = File(cp.finalFilePath)
            val part = File(cp.tempFilePath)
            if (!final.isAbsolute || !part.isAbsolute || final == part ||
                final.name.isBlank() || part.name.isBlank() ||
                !part.name.endsWith(".part") ||
                final.name.endsWith(".part") ||
                final.name.endsWith(".bak") ||
                final.name.endsWith(".bak.staged") ||
                final.name.endsWith(".aircat-journal"))
                return "AIRC_RECOVERY_INVALID_PATH"
            val pathOk = runCatching {
                val parent = final.parentFile ?: return@runCatching false
                val partParent = part.parentFile ?: return@runCatching false
                // Never allow a checkpoint to redirect recovery via symlinks,
                // including a symlink to an existing .part or final destination.
                parent.canonicalFile == root && partParent.canonicalFile == root &&
                    !Files.isSymbolicLink(parent.toPath()) &&
                    !Files.isSymbolicLink(partParent.toPath()) &&
                    !Files.isSymbolicLink(final.toPath()) &&
                    !Files.isSymbolicLink(part.toPath()) &&
                    (!Files.exists(final.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                        Files.isRegularFile(final.toPath(), LinkOption.NOFOLLOW_LINKS)) &&
                    (!Files.exists(part.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                        Files.isRegularFile(part.toPath(), LinkOption.NOFOLLOW_LINKS)) &&
                    final.canonicalFile != part.canonicalFile
            }.getOrDefault(false)
            if (!pathOk) return "AIRC_RECOVERY_UNMANAGED_PATH"
            if (!seenTargets.add(final.canonicalPath)) return "AIRC_RECOVERY_DUPLICATE_TARGET"
            val rollback = File(final.parentFile, "${final.name}.bak")
            val stagedRollback = File(final.parentFile, "${final.name}.bak.staged")
            // A35: the rollback sidecars are *also* mutable filesystem inputs.
            // If another checkpoint has a symlink, directory or empty backup,
            // rejecting it after starting the first commit would break the
            // batch-wide preflight guarantee. Check both before any mutation.
            val rollbackOk = listOf(rollback, stagedRollback).all { candidate ->
                runCatching {
                    val candidatePath = candidate.toPath()
                    !Files.isSymbolicLink(candidatePath) &&
                        candidate.parentFile.canonicalFile == root &&
                        (!Files.exists(candidatePath, LinkOption.NOFOLLOW_LINKS) ||
                            (Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS) &&
                                Files.size(candidatePath) > 0L))
                }.getOrDefault(false)
            }
            if (!rollbackOk) return "AIRC_RECOVERY_UNSAFE_BACKUP"
            if (Files.exists(rollback.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                Files.exists(stagedRollback.toPath(), LinkOption.NOFOLLOW_LINKS))
                return "AIRC_RECOVERY_AMBIGUOUS_BACKUP"
            for (path in listOf(final, part, rollback, stagedRollback)) {
                if (!occupiedPaths.add(path.canonicalPath))
                    return "AIRC_RECOVERY_PATH_COLLISION"
            }
        }
        return null
    }
}
