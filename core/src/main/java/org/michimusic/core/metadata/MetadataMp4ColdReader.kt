package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * ME-10Q read-only iTunes/MP4 atom inspection. Finds late `moov` via seekable
 * box traversal, recognizes a conservative subset of `ilst/data` atoms and
 * returns PRESENT values only. Unobserved atoms are not proof of absence:
 * freeform metadata, custom namespaces, fragmented albums and untested brands
 * remain read-only/unverifiable. Never opens MediaStore/SAF for writing.
 */
object MetadataMp4ColdReader {
    private const val MAX_FILE = 1024L * 1024 * 1024
    private const val MAX_ATOMS = 8192
    private const val MAX_TEXT = 65536
    private class Bad(val issue: MetadataObservationIssue) : Exception()
    private fun u(b: Byte): Int = b.toInt() and 255
    private fun be16(b: ByteArray, at: Int) = (u(b[at]) shl 8) or u(b[at + 1])
    private fun be32(b: ByteArray, at: Int): Long = (u(b[at]).toLong() shl 24) or
        (u(b[at + 1]).toLong() shl 16) or (u(b[at + 2]).toLong() shl 8) or u(b[at + 3]).toLong()
    private fun be64(b: ByteArray, at: Int): Long {
        val hi = be32(b, at); val lo = be32(b, at + 4)
        if (hi > 0x7fffffffL) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
        return (hi shl 32) or lo
    }
    private fun type(b: ByteArray, at: Int) = String(b, at, 4, StandardCharsets.ISO_8859_1)
    private fun data(channel: FileChannel, offset: Long, n: Int): ByteArray {
        if (n !in 0..MAX_TEXT || offset < 0 || offset > channel.size() ||
            n.toLong() > channel.size() - offset) throw Bad(MetadataObservationIssue.TRUNCATED)
        val buffer = ByteBuffer.allocate(n)
        var at = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, at)
            if (count <= 0) throw Bad(MetadataObservationIssue.TRUNCATED)
            at += count
        }
        return buffer.array()
    }
    private data class Atom(val name: String, val start: Long, val payload: Long, val end: Long)
    private fun atom(channel: FileChannel, at: Long, until: Long): Atom {
        if (at > until - 8) throw Bad(MetadataObservationIssue.TRUNCATED)
        val h = data(channel, at, 8)
        val basicSize = be32(h, 0)
        val extended = basicSize == 1L
        val headerBytes = if (extended) 16 else 8
        if (at > until - headerBytes) throw Bad(MetadataObservationIssue.TRUNCATED)
        val length = when {
            extended -> be64(data(channel, at + 8, 8), 0)
            basicSize == 0L -> until - at
            else -> basicSize
        }
        if (length < headerBytes || length > until - at)
            throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
        return Atom(type(h,4),at,at + headerBytes,at + length)
    }
    private fun textValue(payload: ByteArray, dataType: Int): String? {
        val charset = when (dataType) {
            1 -> StandardCharsets.UTF_8
            2 -> StandardCharsets.UTF_16BE
            else -> return null
        }
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString().takeIf {
                    it.isNotBlank() && '\u0000' !in it && '\ufffd' !in it
                }
        } catch (_: CharacterCodingException) { null }
    }
    private val names = mapOf(
        "©nam" to MetadataField.TITLE,
        "©ART" to MetadataField.ARTIST,
        "aART" to MetadataField.ALBUM_ARTIST,
        "©alb" to MetadataField.ALBUM,
        "©gen" to MetadataField.GENRE,
        "©wrt" to MetadataField.COMPOSER,
        "©day" to MetadataField.RELEASE_DATE,
        "©cmt" to MetadataField.COMMENT,
        "©lyr" to MetadataField.LYRICS,
        "©too" to MetadataField.MEDIA_FORMAT,
        "©grp" to MetadataField.GROUPING,
    )
    fun observe(channel: FileChannel): MetadataFieldReadReport {
        val container = MetadataPhysicalContainer.MP4_M4A
        return try {
            if (channel.size() !in 24..MAX_FILE) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
            val counts = IntArray(1)
            val output = mutableMapOf<MetadataField, MutableList<String>>()
            val badFields = mutableSetOf<MetadataField>()
            var seenMeta = false
            var seenIlst = false
            fun visit(start: Long, end: Long, level: Int, inMeta: Boolean = false) {
                if (level > 7) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
                var at = start
                while (at < end) {
                    if (++counts[0] > MAX_ATOMS) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
                    val box = atom(channel,at,end)
                    when {
                        box.name == "moov" && level == 0 -> visit(box.payload,box.end,level+1)
                        box.name == "udta" && level > 0 -> visit(box.payload,box.end,level+1)
                        box.name == "meta" && level > 0 -> {
                            seenMeta = true
                            if (box.payload + 4 > box.end) throw Bad(MetadataObservationIssue.TRUNCATED)
                            visit(box.payload+4,box.end,level+1,true)
                        }
                        box.name == "ilst" && inMeta -> {
                            if (seenIlst) throw Bad(MetadataObservationIssue.DUPLICATE_FIELD)
                            seenIlst = true
                            var itemAt = box.payload
                            while (itemAt < box.end) {
                                if (++counts[0] > MAX_ATOMS) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
                                val item = atom(channel,itemAt,box.end)
                                val field = names[item.name]
                                if (field != null) {
                                    var v = item.payload
                                    while (v < item.end) {
                                        if (++counts[0] > MAX_ATOMS) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
                                        val dataBox = atom(channel,v,item.end)
                                        if (dataBox.name == "data") {
                                            val bytes = dataBox.end - dataBox.payload
                                            if (bytes < 8 || bytes > MAX_TEXT + 8) {
                                                badFields += field
                                            } else {
                                                val header = data(channel,dataBox.payload,8)
                                                val dataType = (u(header[1]) shl 16) or
                                                    (u(header[2]) shl 8) or u(header[3])
                                                val body=data(channel,dataBox.payload+8,(bytes-8).toInt())
                                                val value=textValue(body,dataType)
                                                if (value == null || value.length > field.maxChars)
                                                    badFields += field
                                                else output.getOrPut(field){mutableListOf()} += value
                                            }
                                        }
                                        v=dataBox.end
                                    }
                                }
                                itemAt=item.end
                            }
                        }
                    }
                    at=box.end
                }
            }
            visit(0,channel.size(),0)
            if (!seenMeta || !seenIlst)
                return MetadataReadFieldCatalog.unavailable(
                    MetadataObservationIssue.FIELD_NOT_SUPPORTED,container)
            val readings = MetadataReadFieldCatalog.primary.associateWith { field ->
                val values=output[field].orEmpty()
                when {
                    field in badFields || (!field.multiple && values.size > 1) ->
                        MetadataFieldObservation.Conflicted(MetadataObservationIssue.AMBIGUOUS_ALIAS)
                    values.isNotEmpty() -> MetadataFieldObservation.Present(values.toList())
                    else -> MetadataFieldObservation.Unverifiable(
                        MetadataObservationIssue.FIELD_NOT_SUPPORTED)
                }
            }
            MetadataFieldReadReport(container,MetadataReadFieldCatalog.PARSER_VERSION,readings)
        } catch (bad: Bad) { MetadataReadFieldCatalog.unavailable(bad.issue,container) }
          catch (_: Exception) { MetadataReadFieldCatalog.unavailable(
              MetadataObservationIssue.MALFORMED_STRUCTURE,container) }
    }
}
