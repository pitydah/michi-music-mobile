package org.michimusic.data.repository

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Read-only, bounded metadata extraction for seekable local file descriptors.
 * Does not read the audio payload: MP4 walks container box sizes and APEv2 reads only its tail.
 * Each field is capped; unsupported or malformed structures are ignored rather than guessed.
 */
internal object SeekableEmbeddedTags {
    private const val MAX_PREFIX = 2 * 1024 * 1024
    private const val MAX_APE_TAG = 2 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 8192
    private const val MAX_MP4_BOXES = 16384

    private fun u(b: Byte) = b.toInt() and 0xff
    private fun le32(b: ByteArray, p: Int): Long =
        (u(b[p]).toLong() or (u(b[p + 1]).toLong() shl 8) or
            (u(b[p + 2]).toLong() shl 16) or (u(b[p + 3]).toLong() shl 24))
    private fun be32(b: ByteArray, p: Int): Long =
        ((u(b[p]).toLong() shl 24) or (u(b[p + 1]).toLong() shl 16) or
            (u(b[p + 2]).toLong() shl 8) or u(b[p + 3]).toLong())

    private fun readAt(channel: FileChannel, offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || count > MAX_APE_TAG + 16) return null
        val size = channel.size()
        if (offset > size || count.toLong() > size - offset) return null
        val buffer = ByteBuffer.allocate(count)
        var position = offset
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position)
            if (n <= 0) return null
            position += n
        }
        return buffer.array()
    }

    fun read(channel: FileChannel): EmbeddedTagFields? {
        val length = channel.size()
        if (length < 8) return null
        val signature = readAt(channel, 0, minOf(12L, length).toInt()) ?: return null
        if (signature.size >= 8 && String(signature, 4, 4, StandardCharsets.ISO_8859_1) == "ftyp") {
            // A large mdat may precede moov. Jump directly to atom positions.
            return mp4(channel, length) ?: prefix(channel, length)
        }
        if (signature.size >= 4 && String(signature, 0, 4, StandardCharsets.US_ASCII) == "fLaC") {
            // Large embedded cover-art may precede Vorbis comments: jump by block length.
            return flac(channel, length) ?: prefix(channel, length)
        }
        if (signature.size >= 12 && String(signature, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
            String(signature, 8, 4, StandardCharsets.US_ASCII) == "WAVE") {
            return riff(channel, length) ?: prefix(channel, length)
        }
        if (signature.size >= 12 && String(signature, 0, 4, StandardCharsets.US_ASCII) == "FORM" &&
            String(signature, 8, 4, StandardCharsets.US_ASCII) in setOf("AIFF", "AIFC")) {
            return aiff(channel, length) ?: prefix(channel, length)
        }
        if (signature.size >= 4 && (String(signature, 0, 4, StandardCharsets.US_ASCII) == "wvpk" ||
                    String(signature, 0, 4, StandardCharsets.US_ASCII) == "MAC ")) {
            return merge(ape(channel, length), id3v1(channel, length)) ?: prefix(channel, length)
        }
        // APEv2 overrides ID3v2 only for fields it actually contains; ID3v1 is a
        // last-resort fallback for old MP3 collections, never a guessed filename genre.
        return merge(ape(channel, length), merge(prefix(channel, length), id3v1(channel, length)))
    }

    private fun prefix(channel: FileChannel, length: Long): EmbeddedTagFields? =
        readAt(channel, 0L, minOf(MAX_PREFIX.toLong(), length).toInt())?.let {
            EmbeddedTagFieldsReader.read(ByteArrayInputStream(it))
        }

    /** Read FLAC Vorbis comments without decoding potentially multi-megabyte PICTURE blocks. */
    private fun flac(channel: FileChannel, length: Long): EmbeddedTagFields? {
        var pos = 4L
        repeat(64) {
            val h = readAt(channel, pos, 4) ?: return null
            val kind = u(h[0]) and 127
            val last = u(h[0]) and 128 != 0
            val n = (u(h[1]) shl 16) or (u(h[2]) shl 8) or u(h[3])
            val start = pos + 4
            if (start > length || n.toLong() > length - start) return null
            if (kind == 4) {
                // Reuse the same strict Vorbis comment parser as non-seekable input.
                if (n > MAX_PREFIX - 8) return null
                val block = readAt(channel, start, n) ?: return null
                val prefix = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(),
                    'a'.code.toByte(), 'C'.code.toByte(), (128 or 4).toByte(),
                    (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
                return EmbeddedTagFieldsReader.read(ByteArrayInputStream(prefix + block))
            }
            pos = start + n
            if (last) return null
        }
        return null
    }

    /** ID3v1 last 128 bytes, for older MP3/APE files without modern structured tags. */
    private fun id3v1(channel: FileChannel, length: Long): EmbeddedTagFields? {
        if (length < 128) return null
        val tail = readAt(channel, length - 128, 128) ?: return null
        if (String(tail, 0, 3, StandardCharsets.US_ASCII) != "TAG") return null
        fun value(start: Int, n: Int): String? =
            String(tail, start, n, StandardCharsets.ISO_8859_1)
                .trim('\u0000', ' ', '\t').takeIf { it.isNotBlank() && it.length <= 2048 }
        val genre = Id3GenreCodes.nameForIndex(u(tail[127]))
        val track = if (tail[125] == 0.toByte()) u(tail[126]).takeIf { it > 0 } else null
        return EmbeddedTagFields(
            title = value(3, 30), artist = value(33, 30), album = value(63, 30),
            genres = genre?.let(::listOf).orEmpty(), trackNumber = track, date = value(93, 4),
        )
    }

    /** Skip WAV audio chunks and inspect trailing LIST/INFO or embedded ID3 metadata. */
    private fun riff(channel: FileChannel, length: Long): EmbeddedTagFields? {
        val header = readAt(channel, 0, 12) ?: return null
        val declared = le32(header, 4)
        val end = minOf(length, declared + 8)
        val mapping = mapOf("INAM" to "TITLE", "IART" to "ARTIST", "IPRD" to "ALBUM",
            "IGNR" to "GENRE", "ICRD" to "DATE")
        val tags = linkedMapOf<String, MutableList<String>>()
        var embedded: EmbeddedTagFields? = null
        var p = 12L
        var chunks = 0
        while (p + 8 <= end && chunks++ < MAX_MP4_BOXES) {
            val head = readAt(channel, p, 8) ?: break
            val n = le32(head, 4)
            val next = p + 8 + n
            if (next > end) break
            val type = String(head, 0, 4, StandardCharsets.US_ASCII)
            if (type == "LIST" && n >= 4 && readAt(channel, p + 8, 4)?.contentEquals(
                    "INFO".toByteArray(StandardCharsets.US_ASCII)) == true) {
                var q = p + 12
                while (q + 8 <= next && chunks++ < MAX_MP4_BOXES) {
                    val item = readAt(channel, q, 8) ?: break
                    val itemSize = le32(item, 4)
                    if (itemSize > next - q - 8) break
                    val key = String(item, 0, 4, StandardCharsets.US_ASCII)
                    if (key in mapping && itemSize in 1L..MAX_TEXT_BYTES.toLong()) {
                        readAt(channel, q + 8, itemSize.toInt())?.let { data ->
                            val value = String(data, StandardCharsets.ISO_8859_1).trim('\u0000', ' ', '\r', '\n')
                            if (value.isNotBlank() && value.length <= 2048 && '\uFFFD' !in value) {
                                tags.getOrPut(mapping.getValue(key)) { mutableListOf() }.add(value)
                            }
                        }
                    }
                    q += 8 + itemSize + (itemSize and 1)
                }
            } else if (type == "id3 " || type == "ID3 ") {
                if (n in 10L..MAX_PREFIX.toLong()) {
                    embedded = readAt(channel, p + 8, n.toInt())?.let {
                        EmbeddedTagFieldsReader.read(ByteArrayInputStream(it))
                    } ?: embedded
                }
            }
            p = next + (n and 1)
        }
        val info = tags.takeIf { it.isNotEmpty() }?.let(EmbeddedTagFieldsReader::fromTags)
        return merge(embedded, info)
    }

    /** Skip large AIFF sound data and read metadata chunks at any file offset. */
    private fun aiff(channel: FileChannel, length: Long): EmbeddedTagFields? {
        val header = readAt(channel, 0, 12) ?: return null
        val end = minOf(length, be32(header, 4) + 8)
        val tags = linkedMapOf<String, MutableList<String>>()
        var embedded: EmbeddedTagFields? = null
        var p = 12L
        var chunks = 0
        while (p + 8 <= end && chunks++ < MAX_MP4_BOXES) {
            val head = readAt(channel, p, 8) ?: break
            val n = be32(head, 4)
            val next = p + 8 + n
            if (next > end) break
            val type = String(head, 0, 4, StandardCharsets.US_ASCII)
            val key = when (type) { "NAME" -> "TITLE"; "AUTH" -> "ARTIST"; else -> null }
            if (key != null && n in 1L..MAX_TEXT_BYTES.toLong()) {
                readAt(channel, p + 8, n.toInt())?.let { data ->
                    val value = String(data, StandardCharsets.ISO_8859_1).trim('\u0000', ' ', '\r', '\n')
                    if (value.isNotBlank() && value.length <= 2048 && '\uFFFD' !in value) {
                        tags.getOrPut(key) { mutableListOf() }.add(value)
                    }
                }
            } else if (type == "ID3 " || type == "id3 ") {
                if (n in 10L..MAX_PREFIX.toLong()) {
                    embedded = readAt(channel, p + 8, n.toInt())?.let {
                        EmbeddedTagFieldsReader.read(ByteArrayInputStream(it))
                    } ?: embedded
                }
            }
            p = next + (n and 1)
        }
        val plain = tags.takeIf { it.isNotEmpty() }?.let(EmbeddedTagFieldsReader::fromTags)
        return merge(embedded, plain)
    }

    private fun merge(primary: EmbeddedTagFields?, fallback: EmbeddedTagFields?): EmbeddedTagFields? {
        if (primary == null) return fallback
        if (fallback == null) return primary
        return EmbeddedTagFields(
            title = primary.title ?: fallback.title,
            artist = primary.artist ?: fallback.artist,
            album = primary.album ?: fallback.album,
            albumArtist = primary.albumArtist ?: fallback.albumArtist,
            genres = primary.genres.ifEmpty { fallback.genres },
            composer = primary.composer ?: fallback.composer,
            trackNumber = primary.trackNumber ?: fallback.trackNumber,
            trackTotal = primary.trackTotal ?: fallback.trackTotal,
            discNumber = primary.discNumber ?: fallback.discNumber,
            discTotal = primary.discTotal ?: fallback.discTotal,
            date = primary.date ?: fallback.date,
        )
    }

    private fun ape(channel: FileChannel, length: Long): EmbeddedTagFields? {
        val hasId3v1 = length >= 128L &&
            readAt(channel, length - 128, 3)?.contentEquals("TAG".toByteArray(StandardCharsets.US_ASCII)) == true
        val footerAt = length - (if (hasId3v1) 128 else 0) - 32
        val footer = readAt(channel, footerAt, 32) ?: return null
        if (!footer.copyOfRange(0, 8).contentEquals("APETAGEX".toByteArray(StandardCharsets.US_ASCII))) return null
        val version = le32(footer, 8)
        val total = le32(footer, 12)
        val count = le32(footer, 16)
        if (version !in 1000L..2000L || total !in 32L..MAX_APE_TAG.toLong() || count !in 1L..2048L) return null
        // APEv2 size is items + 32-byte footer, excluding the optional header.
        val firstItem = footerAt + 32 - total
        val itemsLength = (total - 32).toInt()
        val bytes = readAt(channel, firstItem, itemsLength) ?: return null
        val tags = linkedMapOf<String, MutableList<String>>()
        var p = 0
        repeat(count.toInt()) {
            if (p > bytes.size - 9) return null
            val valueLength = le32(bytes, p)
            val flags = le32(bytes, p + 4)
            p += 8
            var keyEnd = p
            while (keyEnd < bytes.size && bytes[keyEnd] != 0.toByte() && keyEnd - p <= 255) keyEnd++
            if (keyEnd >= bytes.size || bytes[keyEnd] != 0.toByte()) return null
            if (keyEnd < p || keyEnd - p !in 2..255) return null
            val name = String(bytes, p, keyEnd - p, StandardCharsets.US_ASCII).uppercase(Locale.ROOT)
            p = keyEnd + 1
            if (valueLength > (bytes.size - p).toLong()) return null
            // APEv2 flags bits 1-2: text (0), binary (1), external (2).
            if (((flags shr 1) and 3L) == 0L && valueLength in 1L..MAX_TEXT_BYTES.toLong()) {
                val value = String(bytes, p, valueLength.toInt(), StandardCharsets.UTF_8)
                value.split('\u0000').forEach { entry ->
                    if (entry.isNotBlank() && entry.length <= 2048 && '\uFFFD' !in entry) {
                        tags.getOrPut(name) { mutableListOf() }.add(entry)
                    }
                }
            }
            p += valueLength.toInt()
        }
        return tags.takeIf { it.isNotEmpty() }?.let(EmbeddedTagFieldsReader::fromTags)
    }

    private data class Box(val type: String, val content: Long, val end: Long)

    private fun box(channel: FileChannel, offset: Long, end: Long): Box? {
        if (end - offset < 8) return null
        val head = readAt(channel, offset, 8) ?: return null
        val declared = be32(head, 0)
        val headerLength = if (declared == 1L) 16 else 8
        val size = when (declared) {
            0L -> end - offset
            1L -> {
                if (end - offset < 16) return null
                val extended = readAt(channel, offset + 8, 8) ?: return null
                val n = ByteBuffer.wrap(extended).order(ByteOrder.BIG_ENDIAN).long
                if (n < 0L) return null
                n
            }
            else -> declared
        }
        if (size < headerLength || size > end - offset) return null
        return Box(String(head, 4, 4, StandardCharsets.ISO_8859_1),
            offset + headerLength, offset + size)
    }

    private fun mp4(channel: FileChannel, length: Long): EmbeddedTagFields? {
        val tags = linkedMapOf<String, MutableList<String>>()
        val names = mapOf(
            "©nam" to "TITLE", "©ART" to "ARTIST", "©alb" to "ALBUM",
            "aART" to "ALBUMARTIST", "©gen" to "GENRE", "©wrt" to "COMPOSER", "©day" to "DATE"
        )
        var visited = 0
        fun dataItem(item: Box) {
            var p = item.content
            while (p + 8 <= item.end && visited++ < MAX_MP4_BOXES) {
                val child = box(channel, p, item.end) ?: return
                if (child.type == "data" && child.end - child.content >= 8) {
                    val contentSize = child.end - child.content - 8
                    val payload = if (contentSize in 1L..MAX_TEXT_BYTES.toLong()) {
                        readAt(channel, child.content + 8, contentSize.toInt())
                    } else null
                    if (payload != null) {
                        if (item.type == "gnre") {
                            // iTunes gnre stores a 1-based ID3v1 index as a big-endian short.
                            if (payload.size >= 2) {
                                val code = u(payload[0]) * 256 + u(payload[1]) - 1
                                Id3GenreCodes.nameForIndex(code)?.let {
                                    tags.getOrPut("GENRE") { mutableListOf() }.add(it)
                                }
                            }
                        } else if (item.type == "trkn" || item.type == "disk") {
                            if (payload.size >= 6) {
                                val n = u(payload[2]) * 256 + u(payload[3])
                                val total = u(payload[4]) * 256 + u(payload[5])
                                if (n > 0) tags.getOrPut(if (item.type == "trkn") "TRACKNUMBER" else "DISCNUMBER") {
                                    mutableListOf()
                                }.add(if (total > 0) "$n/$total" else "$n")
                            }
                        } else names[item.type]?.let { field ->
                            val descriptor = readAt(channel, child.content, 4)
                            val charset = if (descriptor != null && (descriptor[3].toInt() and 255) == 2)
                                StandardCharsets.UTF_16BE else StandardCharsets.UTF_8
                            val value = String(payload, charset).trim('\u0000', ' ', '\n', '\r')
                            if (value.isNotBlank() && value.length <= 2048 && '\uFFFD' !in value) {
                                tags.getOrPut(field) { mutableListOf() }.add(value)
                            }
                        }
                    }
                }
                p = child.end
            }
        }
        fun walk(start: Long, end: Long, depth: Int, inList: Boolean) {
            if (depth > 8 || visited >= MAX_MP4_BOXES) return
            var p = start
            while (p + 8 <= end && visited++ < MAX_MP4_BOXES) {
                val entry = box(channel, p, end) ?: return
                when {
                    inList -> dataItem(entry)
                    entry.type == "moov" || entry.type == "udta" || entry.type == "ilst" ->
                        walk(entry.content, entry.end, depth + 1, entry.type == "ilst")
                    entry.type == "meta" && entry.content + 4 <= entry.end ->
                        walk(entry.content + 4, entry.end, depth + 1, false)
                }
                p = entry.end
            }
        }
        walk(0, length, 0, false)
        return tags.takeIf { it.isNotEmpty() }?.let(EmbeddedTagFieldsReader::fromTags)
    }
}
