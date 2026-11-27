package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
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
    private const val MAX_CONTAINER_DEPTH: Int = 16

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
        // The cap must cover the in-memory MP4 branch too, not only ID3/FLAC.
        if (bytes.size > MAX_TAG_BYTES) return Result.Blocked(Reason.RESOURCE_LIMIT)
        if (bytes.size >= 8 && bytes.copyOfRange(4,8)
                .contentEquals("ftyp".toByteArray(StandardCharsets.US_ASCII)))
            return mp4(bytes)
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
    private fun validContainer(
        bytes: ByteArray,
        start: Int,
        end: Int,
        depth: Int = 0,
    ): Boolean {
        if (depth > MAX_CONTAINER_DEPTH) return false
        var pos = start
        while (pos < end) {
            val child = box(bytes, pos, end) ?: return false
            pos = child.end
            when (child.type) {
                "moov", "udta", "ilst", "covr" ->
                    if (!validContainer(bytes, child.payload, child.end, depth + 1)) return false
                "meta" -> {
                    val childStart = child.payload + 4
                    if (childStart > child.end ||
                        !validContainer(bytes, childStart, child.end, depth + 1)
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

    // ---------------------------------------------------------------------
    // Positional MP4 observation over a FileChannel.
    //
    // The in-memory path above needs the caller to have already materialised
    // the whole container. This path never does that: only atom headers and the
    // `covr` leaf payload are read, every `mdat` payload is skipped by
    // arithmetic, and both atom count and container depth are bounded.
    // ---------------------------------------------------------------------

    private const val MAX_MP4_BYTES: Long = 1024L * 1024L * 1024L
    private const val MAX_CHANNEL_ATOMS: Int = 4096
    private const val MAX_CHANNEL_DEPTH: Int = 8

    private class ChannelRefusal(val reason: Reason) : Exception()

    private class ChannelBox(val type: String, val payload: Long, val end: Long)

    data class ChannelObservation(
        val snapshot: Snapshot,
        val atomsVisited: Int,
        val maxDepth: Int,
        val bytesRead: Long,
        val mediaBytesSkipped: Long,
    )

    sealed interface ChannelResult {
        data class Ready(val observation: ChannelObservation) : ChannelResult
        data class Blocked(val reason: Reason) : ChannelResult
    }

    fun inspectChannel(channel: FileChannel): ChannelResult = try {
        val size = channel.size()
        if (size < 8L) throw ChannelRefusal(Reason.TRUNCATED)
        if (size > MAX_MP4_BYTES) throw ChannelRefusal(Reason.RESOURCE_LIMIT)
        var atoms = 0
        var bytesRead = 0L
        var maxDepth = 0
        var mediaBytesSkipped = 0L

        fun read(offset: Long, count: Int): ByteArray {
            if (count < 0 || offset < 0L || count.toLong() > size - offset)
                throw ChannelRefusal(Reason.TRUNCATED)
            val buffer = ByteBuffer.allocate(count)
            var at = offset
            while (buffer.hasRemaining()) {
                val got = channel.read(buffer, at)
                if (got <= 0) throw ChannelRefusal(Reason.TRUNCATED)
                at += got
                bytesRead += got
            }
            return buffer.array()
        }

        fun next(at: Long, limit: Long): ChannelBox {
            if (limit - at < 8L) throw ChannelRefusal(Reason.TRUNCATED)
            if (++atoms > MAX_CHANNEL_ATOMS) throw ChannelRefusal(Reason.RESOURCE_LIMIT)
            val head = read(at, 16.coerceAtMost((limit - at).toInt()))
            val size32 = ByteBuffer.wrap(head, 0, 4).order(ByteOrder.BIG_ENDIAN)
                .int.toLong() and 0xffffffffL
            val type = String(head, 4, 4, StandardCharsets.US_ASCII)
            var header = 8
            var bytes = size32
            if (size32 == 1L) {
                if (head.size < 16) throw ChannelRefusal(Reason.TRUNCATED)
                val wide = ByteBuffer.wrap(head, 8, 8).order(ByteOrder.BIG_ENDIAN).long
                if (wide < 0L) throw ChannelRefusal(Reason.RESOURCE_LIMIT)
                bytes = wide
                header = 16
            } else if (size32 == 0L) {
                bytes = limit - at
            }
            if (bytes < header || bytes > limit - at)
                throw ChannelRefusal(Reason.INVALID_STRUCTURE)
            return ChannelBox(type, at + header, at + bytes)
        }

        fun covrArtworks(box: ChannelBox): List<Artwork> {
            val images = mutableListOf<Artwork>()
            var at = box.payload
            while (at < box.end) {
                val data = next(at, box.end)
                at = data.end
                if (data.type != "data") continue
                if (data.payload + 8L > data.end) throw ChannelRefusal(Reason.INVALID_STRUCTURE)
                if (images.size >= MAX_ART) throw ChannelRefusal(Reason.TOO_MANY_IMAGES)
                if (data.end - data.payload - 8L > MAX_PICTURE_BYTES.toLong())
                    throw ChannelRefusal(Reason.RESOURCE_LIMIT)
                val head = read(data.payload, 8)
                val mime = when (
                    ByteBuffer.wrap(head, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                ) {
                    13 -> "image/jpeg"
                    14 -> "image/png"
                    else -> throw ChannelRefusal(Reason.ARTWORK_UNSUPPORTED)
                }
                val length = (data.end - data.payload - 8L).toInt()
                val image = read(data.payload + 8L, length)
                if (!validMp4Image(mime, image))
                    throw ChannelRefusal(Reason.ARTWORK_UNSUPPORTED)
                val info = MetadataArtworkEvidence.Picture(
                    MetadataArtworkEvidence.Format.MP4_COVR, 3, mime, image.size,
                    sha256(image), description = "",
                )
                images += Artwork(images.size, info, image)
            }
            return images
        }

        var collected: List<Artwork>? = null
        var sawMoov = false

        fun walk(start: Long, end: Long, level: Int) {
            if (level > MAX_CHANNEL_DEPTH) throw ChannelRefusal(Reason.RESOURCE_LIMIT)
            if (level > maxDepth) maxDepth = level
            var at = start
            while (at < end) {
                val box = next(at, end)
                when (box.type) {
                    "moov" -> {
                        if (level != 0 || sawMoov)
                            throw ChannelRefusal(Reason.INVALID_STRUCTURE)
                        sawMoov = true
                        walk(box.payload, box.end, level + 1)
                    }
                    "udta", "ilst" -> walk(box.payload, box.end, level + 1)
                    "meta" -> {
                        if (box.payload + 4L > box.end)
                            throw ChannelRefusal(Reason.INVALID_STRUCTURE)
                        walk(box.payload + 4L, box.end, level + 1)
                    }
                    "covr" -> {
                        if (collected != null) throw ChannelRefusal(Reason.INVALID_STRUCTURE)
                        collected = covrArtworks(box)
                    }
                    "moof", "mfra" -> throw ChannelRefusal(Reason.INVALID_STRUCTURE)
                    "mdat" -> mediaBytesSkipped += box.end - box.payload
                }
                at = box.end
            }
        }

        walk(0L, size, 0)
        if (!sawMoov) throw ChannelRefusal(Reason.INVALID_STRUCTURE)
        ChannelResult.Ready(
            ChannelObservation(
                snapshot = Snapshot(Format.MP4, collected ?: emptyList(), emptyList()),
                atomsVisited = atoms,
                maxDepth = maxDepth,
                bytesRead = bytesRead,
                mediaBytesSkipped = mediaBytesSkipped,
            ),
        )
    } catch (refusal: ChannelRefusal) {
        ChannelResult.Blocked(refusal.reason)
    } catch (error: Exception) {
        ChannelResult.Blocked(Reason.INVALID_STRUCTURE)
    }
}
