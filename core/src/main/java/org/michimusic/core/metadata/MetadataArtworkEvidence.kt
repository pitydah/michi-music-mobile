package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * ME-10D: independent, read-only evidence for an existing embedded picture.
 * Never decodes a bitmap or writes an APIC / FLAC PICTURE. Only selected binary
 * structures are inspected under an explicit per-picture budget.
 */
object MetadataArtworkEvidence {
    enum class Format { ID3_APIC, FLAC_PICTURE }
    data class Picture(val format: Format, val type: Int, val mime: String,
        val bytes: Int, val sha256: String, val width: Int? = null, val height: Int? = null)
    private const val MAX_BYTES = 8 * 1024 * 1024
    private fun digest(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun valid(mime: String, image: ByteArray): Boolean = when (mime) {
        "image/png" -> image.size >= 24 && image.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
        "image/jpeg" -> image.size >= 4 && image[0] == 0xff.toByte() &&
            image[1] == 0xd8.toByte() && image[2] == 0xff.toByte() &&
            image[image.lastIndex] == 0xd9.toByte()
        else -> false
    }
    private fun text(data: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()
    } catch (_: Exception) { null }
    private fun be(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 255L) shl 24) or
        ((data[offset + 1].toLong() and 255L) shl 16) or
        ((data[offset + 2].toLong() and 255L) shl 8) or (data[offset + 3].toLong() and 255L)

    /** Full FLAC PICTURE metadata-block payload, not including the 4-byte block header. */
    fun flacPicture(block: ByteArray): Picture? {
        if (block.size !in 36..(MAX_BYTES + 4096)) return null
        var pos = 0
        fun word(): Long? {
            if (pos > block.size - 4) return null
            return be(block, pos).also { pos += 4 }
        }
        val type = word()?.takeIf { it in 0L..20L }?.toInt() ?: return null
        val mimeSize = word()?.takeIf { it in 9L..64L }?.toInt() ?: return null
        if (pos > block.size - mimeSize) return null
        val mime = text(block.copyOfRange(pos, pos + mimeSize)) ?: return null
        pos += mimeSize
        if (mime != "image/jpeg" && mime != "image/png") return null
        val descSize = word()?.takeIf { it <= 2048 }?.toInt() ?: return null
        if (pos > block.size - descSize) return null
        if (text(block.copyOfRange(pos, pos + descSize)) == null) return null
        pos += descSize
        val width = word()?.takeIf { it in 1..16384 }?.toInt() ?: return null
        val height = word()?.takeIf { it in 1..16384 }?.toInt() ?: return null
        val depth = word()?.takeIf { it in 1..64 } ?: return null
        val palette = word() ?: return null
        if (palette > 65536L || depth == 0L) return null
        val imageSize = word()?.takeIf { it in 4L..MAX_BYTES.toLong() }?.toInt() ?: return null
        if (pos != block.size - imageSize) return null
        val image = block.copyOfRange(pos, block.size)
        if (!valid(mime, image)) return null
        return Picture(Format.FLAC_PICTURE, type, mime, imageSize, digest(image), width, height)
    }

    /** ID3v2.3/v2.4 APIC frame data (not the ID3 frame header). */
    fun id3Apic(payload: ByteArray): Picture? {
        if (payload.size !in 13..(MAX_BYTES + 2048)) return null
        val encoding = payload[0].toInt() and 255
        if (encoding !in setOf(0, 3)) return null // UTF-16 descriptors remain read-only, not guessed
        val mimeEnd = (1 until minOf(payload.size, 80)).firstOrNull { payload[it] == 0.toByte() }
            ?: return null
        val mime = String(payload, 1, mimeEnd - 1, StandardCharsets.US_ASCII)
        val typeIndex = mimeEnd + 1
        if (typeIndex >= payload.size) return null
        val type = payload[typeIndex].toInt() and 255
        if (type > 20) return null
        val descriptorStart = typeIndex + 1
        val descEnd = (descriptorStart until payload.size).firstOrNull { payload[it] == 0.toByte() }
            ?: return null
        val desc = payload.copyOfRange(descriptorStart, descEnd)
        if (desc.size > 1024) return null
        if (encoding == 3 && text(desc) == null) return null
        val imageStart = descEnd + 1
        val size = payload.size - imageStart
        if (size !in 4..MAX_BYTES) return null
        val image = payload.copyOfRange(imageStart, payload.size)
        if (!valid(mime, image)) return null
        return Picture(Format.ID3_APIC, type, mime, size, digest(image))
    }
}
