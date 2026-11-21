package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * ME-10P. Read-only, bounded Ogg Vorbis / Opus comment reader. Requires CRC
 * correctness, a single serial, ordered pages, complete header packets and
 * a valid comment layout. It cannot create a writer capability or modify audio.
 */
object MetadataOggColdReader {
    private const val MAX_PAGES = 128
    private const val MAX_PACKET = 2 * 1024 * 1024
    private const val MAX_COMMENTS = 4096
    private class Bad(val issue: MetadataObservationIssue) : Exception()
    private fun u(b: Byte): Int = b.toInt() and 255
    private fun le32(b: ByteArray, p: Int): Long =
        u(b[p]).toLong() or (u(b[p + 1]).toLong() shl 8) or
            (u(b[p + 2]).toLong() shl 16) or (u(b[p + 3]).toLong() shl 24)
    private fun bytes(channel: FileChannel, offset: Long, n: Int): ByteArray {
        if (n < 0 || n > MAX_PACKET || offset < 0 || n.toLong() > channel.size() - offset)
            throw Bad(MetadataObservationIssue.TRUNCATED)
        val target = ByteBuffer.allocate(n)
        var at = offset
        while (target.hasRemaining()) {
            val count = channel.read(target, at)
            if (count <= 0) throw Bad(MetadataObservationIssue.TRUNCATED)
            at += count
        }
        return target.array()
    }
    private fun validCrc(page: ByteArray): Boolean {
        val expected = le32(page, 22).toInt()
        var value = 0
        page.indices.forEach { index ->
            val byte = if (index in 22..25) 0 else u(page[index])
            value = value xor (byte shl 24)
            repeat(8) { value = (value shl 1) xor if ((value and Int.MIN_VALUE) != 0)
                0x04C11DB7 else 0 }
        }
        return value == expected
    }
    private fun starts(data: ByteArray, name: ByteArray): Boolean =
        data.size >= name.size && name.indices.all { data[it] == name[it] }
    private fun strictUtf8(raw: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
    } catch (_: CharacterCodingException) { throw Bad(MetadataObservationIssue.BAD_TEXT_ENCODING) }

    private fun parse(packet: ByteArray, opus: Boolean): MetadataFieldReadReport {
        val prefix = if (opus) "OpusTags".toByteArray(StandardCharsets.US_ASCII)
            else byteArrayOf(3) + "vorbis".toByteArray(StandardCharsets.US_ASCII)
        val format = if (opus) MetadataPhysicalContainer.OGG_OPUS else MetadataPhysicalContainer.OGG_VORBIS
        if (!starts(packet, prefix)) throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
        var pos = prefix.size
        fun next(): ByteArray {
            if (pos + 4 > packet.size) throw Bad(MetadataObservationIssue.TRUNCATED)
            val n = le32(packet, pos); pos += 4
            if (n > MAX_PACKET || n > packet.size.toLong() - pos)
                throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
            return packet.copyOfRange(pos, pos + n.toInt()).also { pos += n.toInt() }
        }
        strictUtf8(next()) // vendor is authoritative bytes, not a song field
        if (pos + 4 > packet.size) throw Bad(MetadataObservationIssue.TRUNCATED)
        val count = le32(packet, pos); pos += 4
        if (count > MAX_COMMENTS) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
        val byField = mutableMapOf<MetadataField, MutableList<String>>()
        val aliases = MetadataReadFieldCatalog.primary.associateWith {
            MetadataFlacStageRewriter.preservationAliases(it)
        }.filterValues { it.isNotEmpty() }
        val owners = mutableMapOf<String, MetadataField>()
        for ((field, names) in aliases) for (name in names) {
            val old = owners.put(name, field)
            if (old != null && old != field) throw Bad(MetadataObservationIssue.AMBIGUOUS_ALIAS)
        }
        repeat(count.toInt()) {
            val comment = next()
            val separator = comment.indexOf('='.code.toByte())
            if (separator !in 1..128) throw Bad(MetadataObservationIssue.INVALID_FIELD)
            val key = comment.copyOfRange(0, separator)
            if (key.any { u(it) !in 0x20..0x7e }) throw Bad(MetadataObservationIssue.INVALID_FIELD)
            val field = owners[String(key, StandardCharsets.US_ASCII).uppercase(Locale.ROOT)]
            if (field != null) {
                val value = strictUtf8(comment.copyOfRange(separator + 1, comment.size))
                if (value.isBlank() || value.length > field.maxChars || '\u0000' in value)
                    throw Bad(MetadataObservationIssue.INVALID_FIELD)
                byField.getOrPut(field) { mutableListOf() } += value
                if (byField.getValue(field).size > 32) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
            }
        }
        if (!opus && (pos >= packet.size || u(packet[pos]) != 1 || pos + 1 != packet.size))
            throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
        // Opus tags may have trailing data per specification; no absent claims
        // for fields the alias catalog doesn't understand.
        val observations = MetadataReadFieldCatalog.primary.associateWith { field ->
            val values = byField[field].orEmpty()
            when {
                field !in aliases -> MetadataFieldObservation.Unverifiable(
                    MetadataObservationIssue.FIELD_NOT_SUPPORTED)
                !field.multiple && values.size > 1 -> MetadataFieldObservation.Conflicted(
                    MetadataObservationIssue.DUPLICATE_FIELD)
                values.isEmpty() -> MetadataFieldObservation.VerifiedAbsent
                else -> MetadataFieldObservation.Present(values.toList())
            }
        }
        return MetadataFieldReadReport(format, MetadataReadFieldCatalog.PARSER_VERSION, observations)
    }

    fun observe(channel: FileChannel): MetadataFieldReadReport {
        var format = MetadataPhysicalContainer.UNKNOWN
        return try {
            var offset = 0L
            var serial: Long? = null
            var expectedSequence = 0L
            var awaitingContinuation = false
            val packets = mutableListOf<ByteArray>()
            val collecting = java.io.ByteArrayOutputStream()
            var opus: Boolean? = null
            repeat(MAX_PAGES) { pageIndex ->
                if (offset + 27 > channel.size()) throw Bad(MetadataObservationIssue.TRUNCATED)
                val header = bytes(channel, offset, 27)
                if (!starts(header, "OggS".toByteArray(StandardCharsets.US_ASCII)) ||
                    u(header[4]) != 0 || (u(header[5]) and 0xf8) != 0)
                    throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
                val bos = (u(header[5]) and 0x02) != 0
                val continued = (u(header[5]) and 0x01) != 0
                if (pageIndex == 0 && (!bos || continued))
                    throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
                if (pageIndex > 0 && (bos || continued != awaitingContinuation))
                    throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
                val pageSerial = le32(header, 14)
                val pageSeq = le32(header, 18)
                if ((serial != null && serial != pageSerial) || pageSeq != expectedSequence)
                    throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
                serial = pageSerial
                expectedSequence++
                val segmentCount = u(header[26])
                val segmentLengths = bytes(channel, offset + 27, segmentCount)
                val payloadSize = segmentLengths.sumOf { u(it) }
                val page = bytes(channel, offset, 27 + segmentCount + payloadSize)
                if (!validCrc(page)) throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
                var cursor = 27 + segmentCount
                for (segment in segmentLengths) {
                    val n = u(segment)
                    if (collecting.size() + n > MAX_PACKET)
                        throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
                    collecting.write(page, cursor, n)
                    cursor += n
                    if (n < 255) {
                        val packet = collecting.toByteArray()
                        collecting.reset()
                        packets += packet
                        if (packets.size == 1) {
                            opus = starts(packet, "OpusHead".toByteArray(StandardCharsets.US_ASCII))
                            if (opus == true) {
                                if (packet.size < 19 || u(packet[8]) != 1 || u(packet[9]) == 0)
                                    throw Bad(MetadataObservationIssue.UNSUPPORTED_VERSION)
                                format = MetadataPhysicalContainer.OGG_OPUS
                            } else if (starts(packet, byteArrayOf(1) +
                                    "vorbis".toByteArray(StandardCharsets.US_ASCII))) {
                                if (packet.size < 30) throw Bad(MetadataObservationIssue.TRUNCATED)
                                format = MetadataPhysicalContainer.OGG_VORBIS
                            } else throw Bad(MetadataObservationIssue.UNSUPPORTED_CONTAINER)
                        }
                        if (packets.size == 2) return parse(packet, opus == true)
                    }
                }
                awaitingContinuation = collecting.size() > 0
                offset += page.size
            }
            MetadataReadFieldCatalog.unavailable(MetadataObservationIssue.RESOURCE_LIMIT, format)
        } catch (bad: Bad) { MetadataReadFieldCatalog.unavailable(bad.issue, format) }
          catch (_: Exception) { MetadataReadFieldCatalog.unavailable(
              MetadataObservationIssue.MALFORMED_STRUCTURE, format) }
    }
}
