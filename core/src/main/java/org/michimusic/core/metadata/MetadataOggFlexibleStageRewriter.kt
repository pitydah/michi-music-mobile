package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * Private-only Ogg/Opus-Vorbis variable-length comment transformation.
 *
 * Deliberately supports ONLY the first two complete, single-packet header pages:
 * BOS identification on page 0, comments alone on page 1, and a page 2 that must
 * remain byte-for-byte identical. No audio page, setup packet or sequence number
 * is ever repaginated. Multi-page/interleaved header layouts are refused.
 * A candidate is NOT proof that ContentResolver can safely replace its source.
 */
object MetadataOggFlexibleStageRewriter {
    enum class Code {
        INVALID_REVIEW, UNSAFE_PATH, INVALID_PAGE, UNSUPPORTED_LAYOUT, UNSUPPORTED_FIELD,
        SOURCE_CHANGED, TOO_LARGE, READBACK_FAILED,
    }
    class Refused(val code: Code) : IOException(code.name)
    data class Result(
        val originalBytes: Long,
        val candidateBytes: Long,
        val changedFields: Set<MetadataField>,
        val canCommitToSource: Boolean = false,
    )

    private const val MAX_FILE = 1024L * 1024 * 1024
    private const val MAX_PACKET_BYTES = 65024 // one page, at most 255 lacing values
    private const val MAX_COMMENTS = 4096
    private val opusTags = "OpusTags".toByteArray(Charsets.US_ASCII)
    private val vorbisTags = byteArrayOf(3) + "vorbis".toByteArray(Charsets.US_ASCII)

    private fun u(b: Byte) = b.toInt() and 255
    private fun le32(bytes: ByteArray, at: Int): Long =
        u(bytes[at]).toLong() or (u(bytes[at + 1]).toLong() shl 8) or
            (u(bytes[at + 2]).toLong() shl 16) or (u(bytes[at + 3]).toLong() shl 24)
    private fun leBytes(value: Long) = byteArrayOf(value.toByte(),
        (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
    private fun starts(bytes: ByteArray, prefix: ByteArray) =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun read(channel: FileChannel, offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset > channel.size() ||
            length.toLong() > channel.size() - offset) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val out = ByteBuffer.allocate(length)
        var cursor = offset
        while (out.hasRemaining()) {
            val amount = channel.read(out, cursor)
            if (amount <= 0) throw Refused(Code.SOURCE_CHANGED)
            cursor += amount
        }
        return out.array()
    }

    private fun checksum(bytes: ByteArray): Int {
        var crc = 0
        for (index in bytes.indices) {
            crc = crc xor ((if (index in 22..25) 0 else u(bytes[index])) shl 24)
            repeat(8) {
                crc = (crc shl 1) xor (if ((crc and Int.MIN_VALUE) != 0) 0x04c11db7 else 0)
            }
        }
        return crc
    }

    private data class Page(
        val offset: Long,
        val bytes: ByteArray,
        val serial: Long,
        val sequence: Long,
        val flags: Int,
        val packet: ByteArray?,
    ) {
        val end: Long get() = offset + bytes.size
    }

    private fun page(channel: FileChannel, offset: Long): Page {
        val header = read(channel, offset, 27)
        if (!starts(header, "OggS".toByteArray(Charsets.US_ASCII)) || u(header[4]) != 0 ||
            (u(header[5]) and 0xf8) != 0) throw Refused(Code.INVALID_PAGE)
        val count = u(header[26])
        val segments = read(channel, offset + 27, count)
        val payloadBytes = segments.sumOf(::u)
        val bytes = read(channel, offset, 27 + count + payloadBytes)
        if (le32(bytes, 22).toInt() != checksum(bytes)) throw Refused(Code.INVALID_PAGE)
        val singlePacket = count > 0 && segments.last().let(::u) < 255 &&
            segments.dropLast(1).all { u(it) == 255 }
        val packet = if (singlePacket) bytes.copyOfRange(27 + count, bytes.size) else null
        return Page(offset, bytes, le32(header, 14), le32(header, 18), u(header[5]), packet)
    }

    private fun strictlyUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw Refused(Code.UNSUPPORTED_LAYOUT) }

    private data class Comment(val raw: ByteArray, val owner: MetadataField?, val key: ByteArray)
    private data class PacketParts(val prefix: ByteArray, val vendor: ByteArray,
        val comments: List<Comment>, val trailing: ByteArray)

    private fun packetParts(packet: ByteArray, opus: Boolean): PacketParts {
        val prefix = if (opus) opusTags else vorbisTags
        if (!starts(packet, prefix)) throw Refused(Code.UNSUPPORTED_LAYOUT)
        var cursor = prefix.size
        fun next(): ByteArray {
            if (cursor > packet.size - 4) throw Refused(Code.UNSUPPORTED_LAYOUT)
            val length = le32(packet, cursor)
            cursor += 4
            if (length > MAX_PACKET_BYTES || length > packet.size - cursor)
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            val result = packet.copyOfRange(cursor, cursor + length.toInt())
            cursor += length.toInt()
            return result
        }
        val vendor = next()
        strictlyUtf8(vendor)
        if (cursor > packet.size - 4) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val count = le32(packet, cursor)
        cursor += 4
        if (count > MAX_COMMENTS) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val owners = mutableMapOf<String, MetadataField>()
        for (field in MetadataReadFieldCatalog.primary) {
            for (alias in MetadataFlacStageRewriter.preservationAliases(field)) {
                val previous = owners.put(alias, field)
                if (previous != null && previous != field) throw Refused(Code.UNSUPPORTED_LAYOUT)
            }
        }
        val comments = ArrayList<Comment>(count.toInt())
        repeat(count.toInt()) {
            val raw = next()
            val separator = raw.indexOf('='.code.toByte())
            if (separator !in 1..128 || raw.take(separator).any { u(it) !in 32..126 })
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            val key = raw.copyOfRange(0, separator)
            val owner = owners[String(key, Charsets.US_ASCII).uppercase(Locale.ROOT)]
            if (owner != null) strictlyUtf8(raw.copyOfRange(separator + 1, raw.size))
            comments += Comment(raw, owner, key)
        }
        val trailing = packet.copyOfRange(cursor, packet.size)
        if (!opus && (trailing.size != 1 || u(trailing[0]) != 1))
            throw Refused(Code.UNSUPPORTED_LAYOUT)
        return PacketParts(prefix, vendor, comments, trailing)
    }

    private fun serialize(parts: PacketParts, edits: Map<MetadataField, MetadataFieldDiff>): ByteArray {
        val seen = mutableSetOf<MetadataField>()
        val modified = ArrayList<ByteArray>()
        fun insert(field: MetadataField, key: ByteArray) {
            for (value in edits.getValue(field).after) {
                val raw = key + byteArrayOf('='.code.toByte()) + value.toByteArray(Charsets.UTF_8)
                if (raw.size > MAX_PACKET_BYTES) throw Refused(Code.TOO_LARGE)
                modified += raw
            }
        }
        for (comment in parts.comments) {
            val field = comment.owner
            if (field == null || field !in edits) modified += comment.raw
            else if (seen.add(field)) insert(field, comment.key)
        }
        for ((field, diff) in edits) if (field !in seen) {
            if (diff.before.isNotEmpty()) throw Refused(Code.SOURCE_CHANGED)
            val alias = MetadataFlacStageRewriter.preservationAliases(field).firstOrNull()
                ?: throw Refused(Code.UNSUPPORTED_FIELD)
            insert(field, alias.toByteArray(Charsets.US_ASCII))
        }
        if (modified.size > MAX_COMMENTS) throw Refused(Code.TOO_LARGE)
        val output = ByteArrayOutputStream()
        output.write(parts.prefix)
        output.write(leBytes(parts.vendor.size.toLong()))
        output.write(parts.vendor)
        output.write(leBytes(modified.size.toLong()))
        for (raw in modified) {
            output.write(leBytes(raw.size.toLong()))
            output.write(raw)
        }
        output.write(parts.trailing)
        if (output.size() > MAX_PACKET_BYTES) throw Refused(Code.TOO_LARGE)
        return output.toByteArray()
    }

    private fun newPage(previous: Page, replacement: ByteArray): ByteArray {
        val lacingCount = replacement.size / 255 + 1
        if (replacement.isEmpty() || lacingCount > 255) throw Refused(Code.TOO_LARGE)
        val lacing = ByteArray(lacingCount) { at ->
            if (at == lacingCount - 1) (replacement.size % 255).toByte() else 255.toByte()
        }
        val bytes = previous.bytes.copyOfRange(0, 27) + lacing + replacement
        bytes[26] = lacingCount.toByte()
        leBytes(checksum(bytes).toLong()).copyInto(bytes, 22)
        return bytes
    }

    private fun copyRange(src: FileChannel, dst: FileChannel, offset: Long, length: Long) {
        if (length < 0 || offset < 0 || offset + length > src.size())
            throw Refused(Code.SOURCE_CHANGED)
        val buffer = ByteBuffer.allocate(65536)
        var at = offset
        var remaining = length
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(remaining, buffer.capacity().toLong()).toInt())
            val n = src.read(buffer, at)
            if (n <= 0) throw Refused(Code.SOURCE_CHANGED)
            buffer.flip()
            while (buffer.hasRemaining()) if (dst.write(buffer) <= 0)
                throw Refused(Code.READBACK_FAILED)
            remaining -= n
            at += n
        }
    }

    private fun equalRange(a: FileChannel, aAt: Long, b: FileChannel, bAt: Long, length: Long): Boolean {
        if (length < 0 || aAt + length > a.size() || bAt + length > b.size()) return false
        var processed = 0L
        while (processed < length) {
            val part = minOf(65536L, length - processed).toInt()
            if (!read(a, aAt + processed, part).contentEquals(read(b, bAt + processed, part)))
                return false
            processed += part
        }
        return true
    }

    fun stage(backup: File, stage: File, review: MetadataReview.Ready, expectedBytes: Long): Result {
        if (MetadataReviewIntegrityPolicy.inspect(review) != MetadataReviewIntegrityPolicy.Verdict.Exact)
            throw Refused(Code.INVALID_REVIEW)
        if (expectedBytes !in 64L..MAX_FILE || backup == stage || stage.exists() ||
            Files.isSymbolicLink(backup.toPath()) || Files.isSymbolicLink(stage.toPath()) ||
            !Files.isRegularFile(backup.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            backup.parentFile?.canonicalFile != stage.parentFile?.canonicalFile)
            throw Refused(Code.UNSAFE_PATH)
        val edits = review.diffs.associateBy { it.field }
        if (edits.isEmpty() || edits.keys.any { it !in MetadataOggStageRewriter.supportedFields })
            throw Refused(Code.UNSUPPORTED_FIELD)
        var created = false
        try {
            FileChannel.open(backup.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { src ->
                if (src.size() != expectedBytes) throw Refused(Code.SOURCE_CHANGED)
                val first = page(src, 0)
                val second = page(src, first.end)
                if (first.flags != 2 || first.sequence != 0L || first.packet == null ||
                    second.flags != 0 || second.sequence != 1L || second.serial != first.serial ||
                    second.packet == null || second.end >= src.size())
                    throw Refused(Code.UNSUPPORTED_LAYOUT)
                // Verify the first untouched following page; no shifted page sequence/serial.
                val third = page(src, second.end)
                if (third.sequence != 2L || third.serial != first.serial || (third.flags and 3) != 0)
                    throw Refused(Code.UNSUPPORTED_LAYOUT)
                val opus = starts(first.packet, "OpusHead".toByteArray(Charsets.US_ASCII))
                if (!opus && !starts(first.packet, byteArrayOf(1) + "vorbis".toByteArray(Charsets.US_ASCII)))
                    throw Refused(Code.UNSUPPORTED_LAYOUT)
                val before = MetadataOggColdReader.observe(src)
                val expectedContainer = if (opus) MetadataPhysicalContainer.OGG_OPUS
                    else MetadataPhysicalContainer.OGG_VORBIS
                if (before.container != expectedContainer) throw Refused(Code.UNSUPPORTED_LAYOUT)
                for ((field, diff) in edits) {
                    val observed = before.observation(field)
                    if (if (diff.before.isEmpty()) observed != MetadataFieldObservation.VerifiedAbsent
                        else (observed as? MetadataFieldObservation.Present)?.values != diff.before)
                        throw Refused(Code.SOURCE_CHANGED)
                }
                val parsed = packetParts(second.packet, opus)
                val modifiedPacket = serialize(parsed, edits)
                val modifiedPage = newPage(second, modifiedPacket)
                if (modifiedPage.contentEquals(second.bytes)) throw Refused(Code.INVALID_REVIEW)
                val finalLength = src.size() - second.bytes.size + modifiedPage.size
                if (finalLength !in 64L..MAX_FILE) throw Refused(Code.TOO_LARGE)
                FileChannel.open(stage.toPath(), StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS).use { dst ->
                    created = true
                    copyRange(src, dst, 0, second.offset)
                    var written = 0
                    while (written < modifiedPage.size) {
                        val n = dst.write(ByteBuffer.wrap(modifiedPage, written,
                            modifiedPage.size - written))
                        if (n <= 0) throw Refused(Code.READBACK_FAILED)
                        written += n
                    }
                    copyRange(src, dst, second.end, src.size() - second.end)
                    dst.force(true)
                }
                FileChannel.open(stage.toPath(), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS).use { dst ->
                    if (dst.size() != finalLength || !equalRange(src, 0, dst, 0, second.offset) ||
                        !read(dst, second.offset, modifiedPage.size).contentEquals(modifiedPage) ||
                        !equalRange(src, second.end, dst, second.offset + modifiedPage.size,
                            src.size() - second.end)) throw Refused(Code.READBACK_FAILED)
                    val newSecond = page(dst, second.offset)
                    val newThird = page(dst, newSecond.end)
                    if (newSecond.packet?.contentEquals(modifiedPacket) != true ||
                        newThird.sequence != third.sequence || newThird.serial != third.serial ||
                        !newThird.bytes.contentEquals(third.bytes)) throw Refused(Code.READBACK_FAILED)
                    val after = MetadataOggColdReader.observe(dst)
                    if (after.container != expectedContainer) throw Refused(Code.READBACK_FAILED)
                    for ((field, diff) in edits) {
                        val observed = after.observation(field)
                        if (if (diff.after.isEmpty()) observed != MetadataFieldObservation.VerifiedAbsent
                            else (observed as? MetadataFieldObservation.Present)?.values != diff.after)
                            throw Refused(Code.READBACK_FAILED)
                    }
                    for (field in MetadataField.entries) if (field !in edits &&
                        before.observation(field) != after.observation(field))
                        throw Refused(Code.READBACK_FAILED)
                }
                if (src.size() != expectedBytes) throw Refused(Code.SOURCE_CHANGED)
                return Result(expectedBytes, finalLength, edits.keys)
            }
        } catch (error: Exception) {
            if (created) stage.delete()
            throw error
        }
    }
}
