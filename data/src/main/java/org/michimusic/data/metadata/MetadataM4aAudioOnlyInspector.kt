package org.michimusic.data.metadata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * Bounded structural gate for the MP4 subset certified by the metadata editor.
 *
 * This is deliberately not a general media parser. It proves that the private
 * document has a non-fragmented MP4 atom tree, at least one audio track, no
 * video track, and that every declared audio sample is physically placed inside
 * a recorded `mdat` payload.
 *
 * The evidence separates what metadata surgery can legitimately change from
 * what it must never change:
 *
 * - `mediaPayloadBytes` / `mediaPayloadSha256` fingerprint the concatenated
 *   `mdat` payload exactly as it appears in the document.
 * - `chunkOffsetSha256` fingerprints the sample-to-chunk offset table, which a
 *   rewrite is allowed to relocate.
 * - `samples.samplesSha256` fingerprints the actual bytes referenced by every
 *   sample in decode order with explicit `(track, sample index, size, bytes)`
 *   framing. Swapping chunk offsets, reordering samples, duplicating a
 *   reference or changing the bytes behind a sample therefore changes the
 *   digest even when the `mdat` payload and the declared sizes stay identical.
 * - `samples.codecConfigSha256` fingerprints the effective `stsd` sample entry
 *   configuration (codec, data reference, channel count, sample size, sample
 *   rate and every child configuration atom), not just the four-character code.
 * - `samples.timelineSha256` fingerprints the track identity, timescale,
 *   decode deltas (`stts`), composition offsets (`ctts`) and edit list (`elst`).
 */
internal object MetadataM4aAudioOnlyInspector {
    private const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
    private const val MAX_ATOMS = 16_384
    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_TABLE_ENTRIES = 1_000_000L
    private const val MAX_TABLE_BYTES = 16L * 1024L * 1024L

    private val AUDIO_CODECS = setOf("mp4a", "alac", "ac-3", "ec-3", "Opus", "fLaC")
    private val ENCRYPTED_CODECS = setOf("enca", "encv", "drms", "drmi", "fenc")
    private val ENCRYPTED_SAMPLE_ENTRY_CHILDREN = setOf("sinf", "schi", "tenc")

    data class EditEvidence(
        val duration: Long,
        val mediaTime: Long,
        val mediaRate: Long,
    )

    data class TrackEvidence(
        val trackId: Long,
        val codec: String,
        val dataReferenceIndex: Int,
        val channelCount: Int,
        val sampleSize: Int,
        val sampleRate: Int,
        val timeScale: Long,
        val sampleCount: Long,
        val sampleBytes: Long,
        val decodeDuration: Long,
        val compositionEntries: Long,
        val edits: List<EditEvidence>,
        val samplesSha256: String,
        val codecConfigSha256: String,
        val timelineSha256: String,
    )

    data class SampleEvidence(
        val sampleCount: Long,
        val sampleBytes: Long,
        /** Actual referenced bytes in decode order with (track, index, size) framing. */
        val samplesSha256: String,
        /** Effective sample-entry and codec configuration digest. */
        val codecConfigSha256: String,
        /** Track identity, timescale, stts, ctts and edit-list digest. */
        val timelineSha256: String,
        val fingerprintSha256: String,
        /** Per-track correspondence, sorted by track id. */
        val tracks: List<TrackEvidence>,
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

    /**
     * The single media-equivalence decision used before a physical mutation and
     * during restore/recovery. Chunk offsets may move; referenced sample bytes,
     * codec configuration, timing, edit lists and track identity may not.
     */
    fun equivalent(before: Evidence, after: Evidence): Boolean =
        before.audioTracks == after.audioTracks &&
            before.codec == after.codec &&
            before.mediaPayloadBytes == after.mediaPayloadBytes &&
            before.mediaPayloadSha256 == after.mediaPayloadSha256 &&
            before.samples == after.samples

    private class CancelledException : CancellationException("INSPECTION_CANCELLED")

    private data class Atom(
        val type: String,
        val payload: Long,
        val end: Long,
    )

    private data class Payload(
        val start: Long,
        val end: Long,
    )

    private data class DataReference(
        val type: String,
    )

    private data class ChildBox(
        val type: String,
        val start: Int,
        val length: Int,
    )

    private data class SampleEntry(
        val codec: String,
        val dataReferenceIndex: Int,
        val channelCount: Int,
        val sampleSize: Int,
        val sampleRate: Int,
        val configSha256: String,
    )

    private data class ChunkGroup(
        val firstChunk: Long,
        val samplesPerChunk: Long,
        val sampleDescriptionIndex: Long,
    )

    private data class TimeEntry(
        val count: Long,
        val delta: Long,
    )

    private data class EditEntry(
        val duration: Long,
        val mediaTime: Long,
        val mediaRate: Long,
    )

    private data class AudioTable(
        val sampleEntry: SampleEntry,
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
        val trackId: Long,
        val timeScale: Long,
        val table: AudioTable,
    )

    private data class TrackScan(
        val trackId: Long,
        val mdia: Mdia,
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

    private data class ValidatedTrack(
        val scan: TableScan,
        val samplesSha256: String,
        val timelineSha256: String,
        val codecConfigSha256: String,
        val decodeDuration: Long,
        val compositionEntries: Long,
    )

    fun inspect(channel: FileChannel, checkCancellation: () -> Unit = {}): Result = try {
        val size = channel.size()
        require(size in 24..MAX_FILE_BYTES) { "INVALID_SIZE" }
        val buffer = ByteBuffer.allocate(BUFFER_BYTES)
        val frame = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)

        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted) throw CancelledException()
            checkCancellation()
        }

        fun feedLong(digest: MessageDigest, value: Long) {
            frame.clear()
            frame.putLong(value)
            frame.flip()
            digest.update(frame)
        }

        fun feedText(digest: MessageDigest, text: String) {
            digest.update(text.toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
        }

        var atoms = 0
        var sawFtyp = false
        var sawMoov = false
        var sawMdat = false
        val mediaDigest = MessageDigest.getInstance("SHA-256")
        var mediaBytes = 0L
        val payloads = ArrayList<Payload>()
        var moovScan: MoovScan? = null

        fun next(at: Long, end: Long): Atom {
            checkCancelled()
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
            checkCancelled()
            val length = atom.end - atom.payload
            require(length in minimum..MAX_TABLE_BYTES) {
                "INVALID_" + atom.type.uppercase()
            }
            return read(channel, atom.payload, length.toInt())
        }

        fun parseStsd(atom: Atom, references: List<DataReference>?): SampleEntry {
            val body = payload(atom, 12L)
            val count = u32(body, 4)
            require(count in 1L..MAX_TABLE_ENTRIES) { "INVALID_STSD" }
            // The certified audio-only subset has exactly one sample description.
            // A second entry could hide an encrypted or video codec from the
            // first-entry checks below.
            require(count == 1L) { "MULTIPLE_SAMPLE_DESCRIPTIONS" }
            val at = 8
            require(body.size - at >= 8) { "INVALID_STSD" }
            val entryBytes = u32(body, at)
            require(entryBytes >= 8L + 28L && entryBytes <= (body.size - at).toLong()) {
                "INVALID_STSD"
            }
            val entryEnd = at + entryBytes.toInt()
            val codec = String(body, at + 4, 4, Charsets.ISO_8859_1)
            require(codec !in ENCRYPTED_CODECS) { "ENCRYPTED_TRACK" }
            require(codec in AUDIO_CODECS) { "UNSUPPORTED_AUDIO_CODEC" }
            val entryPayload = at + 8
            val dataReferenceIndex = u16(body, entryPayload + 6)
            val version = u16(body, entryPayload + 8)
            val extraBytes = when (version) {
                0 -> 0
                1 -> 16
                2 -> 36
                else -> throw IllegalArgumentException("UNSUPPORTED_SAMPLE_ENTRY_VERSION")
            }
            val channelCount: Int
            val sampleSize: Int
            val sampleRate: Int
            if (version == 2) {
                val v2 = entryPayload + 28
                require(entryPayload + 28 + extraBytes <= entryEnd) { "INVALID_STSD" }
                val rate = ByteBuffer.wrap(body, v2 + 4, 8)
                    .order(ByteOrder.BIG_ENDIAN).double
                channelCount = u32(body, v2 + 12).toInt()
                sampleSize = u32(body, v2 + 20).toInt()
                require(rate.isFinite() && rate >= 0.0) { "INVALID_STSD" }
                sampleRate = rate.toInt()
            } else {
                channelCount = u16(body, entryPayload + 16)
                sampleSize = u16(body, entryPayload + 18)
                sampleRate = (u32(body, entryPayload + 24) ushr 16).toInt()
            }
            val childStart = entryPayload + 28 + extraBytes
            require(childStart <= entryEnd) { "INVALID_STSD" }
            var childAt = childStart
            val children = ArrayList<ChildBox>()
            while (childAt < entryEnd) {
                checkCancelled()
                require(entryEnd - childAt >= 8) { "INVALID_STSD" }
                val childBytes = u32(body, childAt)
                require(childBytes >= 8L && childBytes <= (entryEnd - childAt).toLong()) {
                    "INVALID_STSD"
                }
                val childType = String(body, childAt + 4, 4, Charsets.ISO_8859_1)
                // `sinf`/`schi`/`tenc` signal a protected sample entry even when
                // its four-character code was forged to look like plain audio.
                require(childType !in ENCRYPTED_SAMPLE_ENTRY_CHILDREN) {
                    "ENCRYPTED_TRACK"
                }
                children += ChildBox(
                    type = childType,
                    start = childAt + 8,
                    length = childBytes.toInt() - 8,
                )
                childAt += childBytes.toInt()
            }
            require(childAt == entryEnd) { "INVALID_STSD" }
            if (references == null) {
                // No `dinf` box: the single implicit data reference must be the
                // self-contained one. Sample offsets are already file-relative.
                require(dataReferenceIndex == 1) { "EXTERNAL_DATA_REFERENCE" }
            } else {
                require(dataReferenceIndex in 1..references.size) { "INVALID_DREF" }
            }
            require(at + entryBytes.toInt() == body.size) { "INVALID_STSD" }

            // The effective configuration is the sample-entry header plus every
            // child atom payload (esds, alac, dOps, dfLa, ...), independent of
            // reserved padding and of child order.
            val config = MessageDigest.getInstance("SHA-256")
            feedText(config, "M4A-CODEC-CONFIG-V1")
            feedText(config, codec)
            feedLong(config, version.toLong())
            feedLong(config, dataReferenceIndex.toLong())
            feedLong(config, channelCount.toLong())
            feedLong(config, sampleSize.toLong())
            feedLong(config, sampleRate.toLong())
            if (version != 2) feedLong(config, u32(body, entryPayload + 24))
            if (extraBytes > 0) {
                feedLong(config, extraBytes.toLong())
                config.update(body, entryPayload + 28, extraBytes)
            }
            feedLong(config, children.size.toLong())
            val ordered = children.sortedWith(
                compareBy<ChildBox> { it.type }.thenBy { it.start },
            )
            for (child in ordered) {
                feedText(config, child.type)
                feedLong(config, child.length.toLong())
                config.update(body, child.start, child.length)
            }
            return SampleEntry(
                codec = codec,
                dataReferenceIndex = dataReferenceIndex,
                channelCount = channelCount,
                sampleSize = sampleSize,
                sampleRate = sampleRate,
                configSha256 = hex(config),
            )
        }

        fun parseTimeTable(atom: Atom): List<TimeEntry> {
            val body = payload(atom, 8L)
            val version = body[0].toInt() and 255
            val count = u32(body, 4)
            val label = "INVALID_" + atom.type.uppercase()
            val signed = atom.type == "ctts" && version == 1
            require(version == 0 || signed) { label }
            require(count in 0L..MAX_TABLE_ENTRIES) { label }
            require(body.size.toLong() == 8L + 8L * count) { label }
            return (0 until count.toInt()).map { index ->
                checkCancelled()
                val entryCount = u32(body, 8 + index * 8)
                val delta = if (signed) {
                    i32(body, 12 + index * 8).toLong()
                } else {
                    u32(body, 12 + index * 8)
                }
                if (!signed) require(delta >= 0L) { label }
                TimeEntry(entryCount, delta)
            }
        }

        fun parseChunkGroups(atom: Atom): List<ChunkGroup> {
            val body = payload(atom, 8L)
            val count = u32(body, 4)
            require(count in 1L..MAX_TABLE_ENTRIES) { "INVALID_STSC" }
            require(body.size.toLong() == 8L + 12L * count) { "INVALID_STSC" }
            return (0 until count.toInt()).map { index ->
                checkCancelled()
                val base = 8 + index * 12
                val firstChunk = u32(body, base)
                val samplesPerChunk = u32(body, base + 4)
                val description = u32(body, base + 8)
                require(firstChunk >= 1L && samplesPerChunk >= 1L) { "INVALID_STSC" }
                require(description >= 1L) { "INVALID_STSC" }
                ChunkGroup(firstChunk, samplesPerChunk, description)
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
                return LongArray(count.toInt()) { index ->
                    checkCancelled()
                    u32(body, 12 + index * 4)
                }
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
                checkCancelled()
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
                checkCancelled()
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
                checkCancelled()
                val base = 8 + index * width
                val duration = if (version == 0) u32(body, base) else u64(body, base)
                val mediaTime = if (version == 0) i32(body, base + 4).toLong() else i64(body, base + 8)
                val mediaRate = if (version == 0) u32(body, base + 8) else u32(body, base + 16)
                require(duration >= 0L && mediaTime >= -1L) { "INVALID_ELST" }
                EditEntry(duration, mediaTime, mediaRate)
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

        fun parseTrackId(atom: Atom): Long {
            val body = payload(atom, 24L)
            val version = body[0].toInt() and 255
            val at = when (version) {
                0 -> 12
                1 -> 20
                else -> throw IllegalArgumentException("UNSUPPORTED_TKHD_VERSION")
            }
            require(body.size >= at + 4) { "INVALID_TRACK_IDENTITY" }
            val trackId = u32(body, at)
            require(trackId > 0L) { "INVALID_TRACK_IDENTITY" }
            return trackId
        }

        fun parseDref(atom: Atom): List<DataReference> {
            val body = payload(atom, 12L)
            val version = body[0].toInt() and 255
            require(version == 0) { "INVALID_DREF" }
            val count = u32(body, 4)
            require(count in 1L..MAX_TABLE_ENTRIES) { "INVALID_DREF" }
            var at = 8
            val references = ArrayList<DataReference>(count.toInt())
            for (index in 0 until count.toInt()) {
                checkCancelled()
                require(body.size - at >= 12) { "INVALID_DREF" }
                val entryBytes = u32(body, at)
                require(entryBytes >= 12L && entryBytes <= (body.size - at).toLong()) {
                    "INVALID_DREF"
                }
                val type = String(body, at + 4, 4, Charsets.ISO_8859_1)
                val flags = u32(body, at + 8)
                // A data reference is self-contained only when it is the local
                // `url ` form with the self-contained flag set. Every external
                // URL/URN or unknown form is outside this certified subset.
                val selfContained = type == "url " && (flags and 1L) != 0L
                require(selfContained) { "EXTERNAL_DATA_REFERENCE" }
                references += DataReference(type)
                at += entryBytes.toInt()
            }
            require(at == body.size) { "INVALID_DREF" }
            return references
        }

        fun parseDinf(atom: Atom): List<DataReference> {
            var at = atom.payload
            var found: List<DataReference>? = null
            while (at < atom.end) {
                val child = next(at, atom.end)
                if (child.type == "dref") {
                    require(found == null) { "DUPLICATE_DREF" }
                    found = parseDref(child)
                }
                at = child.end
            }
            return found ?: throw IllegalArgumentException("INVALID_DINF")
        }

        fun parseStbl(
            start: Long,
            end: Long,
            edits: List<EditEntry>,
            references: List<DataReference>?,
        ): AudioTable {
            var at = start
            var sampleEntry: SampleEntry? = null
            var sizes: LongArray? = null
            var offsets: LongArray? = null
            var groups: List<ChunkGroup>? = null
            var deltas: List<TimeEntry>? = null
            var composition: List<TimeEntry>? = null
            while (at < end) {
                val atom = next(at, end)
                when (atom.type) {
                    "stsd" -> {
                        require(sampleEntry == null) { "DUPLICATE_STSD" }
                        sampleEntry = parseStsd(atom, references)
                    }
                    "stts" -> {
                        require(deltas == null) { "DUPLICATE_STTS" }
                        deltas = parseTimeTable(atom)
                    }
                    "ctts" -> {
                        require(composition == null) { "DUPLICATE_CTTS" }
                        composition = parseTimeTable(atom)
                    }
                    "stsc" -> {
                        require(groups == null) { "DUPLICATE_STSC" }
                        groups = parseChunkGroups(atom)
                    }
                    "stsz", "stz2" -> {
                        require(sizes == null) { "DUPLICATE_SAMPLE_SIZES" }
                        sizes = parseSampleSizes(atom)
                    }
                    "stco" -> {
                        require(offsets == null) { "DUPLICATE_CHUNK_OFFSETS" }
                        offsets = parseChunkOffsets(atom, 4)
                    }
                    "co64" -> {
                        require(offsets == null) { "DUPLICATE_CHUNK_OFFSETS" }
                        offsets = parseChunkOffsets(atom, 8)
                    }
                }
                at = atom.end
            }
            return AudioTable(
                sampleEntry = sampleEntry
                    ?: throw IllegalArgumentException("SAMPLE_DESCRIPTION_MISSING"),
                sizes = sizes ?: throw IllegalArgumentException("SAMPLE_SIZES_MISSING"),
                offsets = offsets ?: throw IllegalArgumentException("CHUNK_OFFSETS_MISSING"),
                groups = groups ?: throw IllegalArgumentException("SAMPLE_TO_CHUNK_MISSING"),
                deltas = deltas ?: throw IllegalArgumentException("TIMELINE_MISSING"),
                composition = composition ?: emptyList(),
                edits = edits,
            )
        }

        fun stblInside(minf: Atom, edits: List<EditEntry>): AudioTable {
            var at = minf.payload
            var found: Atom? = null
            var references: List<DataReference>? = null
            while (at < minf.end) {
                val child = next(at, minf.end)
                when (child.type) {
                    "stbl" -> {
                        require(found == null) { "DUPLICATE_STBL" }
                        found = child
                    }
                    "dinf" -> {
                        require(references == null) { "DUPLICATE_DINF" }
                        references = parseDinf(child)
                    }
                }
                at = child.end
            }
            val stbl = found ?: throw IllegalArgumentException("SAMPLE_TABLE_MISSING")
            return parseStbl(stbl.payload, stbl.end, edits, references)
        }

        fun parseMdia(start: Long, end: Long, edits: List<EditEntry>): Mdia {
            var at = start
            var handler: String? = null
            var timeScale = 0L
            var minf: Atom? = null
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
                        require(minf == null) { "DUPLICATE_MINF" }
                        minf = atom
                    }
                }
                at = atom.end
            }
            // Video and other handlers are counted by `scanMoov`; only the audio
            // handler needs a sample table, and parsing it lazily avoids
            // treating non-audio sample descriptions as certified audio.
            val table = if (handler == "soun") {
                val media = minf ?: throw IllegalArgumentException("SAMPLE_TABLE_MISSING")
                stblInside(media, edits)
            } else {
                null
            }
            return Mdia(handler, timeScale, table)
        }

        fun scanTrack(start: Long, end: Long): TrackScan {
            var at = start
            var edits = emptyList<EditEntry>()
            var mdiaStart = -1L
            var mdiaEnd = -1L
            var trackId = -1L
            while (at < end) {
                val atom = next(at, end)
                when (atom.type) {
                    "edts" -> {
                        require(edits.isEmpty()) { "DUPLICATE_EDTS" }
                        edits = parseEdts(atom)
                    }
                    "tkhd" -> {
                        require(trackId < 0L) { "DUPLICATE_TKHD" }
                        trackId = parseTrackId(atom)
                    }
                    "mdia" -> {
                        require(mdiaStart < 0L) { "DUPLICATE_MDIA" }
                        mdiaStart = atom.payload
                        mdiaEnd = atom.end
                    }
                }
                at = atom.end
            }
            require(trackId > 0L) { "INVALID_TRACK_IDENTITY" }
            require(mdiaStart >= 0L) { "TRACK_WITHOUT_MDIA" }
            return TrackScan(trackId, parseMdia(mdiaStart, mdiaEnd, edits))
        }

        fun scanMoov(start: Long, end: Long): MoovScan {
            var at = start
            var audio = 0
            var video = 0
            val tracks = ArrayList<SoundTrack>()
            val trackIds = HashSet<Long>()
            while (at < end) {
                val atom = next(at, end)
                when (atom.type) {
                    "mvex" -> throw IllegalArgumentException(
                        "FRAGMENTED_MP4_NOT_CERTIFIED",
                    )
                    "trak" -> {
                        val scanned = scanTrack(atom.payload, atom.end)
                        require(trackIds.add(scanned.trackId)) { "DUPLICATE_TRACK_ID" }
                        when (scanned.mdia.handler) {
                            "soun" -> {
                                val table = scanned.mdia.table
                                    ?: throw IllegalArgumentException(
                                        "AUDIO_SAMPLE_TABLE_MISSING",
                                    )
                                require(scanned.mdia.timeScale > 0L) {
                                    "INVALID_TIMESCALE"
                                }
                                audio++
                                tracks += SoundTrack(
                                    scanned.trackId,
                                    scanned.mdia.timeScale,
                                    table,
                                )
                            }
                            "vide" -> video++
                            null -> throw IllegalArgumentException("TRACK_WITHOUT_HANDLER")
                            else -> Unit
                        }
                    }
                }
                at = atom.end
            }
            return MoovScan(audio, video, tracks)
        }

        fun insidePayloads(start: Long, end: Long): Boolean {
            var low = 0
            var high = payloads.size - 1
            var candidate = -1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val payload = payloads[middle]
                if (payload.start <= start) {
                    candidate = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            if (candidate < 0) return false
            val payload = payloads[candidate]
            return start >= payload.start && end <= payload.end
        }

        fun digestSampleBytes(
            start: Long,
            length: Long,
            first: MessageDigest,
            second: MessageDigest,
        ) {
            var at = start
            var remaining = length
            while (remaining > 0L) {
                checkCancelled()
                buffer.clear()
                buffer.limit(minOf(BUFFER_BYTES.toLong(), remaining).toInt())
                var readTotal = 0
                while (buffer.hasRemaining()) {
                    val read = channel.read(buffer, at + readTotal)
                    require(read > 0) { "TRUNCATED_SAMPLE_DATA" }
                    readTotal += read
                }
                at += readTotal
                remaining -= readTotal
                buffer.flip()
                first.update(buffer)
                buffer.rewind()
                second.update(buffer)
            }
        }

        fun validate(track: SoundTrack, overallSamples: MessageDigest): ValidatedTrack {
            val table = track.table
            val sampleEntry = table.sampleEntry
            val sizes = table.sizes
            val offsets = table.offsets
            val groups = table.groups
            require(sampleEntry.codec !in ENCRYPTED_CODECS) { "ENCRYPTED_TRACK" }
            require(sampleEntry.codec in AUDIO_CODECS) { "UNSUPPORTED_AUDIO_CODEC" }
            require(sizes.isNotEmpty()) { "SAMPLE_SIZES_MISSING" }
            require(offsets.isNotEmpty()) { "CHUNK_OFFSETS_MISSING" }
            require(groups.isNotEmpty()) { "SAMPLE_TO_CHUNK_MISSING" }
            require(table.deltas.isNotEmpty()) { "TIMELINE_MISSING" }
            var declared = 0L
            var decodeDuration = 0L
            for (entry in table.deltas) {
                require(entry.count >= 0L && entry.delta >= 0L) { "INVALID_STTS" }
                declared += entry.count
                val duration = entry.count * entry.delta
                require(
                    duration >= 0L && decodeDuration <= Long.MAX_VALUE - duration,
                ) { "INVALID_STTS" }
                decodeDuration += duration
            }
            require(declared == sizes.size.toLong()) { "TIMELINE_SAMPLE_MISMATCH" }
            var compositionEntries = 0L
            for (entry in table.composition) {
                require(entry.count >= 0L) { "INVALID_CTTS" }
                compositionEntries += entry.count
            }
            if (table.composition.isNotEmpty()) {
                require(compositionEntries == sizes.size.toLong()) {
                    "COMPOSITION_SAMPLE_MISMATCH"
                }
            }
            require(groups.first().firstChunk == 1L) { "INVALID_STSC" }

            val trackSamples = MessageDigest.getInstance("SHA-256")
            feedText(trackSamples, "M4A-TRACK-SAMPLES-V1")
            feedLong(trackSamples, track.trackId)
            feedLong(trackSamples, sizes.size.toLong())
            feedText(overallSamples, "track")
            feedLong(overallSamples, track.trackId)
            feedLong(overallSamples, sizes.size.toLong())

            val starts = LongArray(sizes.size)
            var startCount = 0
            var index = 0
            var bytes = 0L
            for (groupIndex in groups.indices) {
                val group = groups[groupIndex]
                require(group.sampleDescriptionIndex == 1L) {
                    "SAMPLE_DESCRIPTION_INDEX_OUT_OF_RANGE"
                }
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
                    // Every declared chunk must carry at least one sample. An
                    // offset table longer than the sample count is inconsistent.
                    require(index < sizes.size) { "INVALID_STSC" }
                    var cursor = offsets[(chunk - 1L).toInt()]
                    var taken = 0L
                    while (taken < group.samplesPerChunk && index < sizes.size) {
                        checkCancelled()
                        val sampleSize = sizes[index]
                        require(sampleSize >= 0L) { "INVALID_SAMPLE_SIZE" }
                        val sampleEnd = cursor + sampleSize
                        require(sampleEnd >= cursor) { "SAMPLE_OUTSIDE_MDAT" }
                        require(insidePayloads(cursor, sampleEnd)) {
                            "SAMPLE_OUTSIDE_MDAT"
                        }
                        feedLong(trackSamples, index.toLong())
                        feedLong(trackSamples, sampleSize)
                        feedLong(overallSamples, index.toLong())
                        feedLong(overallSamples, sampleSize)
                        digestSampleBytes(cursor, sampleSize, trackSamples, overallSamples)
                        if (sampleSize > 0L) starts[startCount++] = cursor
                        cursor = sampleEnd
                        bytes += sampleSize
                        index++
                        taken++
                    }
                    chunk++
                }
            }
            require(index == sizes.size) { "SAMPLE_NOT_IN_CHUNKS" }
            starts.sort()
            var previous = -1L
            for (value in starts) {
                // Zero-sized samples share their cursor and do not own bytes.
                if (value == 0L) continue
                require(value != previous) { "DUPLICATE_SAMPLE_REFERENCE" }
                previous = value
            }

            val timeline = MessageDigest.getInstance("SHA-256")
            feedText(timeline, "M4A-TIMELINE-V1")
            feedLong(timeline, track.trackId)
            feedLong(timeline, track.timeScale)
            feedLong(timeline, sizes.size.toLong())
            feedLong(timeline, decodeDuration)
            for (entry in table.deltas) {
                checkCancelled()
                feedLong(timeline, entry.count)
                feedLong(timeline, entry.delta)
            }
            for (entry in table.composition) {
                checkCancelled()
                feedLong(timeline, entry.count)
                feedLong(timeline, entry.delta)
            }
            for (entry in table.edits) {
                checkCancelled()
                feedLong(timeline, entry.duration)
                feedLong(timeline, entry.mediaTime)
                feedLong(timeline, entry.mediaRate)
            }
            return ValidatedTrack(
                scan = TableScan(sizes.size.toLong(), bytes),
                samplesSha256 = hex(trackSamples),
                timelineSha256 = hex(timeline),
                codecConfigSha256 = sampleEntry.configSha256,
                decodeDuration = decodeDuration,
                compositionEntries = compositionEntries,
            )
        }

        var at = 0L
        while (at < size) {
            checkCancelled()
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
                    mediaBytes += digestRange(
                        channel,
                        atom.payload,
                        atom.end,
                        mediaDigest,
                        buffer,
                        { checkCancelled() },
                    )
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
        val configDigest = MessageDigest.getInstance("SHA-256")
        val offsetDigest = MessageDigest.getInstance("SHA-256")
        feedText(samplesDigest, "M4A-SAMPLES-V1")
        feedLong(samplesDigest, scan.tracks.size.toLong())
        feedText(timelineDigest, "M4A-TIMELINES-V1")
        feedLong(timelineDigest, scan.tracks.size.toLong())
        feedText(configDigest, "M4A-CODECS-V1")
        feedLong(configDigest, scan.tracks.size.toLong())
        feedText(offsetDigest, "M4A-CHUNK-OFFSETS-V1")
        feedLong(offsetDigest, scan.tracks.size.toLong())
        var sampleCount = 0L
        var sampleBytes = 0L
        val tracksEvidence = ArrayList<TrackEvidence>()
        for (track in scan.tracks.sortedBy { it.trackId }) {
            val validated = validate(track, samplesDigest)
            sampleCount += validated.scan.sampleCount
            sampleBytes += validated.scan.sampleBytes
            feedLong(timelineDigest, track.trackId)
            feedText(timelineDigest, validated.timelineSha256)
            feedLong(configDigest, track.trackId)
            feedText(configDigest, track.table.sampleEntry.codec)
            feedText(configDigest, validated.codecConfigSha256)
            feedText(offsetDigest, "track")
            feedLong(offsetDigest, track.trackId)
            feedLong(offsetDigest, track.table.offsets.size.toLong())
            for (offset in track.table.offsets) feedLong(offsetDigest, offset)
            tracksEvidence += TrackEvidence(
                trackId = track.trackId,
                codec = track.table.sampleEntry.codec,
                dataReferenceIndex = track.table.sampleEntry.dataReferenceIndex,
                channelCount = track.table.sampleEntry.channelCount,
                sampleSize = track.table.sampleEntry.sampleSize,
                sampleRate = track.table.sampleEntry.sampleRate,
                timeScale = track.timeScale,
                sampleCount = validated.scan.sampleCount,
                sampleBytes = validated.scan.sampleBytes,
                decodeDuration = validated.decodeDuration,
                compositionEntries = validated.compositionEntries,
                edits = track.table.edits.map {
                    EditEvidence(it.duration, it.mediaTime, it.mediaRate)
                },
                samplesSha256 = validated.samplesSha256,
                codecConfigSha256 = validated.codecConfigSha256,
                timelineSha256 = validated.timelineSha256,
            )
        }
        val samplesSha256 = hex(samplesDigest)
        val timelineSha256 = hex(timelineDigest)
        val codecConfigSha256 = hex(configDigest)
        val fingerprint = MessageDigest.getInstance("SHA-256")
        feedText(fingerprint, "M4A-FINGERPRINT-V1")
        feedText(fingerprint, samplesSha256)
        feedText(fingerprint, codecConfigSha256)
        feedText(fingerprint, timelineSha256)
        feedLong(fingerprint, tracksEvidence.size.toLong())
        for (track in tracksEvidence) {
            feedLong(fingerprint, track.trackId)
            feedLong(fingerprint, track.sampleCount)
            feedLong(fingerprint, track.sampleBytes)
            feedText(fingerprint, track.samplesSha256)
            feedText(fingerprint, track.codecConfigSha256)
            feedText(fingerprint, track.timelineSha256)
        }
        Result.AudioOnly(
            Evidence(
                audioTracks = scan.audioTracks,
                mediaPayloadBytes = mediaBytes,
                mediaPayloadSha256 = hex(mediaDigest),
                codec = tracksEvidence.map { it.codec }.distinct().joinToString(","),
                chunkOffsetSha256 = hex(offsetDigest),
                samples = SampleEvidence(
                    sampleCount = sampleCount,
                    sampleBytes = sampleBytes,
                    samplesSha256 = samplesSha256,
                    codecConfigSha256 = codecConfigSha256,
                    timelineSha256 = timelineSha256,
                    fingerprintSha256 = hex(fingerprint),
                    tracks = tracksEvidence,
                ),
            ),
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Result.Refused(error.message ?: error.javaClass.simpleName)
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
        buffer: ByteBuffer,
        checkCancelled: () -> Unit,
    ): Long {
        require(start in 0..end && end <= channel.size()) { "INVALID_MEDIA_RANGE" }
        var at = start
        while (at < end) {
            checkCancelled()
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
