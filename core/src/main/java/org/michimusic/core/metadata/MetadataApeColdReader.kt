package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * ME-10Q: strict, bounded, READ-ONLY APEv2 footer reader for seekable WavPack
 * and Monkey's Audio. No tag writing, no file scanning and no claim about
 * unsupported APE keys or missing/legacy footer variants.
 */
object MetadataApeColdReader {
    private const val MAX_TAG = 4 * 1024 * 1024
    private const val MAX_ITEMS = 4096
    private class Bad(val issue: MetadataObservationIssue) : Exception()
    private fun u(b: Byte) = b.toInt() and 255
    private fun le32(b: ByteArray, p: Int): Long = u(b[p]).toLong() or
        (u(b[p+1]).toLong() shl 8) or (u(b[p+2]).toLong() shl 16) or (u(b[p+3]).toLong() shl 24)
    private fun get(channel: FileChannel, at: Long, size: Int): ByteArray {
        if (size !in 0..MAX_TAG || at < 0 || at > channel.size() ||
            size.toLong() > channel.size() - at) throw Bad(MetadataObservationIssue.TRUNCATED)
        val buffer = ByteBuffer.allocate(size)
        var pos=at
        while (buffer.hasRemaining()) {
            val n=channel.read(buffer,pos)
            if (n<=0) throw Bad(MetadataObservationIssue.TRUNCATED)
            pos+=n
        }
        return buffer.array()
    }
    private fun decode(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { throw Bad(MetadataObservationIssue.BAD_TEXT_ENCODING) }

    fun observe(channel: FileChannel): MetadataFieldReadReport {
        val detected = try {
            when (String(get(channel,0,4),StandardCharsets.US_ASCII)) {
                "wvpk" -> MetadataPhysicalContainer.WAVPACK
                "MAC " -> MetadataPhysicalContainer.APE
                else -> MetadataPhysicalContainer.UNKNOWN
            }
        } catch (_: Exception) { MetadataPhysicalContainer.UNKNOWN }
        if (detected == MetadataPhysicalContainer.UNKNOWN)
            return MetadataReadFieldCatalog.unavailable(MetadataObservationIssue.UNSUPPORTED_CONTAINER)
        return try {
            val size=channel.size()
            if (size < 64 || size > 1024L*1024*1024)
                throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
            // APEv2 footer is last, except an optional ID3v1 block (not decoded).
            val id3v1 = size >= 128 && String(get(channel,size-128,3),
                StandardCharsets.US_ASCII) == "TAG"
            val footerEnd=size-if (id3v1) 128L else 0L
            val footer=get(channel,footerEnd-32,32)
            if (!String(footer,0,8,StandardCharsets.US_ASCII).equals("APETAGEX"))
                throw Bad(MetadataObservationIssue.FIELD_NOT_SUPPORTED)
            val version=le32(footer,8)
            if (version != 2000L) throw Bad(MetadataObservationIssue.UNSUPPORTED_VERSION)
            val tagBytes=le32(footer,12)
            val count=le32(footer,16)
            val flags=le32(footer,20)
            // Do not infer semantics for reserved flag combinations.
            if (tagBytes !in 32L..MAX_TAG.toLong() || count>MAX_ITEMS ||
                footerEnd-tagBytes < 4 || (flags and 0x1fffffffL)!=0L ||
                footer.copyOfRange(24,32).any { it != 0.toByte() })
                throw Bad(MetadataObservationIssue.UNSUPPORTED_FLAGS)
            val tag=get(channel,footerEnd-tagBytes,(tagBytes-32).toInt())
            val aliases = MetadataReadFieldCatalog.primary.associateWith {
                MetadataFlacStageRewriter.preservationAliases(it)
            }.filterValues { it.isNotEmpty() }
            val owners=mutableMapOf<String,MetadataField>()
            for ((field,names) in aliases) for (name in names) {
                val previous=owners.put(name,field)
                if (previous!=null && previous!=field)
                    throw Bad(MetadataObservationIssue.AMBIGUOUS_ALIAS)
            }
            val output=mutableMapOf<MetadataField, MutableList<String>>()
            var at=0
            repeat(count.toInt()) {
                if (at+8>tag.size) throw Bad(MetadataObservationIssue.TRUNCATED)
                val length=le32(tag,at); val itemFlags=le32(tag,at+4); at+=8
                var zero=at
                while (zero<tag.size && tag[zero]!=0.toByte() && zero-at<=255) zero++
                if (zero !in (at+1)..minOf(tag.size-1,at+255) ||
                    length>tag.size.toLong()-zero-1)
                    throw Bad(MetadataObservationIssue.INVALID_FIELD)
                if ((at until zero).any { u(tag[it]) !in 0x20..0x7e })
                    throw Bad(MetadataObservationIssue.INVALID_FIELD)
                val key=String(tag,at,zero-at,StandardCharsets.US_ASCII)
                at=zero+1
                val value=tag.copyOfRange(at,at+length.toInt());at+=length.toInt()
                if ((itemFlags and 0x6L) != 0L) {
                    // binary / link entries are opaque. Do not map them to tags.
                    if (owners.containsKey(key.uppercase(Locale.ROOT)))
                        throw Bad(MetadataObservationIssue.FIELD_NOT_SUPPORTED)
                    return@repeat
                }
                val field=owners[key.uppercase(Locale.ROOT)] ?: return@repeat
                // APEv2 text items are NUL-delimited. Retain exact order.
                val values=value.toList().splitOnNul()
                for (raw in values) {
                    val str=decode(raw.toByteArray())
                    if (str.isBlank() || '\u0000' in str || str.length>field.maxChars)
                        throw Bad(MetadataObservationIssue.INVALID_FIELD)
                    output.getOrPut(field){mutableListOf()} += str
                    if (output.getValue(field).size>32)
                        throw Bad(MetadataObservationIssue.RESOURCE_LIMIT)
                }
            }
            if (at!=tag.size) throw Bad(MetadataObservationIssue.MALFORMED_STRUCTURE)
            val report=MetadataReadFieldCatalog.primary.associateWith { field ->
                val values=output[field].orEmpty()
                when {
                    field !in aliases -> MetadataFieldObservation.Unverifiable(
                        MetadataObservationIssue.FIELD_NOT_SUPPORTED)
                    values.size>1 && !field.multiple -> MetadataFieldObservation.Conflicted(
                        MetadataObservationIssue.DUPLICATE_FIELD)
                    values.isEmpty() -> MetadataFieldObservation.VerifiedAbsent
                    else -> MetadataFieldObservation.Present(values.toList())
                }
            }
            MetadataFieldReadReport(detected,MetadataReadFieldCatalog.PARSER_VERSION,report)
        } catch (bad: Bad) { MetadataReadFieldCatalog.unavailable(bad.issue,detected) }
          catch (_: Exception) { MetadataReadFieldCatalog.unavailable(
              MetadataObservationIssue.MALFORMED_STRUCTURE,detected) }
    }
    private fun List<Byte>.splitOnNul(): List<List<Byte>> {
        val out=mutableListOf<List<Byte>>()
        var from=0
        for (i in indices) if (this[i]==0.toByte()) {
            out+=subList(from,i);from=i+1
        }
        out+=subList(from,size)
        return out
    }
}
