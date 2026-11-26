package org.michimusic.core.metadata

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * ME-10F. Strict, bounded, read-only inspection of bytes from the same *one*
 * user-requested embedded-metadata read. Never scans audio, creates a tag,
 * resolves a media URI, or confers a physical write capability.
 *
 * The caller supplies exactly the ID3 header+declared tag or FLAC marker+all
 * metadata blocks, never an arbitrary full audio file or truncated prefix.
 */
object MetadataArtworkLyricsProbe {
    const val MAX_TAG_BYTES: Int = 12 * 1024 * 1024
    private const val MAX_ART: Int = 6
    private const val MAX_TEXT_ENTRIES: Int = 64
    private const val MAX_PICTURE_BYTES: Int = 8 * 1024 * 1024

    enum class Format { ID3_V23, ID3_V24, FLAC, MP4 }
    enum class Reason {
        UNKNOWN_FORMAT, UNSUPPORTED_VERSION_OR_FLAGS, TRUNCATED, INVALID_STRUCTURE,
        ARTWORK_UNSUPPORTED, TEXT_UNSUPPORTED, TOO_MANY_IMAGES, TOO_MANY_TEXT_ENTRIES,
        DUPLICATE_TEXT_IDENTITY, RESOURCE_LIMIT, MISSING_STREAMINFO,
    }
    data class Artwork(
        val ordinal: Int,
        val info: MetadataArtworkEvidence.Picture,
        /** Original artwork bytes. In-memory, ephemeral, never persisted or modified. */
        val image: ByteArray,
    )
    data class Snapshot(
        val format: Format,
        val artworks: List<Artwork>,
        val texts: List<MetadataDescribedTextWorkbench.Item>,
    )
    sealed interface Result {
        data class Ready(val snapshot: Snapshot) : Result
        data class Blocked(val reason: Reason) : Result
    }
    private fun u(x: Byte): Int = x.toInt() and 255
    private fun be(b: ByteArray, i: Int): Int =
        (u(b[i]) shl 24) or (u(b[i+1]) shl 16) or (u(b[i+2]) shl 8) or u(b[i+3])
    private fun findZero(b: ByteArray, from: Int): Int = (from until b.size).firstOrNull { b[it] == 0.toByte() } ?: -1
    private fun syn(b: ByteArray, i: Int): Int? {
        if ((i..i + 3).any { u(b[it]) > 127 }) return null
        return (u(b[i]) shl 21) or (u(b[i + 1]) shl 14) or (u(b[i + 2]) shl 7) or u(b[i + 3])
    }
    /** Length of the complete metadata-only region. Null means invalid/unsupported. */
    fun id3DeclaredLength(header: ByteArray): Int? {
        if (header.size != 10 || !header.copyOfRange(0,3).contentEquals("ID3".toByteArray(StandardCharsets.US_ASCII))) return null
        if (u(header[3]) !in 3..4 || u(header[4]) != 0 || u(header[5]) != 0) return null
        val length = syn(header, 6) ?: return null
        return (length + 10).takeIf { it in 20..MAX_TAG_BYTES }
    }
    fun inspect(bytes: ByteArray): Result {
        if (bytes.size >= 8 && bytes.copyOfRange(4,8)
                .contentEquals("ftyp".toByteArray(StandardCharsets.US_ASCII)))
            return mp4(bytes)
        if (bytes.size > MAX_TAG_BYTES) return Result.Blocked(Reason.RESOURCE_LIMIT)
        return when {
            bytes.size >= 10 && bytes.sliceArray(0..2).contentEquals("ID3".toByteArray()) -> id3(bytes)
            bytes.size >= 4 && bytes.copyOfRange(0,4).contentEquals("fLaC".toByteArray()) -> flac(bytes)
            else -> Result.Blocked(Reason.UNKNOWN_FORMAT)
        }
    }
    private fun id3(bytes: ByteArray): Result {
        if (bytes.size < 10) return Result.Blocked(Reason.TRUNCATED)
        val version = u(bytes[3])
        if (version !in 3..4 || u(bytes[4]) != 0 || u(bytes[5]) != 0)
            return Result.Blocked(Reason.UNSUPPORTED_VERSION_OR_FLAGS)
        val length = syn(bytes, 6) ?: return Result.Blocked(Reason.INVALID_STRUCTURE)
        if (length < 10 || length + 10 != bytes.size) return Result.Blocked(Reason.TRUNCATED)
        val images = mutableListOf<Artwork>()
        val texts = mutableListOf<MetadataDescribedTextWorkbench.Item>()
        var pos = 10
        while (pos < bytes.size) {
            if (bytes.size - pos < 10) {
                if ((pos until bytes.size).all { bytes[it] == 0.toByte() }) break
                return Result.Blocked(Reason.INVALID_STRUCTURE)
            }
            if ((pos until pos+4).all { bytes[it] == 0.toByte() }) {
                if ((pos until bytes.size).all { bytes[it] == 0.toByte() }) break
                return Result.Blocked(Reason.INVALID_STRUCTURE)
            }
            val id = String(bytes, pos, 4, StandardCharsets.US_ASCII)
            if (!id.matches(Regex("[A-Z0-9]{4}"))) return Result.Blocked(Reason.INVALID_STRUCTURE)
            val size = if (version == 4) syn(bytes,pos+4) else be(bytes,pos+4)
            if (size == null || size <= 0 || size > bytes.size - pos - 10)
                return Result.Blocked(Reason.INVALID_STRUCTURE)
            if (u(bytes[pos+8]) != 0 || u(bytes[pos+9]) != 0)
                return Result.Blocked(Reason.UNSUPPORTED_VERSION_OR_FLAGS)
            val payload = bytes.copyOfRange(pos+10,pos+10+size)
            if (id == "APIC") {
                if (images.size >= MAX_ART) return Result.Blocked(Reason.TOO_MANY_IMAGES)
                val parsed = MetadataArtworkEvidence.id3ApicFrame(payload, version)
                    ?: return Result.Blocked(Reason.ARTWORK_UNSUPPORTED)
                val info = parsed.picture
                // Our reader only displays a bounded image, not a decoder guarantee.
                if (info.bytes > MAX_PICTURE_BYTES) return Result.Blocked(Reason.RESOURCE_LIMIT)
                images += Artwork(images.size, info, parsed.image)
            }
            val kind = MetadataId3DescribedTagCodec.Kind.entries.firstOrNull { it.frameId == id }
            if (kind != null) {
                if (texts.size >= MAX_TEXT_ENTRIES) return Result.Blocked(Reason.TOO_MANY_TEXT_ENTRIES)
                val entry = MetadataId3DescribedTagCodec.decode(kind,version,payload)
                    ?: return Result.Blocked(Reason.TEXT_UNSUPPORTED)
                texts += MetadataDescribedTextWorkbench.Item(
                    MetadataDescribedTextWorkbench.Identity(entry.kind,entry.language,entry.description), entry.text)
            }
            pos += 10 + size
        }
        if (texts.map { it.identity }.distinct().size != texts.size)
            return Result.Blocked(Reason.DUPLICATE_TEXT_IDENTITY)
        return Result.Ready(Snapshot(if (version == 3) Format.ID3_V23 else Format.ID3_V24, images,texts))
    }
    private data class Box(val type: String, val payload: Int, val end: Int)
    private fun box(bytes: ByteArray, pos: Int, limit: Int): Box? {
        if (limit - pos < 8) return null
        val size32 = be(bytes, pos).toLong() and 0xffffffffL
        val type = String(bytes, pos + 4, 4, StandardCharsets.US_ASCII)
        var header = 8
        var size = size32
        if (size32 == 1L) {
            if (limit - pos < 16) return null
            var wide = 0L
            for (i in 0 until 8) wide = (wide shl 8) or u(bytes[pos + 8 + i]).toLong()
            size = wide; header = 16
        } else if (size32 == 0L) {
            size = (limit - pos).toLong()
        }
        if (size < header || size > (limit - pos).toLong() || size > Int.MAX_VALUE)
            return null
        return Box(type, pos + header, pos + size.toInt())
    }
    private fun find(bytes: ByteArray, start: Int, end: Int, type: String): Box? {
        var pos = start
        while (pos < end) {
            val next = box(bytes, pos, end) ?: return null
            if (next.type == type) return next
            pos = next.end
        }
        return null
    }
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    private fun validMp4Image(mime: String, image: ByteArray): Boolean = when (mime) {
        "image/png" -> image.size >= 24 &&
            u(image[0]) == 0x89 && image[1] == 0x50.toByte() &&
            image[2] == 0x4E.toByte() && image[3] == 0x47.toByte() &&
            image[4] == 0x0D.toByte() && image[5] == 0x0A.toByte() &&
            image[6] == 0x1A.toByte() && image[7] == 0x0A.toByte()
        "image/jpeg" -> image.size >= 4 && u(image[0]) == 0xFF &&
            u(image[1]) == 0xD8 && u(image[2]) == 0xFF &&
            u(image[image.size - 2]) == 0xFF && u(image[image.size - 1]) == 0xD9
        else -> false
    }
    private fun validContainer(bytes: ByteArray, start: Int, end: Int): Boolean {
        var pos = start
        while (pos < end) {
            val child = box(bytes, pos, end) ?: return false
            pos = child.end
            when (child.type) {
                "moov", "udta", "ilst", "covr" ->
                    if (!validContainer(bytes, child.payload, child.end)) return false
                "meta" -> {
                    val childStart = child.payload + 4
                    if (childStart > child.end ||
                        !validContainer(bytes, childStart, child.end)
                    ) return false
                }
            }
        }
        return pos == end
    }

    private fun mp4(bytes: ByteArray): Result {
        if (bytes.size < 8) return Result.Blocked(Reason.TRUNCATED)
        if (!validContainer(bytes, 0, bytes.size))
            return Result.Blocked(Reason.INVALID_STRUCTURE)
        val moov = find(bytes, 0, bytes.size, "moov")
            ?: return Result.Blocked(Reason.INVALID_STRUCTURE)
        if (moov.end - moov.payload > MAX_TAG_BYTES)
            return Result.Blocked(Reason.RESOURCE_LIMIT)
        if (moov.payload >= moov.end)
            return Result.Blocked(Reason.INVALID_STRUCTURE)
        val empty = Snapshot(Format.MP4, emptyList(), emptyList())
        val udta = find(bytes, moov.payload, moov.end, "udta")
            ?: return Result.Ready(empty)
        val meta = find(bytes, udta.payload, udta.end, "meta")
            ?: return Result.Ready(empty)
        val metaStart = meta.payload + 4
        if (metaStart > meta.end) return Result.Blocked(Reason.INVALID_STRUCTURE)
        val ilst = find(bytes, metaStart, meta.end, "ilst")
            ?: return Result.Ready(empty)
        val covr = find(bytes, ilst.payload, ilst.end, "covr")
            ?: return Result.Ready(empty)
        val images = mutableListOf<Artwork>()
        var pos = covr.payload
        while (pos < covr.end) {
            val data = box(bytes, pos, covr.end)
                ?: return Result.Blocked(Reason.INVALID_STRUCTURE)
            pos = data.end
            if (data.type != "data") continue
            if (data.payload + 8 > data.end)
                return Result.Blocked(Reason.INVALID_STRUCTURE)
            val kind = be(bytes, data.payload)
            val mime = when (kind) {
                13 -> "image/jpeg"
                14 -> "image/png"
                else -> return Result.Blocked(Reason.ARTWORK_UNSUPPORTED)
            }
            val image = bytes.copyOfRange(data.payload + 8, data.end)
            if (images.size >= MAX_ART) return Result.Blocked(Reason.TOO_MANY_IMAGES)
            if (image.size > MAX_PICTURE_BYTES) return Result.Blocked(Reason.RESOURCE_LIMIT)
            if (!validMp4Image(mime, image))
                return Result.Blocked(Reason.ARTWORK_UNSUPPORTED)
            val info = MetadataArtworkEvidence.Picture(
                MetadataArtworkEvidence.Format.MP4_COVR, 3, mime, image.size,
                sha256(image), description = "",
            )
            images += Artwork(images.size, info, image)
        }
        return Result.Ready(Snapshot(Format.MP4, images, emptyList()))
    }
    private fun flac(bytes: ByteArray): Result {
        if (bytes.size < 42) return Result.Blocked(Reason.TRUNCATED)
        val images = mutableListOf<Artwork>()
        var pos = 4
        var isFirst = true
        var sawEnd = false
        while (pos < bytes.size) {
            if (bytes.size - pos < 4) return Result.Blocked(Reason.TRUNCATED)
            val head = u(bytes[pos]); val last = (head and 0x80) != 0
            val kind = head and 0x7F
            val size = (u(bytes[pos+1]) shl 16) or (u(bytes[pos+2]) shl 8) or u(bytes[pos+3])
            if (size > bytes.size - pos - 4) return Result.Blocked(Reason.TRUNCATED)
            if (isFirst && (kind != 0 || size != 34)) return Result.Blocked(Reason.MISSING_STREAMINFO)
            isFirst = false
            if (kind == 6) {
                if (images.size >= MAX_ART) return Result.Blocked(Reason.TOO_MANY_IMAGES)
                val info = MetadataArtworkEvidence.flacPicture(bytes.copyOfRange(pos+4,pos+4+size))
                    ?: return Result.Blocked(Reason.ARTWORK_UNSUPPORTED)
                if (info.bytes > MAX_PICTURE_BYTES) return Result.Blocked(Reason.RESOURCE_LIMIT)
                val payload = bytes.copyOfRange(pos+4,pos+4+size)
                val img = payload.copyOfRange(payload.size - info.bytes,payload.size)
                images += Artwork(images.size,info,img)
            }
            pos += size+4
            if (last) { sawEnd = true; break }
        }
        if (!sawEnd || pos != bytes.size) return Result.Blocked(Reason.TRUNCATED)
        return Result.Ready(Snapshot(Format.FLAC,images,emptyList()))
    }
}
