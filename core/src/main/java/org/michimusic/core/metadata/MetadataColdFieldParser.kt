package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.util.Locale

/**
 * ME-04 READ: seekable, bounded, read-only examination of physical tag structures.
 * The caller must have independently checked source ownership and provider stamps.
 * This never reads audio into memory or infers a format from a filename.
 * Fail closed: a skipped/ambiguous frame does NOT prove any field absent.
 */
object MetadataColdFieldParser {
    private const val MAX_ID3_TAG = 16 * 1024 * 1024
    private const val MAX_FLAC_META = 64 * 1024 * 1024L
    private const val MAX_COMMENT = 2 * 1024 * 1024
    private const val MAX_VALUE = 65536
    private const val MAX_FRAMES = 8192
    private const val MAX_BLOCKS = 128
    private const val MAX_TEXT = 8192
    private val catalog = MetadataReadFieldCatalog.primary
    private fun u(b: Byte) = b.toInt() and 255
    private fun be32(b: ByteArray, p: Int): Long =
        (u(b[p]).toLong() shl 24) or (u(b[p+1]).toLong() shl 16) or
            (u(b[p+2]).toLong() shl 8) or u(b[p+3]).toLong()
    private fun le32(b: ByteArray, p: Int): Long =
        u(b[p]).toLong() or (u(b[p+1]).toLong() shl 8) or
            (u(b[p+2]).toLong() shl 16) or (u(b[p+3]).toLong() shl 24)
    private fun synchsafe(b: ByteArray, p: Int): Long? =
        if ((0..3).any { u(b[p+it]) >= 128 }) null else
            (0..3).fold(0L) { value, i -> (value shl 7) or u(b[p+i]).toLong() }

    private class Source(private val channel: FileChannel) {
        val length = channel.size()
        fun read(offset: Long, count: Int): ByteArray? {
            if (offset < 0 || count < 0 || count > MAX_COMMENT ||
                offset > length || count.toLong() > length - offset) return null
            val result = ByteBuffer.allocate(count)
            var pos = offset
            while (result.hasRemaining()) {
                val n = channel.read(result, pos)
                if (n <= 0) return null
                pos += n
            }
            return result.array()
        }
        fun zeroPadding(offset: Long, until: Long): Boolean {
            var at = offset
            while (at < until) {
                val data = read(at, minOf(4096L, until - at).toInt()) ?: return false
                if (data.any { it != 0.toByte() }) return false
                at += data.size
            }
            return true
        }
    }

    fun observe(channel: FileChannel): MetadataFieldReadReport {
        return try {
        val source = Source(channel)
        val header = source.read(0, 10) ?: return unavailable(MetadataObservationIssue.TRUNCATED)
        when {
            header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte() -> flac(source)
            header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
                header[2] == '3'.code.toByte() -> id3(source, header)
            header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte() ->
                MetadataOggColdReader.observe(channel)
            header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() ->
                MetadataMp4ColdReader.observe(channel)
            (header[0] == 'w'.code.toByte() && header[1] == 'v'.code.toByte() &&
                header[2] == 'p'.code.toByte() && header[3] == 'k'.code.toByte()) ||
                (header[0] == 'M'.code.toByte() && header[1] == 'A'.code.toByte() &&
                    header[2] == 'C'.code.toByte() && header[3] == ' '.code.toByte()) ->
                MetadataApeColdReader.observe(channel)
            (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()) ||
                (header[0] == 'F'.code.toByte() && header[1] == 'O'.code.toByte() &&
                    header[2] == 'R'.code.toByte() && header[3] == 'M'.code.toByte()) ->
                MetadataRiffAiffColdReader.observe(channel)
            else -> unavailable(MetadataObservationIssue.UNSUPPORTED_CONTAINER)
        }
    } catch (_: Exception) {
        unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE)
    }
    }

    private fun unavailable(reason: MetadataObservationIssue,
                            format: MetadataPhysicalContainer = MetadataPhysicalContainer.UNKNOWN) =
        MetadataReadFieldCatalog.unavailable(reason, format)

    private fun decoder(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }

    private fun clean(value: String): String? = value.takeIf {
        it.isNotBlank() && it.length <= MAX_VALUE && '\uFFFD' !in it &&
            it.none { ch -> ch == '\u0000' || (ch.code < 32 && ch != '\n' && ch != '\t') }
    }

    private fun decodeId3(data: ByteArray, version: Int): List<String>? =
        MetadataId3TextListCodec.decode(version, data)?.takeIf { values ->
            values.all { clean(it) != null }
        }

    private val id3Keys = mapOf(
        "TIT2" to MetadataField.TITLE,
        "TPE1" to MetadataField.ARTIST,
        "TALB" to MetadataField.ALBUM,
        "TPE2" to MetadataField.ALBUM_ARTIST,
        "TCON" to MetadataField.GENRE,
        "TCOM" to MetadataField.COMPOSER,
        "TRCK" to MetadataField.TRACK_NUMBER,
        "TPOS" to MetadataField.DISC_NUMBER,
        "TDRC" to MetadataField.RELEASE_DATE,
        "TYER" to MetadataField.RELEASE_DATE,
        "TIT3" to MetadataField.SUBTITLE,
        "TORY" to MetadataField.ORIGINAL_RELEASE_DATE,
        "TDOR" to MetadataField.ORIGINAL_RELEASE_DATE,
        "TPUB" to MetadataField.LABEL,
        "TSRC" to MetadataField.ISRC,
        "TBPM" to MetadataField.BPM,
        "TKEY" to MetadataField.MUSICAL_KEY,
        "TIT1" to MetadataField.GROUPING,
        "TCOP" to MetadataField.COPYRIGHT,
        "TLAN" to MetadataField.LANGUAGE,
        "TOPE" to MetadataField.ORIGINAL_ARTIST,
        "TEXT" to MetadataField.LYRICIST,
        "TPE3" to MetadataField.CONDUCTOR,
        "TPE4" to MetadataField.REMIXER,
    )

    private fun id3(source: Source, header: ByteArray): MetadataFieldReadReport {
        val v = u(header[3])
        val format = when (v) {
            3 -> MetadataPhysicalContainer.ID3_V23
            4 -> MetadataPhysicalContainer.ID3_V24
            else -> MetadataPhysicalContainer.UNKNOWN
        }
        if (v != 3 && v != 4) return unavailable(MetadataObservationIssue.UNSUPPORTED_VERSION)
        // Global unsynchronisation, extended header, experimental tag or footer:
        // no negative evidence is defensible until dedicated handling is implemented.
        if (u(header[5]) != 0 || u(header[4]) != 0)
            return unavailable(MetadataObservationIssue.UNSUPPORTED_FLAGS, format)
        val tagLength = synchsafe(header, 6)
            ?: return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
        if (tagLength > MAX_ID3_TAG) return unavailable(MetadataObservationIssue.RESOURCE_LIMIT, format)
        val end = 10L + tagLength
        if (end > source.length) return unavailable(MetadataObservationIssue.TRUNCATED, format)
        val values = mutableMapOf<MetadataField, MutableList<List<String>>>()
        val rejected = mutableMapOf<MetadataField, MetadataObservationIssue>()
        var at = 10L; var count = 0; var supplementalDateSeen = false
        while (at < end) {
            if (++count > MAX_FRAMES) return unavailable(MetadataObservationIssue.RESOURCE_LIMIT, format)
            if (end - at < 10) {
                if (!source.zeroPadding(at, end))
                    return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
                break
            }
            val frame = source.read(at, 10)
                ?: return unavailable(MetadataObservationIssue.TRUNCATED, format)
            if (frame.take(4).all { it == 0.toByte() }) {
                if (!source.zeroPadding(at, end))
                    return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
                break
            }
            val id = String(frame, 0, 4, StandardCharsets.US_ASCII)
            if (!id.matches(Regex("[A-Z0-9]{4}")))
                return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
            val length = if (v == 4) synchsafe(frame, 4) else be32(frame, 4)
            if (length == null || length < 1 || length > end - at - 10)
                return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
            if (id in setOf("TDAT", "TIME")) supplementalDateSeen = true
            if (id == "UFID") {
                val flags = u(frame[8]) or u(frame[9])
                val maybeMusicBrainz = if (flags == 0 && length <= MAX_TEXT) {
                    val payload = source.read(at + 10, length.toInt())
                        ?: return unavailable(MetadataObservationIssue.TRUNCATED, format)
                    MetadataMusicBrainzTagMap.ufidMayBeMusicBrainz(payload)
                } else null
                if (maybeMusicBrainz != false)
                    rejected[MetadataField.MUSICBRAINZ_RECORDING_ID] =
                        MetadataObservationIssue.AMBIGUOUS_ALIAS
            }
            val describedKind = MetadataId3DescribedTagCodec.Kind.entries.firstOrNull { it.frameId == id }
            if (describedKind != null) {
                val flags = u(frame[8]) or u(frame[9])
                val target = describedKind.field
                when {
                    flags != 0 -> rejected[target] = MetadataObservationIssue.UNSUPPORTED_FLAGS
                    length > 160 * 1024 -> rejected[target] = MetadataObservationIssue.RESOURCE_LIMIT
                    else -> {
                        val payload = source.read(at + 10, length.toInt())
                            ?: return unavailable(MetadataObservationIssue.TRUNCATED, format)
                        val entry = MetadataId3DescribedTagCodec.decode(describedKind, v, payload)
                        when {
                            entry == null -> rejected[target] = MetadataObservationIssue.BAD_TEXT_ENCODING
                            !entry.canonical -> rejected[target] = MetadataObservationIssue.AMBIGUOUS_ALIAS
                            else -> values.getOrPut(target) { mutableListOf() }.add(listOf(entry.text))
                        }
                    }
                }
            }
            val field = id3Keys[id]
            if (id == "TXXX") {
                val txxxFields = MetadataMusicBrainzTagMap.id3Fields
                val flags = u(frame[8]) or u(frame[9])
                if (flags != 0 || length > MAX_TEXT) {
                    for (f in txxxFields) rejected[f] =
                        if (flags != 0) MetadataObservationIssue.UNSUPPORTED_FLAGS
                        else MetadataObservationIssue.RESOURCE_LIMIT
                } else {
                    val payload = source.read(at + 10, length.toInt())
                        ?: return unavailable(MetadataObservationIssue.TRUNCATED, format)
                    val decoded = MetadataMusicBrainzTagMap.decodeId3(payload, v)
                    if (decoded == null) {
                        // A malformed user text frame might conceal a known descriptor.
                        for (f in txxxFields) rejected[f] = MetadataObservationIssue.BAD_TEXT_ENCODING
                    } else {
                        val f = MetadataMusicBrainzTagMap.fromId3Description(decoded.description)
                        if (f != null) {
                            if (!MetadataMusicBrainzTagMap.valid(f, listOf(decoded.value)))
                                rejected[f] = MetadataObservationIssue.INVALID_FIELD
                            else values.getOrPut(f) { mutableListOf() }.add(listOf(decoded.value))
                        }
                    }
                }
            }
            if (field != null) {
                val flags = u(frame[8]) or u(frame[9]); val expectedVersion =
                    (v == 3 && id in setOf("TYER", "TORY")) ||
                        (v == 4 && id in setOf("TDRC", "TDOR")) ||
                        id !in setOf("TYER", "TDRC", "TORY", "TDOR")
                when {
                    !expectedVersion -> rejected[field] = MetadataObservationIssue.UNSUPPORTED_VERSION
                    flags != 0 -> rejected[field] = MetadataObservationIssue.UNSUPPORTED_FLAGS
                    length > MAX_TEXT -> rejected[field] = MetadataObservationIssue.RESOURCE_LIMIT
                    else -> {
                        val payload = source.read(at + 10, length.toInt())
                            ?: return unavailable(MetadataObservationIssue.TRUNCATED, format)
                        val parsed = decodeId3(payload, v)
                        if (parsed == null) rejected[field] = MetadataObservationIssue.BAD_TEXT_ENCODING
                        else values.getOrPut(field) { mutableListOf() }.add(parsed)
                    }
                }
            }
            at += 10 + length
        }
        val observations = linkedMapOf<MetadataField, MetadataFieldObservation>()
        for (field in catalog) {
            val entries = values[field].orEmpty()
            observations[field] = when {
                field in rejected -> MetadataFieldObservation.Unverifiable(rejected.getValue(field))
                field !in id3Keys.values && field !in MetadataMusicBrainzTagMap.id3Fields &&
                    field !in MetadataId3DescribedTagCodec.fields &&
                    field !in setOf(MetadataField.TRACK_TOTAL, MetadataField.DISC_TOTAL) ->
                    MetadataFieldObservation.Unverifiable(MetadataObservationIssue.FIELD_NOT_SUPPORTED)
                field in setOf(MetadataField.TRACK_TOTAL, MetadataField.DISC_TOTAL) ->
                    MetadataFieldObservation.VerifiedAbsent // replaced below by paired frame parsing
                entries.size > 1 -> MetadataFieldObservation.Conflicted(MetadataObservationIssue.DUPLICATE_FIELD)
                entries.isEmpty() -> MetadataFieldObservation.VerifiedAbsent
                else -> MetadataFieldObservation.Present(entries.single())
            }
        }
        for ((numberField, totalField) in listOf(
            MetadataField.TRACK_NUMBER to MetadataField.TRACK_TOTAL,
            MetadataField.DISC_NUMBER to MetadataField.DISC_TOTAL)) {
            val current = observations.getValue(numberField)
            if (current is MetadataFieldObservation.Present) {
                val item = current.values.singleOrNull()
                val pieces = item?.split('/', limit = 3)
                val first = pieces?.firstOrNull()?.toIntOrNull()
                val total = pieces?.getOrNull(1)?.toIntOrNull()
                if (pieces == null || pieces.size > 2 || first == null || first !in 1..9999 ||
                    (pieces.size == 2 && (total == null || total !in 1..9999))) {
                    val bad = MetadataFieldObservation.Unverifiable(MetadataObservationIssue.INVALID_FIELD)
                    observations[numberField] = bad; observations[totalField] = bad
                } else {
                    observations[numberField] = MetadataFieldObservation.Present(listOf(first.toString()))
                    observations[totalField] = if (total != null)
                        MetadataFieldObservation.Present(listOf(total.toString()))
                    else MetadataFieldObservation.VerifiedAbsent
                }
            } else if (current !is MetadataFieldObservation.VerifiedAbsent) {
                observations[totalField] = current
            }
        }
        // No silent interpretation of numeric legacy TCON codes.
        val genre = observations[MetadataField.GENRE]
        if (genre is MetadataFieldObservation.Present && genre.values.any {
            Regex("\\(\\d+\\)").containsMatchIn(it)
        }) observations[MetadataField.GENRE] = MetadataFieldObservation.Unverifiable(
            MetadataObservationIssue.INVALID_FIELD)
        val date = observations[MetadataField.RELEASE_DATE]
        if (supplementalDateSeen)
            observations[MetadataField.RELEASE_DATE] = MetadataFieldObservation.Unverifiable(
                MetadataObservationIssue.UNSUPPORTED_VERSION)
        else if (date is MetadataFieldObservation.Present && date.values.size != 1)
            observations[MetadataField.RELEASE_DATE] = MetadataFieldObservation.Conflicted(
                MetadataObservationIssue.DUPLICATE_FIELD)
        return MetadataFieldReadReport(format, MetadataReadFieldCatalog.PARSER_VERSION, observations)
    }

    private val flacKeys = mapOf(
        "TITLE" to MetadataField.TITLE, "ARTIST" to MetadataField.ARTIST,
        "ALBUM" to MetadataField.ALBUM,
        "ALBUMARTIST" to MetadataField.ALBUM_ARTIST,
        "ALBUM ARTIST" to MetadataField.ALBUM_ARTIST,
        "ALBUM_ARTIST" to MetadataField.ALBUM_ARTIST,
        "GENRE" to MetadataField.GENRE,
        "COMPOSER" to MetadataField.COMPOSER,
        "TRACKNUMBER" to MetadataField.TRACK_NUMBER,
        "TRACKTOTAL" to MetadataField.TRACK_TOTAL,
        "TOTALTRACKS" to MetadataField.TRACK_TOTAL,
        "DISCNUMBER" to MetadataField.DISC_NUMBER,
        "DISCTOTAL" to MetadataField.DISC_TOTAL,
        "TOTALDISCS" to MetadataField.DISC_TOTAL,
        "DATE" to MetadataField.RELEASE_DATE,
        "YEAR" to MetadataField.RELEASE_DATE,
        "TRACK" to MetadataField.TRACK_NUMBER,
        "DISC" to MetadataField.DISC_NUMBER,
        "SUBTITLE" to MetadataField.SUBTITLE,
        "ORIGINALDATE" to MetadataField.ORIGINAL_RELEASE_DATE,
        "ORIGINALYEAR" to MetadataField.ORIGINAL_RELEASE_DATE,
        "LABEL" to MetadataField.LABEL,
        "ORGANIZATION" to MetadataField.LABEL,
        "CATALOGNUMBER" to MetadataField.CATALOG_NUMBER,
        "ISRC" to MetadataField.ISRC,
        "BPM" to MetadataField.BPM,
        "INITIALKEY" to MetadataField.MUSICAL_KEY,
        "ALBUMSUBTITLE" to MetadataField.ALBUM_SUBTITLE,
        "ORIGINALARTIST" to MetadataField.ORIGINAL_ARTIST,
        "ORCHESTRA" to MetadataField.ORCHESTRA,
        "ENSEMBLE" to MetadataField.ENSEMBLE,
        "SOLOIST" to MetadataField.SOLOIST,
        "INSTRUMENT" to MetadataField.INSTRUMENT,
        "MASTERINGENGINEER" to MetadataField.MASTERING_ENGINEER,
        "EXECUTIVEPRODUCER" to MetadataField.EXECUTIVE_PRODUCER,
        "WORKNUMBER" to MetadataField.WORK_NUMBER,
        "OPUS" to MetadataField.OPUS_NUMBER,
        "MOVEMENTNUMBER" to MetadataField.MOVEMENT_NUMBER,
        "MOVEMENTTOTAL" to MetadataField.MOVEMENT_TOTAL,
        "RECORDINGDATE" to MetadataField.RECORDING_DATE,
        "EDITION" to MetadataField.EDITION,
        "RELEASETYPE" to MetadataField.RELEASE_TYPE,
        "RELEASESTATUS" to MetadataField.RELEASE_STATUS,
        "MEDIA" to MetadataField.MEDIA_FORMAT,
        "COMPILATION" to MetadataField.COMPILATION,
        "SCRIPT" to MetadataField.SCRIPT,
        "WORK" to MetadataField.WORK,
        "MOVEMENT" to MetadataField.MOVEMENT,
        "ARRANGER" to MetadataField.ARRANGER,
        "LYRICIST" to MetadataField.LYRICIST,
        "CONDUCTOR" to MetadataField.CONDUCTOR,
        "PERFORMER" to MetadataField.PERFORMER,
        "PRODUCER" to MetadataField.PRODUCER,
        "COMMENT" to MetadataField.COMMENT,
        "COPYRIGHT" to MetadataField.COPYRIGHT,
        "LANGUAGE" to MetadataField.LANGUAGE,
        "PUBLISHER" to MetadataField.PUBLISHER,
        "GROUPING" to MetadataField.GROUPING,
        "MOOD" to MetadataField.MOOD,
        "LYRICS" to MetadataField.LYRICS,
        "UNSYNCEDLYRICS" to MetadataField.LYRICS,
    ) + MetadataMusicBrainzTagMap.flacKeys.flatMap { (field, keys) -> keys.map { it to field } }.toMap()
    private val multivalued = setOf(MetadataField.ARTIST, MetadataField.ALBUM_ARTIST,
        MetadataField.GENRE, MetadataField.COMPOSER, MetadataField.ORCHESTRA,
        MetadataField.ENSEMBLE, MetadataField.SOLOIST, MetadataField.INSTRUMENT,
        MetadataField.ORIGINAL_ARTIST, MetadataField.LYRICIST, MetadataField.ARRANGER,
        MetadataField.PERFORMER, MetadataField.CONDUCTOR, MetadataField.PRODUCER,
        MetadataField.MUSICBRAINZ_ARTIST_ID, MetadataField.MUSICBRAINZ_LABEL_ID)

    private fun flac(source: Source): MetadataFieldReadReport {
        val format = MetadataPhysicalContainer.FLAC
        if (source.length < 42) return unavailable(MetadataObservationIssue.TRUNCATED, format)
        var at = 4L; var blocks = 0; var commentOffset = -1L; var commentSize = 0
        var end = false
        while (!end) {
            if (++blocks > MAX_BLOCKS || at > MAX_FLAC_META)
                return unavailable(MetadataObservationIssue.RESOURCE_LIMIT, format)
            val h = source.read(at, 4)
                ?: return unavailable(MetadataObservationIssue.TRUNCATED, format)
            val type = u(h[0]) and 127
            end = u(h[0]) and 128 != 0
            val size = (u(h[1]) shl 16) or (u(h[2]) shl 8) or u(h[3])
            if (at + 4 + size > source.length) return unavailable(MetadataObservationIssue.TRUNCATED, format)
            if (blocks == 1) {
                if (type != 0 || size != 34)
                    return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
                val info = source.read(at + 4, 34)
                    ?: return unavailable(MetadataObservationIssue.TRUNCATED, format)
                val minBlock = (u(info[0]) shl 8) or u(info[1])
                val maxBlock = (u(info[2]) shl 8) or u(info[3])
                val sampleRate = (u(info[10]) shl 12) or (u(info[11]) shl 4) or
                    (u(info[12]) ushr 4)
                if (minBlock < 16 || maxBlock < minBlock || sampleRate == 0)
                    return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
            }
            if (type == 4) {
                if (commentOffset >= 0) return unavailable(MetadataObservationIssue.DUPLICATE_FIELD, format)
                if (size > MAX_COMMENT) return unavailable(MetadataObservationIssue.RESOURCE_LIMIT, format)
                commentOffset = at + 4; commentSize = size
            }
            at += 4 + size
        }
        if (at > MAX_FLAC_META) return unavailable(MetadataObservationIssue.RESOURCE_LIMIT, format)
        if (commentOffset < 0) return MetadataFieldReadReport(format,
            MetadataReadFieldCatalog.PARSER_VERSION, catalog.associateWith { field ->
                if (field in flacKeys.values) MetadataFieldObservation.VerifiedAbsent
                else MetadataFieldObservation.Unverifiable(MetadataObservationIssue.FIELD_NOT_SUPPORTED) })
        val comments = source.read(commentOffset, commentSize)
            ?: return unavailable(MetadataObservationIssue.TRUNCATED, format)
        var p = 0
        fun next(): ByteArray? {
            if (p + 4 > comments.size) return null
            val size = le32(comments, p); p += 4
            if (size > MAX_COMMENT || size > comments.size - p) return null
            val item = comments.copyOfRange(p, p + size.toInt())
            p += size.toInt(); return item
        }
        // Vendor is a length-prefixed opaque UTF-8-ish string. Never use it as a tag.
        next() ?: return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
        if (p + 4 > comments.size) return unavailable(MetadataObservationIssue.TRUNCATED, format)
        val count = le32(comments, p); p += 4
        if (count > 4096) return unavailable(MetadataObservationIssue.RESOURCE_LIMIT, format)
        val values = mutableMapOf<MetadataField, MutableList<Pair<String,String>>>()
        val invalid = mutableSetOf<MetadataField>()
        for (i in 0 until count.toInt()) {
            val raw = next() ?: return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
            val equals = raw.indexOf('='.code.toByte())
            if (equals <= 0) return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
            val keyBytes = raw.copyOfRange(0, equals)
            if (keyBytes.any { u(it) !in 0x20..0x7d || it == '='.code.toByte() })
                return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
            val key = String(keyBytes, StandardCharsets.US_ASCII).uppercase(Locale.ROOT)
            val field = flacKeys[key] ?: continue
            val value = decoder(raw.copyOfRange(equals+1, raw.size), StandardCharsets.UTF_8)
            if (value == null || clean(value) == null) invalid.add(field)
            else values.getOrPut(field) { mutableListOf() }.add(key to value)
        }
        if (p != comments.size) return unavailable(MetadataObservationIssue.MALFORMED_STRUCTURE, format)
        val observations = linkedMapOf<MetadataField, MetadataFieldObservation>()
        for (field in catalog) {
            val entries = values[field].orEmpty()
            val aliases = entries.map { it.first }.distinct()
            val rawValues = entries.map { it.second }
            observations[field] = when {
                field !in flacKeys.values ->
                    MetadataFieldObservation.Unverifiable(MetadataObservationIssue.FIELD_NOT_SUPPORTED)
                field in invalid -> MetadataFieldObservation.Unverifiable(MetadataObservationIssue.INVALID_FIELD)
                aliases.size > 1 -> MetadataFieldObservation.Conflicted(MetadataObservationIssue.AMBIGUOUS_ALIAS)
                field in MetadataMusicBrainzTagMap.flacKeys &&
                    !MetadataMusicBrainzTagMap.valid(field, rawValues) ->
                    MetadataFieldObservation.Unverifiable(MetadataObservationIssue.INVALID_FIELD)
                entries.isEmpty() -> MetadataFieldObservation.VerifiedAbsent
                field !in multivalued && entries.size > 1 ->
                    MetadataFieldObservation.Conflicted(MetadataObservationIssue.DUPLICATE_FIELD)
                else -> MetadataFieldObservation.Present(rawValues)
            }
        }
        // A numbered fraction must not silently replace a distinct explicit total.
        for ((num, total) in listOf(
            MetadataField.TRACK_NUMBER to MetadataField.TRACK_TOTAL,
            MetadataField.DISC_NUMBER to MetadataField.DISC_TOTAL)) {
            val value = observations[num]
            if (value is MetadataFieldObservation.Present) {
                val text = value.values.singleOrNull()
                val parts = text?.split('/', limit = 3)
                val n = parts?.getOrNull(0)?.toIntOrNull()
                val embeddedTotal = parts?.getOrNull(1)?.toIntOrNull()
                if (parts == null || parts.size > 2 || n == null || n !in 1..9999 ||
                    (parts.size == 2 && (embeddedTotal == null || embeddedTotal !in 1..9999))) {
                    val bad = MetadataFieldObservation.Unverifiable(MetadataObservationIssue.INVALID_FIELD)
                    observations[num] = bad; observations[total] = bad
                } else {
                    observations[num] = MetadataFieldObservation.Present(listOf(n.toString()))
                    val separate = observations[total]
                    if (embeddedTotal != null) {
                        if (separate is MetadataFieldObservation.Present &&
                            separate.values.singleOrNull()?.toIntOrNull() != embeddedTotal) {
                            observations[total] = MetadataFieldObservation.Conflicted(
                                MetadataObservationIssue.AMBIGUOUS_ALIAS)
                        } else if (separate == MetadataFieldObservation.VerifiedAbsent) {
                            observations[total] = MetadataFieldObservation.Present(listOf(embeddedTotal.toString()))
                        }
                    }
                }
            }
            val currentTotal = observations[total]
            if (currentTotal is MetadataFieldObservation.Present &&
                (currentTotal.values.singleOrNull()?.toIntOrNull() ?: 0) !in 1..9999)
                observations[total] = MetadataFieldObservation.Unverifiable(MetadataObservationIssue.INVALID_FIELD)
        }
        return MetadataFieldReadReport(format, MetadataReadFieldCatalog.PARSER_VERSION, observations)
    }
}
