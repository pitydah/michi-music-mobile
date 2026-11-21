package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * ME-10Q: bounded, seekable, read-only inspection of legacy RIFF INFO / AIFF
 * text chunks. This is POSITIVE evidence only: a missing INFO/NAME chunk is
 * NOT proof of absent ID3-in-RIFF or other legacy/vendor metadata. Unknown
 * binary chunks are skipped by offset without reading audio into memory.
 * Non-ASCII legacy strings are left unverified instead of guessed codepages.
 */
object MetadataRiffAiffColdReader {
    private const val MAX_TEXT = 64 * 1024
    private const val MAX_CHUNKS = 4096
    private const val MAX_FILE = 1024L * 1024 * 1024
    private class Bad(val issue: MetadataObservationIssue) : Exception()
    private fun u(b: Byte) = b.toInt() and 255
    private fun le32(b: ByteArray, p: Int): Long = u(b[p]).toLong() or
        (u(b[p+1]).toLong() shl 8) or (u(b[p+2]).toLong() shl 16) or (u(b[p+3]).toLong() shl 24)
    private fun be32(b: ByteArray, p: Int): Long = (u(b[p]).toLong() shl 24) or
        (u(b[p+1]).toLong() shl 16) or (u(b[p+2]).toLong() shl 8) or u(b[p+3]).toLong()
    private fun take(channel: FileChannel, offset: Long, n: Int): ByteArray {
        if (n < 0 || n > MAX_TEXT || offset < 0 || offset > channel.size() ||
            n.toLong() > channel.size() - offset) throw Bad(MetadataObservationIssue.TRUNCATED)
        val buffer=ByteBuffer.allocate(n)
        var pos=offset
        while (buffer.hasRemaining()) {
            val count=channel.read(buffer,pos)
            if (count<=0) throw Bad(MetadataObservationIssue.TRUNCATED)
            pos+=count
        }
        return buffer.array()
    }
    private fun id(raw: ByteArray, at: Int): String = String(raw,at,4,StandardCharsets.US_ASCII)
    private fun clean(raw: ByteArray): String? {
        if (raw.size > MAX_TEXT || raw.any { u(it)>0x7e }) return null
        val value=String(raw,StandardCharsets.US_ASCII).trimEnd('\u0000')
        return value.takeIf { it.isNotBlank() && value.length<=MAX_TEXT &&
            it.all { ch -> ch.code>=0x20 || ch=='\n' || ch=='\t' } }
    }
    private val wav = mapOf(
        "INAM" to MetadataField.TITLE, "IART" to MetadataField.ARTIST,
        "IPRD" to MetadataField.ALBUM, "IGNR" to MetadataField.GENRE,
        "ICMT" to MetadataField.COMMENT, "ICRD" to MetadataField.RELEASE_DATE,
        "IENG" to MetadataField.ENGINEER,
    )
    private val aiff = mapOf(
        "NAME" to MetadataField.TITLE, "AUTH" to MetadataField.ARTIST,
        "ANNO" to MetadataField.COMMENT, "(c) " to MetadataField.COPYRIGHT,
    )
    fun observe(channel: FileChannel): MetadataFieldReadReport {
        var format=MetadataPhysicalContainer.UNKNOWN
        return try {
            if (channel.size() !in 12..MAX_FILE) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
            val h=take(channel,0,12)
            val riff = id(h,0)=="RIFF" && id(h,8)=="WAVE"
            val form = id(h,0)=="FORM" && id(h,8) in setOf("AIFF","AIFC")
            if (!riff && !form) throw Bad(MetadataObservationIssue.UNSUPPORTED_CONTAINER)
            format=if (riff) MetadataPhysicalContainer.WAV else MetadataPhysicalContainer.AIFF
            val length=if (riff) le32(h,4) else be32(h,4)
            if (length+8 > channel.size() || length<4) throw Bad(MetadataObservationIssue.TRUNCATED)
            val boundary=length+8
            var at=12L
            var count=0
            val found=mutableMapOf<MetadataField, MutableList<String>>()
            val conflicts=mutableSetOf<MetadataField>()
            fun append(field: MetadataField, raw: ByteArray) {
                val text=clean(raw)
                if (text==null) { conflicts+=field; return }
                found.getOrPut(field){ mutableListOf() }+=text
            }
            while (at+8<=boundary) {
                if (++count>MAX_CHUNKS) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
                val header=take(channel,at,8)
                val name=id(header,0)
                val size=if (riff) le32(header,4) else be32(header,4)
                val next=at+8+size+(size and 1L)
                if (next>boundary || next<=at) throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
                if (riff && name=="LIST" && size>=4) {
                    val listType=id(take(channel,at+8,4),0)
                    if (listType=="INFO") {
                        var nested=at+12
                        val end=at+8+size
                        while (nested+8<=end) {
                            if (++count>MAX_CHUNKS) throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
                            val sh=take(channel,nested,8)
                            val sn=id(sh,0)
                            val slen=le32(sh,4)
                            val stop=nested+8+slen+(slen and 1L)
                            if (stop>end || stop<=nested)
                                throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
                            val field=wav[sn]
                            if (field!=null) {
                                if (slen>MAX_TEXT) conflicts+=field
                                else append(field,take(channel,nested+8,slen.toInt()))
                            }
                            nested=stop
                        }
                        if (nested!=end) throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
                    }
                } else if (form) {
                    val field=aiff[name]
                    if (field!=null) {
                        if (size>MAX_TEXT) conflicts+=field
                        else append(field,take(channel,at+8,size.toInt()))
                    }
                }
                at=next
            }
            if (at!=boundary) throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
            MetadataFieldReadReport(format,MetadataReadFieldCatalog.PARSER_VERSION,
                MetadataReadFieldCatalog.primary.associateWith { field ->
                    val values=found[field].orEmpty()
                    when {
                        field in conflicts || (!field.multiple && values.size>1) ->
                            MetadataFieldObservation.Conflicted(MetadataObservationIssue.AMBIGUOUS_ALIAS)
                        values.isNotEmpty() -> MetadataFieldObservation.Present(values.toList())
                        else -> MetadataFieldObservation.Unverifiable(
                            MetadataObservationIssue.FIELD_NOT_SUPPORTED)
                    }
                })
        } catch (bad: Bad) { MetadataReadFieldCatalog.unavailable(bad.issue,format) }
          catch (_: Exception) { MetadataReadFieldCatalog.unavailable(
              MetadataObservationIssue.MALFORMED_STRUCTURE,format) }
    }
}
