package org.michimusic.core.metadata

import java.security.MessageDigest

/**
 * ME-19: append-only artwork addition. The new picture has no source identity;
 * it is validated by its own bytes, MIME, type and description. Existing
 * pictures are never addressed or reordered by this operation.
 */
object MetadataArtworkAdditionPreview {
    const val MAX_ART = 6
    enum class Reason {
        NO_INSPECTION, TOO_MANY_ARTWORKS, INVALID_BYTES,
        UNSUPPORTED_MIME, INVALID_TYPE, INVALID_DESCRIPTION,
    }
    data class Proposal(
        val ordinal: Int,
        val type: Int,
        val description: String,
        val proposedMime: String,
        val proposedSize: Int,
        val proposedSha256: String,
        val proposedBytes: ByteArray,
    )
    sealed interface Result {
        data class Ready(val proposal: Proposal) : Result
        data class Blocked(val reason: Reason) : Result
    }

    fun review(
        snapshot: MetadataArtworkLyricsProbe.Snapshot?,
        bytes: ByteArray,
        mime: String,
        type: Int = 3,
        description: String = "",
    ): Result {
        if (snapshot == null) return Result.Blocked(Reason.NO_INSPECTION)
        if (snapshot.artworks.size >= MAX_ART)
            return Result.Blocked(Reason.TOO_MANY_ARTWORKS)
        if (bytes.size !in 16..MetadataArtworkReplacementPreview.MAX_IMAGE_BYTES)
            return Result.Blocked(Reason.INVALID_BYTES)
        if (mime !in setOf("image/jpeg", "image/png"))
            return Result.Blocked(Reason.UNSUPPORTED_MIME)
        if (!matchesSignature(mime, bytes))
            return Result.Blocked(Reason.INVALID_BYTES)
        if (type !in 0..20) return Result.Blocked(Reason.INVALID_TYPE)
        if (description.length > 2048 || description.any { it.isISOControl() })
            return Result.Blocked(Reason.INVALID_DESCRIPTION)
        return Result.Ready(Proposal(
            ordinal = snapshot.artworks.size,
            type = type,
            description = description,
            proposedMime = mime,
            proposedSize = bytes.size,
            proposedSha256 = sha256(bytes),
            proposedBytes = bytes.copyOf(),
        ))
    }

    private fun matchesSignature(mime: String, bytes: ByteArray): Boolean = when (mime) {
        "image/png" -> bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() &&
            bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte()
        "image/jpeg" -> bytes.size >= 3 &&
            bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte()
        else -> false
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
}
