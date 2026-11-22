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

    /** One validated APIC payload. Shared by evidence and the artwork probe. */
    data class Id3ApicFrame(
        val picture: Picture,
        val image: ByteArray,
        val encoding: Int,
        val description: String,
        /** Offset of the first image byte inside the APIC payload. */
        val imageStart: Int,
    )

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)

    private fun pngInfo(image: ByteArray): Pair<Int, Int>? {
        if (image.size < 33) return null
        if (!image.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))) return null
        if (be(image, 8) != 13L) return null
        if (!image.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(StandardCharsets.US_ASCII)))
            return null
        val width = be(image, 16).toInt()
        val height = be(image, 20).toInt()
        if (width !in 1..16384 || height !in 1..16384) return null
        // Require the terminal IEND chunk, not just a plausible header.
        var at = 8
        while (at <= image.size - 12) {
            val length = be(image, at)
            if (length < 0 || length > image.size - at - 12) return null
            if (image.copyOfRange(at + 4, at + 8)
                    .contentEquals("IEND".toByteArray(StandardCharsets.US_ASCII))) {
                return width to height
            }
            at += 12 + length.toInt()
        }
        return null
    }

    private fun jpegInfo(image: ByteArray): Pair<Int, Int>? {
        if (image.size < 12) return null
        if (image[0] != 0xff.toByte() || image[1] != 0xd8.toByte()) return null
        if (image[image.lastIndex - 1] != 0xff.toByte() ||
            image[image.lastIndex] != 0xd9.toByte()) return null
        var at = 2
        var result: Pair<Int, Int>? = null
        while (at + 4 <= image.size) {
            if (image[at] != 0xff.toByte()) return null
            var markerAt = at + 1
            // A marker may be preceded by any number of 0xff fill bytes.
            while (markerAt < image.size && image[markerAt] == 0xff.toByte()) markerAt++
            if (markerAt >= image.size) return null
            val marker = image[markerAt].toInt() and 255
            at = markerAt + 1
            // Entropy-coded data follows SOS; a marker walk cannot continue there.
            if (marker == 0xda) return result
            if (marker == 0xd8 || marker == 0xd9 || marker == 0x01 ||
                marker in 0xd0..0xd7) continue
            if (at + 2 > image.size) return null
            val length = u16(image, at)
            if (length < 2 || at + length > image.size) return null
            if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
                    0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                if (length < 7) return null
                val height = u16(image, at + 3)
                val width = u16(image, at + 5)
                if (width !in 1..16384 || height !in 1..16384) return null
                result = width to height
            }
            at += length
        }
        return result
    }

    private fun imageInfo(mime: String, image: ByteArray): Pair<Int, Int>? = when (mime) {
        "image/png" -> pngInfo(image)
        "image/jpeg" -> jpegInfo(image)
        else -> null
    }

    private fun decodeText(data: ByteArray, encoding: Int): String? = try {
        when (encoding) {
            0 -> String(data, StandardCharsets.ISO_8859_1)
            1 -> String(data, StandardCharsets.UTF_16)
            2 -> String(data, StandardCharsets.UTF_16BE)
            3 -> StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString()
            else -> null
        }
    } catch (_: Exception) { null }

    private fun descriptionEnd(payload: ByteArray, start: Int, encoding: Int): Int? {
        if (start >= payload.size) return null
        if (encoding == 0 || encoding == 3) {
            val end = (start until payload.size).firstOrNull { payload[it] == 0.toByte() }
                ?: return null
            return end
        }
        // UTF-16 descriptions terminate on an aligned 00 00 code unit.
        var at = start
        if (at + 1 >= payload.size) return null
        if (payload[at] == 0.toByte() && payload[at + 1] == 0.toByte()) return at
        while (at + 1 < payload.size) {
            if (payload[at] == 0.toByte() && payload[at + 1] == 0.toByte()) return at
            at += 2
        }
        return null
    }

    /**
     * ID3v2.3/v2.4 APIC payload parser. v2.3 accepts Latin-1 and UTF-16; v2.4
     * additionally accepts UTF-16BE and UTF-8. The MIME and description are
     * bounded and the image is validated against the declared MIME family.
     */
    fun id3ApicFrame(payload: ByteArray, version: Int = 4): Id3ApicFrame? {
        if (version !in 3..4) return null
        if (payload.size !in 8..(MAX_BYTES + 2048)) return null
        val encoding = payload[0].toInt() and 255
        val allowed = if (version == 3) setOf(0, 1) else setOf(0, 1, 2, 3)
        if (encoding !in allowed) return null
        val mimeEnd = (1 until minOf(payload.size, 80)).firstOrNull { payload[it] == 0.toByte() }
            ?: return null
        val mime = try {
            String(payload, 1, mimeEnd - 1, StandardCharsets.US_ASCII)
        } catch (_: Exception) { return null }
        if (mime != "image/jpeg" && mime != "image/png") return null
        val typeIndex = mimeEnd + 1
        if (typeIndex >= payload.size) return null
        val type = payload[typeIndex].toInt() and 255
        if (type > 20) return null
        val descriptorStart = typeIndex + 1
        val descEnd = descriptionEnd(payload, descriptorStart, encoding) ?: return null
        if (descEnd - descriptorStart > 1024) return null
        val descriptionBytes = payload.copyOfRange(descriptorStart, descEnd)
        if (encoding == 1 && descriptionBytes.isNotEmpty()) {
            if (descriptionBytes.size < 2) return null
            val bom = ((descriptionBytes[0].toInt() and 255) shl 8) or
                (descriptionBytes[1].toInt() and 255)
            if (bom != 0xfeff && bom != 0xfffe) return null
            if (descriptionBytes.size % 2 != 0) return null
        }
        if (encoding == 2 && descriptionBytes.size % 2 != 0) return null
        val description = decodeText(descriptionBytes, encoding) ?: return null
        val imageStart = descEnd + if (encoding == 0 || encoding == 3) 1 else 2
        if (imageStart >= payload.size) return null
        val image = payload.copyOfRange(imageStart, payload.size)
        if (image.size !in 4..MAX_BYTES) return null
        val dimensions = imageInfo(mime, image) ?: return null
        val info = Picture(Format.ID3_APIC, type, mime, image.size, digest(image),
            dimensions.first, dimensions.second)
        return Id3ApicFrame(info, image, encoding, description, imageStart)
    }

    /** Compatibility entry point for callers without an ID3 version. */
    fun id3Apic(payload: ByteArray): Picture? = id3ApicFrame(payload, 4)?.picture
}
