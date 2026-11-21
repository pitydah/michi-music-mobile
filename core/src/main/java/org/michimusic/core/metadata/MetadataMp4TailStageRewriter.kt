package org.michimusic.core.metadata

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * Private-only variable-length MP4 text staging. The *entire* moov atom must
 * be last, after all media-data atoms. Changes remain inside that terminal moov;
 * all original offsets into the preceding mdat remain numerically unchanged.
 *
 * No fragmented MP4, extended size boxes, nested item collections, duplicate
 * metadata items, new/deleted atoms, media offset changes or source writes.
 */
object MetadataMp4TailStageRewriter {
    enum class Code { INVALID_REVIEW, UNSAFE_PATH, UNSUPPORTED_LAYOUT, UNSUPPORTED_CHANGE,
        DUPLICATE_FIELD, SOURCE_CHANGED, TOO_LARGE, READBACK_FAILED }
    class Refused(val code: Code) : IOException(code.name)
    data class Result(val originalBytes: Long, val candidateBytes: Long,
        val changedFields: Set<MetadataField>, val canCommitToSource: Boolean = false)

    private const val MAX_FILE = 1024L * 1024 * 1024
    private const val MAX_MOOV = 16 * 1024 * 1024
    private const val MAX_BOXES = 8192
    private val names = mapOf("©nam" to MetadataField.TITLE,
        "©ART" to MetadataField.ARTIST, "aART" to MetadataField.ALBUM_ARTIST,
        "©alb" to MetadataField.ALBUM, "©gen" to MetadataField.GENRE,
        "©wrt" to MetadataField.COMPOSER, "©day" to MetadataField.RELEASE_DATE,
        "©cmt" to MetadataField.COMMENT, "©lyr" to MetadataField.LYRICS,
        "©too" to MetadataField.MEDIA_FORMAT, "©grp" to MetadataField.GROUPING)

    private fun u(v: Byte) = v.toInt() and 255
    private fun be32(bytes: ByteArray, offset: Int): Long =
        (u(bytes[offset]).toLong() shl 24) or (u(bytes[offset + 1]).toLong() shl 16) or
            (u(bytes[offset + 2]).toLong() shl 8) or u(bytes[offset + 3]).toLong()
    private fun beBytes(value: Int) = byteArrayOf((value ushr 24).toByte(),
        (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun read(channel: FileChannel, at: Long, length: Int): ByteArray {
        if (length < 0 || at < 0 || at > channel.size() || length.toLong() > channel.size() - at)
            throw Refused(Code.UNSUPPORTED_LAYOUT)
        val out = ByteBuffer.allocate(length)
        var position = at
        while (out.hasRemaining()) {
            val n = channel.read(out, position)
            if (n <= 0) throw Refused(Code.SOURCE_CHANGED)
            position += n
        }
        return out.array()
    }

    private data class Box(val at: Int, val end: Int, val type: String) {
        val payload get() = at + 8
    }
    private fun box(bytes: ByteArray, at: Int, end: Int): Box {
        if (at < 0 || at > end - 8) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val size = be32(bytes, at)
        if (size !in 8..Int.MAX_VALUE.toLong() || size > end.toLong() - at)
            throw Refused(Code.UNSUPPORTED_LAYOUT)
        return Box(at, at + size.toInt(), String(bytes, at + 4, 4, StandardCharsets.ISO_8859_1))
    }
    private fun encodeBox(original: ByteArray, originalBox: Box, payload: ByteArray): ByteArray {
        val length = payload.size.toLong() + 8
        if (length > MAX_MOOV || length > Int.MAX_VALUE) throw Refused(Code.TOO_LARGE)
        return beBytes(length.toInt()) + original.copyOfRange(originalBox.at + 4,
            originalBox.at + 8) + payload
    }
    private fun strictText(raw: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw)).toString()
    } catch (_: Exception) { throw Refused(Code.UNSUPPORTED_LAYOUT) }
    private fun copyRange(src: FileChannel, dst: FileChannel, size: Long) {
        val buffer = ByteBuffer.allocate(65536)
        var offset = 0L
        while (offset < size) {
            buffer.clear(); buffer.limit(minOf(65536L, size - offset).toInt())
            val n = src.read(buffer, offset)
            if (n <= 0) throw Refused(Code.SOURCE_CHANGED)
            buffer.flip()
            while (buffer.hasRemaining()) if (dst.write(buffer) <= 0) throw Refused(Code.READBACK_FAILED)
            offset += n
        }
    }
    private fun samePrefix(a: FileChannel, b: FileChannel, length: Long): Boolean {
        var offset = 0L
        while (offset < length) {
            val count = minOf(65536L, length - offset).toInt()
            if (!read(a, offset, count).contentEquals(read(b, offset, count))) return false
            offset += count
        }
        return true
    }

    private fun transformMoov(original: ByteArray,
                              edits: Map<MetadataField, MetadataFieldDiff>): ByteArray {
        val moov = box(original, 0, original.size)
        if (moov.type != "moov" || moov.end != original.size) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val seen = mutableSetOf<MetadataField>()
        var foundIlst = 0
        var foundUdta = 0
        var foundMeta = 0
        var boxes = 0
        fun walk(container: Box, prefix: Int, depth: Int, inIlst: Boolean): ByteArray {
            if (depth > 7) throw Refused(Code.UNSUPPORTED_LAYOUT)
            val content = java.io.ByteArrayOutputStream()
            content.write(original, container.payload, prefix)
            var at = container.payload + prefix
            while (at < container.end) {
                if (++boxes > MAX_BOXES) throw Refused(Code.TOO_LARGE)
                val child = box(original, at, container.end)
                val bytes: ByteArray = when {
                    child.type == "udta" && container.type == "moov" -> {
                        if (++foundUdta != 1) throw Refused(Code.UNSUPPORTED_LAYOUT)
                        walk(child, 0, depth + 1, false)
                    }
                    child.type == "meta" && container.type == "udta" -> {
                        if (++foundMeta != 1 || child.end - child.payload < 4)
                            throw Refused(Code.UNSUPPORTED_LAYOUT)
                        walk(child, 4, depth + 1, false)
                    }
                    child.type == "ilst" && container.type == "meta" -> {
                        if (++foundIlst != 1) throw Refused(Code.UNSUPPORTED_LAYOUT)
                        walk(child, 0, depth + 1, true)
                    }
                    inIlst && container.type == "ilst" && names.containsKey(child.type) -> {
                        val field = names.getValue(child.type)
                        // Unknown or unchanged known tags are copied EXACTLY, including side atoms.
                        if (field !in edits) original.copyOfRange(child.at, child.end)
                        else {
                            if (!seen.add(field)) throw Refused(Code.DUPLICATE_FIELD)
                            val diff = edits.getValue(field)
                            if (diff.before.size != 1 || diff.after.size != 1)
                                throw Refused(Code.UNSUPPORTED_CHANGE)
                            val data = box(original, child.payload, child.end)
                            if (data.type != "data" || data.end != child.end ||
                                data.end - data.payload < 9 ||
                                be32(original, data.payload) != 1L)
                                throw Refused(Code.UNSUPPORTED_LAYOUT)
                            val prior = strictText(original.copyOfRange(data.payload + 8, data.end))
                            if (prior != diff.before.single()) throw Refused(Code.SOURCE_CHANGED)
                            val altered = diff.after.single().toByteArray(Charsets.UTF_8)
                            if (altered.isEmpty() || altered.size > 65536)
                                throw Refused(Code.UNSUPPORTED_CHANGE)
                            val contentData = original.copyOfRange(data.payload, data.payload + 8) + altered
                            val rebuiltData = encodeBox(original, data, contentData)
                            encodeBox(original, child, rebuiltData)
                        }
                    }
                    else -> original.copyOfRange(child.at, child.end)
                }
                content.write(bytes)
                at = child.end
            }
            if (at != container.end) throw Refused(Code.UNSUPPORTED_LAYOUT)
            return encodeBox(original, container, content.toByteArray())
        }
        val rebuilt = walk(moov, 0, 0, false)
        if (foundUdta != 1 || foundMeta != 1 || foundIlst != 1 || seen != edits.keys)
            throw Refused(Code.UNSUPPORTED_CHANGE)
        return rebuilt
    }

    fun stage(backup: File, stage: File, review: MetadataReview.Ready, expectedBytes: Long): Result {
        if (MetadataReviewIntegrityPolicy.inspect(review) != MetadataReviewIntegrityPolicy.Verdict.Exact)
            throw Refused(Code.INVALID_REVIEW)
        if (expectedBytes !in 24L..MAX_FILE || backup == stage || stage.exists() ||
            Files.isSymbolicLink(backup.toPath()) || Files.isSymbolicLink(stage.toPath()) ||
            !Files.isRegularFile(backup.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            backup.parentFile?.canonicalFile != stage.parentFile?.canonicalFile)
            throw Refused(Code.UNSAFE_PATH)
        val edits = review.diffs.associateBy { it.field }
        if (edits.isEmpty() || edits.keys.any { it !in MetadataMp4StageRewriter.supportedFields })
            throw Refused(Code.UNSUPPORTED_CHANGE)
        var created = false
        try {
            FileChannel.open(backup.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { src ->
                if (src.size() != expectedBytes) throw Refused(Code.SOURCE_CHANGED)
                var at = 0L
                var firstType: String? = null
                var moovAt = -1L
                var moovSize = 0
                var mdatSeen = false
                var count = 0
                while (at < src.size()) {
                    if (++count > MAX_BOXES || at > src.size() - 8) throw Refused(Code.UNSUPPORTED_LAYOUT)
                    val header = read(src, at, 8)
                    val length = be32(header, 0)
                    if (length < 8 || length > src.size() - at) throw Refused(Code.UNSUPPORTED_LAYOUT)
                    val kind = String(header, 4, 4, StandardCharsets.ISO_8859_1)
                    if (firstType == null) firstType = kind
                    if (kind == "moof" || kind == "mfra") throw Refused(Code.UNSUPPORTED_LAYOUT)
                    if (kind == "mdat") {
                        if (moovAt >= 0) throw Refused(Code.UNSUPPORTED_LAYOUT)
                        mdatSeen = true
                    }
                    if (kind == "moov") {
                        if (moovAt >= 0 || !mdatSeen || at + length != src.size() ||
                            length > MAX_MOOV) throw Refused(Code.UNSUPPORTED_LAYOUT)
                        moovAt = at
                        moovSize = length.toInt()
                    }
                    at += length
                }
                if (at != src.size() || firstType != "ftyp" || moovAt < 0 || !mdatSeen)
                    throw Refused(Code.UNSUPPORTED_LAYOUT)
                val before = MetadataMp4ColdReader.observe(src)
                if (before.container != MetadataPhysicalContainer.MP4_M4A)
                    throw Refused(Code.UNSUPPORTED_LAYOUT)
                for ((field, diff) in edits) if (
                    diff.before.size != 1 || diff.after.size != 1 ||
                    (before.observation(field) as? MetadataFieldObservation.Present)?.values != diff.before)
                    throw Refused(Code.UNSUPPORTED_CHANGE)
                val oldMoov = read(src, moovAt, moovSize)
                val newMoov = transformMoov(oldMoov, edits)
                val finalLength = moovAt + newMoov.size
                if (finalLength > MAX_FILE) throw Refused(Code.TOO_LARGE)
                FileChannel.open(stage.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { dst ->
                    created = true
                    copyRange(src, dst, moovAt)
                    var written = 0
                    while (written < newMoov.size) {
                        val n = dst.write(ByteBuffer.wrap(newMoov, written, newMoov.size - written))
                        if (n <= 0) throw Refused(Code.READBACK_FAILED)
                        written += n
                    }
                    dst.force(true)
                }
                FileChannel.open(stage.toPath(), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS).use { dst ->
                    if (dst.size() != finalLength || !samePrefix(src, dst, moovAt) ||
                        !read(dst, moovAt, newMoov.size).contentEquals(newMoov))
                        throw Refused(Code.READBACK_FAILED)
                    val after = MetadataMp4ColdReader.observe(dst)
                    if (after.container != before.container) throw Refused(Code.READBACK_FAILED)
                    for ((field, diff) in edits) if (
                        (after.observation(field) as? MetadataFieldObservation.Present)?.values != diff.after)
                        throw Refused(Code.READBACK_FAILED)
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
