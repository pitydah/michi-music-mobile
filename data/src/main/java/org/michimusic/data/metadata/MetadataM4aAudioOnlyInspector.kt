package org.michimusic.data.metadata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Bounded structural gate for the MP4 subset certified by the metadata editor.
 *
 * This is deliberately not a general media parser. It proves that the private
 * document has a non-fragmented MP4 atom tree, at least one audio track, no
 * video track, and that every declared audio sample is physically placed inside
 * a recorded `mdat` payload.
 *
 * The evidence separates two concerns that metadata surgery can legitimately
 * change from the two it must never change:
 *
 * - `mediaPayloadBytes` / `mediaPayloadSha256` fingerprint the concatenated
 *   `mdat` payload exactly as it appears in the document.
 * - `chunkOffsetSha256` fingerprints the sample-to-chunk offset table, which a
 *   rewrite is allowed to relocate.
 * - `samples` fingerprints the ordered sample sizes plus the decoding timeline
 *   (timescale, `stts`, `ctts`, `elst`). Equal fingerprints prove sample
 *   equivalence without requiring equal byte offsets.
 */
internal object MetadataM4aAudioOnlyInspector {
    private const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
    private const val MAX_ATOMS = 16_384
    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_TABLE_ENTRIES = 1_000_000L
    private const val MAX_TABLE_BYTES = 16L * 1024L * 1024L

    private val AUDIO_CODECS = setOf("mp4a", "alac", "ac-3", "ec-3", "Opus", "fLaC")
    private val ENCRYPTED_CODECS = setOf("enca", "encv", "drms", "drmi", "fenc")

    data class SampleEvidence(
        val sampleCount: Long,
        val sampleBytes: Long,
        val samplesSha256: String,
        val timelineSha256: String,
        val fingerprintSha256: String,
    )

    data class Evidence(
        val audioTracks: Int,
        val mediaPayloadBytes: Long,
        val mediaPayloadSha256: String,
        val codec: String,
        val chunkOffsetSha256: String,
        val samples: SampleEvidence,
    )

    sealed interface Result {
        data class AudioOnly(val evidence: Evidence) : Result
        data class Refused(val reason: String) : Result
    }

    private data class Atom(
        val type: String,
        val payload: Long,
        val end: Long,
    )

    private data class Payload(
        val start: Long,
        val end: Long,
    )

    private data class ChunkGroup(
        val firstChunk: Long,
        val samplesPerChunk: Long,
    )

    private data class TimeEntry(
        val count: Long,
        val delta: Long,
    )

    private data class EditEntry(
        val duration: Long,
        val mediaTime: Long,
    )

    private data class AudioTable(
        val codec: String,
        val sizes: LongArray,
        val offsets: LongArray,
        val groups: List<ChunkGroup>,
        val deltas: List<TimeEntry>,
        val composition: List<TimeEntry> = emptyList(),
        val edits: List<EditEntry> = emptyList(),
    )

    private data class Mdia(
        val handler: String?,
        val timeScale: Long,
        val table: AudioTable?,
    )

    private data class SoundTrack(
        val timeScale: Long,
        val table: AudioTable,
    )

    private data class MoovScan(
        val audioTracks: Int,
        val videoTracks: Int,
        val tracks: List<SoundTrack>,
    )

    private data class TableScan(
        val sampleCount: Long,
        val sampleBytes: Long,
    )

    fun inspect(channel: FileChannel): Result = try {
        val size = channel.size()
        require(size in 24..MAX_FILE_BYTES) { "INVALID_SIZE" }
        var atoms = 0
        var sawFtyp = false
        var sawMoov = false
        var sawMdat = false
        val mediaDigest = MessageDigest.getInstance("SHA-256")
        var mediaBytes = 0L
        val payloads = ArrayList<Payload>()
        var moovScan: MoovScan? = null

        fun next(at: Long, end: Long): Atom {
            require(++atoms <= MAX_ATOMS) { "ATOM_LIMIT" }
            require(at >= 0L && end >= at && at <= end - 8L) { "TRUNCATED_ATOM" }
            val header = read(channel, at, 16.coerceAtMost((end - at).toInt()))
            val size32 = ByteBuffer.wrap(header, 0, 4)
                .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val headerBytes: Int
            val atomBytes = when (size32) {
                0L -> {
                    headerBytes = 8
                    end - at
                }
                1L -> {
                    require(header.size >= 16) { "TRUNCATED_EXTENDED_ATOM" }
                    headerBytes = 16
                    ByteBuffer.wrap(header, 8, 8).order(ByteOrder.BIG_ENDIAN).long
                }
                else -> {
                    headerBytes = 8
                    size32
                }
            }
            require(atomBytes >= headerBytes && atomBytes <= end - at) {
                "INVALID_ATOM_SIZE"
            }
            return Atom(type, at + headerBytes, at + atomBytes)
        }

        fun payload(atom: Atom, minimum: Long): ByteArray {
            val length = atom.end - atom.payload
            require(length in minimum..MAX_TABLE_BYTES) {
                "INVALID_" + atom.type.uppercase()
            }
            return read(channel, atom.payload, length.toInt())
        }

        fun parseStsd(atom: Atom): String {
            val body = payload(atom, 8L)
            val count = u32(body, 4)
            require(count in 1L..MAX_TABLE_ENTRIES) { "INVALID_STSD" }
            // The certified audio-only subset has exactly one sample description.
            // A second entry could hide an encrypted or video codec from the
            // first-entry checks below.
            require(count == 1L) { "MULTIPLE_SAMPLE_DESCRIPTIONS" }
            var at = 8
            var codec: String? = null
            for (index in 0 until count.toInt()) {
                require(body.size - at >= 8) { "INVALID_STSD" }
                val entryBytes = u32(body, at)
                require(entryBytes >= 8L && entryBytes <= (body.size - at).toLong()) {
                    "INVALID_STSD"
                }
                val type = String(body, at + 4, 4, Charsets.ISO_8859_1)
                if (index == 0) codec = type
                at += entryBytes.toInt()
            }
            require(at == body.size) { "INVALID_STSD" }
            return codec ?: throw IllegalArgumentException("INVALID_STSD")
        }

        fun parseTimeTable(atom: Atom): List<TimeEntry> {
            val body = payload(atom, 8L)
            val count = u32(body, 4)
            val label = "INVALID_" + atom.type.uppercase()
            require(count in 0L..MAX_TABLE_ENTRIES) { label }
            require(body.size.toLong() == 8L + 8L * count) { label }
            return (0 until count.toInt()).map { index ->
                TimeEntry(u32(body, 8 + index * 8), u32(body, 12 + index * 8))
            }
        }

        fun parseChunkGroups(atom: Atom): List<ChunkGroup> {
            val body = payload(atom, 8L)
            val count = u32(body, 4)
            require(count in 1L..MAX_TABLE_ENTRIES) { "INVALID_STSC" }
            require(body.size.toLong() == 8L + 12L * count) { "INVALID_STSC" }
            return (0 until count.toInt()).map { index ->
                val base = 8 + index * 12
                val firstChunk = u32(body, base)
                val samplesPerChunk = u32(body, base + 4)
                val description = u32(body, base + 8)
                require(firstChunk >= 1L && samplesPerChunk >= 1L) { "INVALID_STSC" }
                require(description >= 1L) { "INVALID_STSC" }
                ChunkGroup(firstChunk, samplesPerChunk)
            }
        }

        fun parseSampleSizes(atom: Atom): LongArray {
            val body = payload(atom, 12L)
            val count = u32(body, 8)
            val label = "INVALID_" + atom.type.uppercase()
            require(count in 0L..MAX_TABLE_ENTRIES) { label }
            if (atom.type == "stsz") {
                val uniform = u32(body, 4)
                if (uniform != 0L) {
                    require(count > 0L && body.size == 12) { "INVALID_STSZ" }
                    return LongArray(count.toInt()) { uniform }
                }
                require(body.size.toLong() == 12L + 4L * count) { "INVALID_STSZ" }
                return LongArray(count.toInt()) { index -> u32(body, 12 + index * 4) }
            }
            val fieldSize = body[7].toInt() and 255
            require(fieldSize == 4 || fieldSize == 8 || fieldSize == 16) { "INVALID_STZ2" }
            // Reserved are the 24 high bits; the low byte of the same word is
            // `field_size`, already validated above.
            require(u32(body, 4) and 0xffffff00L == 0L) { "INVALID_STZ2" }
            require(body.size.toLong() == 12L + (count * fieldSize + 7L) / 8L) {
                "INVALID_STZ2"
            }
            val sizes = LongArray(count.toInt())
            for (index in sizes.indices) {
                val bit = index * fieldSize
                val at = 12 + bit / 8
                sizes[index] = when (fieldSize) {
                    16 -> u16(body, at).toLong()
                    8 -> (body[at].toInt() and 255).toLong()
                    else ->
                        if (bit % 8 == 0) {
                            ((body[at].toInt() and 255) ushr 4).toLong()
                        } else {
                            ((body[at].toInt() and 255) and 15).toLong()
                        }
                }
            }
            return sizes
        }

        fun parseChunkOffsets(atom: Atom, width: Int): LongArray {
            val body = payload(atom, 8L)
            val count = u32(body, 4)
            val label = "INVALID_" + atom.type.uppercase()
            require(count in 1L..MAX_TABLE_ENTRIES) { label }
            require(body.size.toLong() == 8L + width.toLong() * count) { label }
            return LongArray(count.toInt()) { index ->
                if (width == 4) u32(body, 8 + index * 4) else u64(body, 8 + index * 8)
            }
        }

        fun parseEdits(atom: Atom): List<EditEntry> {
            val body = payload(atom, 8L)
            val version = body[0].toInt() and 255
            val count = u32(body, 4)
            require(count in 0L..MAX_TABLE_ENTRIES) { "INVALID_ELST" }
            val width = when (version) {
                0 -> 12
                1 -> 20
                else -> throw IllegalArgumentException("INVALID_ELST")
            }
            require(body.size.toLong() == 8L + width.toLong() * count) { "INVALID_ELST" }
            return (0 until count.toInt()).map { index ->
                val base = 8 + index * width
                val duration = if (version == 0) u32(body, base) else u64(body, base)
                val mediaTime = if (version == 0) i32(body, base + 4).toLong() else i64(body, base + 8)
                require(duration >= 0L && mediaTime >= -1L) { "INVALID_ELST" }
                EditEntry(duration, mediaTime)
            }
        }

        fun parseEdts(atom: Atom): List<EditEntry> {
            var at = atom.payload
            var found: List<EditEntry>? = null
            while (at < atom.end) {
                val child = next(at, atom.end)
                if (child.type == "elst") {
                    require(found == null) { "DUPLICATE_ELST" }
                    found = parseEdits(child)
                }
                at = child.end
            }
            return found ?: emptyList()
        }

        fun parseTimescale(atom: Atom): Long {
            val body = payload(atom, 24L)
            val version = body[0].toInt() and 255
            val timeScale = when (version) {
                0 -> u32(body, 12)
                1 -> u32(body, 20)
                else -> throw IllegalArgumentException("UNSUPPORTED_MDHD_VERSION")
            }
            require(timeScale > 0L) { "INVALID_TIMESCALE" }
            return timeScale
        }

        fun parseStbl(start: Long, end: Long, edits: List<EditEntry>): AudioTable {
            var at = start
            var codec: String? = null
            var sizes: LongArray? = null
            var offsets: LongArray? = null
            var groups: List<ChunkGroup>? = null
            var deltas: List<TimeEntry>? = null
            var composition: List<TimeEntry> = emptyList()
            while (at < end) {
                val atom = next(at, end)
                when (atom.type) {
                    "stsd" -> codec = parseStsd(atom)
                    "stts" -> deltas = parseTimeTable(atom)
                    "ctts" -> composition = parseTimeTable(atom)
                    "stsc" -> groups = parseChunkGroups(atom)
                    "stsz", "stz2" -> sizes = parseSampleSizes(atom)
                    "stco" -> offsets = parseChunkOffsets(atom, 4)
                    "co64" -> offsets = parseChunkOffsets(atom, 8)
                }
                at = atom.end
            }
            return AudioTable(
                codec = codec ?: throw IllegalArgumentException("SAMPLE_DESCRIPTION_MISSING"),
                sizes = sizes ?: throw IllegalArgumentException("SAMPLE_SIZES_MISSING"),
                offsets = offsets ?: throw IllegalArgumentException("CHUNK_OFFSETS_MISSING"),
                groups = groups ?: throw IllegalArgumentException("SAMPLE_TO_CHUNK_MISSING"),
                deltas = deltas ?: throw IllegalArgumentException("TIMELINE_MISSING"),
                composition = composition,
                edits = edits,
            )
        }

        fun stblInside(minf: Atom, edits: List<EditEntry>): AudioTable {
            var at = minf.payload
            var found: Atom? = null
            while (at < minf.end) {
                val child = next(at, minf.end)
                if (child.type == "stbl") {
                    require(found == null) { "DUPLICATE_STBL" }
                    found = child
                }
                at = child.end
            }
            val stbl = found ?: throw IllegalArgumentException("SAMPLE_TABLE_MISSING")
            return parseStbl(stbl.payload, stbl.end, edits)
        }

        fun parseMdia(start: Long, end: Long, edits: List<EditEntry>): Mdia {
            var at = start
            var handler: String? = null
            var timeScale = 0L
            var table: AudioTable? = null
            while (at < end) {
                val atom = next(at, end)
                when (atom.type) {
                    "hdlr" -> {
                        require(handler == null && atom.end - atom.payload >= 12L) {
                            "INVALID_HANDLER"
                        }
                        handler = String(
                            read(channel, atom.payload + 8L, 4),
                            Charsets.US_ASCII,
                        )
                    }
                    "mdhd" -> {
                        require(timeScale == 0L) { "DUPLICATE_MDHD" }
                        timeScale = parseTimescale(atom)
                    }
                    "minf" -> {
                        require(table == null) { "DUPLICATE_STBL" }
                        table = stblInside(atom, edits)
                    }
                }
                at = atom.end
            }
            return Mdia(handler, timeScale, table)
        }

        fun scanTrack(start: Long, end: Long): Mdia {
            var at = start
            var edits = emptyList<EditEntry>()
            var mdiaStart = -1L
            var mdiaEnd = -1L
            while (at < end) {
                val atom = next(at, end)
                when (atom.type) {
                    "edts" -> {
                        require(edits.isEmpty()) { "DUPLICATE_EDTS" }
                        edits = parseEdts(atom)
                    }
                    "mdia" -> {
                        require(mdiaStart < 0L) { "DUPLICATE_MDIA" }
                        mdiaStart = atom.payload
                        mdiaEnd = atom.end
                    }
                }
                at = atom.end
            }
            require(mdiaStart >= 0L) { "TRACK_WITHOUT_MDIA" }
            return parseMdia(mdiaStart, mdiaEnd, edits)
        }

        fun scanMoov(start: Long, end: Long): MoovScan {
            var at = start
            var audio = 0
            var video = 0
            val tracks = ArrayList<SoundTrack>()
            while (at < end) {
                val atom = next(at, end)
                if (atom.type == "trak") {
                    val mdia = scanTrack(atom.payload, atom.end)
                    when (mdia.handler) {
                        "soun" -> {
                            val table = mdia.table
                                ?: throw IllegalArgumentException("AUDIO_SAMPLE_TABLE_MISSING")
                            require(mdia.timeScale > 0L) { "INVALID_TIMESCALE" }
                            audio++
                            tracks += SoundTrack(mdia.timeScale, table)
                        }
                        "vide" -> video++
                        null -> throw IllegalArgumentException("TRACK_WITHOUT_HANDLER")
                    }
                }
                at = atom.end
            }
            return MoovScan(audio, video, tracks)
        }

        fun validate(sound: SoundTrack): TableScan {
            val table = sound.table
            val sizes = table.sizes
            val offsets = table.offsets
            val groups = table.groups
            require(table.codec !in ENCRYPTED_CODECS) { "ENCRYPTED_TRACK" }
            require(table.codec in AUDIO_CODECS) { "UNSUPPORTED_AUDIO_CODEC" }
            require(sizes.isNotEmpty()) { "SAMPLE_SIZES_MISSING" }
            require(offsets.isNotEmpty()) { "CHUNK_OFFSETS_MISSING" }
            require(groups.isNotEmpty()) { "SAMPLE_TO_CHUNK_MISSING" }
            require(table.deltas.isNotEmpty()) { "TIMELINE_MISSING" }
            var declared = 0L
            for (entry in table.deltas) {
                require(entry.count >= 0L) { "INVALID_STTS" }
                declared += entry.count
            }
            require(declared == sizes.size.toLong()) { "TIMELINE_SAMPLE_MISMATCH" }
            if (table.composition.isNotEmpty()) {
                var composed = 0L
                for (entry in table.composition) {
                    require(entry.count >= 0L) { "INVALID_CTTS" }
                    composed += entry.count
                }
                require(composed == sizes.size.toLong()) { "COMPOSITION_SAMPLE_MISMATCH" }
            }
            require(groups.first().firstChunk == 1L) { "INVALID_STSC" }
            var index = 0
            var bytes = 0L
            for (groupIndex in groups.indices) {
                val group = groups[groupIndex]
                if (groupIndex > 0) {
                    require(group.firstChunk > groups[groupIndex - 1].firstChunk) {
                        "INVALID_STSC"
                    }
                }
                require(group.firstChunk <= offsets.size.toLong()) { "INVALID_STSC" }
                val nextFirst = if (groupIndex + 1 < groups.size) {
                    groups[groupIndex + 1].firstChunk
                } else {
                    offsets.size.toLong() + 1L
                }
                var chunk = group.firstChunk
                while (chunk < nextFirst && chunk <= offsets.size.toLong()) {
                    var cursor = offsets[(chunk - 1L).toInt()]
                    var taken = 0L
                    while (taken < group.samplesPerChunk && index < sizes.size) {
                        val sampleSize = sizes[index]
                        require(sampleSize >= 0L) { "INVALID_SAMPLE_SIZE" }
                        val sampleEnd = cursor + sampleSize
                        require(sampleEnd >= cursor) { "SAMPLE_OUTSIDE_MDAT" }
                        require(
                            payloads.any { cursor >= it.start && sampleEnd <= it.end },
                        ) { "SAMPLE_OUTSIDE_MDAT" }
                        cursor = sampleEnd
                        bytes += sampleSize
                        index++
                        taken++
                    }
                    chunk++
                }
            }
            require(index == sizes.size) { "SAMPLE_NOT_IN_CHUNKS" }
            return TableScan(sizes.size.toLong(), bytes)
        }

        var at = 0L
        while (at < size) {
            val atom = next(at, size)
            when (atom.type) {
                "ftyp" -> {
                    require(!sawFtyp && at == 0L) { "INVALID_FTYP" }
                    sawFtyp = true
                }
                "moov" -> {
                    require(!sawMoov) { "DUPLICATE_MOOV" }
                    sawMoov = true
                    moovScan = scanMoov(atom.payload, atom.end)
                }
                "mdat" -> {
                    sawMdat = true
                    payloads += Payload(atom.payload, atom.end)
                    mediaBytes += digestRange(channel, atom.payload, atom.end, mediaDigest)
                }
                "moof", "mfra" -> error("FRAGMENTED_MP4_NOT_CERTIFIED")
            }
            at = atom.end
        }
        require(sawFtyp && sawMoov && sawMdat) { "REQUIRED_ATOM_MISSING" }
        require(mediaBytes > 0L) { "EMPTY_MEDIA_PAYLOAD" }
        val scan = moovScan ?: throw IllegalArgumentException("REQUIRED_ATOM_MISSING")
        require(scan.audioTracks > 0) { "AUDIO_TRACK_MISSING" }
        require(scan.videoTracks == 0) { "VIDEO_TRACK_PRESENT" }
        require(scan.tracks.isNotEmpty()) { "AUDIO_TRACK_MISSING" }
        val samplesDigest = MessageDigest.getInstance("SHA-256")
        val timelineDigest = MessageDigest.getInstance("SHA-256")
        val offsetDigest = MessageDigest.getInstance("SHA-256")
        var sampleCount = 0L
        var sampleBytes = 0L
        for (track in scan.tracks) {
            val validated = validate(track)
            sampleCount += validated.sampleCount
            sampleBytes += validated.sampleBytes
            feed(samplesDigest, "track")
            feed(samplesDigest, track.table.codec)
            feed(samplesDigest, validated.sampleCount)
            for (sampleSize in track.table.sizes) feed(samplesDigest, sampleSize)
            feed(timelineDigest, "track")
            feed(timelineDigest, track.timeScale)
            feed(timelineDigest, validated.sampleCount)
            for (entry in track.table.deltas) {
                feed(timelineDigest, entry.count)
                feed(timelineDigest, entry.delta)
            }
            for (entry in track.table.composition) {
                feed(timelineDigest, entry.count)
                feed(timelineDigest, entry.delta)
            }
            for (entry in track.table.edits) {
                feed(timelineDigest, entry.duration)
                feed(timelineDigest, entry.mediaTime)
            }
            feed(offsetDigest, "track")
            feed(offsetDigest, track.table.offsets.size.toLong())
            for (offset in track.table.offsets) feed(offsetDigest, offset)
        }
        val samplesSha256 = hex(samplesDigest)
        val timelineSha256 = hex(timelineDigest)
        val fingerprint = MessageDigest.getInstance("SHA-256")
        feed(fingerprint, samplesSha256)
        feed(fingerprint, timelineSha256)
        Result.AudioOnly(
            Evidence(
                audioTracks = scan.audioTracks,
                mediaPayloadBytes = mediaBytes,
                mediaPayloadSha256 = hex(mediaDigest),
                codec = scan.tracks.map { it.table.codec }.distinct().joinToString(","),
                chunkOffsetSha256 = hex(offsetDigest),
                samples = SampleEvidence(
                    sampleCount = sampleCount,
                    sampleBytes = sampleBytes,
                    samplesSha256 = samplesSha256,
                    timelineSha256 = timelineSha256,
                    fingerprintSha256 = hex(fingerprint),
                ),
            ),
        )
    } catch (error: Exception) {
        Result.Refused(error.message ?: error.javaClass.simpleName)
    }

    private fun feed(digest: MessageDigest, value: Long) {
        digest.update(
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array(),
        )
    }

    private fun feed(digest: MessageDigest, text: String) {
        digest.update(text.toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
    }

    private fun hex(digest: MessageDigest): String =
        digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }

    private fun u16(bytes: ByteArray, at: Int): Int {
        require(at >= 0 && at + 2 <= bytes.size) { "INVALID_TABLE_VALUE" }
        return ByteBuffer.wrap(bytes, at, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    }

    private fun u32(bytes: ByteArray, at: Int): Long {
        require(at >= 0 && at + 4 <= bytes.size) { "INVALID_TABLE_VALUE" }
        return ByteBuffer.wrap(bytes, at, 4)
            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
    }

    private fun u64(bytes: ByteArray, at: Int): Long {
        require(at >= 0 && at + 8 <= bytes.size) { "INVALID_TABLE_VALUE" }
        val value = ByteBuffer.wrap(bytes, at, 8).order(ByteOrder.BIG_ENDIAN).long
        require(value >= 0L) { "INVALID_TABLE_VALUE" }
        return value
    }

    private fun i32(bytes: ByteArray, at: Int): Int {
        require(at >= 0 && at + 4 <= bytes.size) { "INVALID_TABLE_VALUE" }
        return ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun i64(bytes: ByteArray, at: Int): Long {
        require(at >= 0 && at + 8 <= bytes.size) { "INVALID_TABLE_VALUE" }
        return ByteBuffer.wrap(bytes, at, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun read(channel: FileChannel, offset: Long, bytes: Int): ByteArray {
        require(bytes >= 0 && offset >= 0L && bytes.toLong() <= channel.size() - offset) {
            "READ_OUT_OF_RANGE"
        }
        val buffer = ByteBuffer.allocate(bytes)
        var at = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, at)
            require(count > 0) { "TRUNCATED_READ" }
            at += count
        }
        return buffer.array()
    }

    private fun digestRange(
        channel: FileChannel,
        start: Long,
        end: Long,
        digest: MessageDigest,
    ): Long {
        require(start in 0..end && end <= channel.size()) { "INVALID_MEDIA_RANGE" }
        val buffer = ByteBuffer.allocate(BUFFER_BYTES)
        var at = start
        while (at < end) {
            buffer.clear()
            buffer.limit(minOf(BUFFER_BYTES.toLong(), end - at).toInt())
            val count = channel.read(buffer, at)
            require(count > 0) { "TRUNCATED_MEDIA_PAYLOAD" }
            at += count
            buffer.flip()
            digest.update(buffer)
        }
        return end - start
    }
}
