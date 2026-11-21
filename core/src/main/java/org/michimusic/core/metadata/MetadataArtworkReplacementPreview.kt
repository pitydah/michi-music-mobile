package org.michimusic.core.metadata

import java.security.MessageDigest

/** ME-10F: image *proposal*, never a binary writer. No mutation of embedded tags. */
object MetadataArtworkReplacementPreview {
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    enum class Reason { ORIGINAL_NOT_VERIFIED, INVALID_SELECTION, INVALID_BYTES, UNSUPPORTED_MIME,
        SAME_IMAGE, TOO_LARGE, MULTIPLE_TARGETS }
    data class Proposal(
        val index: Int,
        val sourceSha256: String,
        val proposedSha256: String,
        val proposedMime: String,
        val proposedSize: Int,
        /** Temporary user-selected image. Not a commit-ready file. */
        val proposedBytes: ByteArray,
    )
    sealed interface Result {
        data class Ready(val proposal: Proposal) : Result
        data class Blocked(val reason: Reason) : Result
    }
    fun review(original: MetadataArtworkLyricsProbe.Snapshot?, ordinal: Int,
        image: ByteArray, mime: String): Result {
        if (original == null) return Result.Blocked(Reason.ORIGINAL_NOT_VERIFIED)
        val item = original.artworks.singleOrNull { it.ordinal == ordinal }
            ?: return Result.Blocked(Reason.INVALID_SELECTION)
        if (original.artworks.count { it.info.type == item.info.type } != 1)
            return Result.Blocked(Reason.MULTIPLE_TARGETS)
        val originalHash = MessageDigest.getInstance("SHA-256").digest(item.image)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        if (item.info.bytes != item.image.size || item.info.sha256 != originalHash)
            return Result.Blocked(Reason.ORIGINAL_NOT_VERIFIED)
        if (image.size !in 16..MAX_IMAGE_BYTES) return Result.Blocked(Reason.TOO_LARGE)
        if (mime !in setOf("image/jpeg","image/png")) return Result.Blocked(Reason.UNSUPPORTED_MIME)
        val signature = when (mime) {
            "image/png" -> image.size >= 24 && image.copyOfRange(0,8).contentEquals(
                byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10))
            else -> image[0] == 0xff.toByte() && image[1] == 0xd8.toByte() &&
                image[2] == 0xff.toByte() && image.last() == 0xd9.toByte()
        }
        if (!signature) return Result.Blocked(Reason.INVALID_BYTES)
        val hash = MessageDigest.getInstance("SHA-256").digest(image)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        if (hash == item.info.sha256) return Result.Blocked(Reason.SAME_IMAGE)
        return Result.Ready(Proposal(ordinal,item.info.sha256,hash,mime,image.size,image.copyOf()))
    }
}
