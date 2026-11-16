package org.michimusic.mobile.devices.aircat

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * A42: deny success if any original file lacks a matching, positive-size
 * completed receipt. This is a final batch check, not a replacement for each
 * file's SHA-256 verification, Room receipt and durable journal completion.
 * Operates once per user-requested transfer; no worker, network or disk scan.
 */
internal object AirCatBatchCompletionPolicy {
    /** A44: a batch cannot become COMPLETED on two aggregate flags alone.
     * Receipts are ordered one-to-one with signed manifest items, created from
     * checkpoint+library readbacks AFTER each file's durable journal settlement.
     */
    fun completeWithReceipts(
        manifest: AirCatManifest,
        committedFilesInManifestOrder: List<File>,
        receipts: List<AirCatCommitReceiptPolicy.Confirmed>,
        allShaVerified: Boolean,
        allLibraryAndCheckpointsCommitted: Boolean,
        reportedBytes: Long,
    ): Boolean {
        if (!complete(manifest, committedFilesInManifestOrder,
                allShaVerified, allLibraryAndCheckpointsCommitted, reportedBytes) ||
            receipts.size != manifest.items.size) return false
        return manifest.items.indices.all { index ->
            val item = manifest.items[index]
            val file = committedFilesInManifestOrder[index]
            val receipt = receipts[index]
            receipt.transferKey.isNotBlank() &&
                receipt.transferId == manifest.transferId &&
                receipt.sourceMichiId == manifest.sourceDeviceId &&
                receipt.logicalTrackId == item.logicalTrackId &&
                receipt.remoteTrackId == item.remoteTrackId &&
                receipt.size == item.size &&
                receipt.sha256.equals(item.sha256, ignoreCase = true) &&
                receipt.finalPath == file.absolutePath
        }
    }

    fun complete(
        manifest: AirCatManifest,
        committedFilesInManifestOrder: List<File>,
        allShaVerified: Boolean,
        allLibraryAndCheckpointsCommitted: Boolean,
        reportedBytes: Long,
    ): Boolean {
        if (!allShaVerified || !allLibraryAndCheckpointsCommitted ||
            manifest.manifestVersion != 1 || manifest.items.isEmpty() ||
            manifest.totalTracks != manifest.items.size ||
            committedFilesInManifestOrder.size != manifest.items.size ||
            manifest.totalBytes <= 0L || reportedBytes != manifest.totalBytes) return false
        val logicalIds = HashSet<String>()
        val remoteIds = HashSet<String>()
        val finalPaths = HashSet<String>()
        var sum = 0L
        for ((item, file) in manifest.items.zip(committedFilesInManifestOrder)) {
            if (item.logicalTrackId.isBlank() || item.remoteTrackId.isBlank() ||
                !logicalIds.add(item.logicalTrackId) || !remoteIds.add(item.remoteTrackId) ||
                item.size <= 0L || !item.sha256.matches(Regex("[a-fA-F0-9]{64}"))) return false
            val valid = runCatching {
                !Files.isSymbolicLink(file.toPath()) &&
                    !Files.isSymbolicLink(file.parentFile.toPath()) &&
                    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    finalPaths.add(file.canonicalPath) && file.length() == item.size
            }.getOrDefault(false)
            if (!valid) return false
            sum = try { Math.addExact(sum, item.size) }
                catch (_: ArithmeticException) { return false }
        }
        return sum == manifest.totalBytes
    }
}
