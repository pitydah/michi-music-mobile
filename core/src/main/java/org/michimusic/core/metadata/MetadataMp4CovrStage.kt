package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * ME-21: private-only M4A cover-art staging by direct ISO base media surgery.
 *
 * Only the `moov` tree is rebuilt: every atom outside `moov` is copied verbatim
 * from the private source, so the `mdat` payload is byte-identical by
 * construction. When `moov` precedes `mdat` it is relocated to the end and the
 * `stco`/`co64` chunk offsets are patched by the exact displacement, keeping the
 * emitted samples inside the copied media payload.
 *
 * Edits are addressed by probe ordinal: [MetadataSelectiveAssetStage.Edit.Picture]
 * replaces one observed image, `PictureAdd` appends one and `PictureRemove` drops
 * one. Every unselected image is re-emitted with its original `data` payload, so
 * it stays byte-exact. The candidate is verified with
 * [MetadataArtworkLyricsProbe.inspectChannel] before it is handed back.
 *
 * Not a commit path: no provider IO, no Room, no permission elevation.
 */
object MetadataMp4CovrStage {
    private const val MAX_MOOV_BYTES = 32L * 1024L * 1024L
    private const val MAX_ATOMS = 8192
    private const val MAX_DEPTH = 8
    private const val JPEG_KIND = 13
    private const val PNG_KIND = 14

    enum class Code {
        UNSAFE_SOURCE,
        DESTINATION_EXISTS,
        INVALID_DIGEST,
        SOURCE_CHANGED,
        UNSUPPORTED_LAYOUT,
        UNSUPPORTED_EDIT,
        READBACK_FAILED,
    }

    class Refused(val code: Code) : IOException(code.name)

    data class Result(
        val candidateBytes: Long,
        val candidateSha256: String,
        val resources: MetadataArtworkLyricsProbe.Snapshot,
    )

    /** One observed `covr` image, exactly as declared by its `data` box. */
    private class Image(val kind: Int, val locale: ByteArray, val image: ByteArray)

    private class Node(
        val type: String,
        val prefix: ByteArray,
        val children: MutableList<Node>,
        var payload: ByteArray,
        val container: Boolean,
    )

    private data class Box(val type: String, val start: Long, val end: Long)

    private val containerTypes =
        setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst", "covr")

    fun stage(
        source: File,
        destination: File,
        expectedBytes: Long,
        expectedSourceSha256: String,
        observed: MetadataArtworkLyricsProbe.Snapshot?,
        edits: List<MetadataSelectiveAssetStage.Edit>,
    ): Result {
        if (!expectedSourceSha256.matches(Regex("[a-f0-9]{64}")))
            throw Refused(Code.INVALID_DIGEST)
        if (edits.isEmpty()) throw Refused(Code.UNSUPPORTED_EDIT)
        val sourcePath = source.toPath()
        val destinationPath = destination.toPath()
        if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS))
            throw Refused(Code.UNSAFE_SOURCE)
        if (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS))
            throw Refused(Code.DESTINATION_EXISTS)
        if (source.canonicalFile.parentFile != destination.canonicalFile.parentFile)
            throw Refused(Code.UNSAFE_SOURCE)
        if (source.length() != expectedBytes || digest(source) != expectedSourceSha256)
            throw Refused(Code.SOURCE_CHANGED)
        if (observed != null &&
            observed.format != MetadataArtworkLyricsProbe.Format.MP4)
            throw Refused(Code.SOURCE_CHANGED)

        try {
            val layout = topLevel(source)
            val moov = layout.singleOrNull { it.type == "moov" }
                ?: throw Refused(Code.UNSUPPORTED_LAYOUT)
            if (layout.count { it.type == "mdat" } != 1)
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            if (moov.end - moov.start > MAX_MOOV_BYTES)
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            val tree = parse(readRange(source, moov.start, moov.end), 0)
            val moovNode = tree.singleOrNull { it.type == "moov" }
                ?: throw Refused(Code.UNSUPPORTED_LAYOUT)
            val current = readImages(moovNode.children)
            if (observed != null) match(observed, current)
            val next = apply(current, edits)
            setImages(moovNode.children, next)

            val media = layout.first { it.type == "mdat" }
            var mdatStart = 0L
            for (box in layout) {
                if (box.start == media.start) break
                if (box.type == "moov") continue
                mdatStart += box.end - box.start
            }
            val displaced = mdatStart - media.start
            if (displaced != 0L) patchOffsets(moovNode.children, displaced)
            val moovBytes = encode(tree)

            FileChannel.open(sourcePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                .use { input ->
                    FileChannel.open(
                        destinationPath,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    ).use { output ->
                        var position = 0L
                        for (box in layout) {
                            if (box.type == "moov") continue
                            if (box.start == media.start) {
                                require(position == mdatStart) { "LAYOUT_DRIFT" }
                            }
                            var copied = 0L
                            val length = box.end - box.start
                            while (copied < length) {
                                val moved = input.transferTo(box.start + copied, length - copied, output)
                                if (moved <= 0L) throw Refused(Code.SOURCE_CHANGED)
                                copied += moved
                            }
                            position += length
                        }
                        output.write(ByteBuffer.wrap(moovBytes))
                        output.force(true)
                    }
                }
            val candidateBytes = destination.length()
            val candidateSha256 = digest(destination)
            val resources = readback(destination, next)
            return Result(candidateBytes, candidateSha256, resources)
        } catch (error: Throwable) {
            if (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)) destination.delete()
            throw error
        }
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    private fun match(
        observed: MetadataArtworkLyricsProbe.Snapshot,
        current: List<Image>,
    ) {
        if (observed.artworks.size != current.size) throw Refused(Code.SOURCE_CHANGED)
        observed.artworks.zip(current).forEach { (artwork, image) ->
            if (artwork.info.mime != mimeOf(image.kind) ||
                artwork.info.bytes != image.image.size ||
                artwork.info.sha256 != sha256(image.image) ||
                !artwork.image.contentEquals(image.image)
            ) throw Refused(Code.SOURCE_CHANGED)
        }
    }

    private fun readback(destination: File, expected: List<Image>): MetadataArtworkLyricsProbe.Snapshot {
        val observation = FileChannel.open(
            destination.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { MetadataArtworkLyricsProbe.inspectChannel(it) }
            as? MetadataArtworkLyricsProbe.ChannelResult.Ready
            ?: throw Refused(Code.READBACK_FAILED)
        val snapshot = observation.observation.snapshot
        if (snapshot.format != MetadataArtworkLyricsProbe.Format.MP4)
            throw Refused(Code.READBACK_FAILED)
        if (snapshot.artworks.size != expected.size) throw Refused(Code.READBACK_FAILED)
        snapshot.artworks.zip(expected).forEachIndexed { index, (artwork, image) ->
            if (artwork.ordinal != index ||
                artwork.info.mime != mimeOf(image.kind) ||
                artwork.info.bytes != image.image.size ||
                artwork.info.sha256 != sha256(image.image) ||
                !artwork.image.contentEquals(image.image)
            ) throw Refused(Code.READBACK_FAILED)
        }
        return snapshot
    }

    // ------------------------------------------------------------------
    // Edit application
    // ------------------------------------------------------------------

    private fun apply(
        current: List<Image>,
        edits: List<MetadataSelectiveAssetStage.Edit>,
    ): List<Image> {
        val replaced = HashMap<Int, Image>()
        val removed = HashSet<Int>()
        val additions = ArrayList<Pair<Int, Image>>()
        for (edit in edits) {
            when (edit) {
                is MetadataSelectiveAssetStage.Edit.Picture -> {
                    val index = edit.proposal.index
                    val item = current.getOrNull(index)
                        ?: throw Refused(Code.SOURCE_CHANGED)
                    if (index in replaced || index in removed)
                        throw Refused(Code.UNSUPPORTED_EDIT)
                    if (sha256(item.image) != edit.proposal.sourceSha256)
                        throw Refused(Code.SOURCE_CHANGED)
                    replaced[index] = image(
                        edit.proposal.proposedMime,
                        item.locale,
                        edit.proposal.proposedBytes,
                    )
                }
                is MetadataSelectiveAssetStage.Edit.PictureAdd -> {
                    val index = edit.proposal.ordinal
                    if (index != current.size) throw Refused(Code.UNSUPPORTED_EDIT)
                    if (additions.any { it.first == index })
                        throw Refused(Code.UNSUPPORTED_EDIT)
                    additions += index to image(
                        edit.proposal.proposedMime,
                        ByteArray(4),
                        edit.proposal.proposedBytes,
                    )
                }
                is MetadataSelectiveAssetStage.Edit.PictureRemove -> {
                    val item = current.getOrNull(edit.ordinal)
                        ?: throw Refused(Code.SOURCE_CHANGED)
                    if (edit.ordinal in removed || edit.ordinal in replaced)
                        throw Refused(Code.UNSUPPORTED_EDIT)
                    if (sha256(item.image) != edit.sourceSha256)
                        throw Refused(Code.SOURCE_CHANGED)
                    removed += edit.ordinal
                }
                is MetadataSelectiveAssetStage.Edit.DescribedText ->
                    throw Refused(Code.UNSUPPORTED_EDIT)
            }
        }
        val next = ArrayList<Image>(current.size + additions.size)
        current.forEachIndexed { index, item ->
            if (index in removed) return@forEachIndexed
            next += replaced[index] ?: item
        }
        additions.sortedBy { it.first }.forEach { next += it.second }
        return next
    }

    private fun image(mime: String, locale: ByteArray, bytes: ByteArray): Image {
        val kind = when (mime) {
            "image/jpeg" -> JPEG_KIND
            "image/png" -> PNG_KIND
            else -> throw Refused(Code.UNSUPPORTED_EDIT)
        }
        if (bytes.size !in 16..MetadataArtworkReplacementPreview.MAX_IMAGE_BYTES)
            throw Refused(Code.UNSUPPORTED_EDIT)
        if (!validImage(kind, bytes)) throw Refused(Code.UNSUPPORTED_EDIT)
        return Image(kind, locale.copyOf(4), bytes.copyOf())
    }

    private fun validImage(kind: Int, bytes: ByteArray): Boolean = when (kind) {
        PNG_KIND -> bytes.size >= 8 &&
            u(bytes[0]) == 0x89 && u(bytes[1]) == 0x50 && u(bytes[2]) == 0x4E &&
            u(bytes[3]) == 0x47 && u(bytes[4]) == 0x0D && u(bytes[5]) == 0x0A &&
            u(bytes[6]) == 0x1A && u(bytes[7]) == 0x0A
        JPEG_KIND -> bytes.size >= 4 &&
            u(bytes[0]) == 0xFF && u(bytes[1]) == 0xD8 && u(bytes[2]) == 0xFF &&
            u(bytes[bytes.size - 2]) == 0xFF && u(bytes[bytes.size - 1]) == 0xD9
        else -> false
    }

    private fun mimeOf(kind: Int): String = when (kind) {
        JPEG_KIND -> "image/jpeg"
        PNG_KIND -> "image/png"
        else -> throw Refused(Code.UNSUPPORTED_LAYOUT)
    }

    // ------------------------------------------------------------------
    // `covr` access inside the parsed `moov` tree
    // ------------------------------------------------------------------

    private fun readImages(tree: MutableList<Node>): List<Image> {
        val udta = child(tree, "udta") ?: return emptyList()
        val meta = child(udta.children, "meta") ?: return emptyList()
        val ilst = child(meta.children, "ilst") ?: return emptyList()
        val covr = child(ilst.children, "covr") ?: return emptyList()
        val images = ArrayList<Image>()
        for (node in covr.children) {
            if (node.type != "data" || node.container) continue
            if (node.payload.size < 8) throw Refused(Code.UNSUPPORTED_LAYOUT)
            val kind = u32(node.payload, 0).toInt()
            mimeOf(kind)
            images += Image(
                kind,
                node.payload.copyOfRange(4, 8),
                node.payload.copyOfRange(8, node.payload.size),
            )
        }
        return images
    }

    private fun setImages(tree: MutableList<Node>, images: List<Image>) {
        if (images.isEmpty()) {
            val udta = child(tree, "udta") ?: return
            val meta = child(udta.children, "meta") ?: return
            val ilst = child(meta.children, "ilst") ?: return
            ilst.children.removeAll { it.type == "covr" }
            return
        }
        val udta = child(tree, "udta")
            ?: Node("udta", EMPTY, ArrayList(), EMPTY, true).also { tree += it }
        val meta = child(udta.children, "meta")
            ?: Node("meta", ByteArray(4), ArrayList(), EMPTY, true).also {
                udta.children += it
                it.children += handler()
            }
        val ilst = child(meta.children, "ilst")
            ?: Node("ilst", EMPTY, ArrayList(), EMPTY, true).also { meta.children += it }
        val datas = images.mapTo(ArrayList()) { data(it) }
        val existing = child(ilst.children, "covr")
        if (existing != null) {
            existing.children.clear()
            existing.children += datas
        } else {
            ilst.children += Node("covr", EMPTY, datas, EMPTY, true)
        }
    }

    private fun data(image: Image): Node {
        val payload = ByteArray(8 + image.image.size)
        putU32(payload, 0, image.kind)
        image.locale.copyInto(payload, 4)
        image.image.copyInto(payload, 8)
        return Node("data", EMPTY, ArrayList(), payload, false)
    }

    /** Minimal `mdir` handler so a created `meta` stays a standard iTunes atom. */
    private fun handler(): Node {
        val payload = ByteArray(25)
        "mdir".toByteArray(Charsets.ISO_8859_1).copyInto(payload, 8)
        "appl".toByteArray(Charsets.ISO_8859_1).copyInto(payload, 12)
        return Node("hdlr", EMPTY, ArrayList(), payload, false)
    }

    private fun child(nodes: List<Node>, type: String): Node? =
        nodes.firstOrNull { it.type == type && it.container }

    // ------------------------------------------------------------------
    // Chunk offset relocation
    // ------------------------------------------------------------------

    private fun patchOffsets(nodes: List<Node>, delta: Long) {
        for (node in nodes) {
            if (node.container) {
                patchOffsets(node.children, delta)
                continue
            }
            when (node.type) {
                "stco" -> node.payload = patch32(node.payload, delta)
                "co64" -> node.payload = patch64(node.payload, delta)
            }
        }
    }

    private fun patch32(payload: ByteArray, delta: Long): ByteArray {
        if (payload.size < 8) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val count = u32(payload, 4)
        if (count < 0L || 8L + count * 4L != payload.size.toLong())
            throw Refused(Code.UNSUPPORTED_LAYOUT)
        val patched = payload.copyOf()
        for (index in 0 until count.toInt()) {
            val at = 8 + index * 4
            val value = (u32(patched, at) and 0xFFFFFFFFL) + delta
            if (value < 0L || value > 0xFFFFFFFFL) throw Refused(Code.UNSUPPORTED_LAYOUT)
            putU32(patched, at, value.toInt())
        }
        return patched
    }

    private fun patch64(payload: ByteArray, delta: Long): ByteArray {
        if (payload.size < 8) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val count = u32(payload, 4)
        if (count < 0L || 8L + count * 8L != payload.size.toLong())
            throw Refused(Code.UNSUPPORTED_LAYOUT)
        val patched = payload.copyOf()
        for (index in 0 until count.toInt()) {
            val at = 8 + index * 8
            val value = ByteBuffer.wrap(patched, at, 8).long + delta
            if (value < 0L) throw Refused(Code.UNSUPPORTED_LAYOUT)
            ByteBuffer.wrap(patched, at, 8).putLong(value)
        }
        return patched
    }

    // ------------------------------------------------------------------
    // Container parsing / encoding
    // ------------------------------------------------------------------

    private fun parse(bytes: ByteArray, depth: Int, budget: IntArray = intArrayOf(0)): MutableList<Node> {
        if (depth > MAX_DEPTH) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val nodes = ArrayList<Node>()
        var at = 0
        while (at < bytes.size) {
            if (++budget[0] > MAX_ATOMS) throw Refused(Code.UNSUPPORTED_LAYOUT)
            if (at + 8 > bytes.size) throw Refused(Code.UNSUPPORTED_LAYOUT)
            val size32 = u32(bytes, at)
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            var size = size32
            if (size32 == 1L) {
                if (at + 16 > bytes.size) throw Refused(Code.UNSUPPORTED_LAYOUT)
                size = ByteBuffer.wrap(bytes, at + 8, 8).long
                header = 16
            } else if (size32 == 0L) {
                size = (bytes.size - at).toLong()
            }
            if (size < header.toLong() || size > (bytes.size - at).toLong())
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            val payloadStart = at + header
            val end = (at + size).toInt()
            if (type in containerTypes) {
                val prefixLength = if (type == "meta") 4 else 0
                if (end - payloadStart < prefixLength) throw Refused(Code.UNSUPPORTED_LAYOUT)
                val prefix = bytes.copyOfRange(payloadStart, payloadStart + prefixLength)
                val body = bytes.copyOfRange(payloadStart + prefixLength, end)
                nodes += Node(type, prefix, parse(body, depth + 1, budget), EMPTY, true)
            } else {
                nodes += Node(type, EMPTY, ArrayList(), bytes.copyOfRange(payloadStart, end), false)
            }
            at = end
        }
        return nodes
    }

    private fun boxSize(node: Node): Long = 8L + node.prefix.size +
        if (node.container) node.children.sumOf { boxSize(it) }
        else node.payload.size.toLong()

    private fun encode(nodes: List<Node>): ByteArray {
        val out = ByteArrayOutputStream()
        nodes.forEach { encodeNode(it, out) }
        return out.toByteArray()
    }

    private fun encodeNode(node: Node, out: ByteArrayOutputStream) {
        val size = boxSize(node)
        if (size > 0xFFFFFFFFL) throw Refused(Code.UNSUPPORTED_LAYOUT)
        out.write(ByteBuffer.allocate(4).putInt(size.toInt()).array())
        out.write(node.type.toByteArray(Charsets.ISO_8859_1))
        if (node.prefix.isNotEmpty()) out.write(node.prefix)
        if (node.container) node.children.forEach { encodeNode(it, out) }
        else out.write(node.payload)
    }

    // ------------------------------------------------------------------
    // File helpers
    // ------------------------------------------------------------------

    private fun topLevel(file: File): List<Box> =
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            .use { channel ->
                val size = channel.size()
                val boxes = ArrayList<Box>()
                var at = 0L
                while (at < size) {
                    if (boxes.size > MAX_ATOMS) throw Refused(Code.UNSUPPORTED_LAYOUT)
                    if (size - at < 8L) throw Refused(Code.UNSUPPORTED_LAYOUT)
                    val head = ByteBuffer.allocate(16)
                    head.limit(8)
                    readFully(channel, head, at)
                    val size32 = head.getInt(0).toLong() and 0xFFFFFFFFL
                    val type = String(head.array(), 4, 4, Charsets.ISO_8859_1)
                    var header = 8L
                    var length = size32
                    if (size32 == 1L) {
                        head.clear()
                        readFully(channel, head, at)
                        length = head.getLong(8)
                        header = 16L
                    } else if (size32 == 0L) {
                        length = size - at
                    }
                    if (length < header || length > size - at)
                        throw Refused(Code.UNSUPPORTED_LAYOUT)
                    boxes += Box(type, at, at + length)
                    at += length
                }
                boxes
            }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer, at: Long) {
        var position = at
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) throw Refused(Code.UNSUPPORTED_LAYOUT)
            position += read
        }
    }

    private fun readRange(file: File, start: Long, end: Long): ByteArray {
        val length = end - start
        if (length <= 0L || length > MAX_MOOV_BYTES) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val bytes = ByteArray(length.toInt())
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            .use { channel -> readFully(channel, ByteBuffer.wrap(bytes), start) }
        return bytes
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            .use { channel ->
                val buffer = ByteBuffer.allocate(64 * 1024)
                var at = 0L
                val size = channel.size()
                while (at < size) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), size - at).toInt())
                    val read = channel.read(buffer, at)
                    if (read <= 0) break
                    at += read
                    buffer.flip()
                    digest.update(buffer)
                }
            }
        return digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }

    private fun u(byte: Byte): Int = byte.toInt() and 255

    private fun u32(bytes: ByteArray, at: Int): Long =
        ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xFFFFFFFFL

    private fun putU32(bytes: ByteArray, at: Int, value: Int) {
        ByteBuffer.wrap(bytes, at, 4).putInt(value)
    }

    private val EMPTY = ByteArray(0)
}
