package org.michimusic.mobile.artist.artwork

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * D45: isolate an exact, unreferenced file before irreversible deletion.
 * No nonatomic fallback is allowed. Private quarantine is kept when an inode
 * swap, unexpected bytes, symlink, or interrupted move makes ownership unclear.
 */
internal sealed interface ArtistArtworkQuarantineStage {
    data class Staged(val path: Path) : ArtistArtworkQuarantineStage
    data object SourceAbsent : ArtistArtworkQuarantineStage
    data object ReviewRequired : ArtistArtworkQuarantineStage
    data object Retry : ArtistArtworkQuarantineStage
}

internal enum class ArtistArtworkQuarantineDelete { DELETED, REVIEW_REQUIRED, RETRY }

internal object ArtistArtworkQuarantine {
    private const val DIRECTORY = ".pending-artwork-delete"
    private val HEX = Regex("[a-f0-9]{64}")
    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Stable private destination; no untrusted cleanup ID becomes a pathname. */
    fun destination(root: File, cleanupId: String, assetId: String): Path =
        root.toPath().resolve(DIRECTORY).resolve("q-" + sha256(cleanupId + "\u0000" + assetId))

    private fun attrs(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW)
    } catch (_: IOException) { null }

    private fun isSafeFile(path: Path): Boolean {
        val a = attrs(path) ?: return false
        return a.isRegularFile && !a.isSymbolicLink && a.fileKey() != null
    }

    private fun safeDirectory(path: Path): Boolean = Files.isDirectory(path, NOFOLLOW) && !Files.isSymbolicLink(path)

    private fun matches(path: Path, expectedHash: String): Boolean =
        isSafeFile(path) && artworkCleanupDigestMatches(path.toFile(), expectedHash)

    fun stage(
        root: File,
        source: File,
        cleanupId: String,
        assetId: String,
        expectedHash: String,
        /** Test-only race injection; production always uses the default. */
        beforeAtomicMove: () -> Unit = {},
        /** Test-only: a replacement between the FINAL digest and atomic rename. */
        afterFinalDigest: () -> Unit = {},
    ): ArtistArtworkQuarantineStage {
        if (!HEX.matches(expectedHash) || cleanupId.isBlank() || assetId.isBlank())
            return ArtistArtworkQuarantineStage.ReviewRequired
        val base = root.toPath()
        val path = source.toPath()
        if (path.parent != base || Files.isSymbolicLink(base) || Files.isSymbolicLink(path))
            return ArtistArtworkQuarantineStage.ReviewRequired
        val folder = base.resolve(DIRECTORY)
        try {
            if (Files.notExists(base, NOFOLLOW)) return ArtistArtworkQuarantineStage.SourceAbsent
            if (!safeDirectory(base)) return ArtistArtworkQuarantineStage.ReviewRequired
            if (!Files.exists(folder, NOFOLLOW)) Files.createDirectory(folder)
            if (!Files.isDirectory(folder, NOFOLLOW) || Files.isSymbolicLink(folder))
                return ArtistArtworkQuarantineStage.ReviewRequired
            val quarantine = destination(root, cleanupId, assetId)
            if (Files.exists(quarantine, NOFOLLOW)) {
                // Power loss after the move: exact, verified file remains recoverable.
                if (Files.exists(path, NOFOLLOW)) {
                    // The previous recovery may have been interrupted after linking.
                    // Remove only the redundant verified link, then re-enter staging on retry.
                    return if (finishInterruptedRestore(quarantine, path, expectedHash))
                        ArtistArtworkQuarantineStage.Retry else ArtistArtworkQuarantineStage.ReviewRequired
                }
                return if (matches(quarantine, expectedHash))
                    ArtistArtworkQuarantineStage.Staged(quarantine)
                else ArtistArtworkQuarantineStage.ReviewRequired
            }
            if (Files.notExists(path, NOFOLLOW)) return ArtistArtworkQuarantineStage.SourceAbsent
            if (!Files.exists(path, NOFOLLOW)) return ArtistArtworkQuarantineStage.Retry
            val before = attrs(path) ?: return ArtistArtworkQuarantineStage.ReviewRequired
            if (!before.isRegularFile || before.fileKey() == null || !matches(path, expectedHash))
                return ArtistArtworkQuarantineStage.ReviewRequired
            beforeAtomicMove()
            // Re-verify immediately before rename: do not stage a surprise replacement
            // that appeared while the first digest was being calculated.
            val immediatelyBefore = attrs(path)
            if (immediatelyBefore?.fileKey() != before.fileKey() ||
                immediatelyBefore?.size() != before.size() ||
                !matches(path, expectedHash))
                return ArtistArtworkQuarantineStage.ReviewRequired
            // Must be same-volume ATOMIC_MOVE; never silently fall back to copy/delete.
            afterFinalDigest()
            if (!safeDirectory(base) || !safeDirectory(folder) || !Files.notExists(quarantine, NOFOLLOW))
                return ArtistArtworkQuarantineStage.ReviewRequired
            Files.move(path, quarantine, StandardCopyOption.ATOMIC_MOVE)
            val after = attrs(quarantine)
            if (after == null || !after.isRegularFile || after.isSymbolicLink ||
                before.fileKey() != after.fileKey() || before.size() != after.size() ||
                !matches(quarantine, expectedHash)) {
                // D47: race after final digest. Move the unexpected regular inode BACK
                // only if the source slot is still vacant; never overwrite a newcomer.
                // If restoration is impossible, quarantine is retained for review.
                restoreUnexpected(quarantine, path)
                return ArtistArtworkQuarantineStage.ReviewRequired
            }
            return ArtistArtworkQuarantineStage.Staged(quarantine)
        } catch (_: AtomicMoveNotSupportedException) {
            // Repeating an unsupported operation would cause endless worker wakeups.
            return ArtistArtworkQuarantineStage.ReviewRequired
        } catch (_: UnsupportedOperationException) {
            return ArtistArtworkQuarantineStage.ReviewRequired
        } catch (_: IOException) {
            return ArtistArtworkQuarantineStage.Retry
        } catch (_: SecurityException) {
            return ArtistArtworkQuarantineStage.ReviewRequired
        }
    }


    /** Repatriate an unexpected inode safely; never authorize its deletion. */
    private fun restoreUnexpected(quarantine: Path, original: Path): Boolean {
        val found = attrs(quarantine) ?: return false
        if (!found.isRegularFile || found.isSymbolicLink || found.fileKey() == null ||
            Files.exists(original, NOFOLLOW)) return false
        // ATOMIC_MOVE may replace an existing destination depending on FileSystemProvider.
        // An exclusive hard link fails when another actor has recreated `original`.
        return restoreByExclusiveLink(quarantine, original, found)

    }

    /** One atomic fail-if-exists name reservation, then unlink quarantine. */
    private fun restoreByExclusiveLink(quarantine: Path, source: Path,
                                       verified: BasicFileAttributes): Boolean {
        if (!verified.isRegularFile || verified.isSymbolicLink || verified.fileKey() == null ||
            Files.exists(source, NOFOLLOW)) return false
        return try {
            // No REPLACE_EXISTING: unlike ATOMIC_MOVE, createLink never overwrites.
            Files.createLink(source, quarantine)
            val restored = attrs(source)
            if (restored?.fileKey() != verified.fileKey() || restored?.isSymbolicLink != false ||
                !safeDirectory(quarantine.parent) || !safeDirectory(source.parent) ||
                attrs(quarantine)?.fileKey() != verified.fileKey()) {
                // Preserve both names for inspection, never attempt an unsafe unlink.
                false
            } else {
                Files.delete(quarantine)
                true
            }
        } catch (_: IOException) { false }
        catch (_: UnsupportedOperationException) { false }
        catch (_: SecurityException) { false }
    }

    /** Only the app-private, isolated file is unlinked; source pathname is never unlinked. */
    fun destroy(stage: ArtistArtworkQuarantineStage.Staged, root: File,
                cleanupId: String, assetId: String, expectedHash: String): ArtistArtworkQuarantineDelete {
        val expected = destination(root, cleanupId, assetId)
        // A nonmatching quarantined file requires human review, not infinite
        // WorkManager retries that can never become safe on their own.
        if (stage.path != expected || !safeDirectory(root.toPath()) ||
            !safeDirectory(expected.parent) || !matches(expected, expectedHash))
            return ArtistArtworkQuarantineDelete.REVIEW_REQUIRED
        return try {
            Files.delete(expected)
            ArtistArtworkQuarantineDelete.DELETED
        } catch (_: java.nio.file.NoSuchFileException) {
            ArtistArtworkQuarantineDelete.REVIEW_REQUIRED
        } catch (_: IOException) {
            ArtistArtworkQuarantineDelete.RETRY
        } catch (_: SecurityException) {
            ArtistArtworkQuarantineDelete.REVIEW_REQUIRED
        }
    }

    /** D52: recover a shared asset after interruption between stage and journal ack.
     * Never destroy quarantine here, except the verified unlink after exclusive restoration.
     * If both names exist, complete only a verified same-inode interrupted restoration;
     * preserve distinct inodes (even identical bytes) for review.
     */
    fun recoverReferenced(root: File, source: File, cleanupId: String,
                          assetId: String, expectedHash: String): Boolean {
        val base = root.toPath()
        val original = source.toPath()
        val q = destination(root, cleanupId, assetId)
        if (!HEX.matches(expectedHash) || original.parent != base || !safeDirectory(base)) return false
        if (!Files.notExists(q.parent, NOFOLLOW) && !safeDirectory(q.parent)) return false
        if (Files.notExists(q, NOFOLLOW)) return matches(original, expectedHash)
        if (!Files.notExists(original, NOFOLLOW)) {
            // D57: crash between exclusive hard-link creation and quarantine unlink.
            // Equal bytes alone are insufficient: both names must be the SAME inode.
            return finishInterruptedRestore(q, original, expectedHash)
        }
        return restore(ArtistArtworkQuarantineStage.Staged(q), root, source,
            cleanupId, assetId, expectedHash)
    }

    /** Complete only a proven interrupted restoration, never unlink the source name. */
    private fun finishInterruptedRestore(q: Path, source: Path, hash: String): Boolean {
        return try {
            if (!safeDirectory(q.parent) || !safeDirectory(source.parent)) return false
            val first = attrs(q) ?: return false
            val restored = attrs(source) ?: return false
            val key = first.fileKey() ?: return false
            if (!first.isRegularFile || !restored.isRegularFile ||
                restored.fileKey() != key || !matches(q, hash) || !matches(source, hash)) return false
            if (!safeDirectory(q.parent) || !safeDirectory(source.parent) ||
                attrs(q)?.fileKey() != key || attrs(source)?.fileKey() != key) return false
            Files.delete(q)
            matches(source, hash)
        } catch (_: IOException) { false }
        catch (_: UnsupportedOperationException) { false }
        catch (_: SecurityException) { false }
    }

    /** A newly referenced asset must be restored, not silently discarded. */
    fun restore(stage: ArtistArtworkQuarantineStage.Staged, root: File,
                source: File, cleanupId: String, assetId: String, expectedHash: String): Boolean {
        val expected = destination(root, cleanupId, assetId)
        if (stage.path != expected || source.toPath().parent != root.toPath() ||
            !safeDirectory(root.toPath()) || !safeDirectory(expected.parent) ||
            !Files.notExists(source.toPath(), NOFOLLOW) ||
            !matches(expected, expectedHash)) return false
        val verified = attrs(expected) ?: return false
        return restoreByExclusiveLink(expected, source.toPath(), verified)
    }
}
