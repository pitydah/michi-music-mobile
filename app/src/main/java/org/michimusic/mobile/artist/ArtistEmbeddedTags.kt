package org.michimusic.mobile.artist

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Explicit, bounded inspection of local tags; it NEVER mutates library or playback identities.
 * A parsed MBID is a *candidate*, not verification of the credit or authority to merge artists.
 * Unsupported/corrupt tags fail closed. No filename splitting, name-only matching or network IO.
 */
data class EmbeddedArtistTags(
    val artistMbids: Set<String> = emptySet(),
    val albumArtistMbids: Set<String> = emptySet(),
    val creditedNames: List<String> = emptyList(),
    val albumArtists: List<String> = emptyList(),
)

/** Per-file evidence is kept separate: several MBIDs do not imply one joint artist. */
data class EmbeddedTrackEvidence(val uri: String, val tags: EmbeddedArtistTags)

data class EmbeddedArtistReview(
    val inspectedFiles: Int,
    val taggedFiles: Int,
    val artistMbids: Set<String>,
    val albumArtistMbids: Set<String>,
    val creditedNames: List<String>,
    val albumArtists: List<String>,
    val trackEvidence: List<EmbeddedTrackEvidence> = emptyList(),
) {
    /** A unique suggestion still requires the listener's explicit confirmation. */
    val suggestedMbid: String? get() = artistMbids.singleOrNull()
        ?.takeIf { creditedNames.distinct().size <= 1 }
    val hasConflict: Boolean get() = artistMbids.size > 1
}

/** Test seam: UI asks for local metadata only on explicit user interaction. */
fun interface ArtistTagProbe {
    suspend fun inspect(localUris: List<String>): EmbeddedArtistReview
}

object ArtistEmbeddedTagCodec {
    const val MAX_TAG_BYTES = 512 * 1024
    private const val MAX_FRAMES = 512
    private const val MAX_COMMENTS = 512
    private val UUID = Regex("(?i)[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
    private val UTF16 = Charsets.UTF_16

    fun parse(header: ByteArray): EmbeddedArtistTags {
        if (header.size > MAX_TAG_BYTES) return EmbeddedArtistTags()
        return when {
            header.size >= 10 && header.copyOfRange(0, 3).contentEquals("ID3".toByteArray()) -> parseId3(header)
            header.size >= 4 && header.copyOfRange(0, 4).contentEquals("fLaC".toByteArray()) -> parseFlac(header)
            header.size >= 12 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) -> parseMp4(header)
            else -> EmbeddedArtistTags()
        }
    }

    private fun canonical(s: String): String = s.uppercase().filter(Char::isLetterOrDigit)
    private fun safeValue(s: String): String? = s.trim().take(180).takeIf { it.isNotEmpty() && !it.any { c -> c == '\uFFFD' || c.code < 0x20 } }
    private fun collectMbids(values: Collection<String>): Set<String> = values.flatMap { text ->
        UUID.findAll(text).map { it.value.lowercase() }.toList()
    }.toSet()
    private fun read32(data: ByteArray, pos: Int, little: Boolean = false): Int? {
        if (pos < 0 || pos + 4 > data.size) return null
        return ByteBuffer.wrap(data, pos, 4).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int
    }
    private fun synchsafe(data: ByteArray, pos: Int): Int? {
        if (pos < 0 || pos + 4 > data.size || (0..3).any { (data[pos + it].toInt() and 128) != 0 }) return null
        return (0..3).fold(0) { acc, i -> (acc shl 7) or (data[pos + i].toInt() and 127) }
    }

    private fun parseId3(bytes: ByteArray): EmbeddedArtistTags {
        val major = bytes[3].toInt() and 255
        val flags = bytes[5].toInt() and 255
        if (major !in 3..4 || flags and 0xC0 != 0) return EmbeddedArtistTags() // no unsync/extended header support
        val tagLen = synchsafe(bytes, 6) ?: return EmbeddedArtistTags()
        if (tagLen <= 0 || tagLen + 10 > bytes.size) return EmbeddedArtistTags()
        val names = mutableListOf<String>()
        val albumNames = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val albumIds = mutableListOf<String>()
        var offset = 10
        var frames = 0
        while (offset + 10 <= tagLen + 10 && frames++ < MAX_FRAMES) {
            val frame = bytes.copyOfRange(offset, offset + 4).toString(StandardCharsets.ISO_8859_1)
            if (!frame.matches(Regex("[A-Z0-9]{4}"))) break
            val size = if (major == 4) synchsafe(bytes, offset + 4) else read32(bytes, offset + 4)
            if (size == null || size < 0 || size > MAX_TAG_BYTES || offset + 10L + size > tagLen + 10L) break
            val secondFlag = bytes[offset + 9].toInt() and 255
            val unsupported = if (major == 3) secondFlag and 0xE0 != 0 else secondFlag and 0x0F != 0
            if (!unsupported && size > 1 && frame in setOf("TXXX", "TPE1", "TPE2")) {
                val payload = bytes.copyOfRange(offset + 10, offset + 10 + size)
                val encoded = decodeText(payload)
                if (frame == "TXXX") {
                    val description = encoded.firstOrNull().orEmpty()
                    val values = encoded.drop(1)
                    when (canonical(description)) {
                        "MUSICBRAINZARTISTID" -> ids += values
                        "MUSICBRAINZALBUMARTISTID" -> albumIds += values
                        "ARTISTS" -> names += values.mapNotNull(::safeValue)
                        "ALBUMARTIST", "ALBUMARTISTS" -> albumNames += values.mapNotNull(::safeValue)
                    }
                } else if (frame == "TPE2") albumNames += encoded.mapNotNull(::safeValue)
                // TPE1 is a display credit and must not be split on &, feat. or commas.
            }
            offset += 10 + size
        }
        return EmbeddedArtistTags(collectMbids(ids), collectMbids(albumIds), names.distinct(), albumNames.distinct())
    }

    private fun decodeText(payload: ByteArray): List<String> {
        if (payload.isEmpty()) return emptyList()
        val encoding = payload[0].toInt() and 255
        val raw = payload.copyOfRange(1, payload.size)
        val charset: Charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> UTF16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> return emptyList()
        }
        return try {
            val text = String(raw, charset)
            text.split('\u0000').map(String::trim).filter(String::isNotEmpty).take(64)
        } catch (_: Exception) { emptyList() }
    }

    /** Bounded MP4/M4A atom walker. iTunes freeform '----' contains MusicBrainz tags.
     * Files with `moov` outside the first MAX_TAG_BYTES are intentionally unsupported rather than
     * silently requesting network access or reading an unbounded media payload.
     */
    private fun parseMp4(data: ByteArray): EmbeddedArtistTags {
        val ids = mutableListOf<String>()
        val albumIds = mutableListOf<String>()
        val names = mutableListOf<String>()
        val albumNames = mutableListOf<String>()
        var inspectedAtoms = 0

        fun atoms(from: Int, until: Int, handle: (Int, Int, String) -> Unit) {
            var at = from
            while (at + 8 <= until && inspectedAtoms++ < MAX_FRAMES) {
                val length = read32(data, at) ?: break
                // Do not accept extended, zero-sized, or out-of-window atoms.
                if (length < 8 || at.toLong() + length > until.toLong()) break
                val type = String(data, at + 4, 4, StandardCharsets.ISO_8859_1)
                handle(at + 8, at + length, type)
                at += length
            }
        }

        fun readData(from: Int, until: Int): String? {
            if (from + 8 > until) return null // 4 bytes data type, 4 bytes locale
            val kind = read32(data, from) ?: return null
            if (kind !in 0..2) return null
            val length = until - (from + 8)
            if (length !in 1..4096) return null
            val encoding = if (kind == 2) StandardCharsets.UTF_16BE else StandardCharsets.UTF_8
            return safeValue(String(data, from + 8, length, encoding))
        }

        fun freeform(from: Int, until: Int) {
            var mean: String? = null
            var name: String? = null
            val values = mutableListOf<String>()
            atoms(from, until) { start, end, type ->
                when (type) {
                    "mean" -> if (start + 4 <= end) mean = String(data, start + 4, end - start - 4, StandardCharsets.UTF_8)
                    "name" -> if (start + 4 <= end) name = String(data, start + 4, end - start - 4, StandardCharsets.UTF_8)
                    "data" -> readData(start, end)?.let(values::add)
                }
            }
            // Only the documented iTunes namespace can claim MusicBrainz's freeform keys.
            if (mean != "com.apple.iTunes" || values.isEmpty()) return
            when (canonical(name.orEmpty())) {
                "MUSICBRAINZARTISTID" -> ids += values
                "MUSICBRAINZALBUMARTISTID" -> albumIds += values
                "ARTISTS" -> names += values
                "ALBUMARTIST", "ALBUMARTISTS" -> albumNames += values
            }
        }

        fun ilst(from: Int, until: Int) {
            atoms(from, until) { start, end, type ->
                when (type) {
                    "----" -> freeform(start, end)
                    "aART" -> atoms(start, end) { fromData, toData, key ->
                        if (key == "data") readData(fromData, toData)?.let(albumNames::add)
                    }
                    // '©ART' is an unstructured display credit. Do not split on '&' or commas.
                }
            }
        }

        fun inspect(from: Int, until: Int, depth: Int) {
            if (depth > 5) return
            atoms(from, until) { start, end, type ->
                when (type) {
                    "ilst" -> ilst(start, end)
                    "moov", "udta" -> inspect(start, end, depth + 1)
                    "meta" -> if (start + 4 <= end) inspect(start + 4, end, depth + 1)
                }
            }
        }
        inspect(0, data.size, 0)
        return EmbeddedArtistTags(
            collectMbids(ids), collectMbids(albumIds),
            names.mapNotNull(::safeValue).distinct(), albumNames.mapNotNull(::safeValue).distinct(),
        )
    }

    private fun parseFlac(data: ByteArray): EmbeddedArtistTags {
        var pos = 4
        var blocks = 0
        val comments = mutableMapOf<String, MutableList<String>>()
        while (pos + 4 <= data.size && blocks++ < 32) {
            val type = data[pos].toInt() and 0x7F
            val isLast = data[pos].toInt() and 0x80 != 0
            val len = ((data[pos + 1].toInt() and 255) shl 16) or
                ((data[pos + 2].toInt() and 255) shl 8) or (data[pos + 3].toInt() and 255)
            pos += 4
            if (len < 0 || pos.toLong() + len > data.size || len > MAX_TAG_BYTES) break
            if (type == 4) {
                val end = pos + len
                var at = pos
                fun readLength(): Int? {
                    val n = read32(data, at, little = true) ?: return null
                    at += 4
                    return n.takeIf { it >= 0 && at.toLong() + it <= end }
                }
                val vendor = readLength() ?: break
                at += vendor
                val count = readLength() ?: break
                if (count !in 0..MAX_COMMENTS) break
                repeat(count) {
                    val n = readLength() ?: return EmbeddedArtistTags()
                    val entry = String(data, at, n, StandardCharsets.UTF_8)
                    at += n
                    val key = entry.substringBefore('=', "")
                    val value = entry.substringAfter('=', "")
                    if (key.isNotBlank() && value.isNotBlank())
                        comments.getOrPut(canonical(key)) { mutableListOf() }.add(value.take(1024))
                }
            }
            pos += len
            if (isLast) break
        }
        return EmbeddedArtistTags(
            artistMbids = collectMbids(comments["MUSICBRAINZARTISTID"].orEmpty()),
            albumArtistMbids = collectMbids(comments["MUSICBRAINZALBUMARTISTID"].orEmpty()),
            creditedNames = comments["ARTISTS"].orEmpty().mapNotNull(::safeValue).distinct(),
            albumArtists = (comments["ALBUMARTIST"].orEmpty() + comments["ALBUMARTISTS"].orEmpty()).mapNotNull(::safeValue).distinct(),
        )
    }
}
