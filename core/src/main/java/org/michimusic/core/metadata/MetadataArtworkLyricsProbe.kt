package org.michimusic.core.metadata

import java.nio.charset.StandardCharsets

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

    enum class Format { ID3_V23, ID3_V24, FLAC }
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
                val info = MetadataArtworkEvidence.id3Apic(payload)
                    ?: return Result.Blocked(Reason.ARTWORK_UNSUPPORTED)
                // Our reader only displays a bounded image, not a decoder guarantee.
                if (info.bytes > MAX_PICTURE_BYTES) return Result.Blocked(Reason.RESOURCE_LIMIT)
                // The image start follows encoding, mime-NUL, picture type and description-NUL.
                val mimeEnd = findZero(payload,1)
                if (mimeEnd < 0 || mimeEnd + 2 >= payload.size) return Result.Blocked(Reason.INVALID_STRUCTURE)
                val descEnd = findZero(payload,mimeEnd+2)
                if (descEnd < 0 || descEnd+1+info.bytes != payload.size)
                    return Result.Blocked(Reason.INVALID_STRUCTURE)
                images += Artwork(images.size,info,payload.copyOfRange(descEnd+1,payload.size))
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
