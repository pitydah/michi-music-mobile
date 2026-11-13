package org.michimusic.data.repository

import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Bounded, read-only extraction. Values retain original spelling; unknown fields are absent. */
internal data class EmbeddedTagFields(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genres: List<String> = emptyList(),
    val composer: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val date: String? = null,
) {
    val genre: String? get() = genres.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/** Never guesses genres from filename, directory or artwork. No external I/O. */
internal object EmbeddedTagFieldsReader {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private fun u(v: Byte) = v.toInt() and 255
    private fun be(b: ByteArray, p: Int) = (u(b[p]) shl 24) or (u(b[p+1]) shl 16) or (u(b[p+2]) shl 8) or u(b[p+3])
    private fun le(b: ByteArray, p: Int) = u(b[p]) or (u(b[p+1]) shl 8) or (u(b[p+2]) shl 16) or (u(b[p+3]) shl 24)
    private fun sync(b: ByteArray, p: Int): Int? {
        if (p + 4 > b.size || (0..3).any { u(b[p+it]) >= 128 }) return null
        return (0..3).fold(0) { v, i -> (v shl 7) or u(b[p+i]) }
    }
    private fun valid(value: String): String? = value.trim('\u0000',' ','\r','\n','\t').takeIf {
        it.isNotBlank() && it.length <= 2048 && '\uFFFD' !in it && it.none { ch -> ch.code < 32 }
    }
    private fun numberPair(value: String?): Pair<Int?,Int?> {
        val parts = value?.trim()?.split('/', limit=2) ?: return null to null
        fun parse(s: String?) = s?.trim()?.toIntOrNull()?.takeIf { it in 1..9999 }
        return parse(parts.getOrNull(0)) to parse(parts.getOrNull(1))
    }
    internal fun fromTags(tags: Map<String,List<String>>): EmbeddedTagFields {
        fun first(vararg keys: String): String? = keys.asSequence().flatMap { tags[it].orEmpty().asSequence() }.mapNotNull(::valid).firstOrNull()
        val track = numberPair(first("TRACKNUMBER","TRACK","TRCK"))
        val disc = numberPair(first("DISCNUMBER","DISC","TPOS"))
        val genres = tags["GENRE"].orEmpty().plus(tags["TCON"].orEmpty()).mapNotNull(::valid).distinct()
        return EmbeddedTagFields(first("TITLE","TIT2"),first("ARTIST","TPE1"),first("ALBUM","TALB"),
            first("ALBUMARTIST","ALBUM ARTIST","TPE2"), genres, first("COMPOSER","TCOM"),
            track.first, numberPair(first("TRACKTOTAL","TOTALTRACKS")).first ?: track.second,
            disc.first, numberPair(first("DISCTOTAL","TOTALDISCS")).first ?: disc.second,
            first("DATE","TDRC","YEAR","TYER"))
    }
    fun read(stream: InputStream): EmbeddedTagFields? = stream.use { input ->
        val bytes = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (bytes.size() < MAX_BYTES) {
            val n = input.read(chunk,0,minOf(chunk.size,MAX_BYTES-bytes.size()))
            if (n < 0) break
            if (n == 0) { val single = input.read(); if (single < 0) break; bytes.write(single) }
            else bytes.write(chunk,0,n)
        }
        val b = bytes.toByteArray()
        val tags = when {
            b.size >= 10 && String(b,0,3,StandardCharsets.US_ASCII)=="ID3" -> id3(b)
            b.size >= 4 && String(b,0,4,StandardCharsets.US_ASCII)=="fLaC" -> flac(b)
            b.size >= 27 && String(b,0,4,StandardCharsets.US_ASCII)=="OggS" -> ogg(b)
            b.size >= 8 && String(b,4,4,StandardCharsets.ISO_8859_1)=="ftyp" -> mp4(b)
            b.size >= 12 && String(b,0,4,StandardCharsets.US_ASCII)=="RIFF" && String(b,8,4,StandardCharsets.US_ASCII)=="WAVE" -> riff(b)
            else -> null
        }
        tags?.takeIf { it.isNotEmpty() }?.let(::fromTags)
    }
    /** Reverse the ID3 sync escape FF 00 -> FF, without touching ordinary NUL text. */
    private fun decodeUnsync(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(length)
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val current = bytes[pos]
            out.write(current.toInt() and 255)
            pos++
            if (current == 0xFF.toByte() && pos < end && bytes[pos] == 0.toByte()) pos++
        }
        return out.toByteArray()
    }
    private fun id3(b: ByteArray): Map<String,List<String>>? {
        val version=u(b[3]); val flags=u(b[5])
        if (version !in 2..4 || (version == 2 && flags and 0x40 != 0)) return null
        val length=sync(b,6) ?: return null
        if (length.toLong()+10 > b.size) return null
        val end=length+10; var p=10
        // ID3v2.3 ext header size excludes the 4-byte size field;
        // ID3v2.4 synchsafe size includes it. Reject truncated/malformed blocks.
        if (flags and 0x40 != 0) {
            if (p+4 > end) return null
            val extended = if (version == 3) be(b,p).toLong().let { it+4 }
                else (sync(b,p) ?: return null).toLong()
            if (extended < (if (version == 3) 10 else 6) || extended > end-p) return null
            p += extended.toInt()
        }
        val tags=linkedMapOf<String,MutableList<String>>()
        val names=mapOf("TT2" to "TIT2","TP1" to "TPE1","TAL" to "TALB","TP2" to "TPE2","TCO" to "TCON","TCM" to "TCOM","TRK" to "TRCK","TPA" to "TPOS","TYE" to "TYER")
        val allowed=setOf("TIT2","TPE1","TALB","TPE2","TCON","TCOM","TRCK","TPOS","TDRC","TYER")
        while (p+(if(version==2) 6 else 10)<=end) {
            val header=if(version==2) 6 else 10
            val raw=String(b,p,if(version==2) 3 else 4,StandardCharsets.US_ASCII)
            if (!raw.matches(Regex(if(version==2) "[A-Z0-9]{3}" else "[A-Z0-9]{4}"))) break
            val size=when(version){2 -> (u(b[p+3]) shl 16) or (u(b[p+4]) shl 8) or u(b[p+5]);4 -> sync(b,p+4) ?: break;else -> be(b,p+4)}
            if(size<0 || p.toLong()+header+size>end) break
            val frameFlags=if(version==2) 0 else u(b[p+9])
            // Compression, encryption, grouping and DLI need dedicated decoders;
            // unsynchronisation by itself is safe to reverse on text payloads.
            val unsupported=if(version==3) frameFlags and 0xE0 != 0
                else version==4 && frameFlags and 0x4D != 0
            val key=names[raw] ?: raw
            if(key in allowed && size>1 && !unsupported) {
                val unsynchronised = (flags and 0x80 != 0) ||
                    (version == 4 && frameFlags and 0x02 != 0)
                val content = if (unsynchronised) decodeUnsync(b, p+header, size)
                    else b.copyOfRange(p+header,p+header+size)
                val charset: Charset?=when(u(content[0])){0->StandardCharsets.ISO_8859_1;1->StandardCharsets.UTF_16;2->StandardCharsets.UTF_16BE;3->StandardCharsets.UTF_8;else->null}
                if (charset != null && content.size > 1) {
                    val decoded = String(content,1,content.size-1,charset)
                    val values = if (key == "TCON") Id3GenreCodes.tconValues(decoded)
                        else listOf(decoded)
                    values.mapNotNull(::valid).forEach {
                        tags.getOrPut(key){mutableListOf()}.add(it)
                    }
                }
            }
            p+=header+size
        }
        return tags
    }
    private fun comments(b: ByteArray, start: Int, end: Int): Map<String,List<String>>? {
        var p=start
        fun entry(): String? {
            if(p+4>end) return null
            val n=le(b,p); p+=4
            if(n<0 || n>MAX_BYTES || p.toLong()+n>end) return null
            val value=String(b,p,n,StandardCharsets.UTF_8);p+=n;return value
        }
        entry() ?: return null // vendor
        if(p+4>end) return null
        val count=le(b,p);p+=4
        if(count !in 0..4096) return null
        val tags=linkedMapOf<String,MutableList<String>>()
        repeat(count) {
            val e=entry() ?: return null
            val index=e.indexOf('=')
            if(index>0) {
                val key=e.substring(0,index).uppercase(java.util.Locale.ROOT)
                valid(e.substring(index+1))?.let { tags.getOrPut(key){mutableListOf()}.add(it) }
            }
        }
        return tags
    }
    private fun flac(b: ByteArray): Map<String,List<String>>? {
        var p=4
        repeat(64) {
            if(p+4>b.size) return null
            val type=u(b[p]);val n=(u(b[p+1]) shl 16) or (u(b[p+2]) shl 8) or u(b[p+3]);p+=4
            if(p.toLong()+n>b.size) return null
            if(type and 127==4) return comments(b,p,p+n)
            p+=n
            if(type and 128!=0) return null
        }
        return null
    }
    /** Walks bounded MP4 atoms; rejects truncated/extended atoms rather than guessing offsets. */
    private fun mp4(b: ByteArray): Map<String,List<String>>? {
        val tags=linkedMapOf<String,MutableList<String>>()
        val wanted=mapOf(
            "\u00a9nam" to "TITLE", "\u00a9ART" to "ARTIST", "\u00a9alb" to "ALBUM",
            "aART" to "ALBUMARTIST", "\u00a9gen" to "GENRE", "\u00a9wrt" to "COMPOSER",
            "\u00a9day" to "DATE"
        )
        fun walk(start:Int,end:Int,depth:Int) {
            if(depth>8 || start<0 || end>b.size) return
            var p=start
            while(p.toLong()+8<=end) {
                val size=be(b,p).toLong() and 0xffffffffL
                if(size<8 || p.toLong()+size>end) return
                val next=(p.toLong()+size).toInt()
                val type=String(b,p+4,4,StandardCharsets.ISO_8859_1)
                if(type in wanted || type=="trkn" || type=="disk" || type=="gnre") {
                    var q=p+8
                    while(q.toLong()+16<=next) {
                        val n=be(b,q).toLong() and 0xffffffffL
                        if(n<16 || q.toLong()+n>next) break
                        if(String(b,q+4,4,StandardCharsets.ISO_8859_1)=="data") {
                            val payload=q+16
                            val count=n.toInt()-16
                            if (type == "gnre") {
                                if (count >= 2) {
                                    val index = u(b[payload]) * 256 + u(b[payload+1]) - 1
                                    Id3GenreCodes.nameForIndex(index)?.let {
                                        tags.getOrPut("GENRE") { mutableListOf() }.add(it)
                                    }
                                }
                            } else if(type=="trkn" || type=="disk") {
                                if(count>=6) {
                                    val number=(u(b[payload+2]) shl 8) or u(b[payload+3])
                                    val total=(u(b[payload+4]) shl 8) or u(b[payload+5])
                                    val key=if(type=="trkn") "TRACKNUMBER" else "DISCNUMBER"
                                    if(number>0) tags.getOrPut(key){mutableListOf()}.add(
                                        if(total>0) "$number/$total" else "$number")
                                }
                            } else if(count>0) {
                                val charset=if(u(b[q+11])==2) StandardCharsets.UTF_16BE else StandardCharsets.UTF_8
                                valid(String(b,payload,count,charset))?.let {
                                    tags.getOrPut(wanted.getValue(type)){mutableListOf()}.add(it)
                                }
                            }
                        }
                        q+=n.toInt()
                    }
                } else if(type=="moov" || type=="udta" || type=="meta" || type=="ilst") {
                    walk(p+8+(if(type=="meta") 4 else 0),next,depth+1)
                }
                p=next
            }
        }
        walk(0,b.size,0)
        return tags.takeIf { it.isNotEmpty() }
    }
    /** RIFF/WAVE LIST-INFO tags, with chunk padding and strict bounds. */
    private fun riff(b: ByteArray): Map<String,List<String>>? {
        val keys = mapOf("INAM" to "TITLE", "IART" to "ARTIST", "IPRD" to "ALBUM",
            "IGNR" to "GENRE", "ICRD" to "DATE", "ICMT" to "COMMENT")
        val tags=linkedMapOf<String,MutableList<String>>()
        var p=12
        while(p.toLong()+8<=b.size) {
            val length=le(b,p+4).toLong() and 0xffffffffL
            val end=p.toLong()+8+length
            if(end>b.size) break
            if(String(b,p,4,StandardCharsets.US_ASCII)=="LIST" && length>=4 &&
                String(b,p+8,4,StandardCharsets.US_ASCII)=="INFO") {
                var q=p+12
                while(q.toLong()+8<=end) {
                    val n=le(b,q+4).toLong() and 0xffffffffL
                    if(q.toLong()+8+n>end) break
                    val id=String(b,q,4,StandardCharsets.US_ASCII)
                    if(id in keys && n in 1..2048) {
                        valid(String(b,q+8,n.toInt(),StandardCharsets.ISO_8859_1))?.let {
                            tags.getOrPut(keys.getValue(id)){mutableListOf()}.add(it)
                        }
                    }
                    q=(q.toLong()+8+n+(n and 1)).toInt()
                }
            }
            p=(end+(length and 1)).toInt()
        }
        return tags.takeIf { it.isNotEmpty() }
    }
    private fun ogg(b: ByteArray): Map<String,List<String>>? {
        var p=0;var pages=0;val packet=java.io.ByteArrayOutputStream()
        while(p+27<=b.size && pages++<128) {
            if(String(b,p,4,StandardCharsets.US_ASCII)!="OggS" || u(b[p+4])!=0) return null
            val segments=u(b[p+26]);if(p+27+segments>b.size) return null
            val sizes=(0 until segments).map{u(b[p+27+it])}
            var pos=p+27+segments
            if(pos.toLong()+sizes.sum()>b.size) return null
            for(n in sizes) {
                if(packet.size()+n>MAX_BYTES) return null
                packet.write(b,pos,n);pos+=n
                if(n<255) {
                    val data=packet.toByteArray()
                    if(data.size>=7 && u(data[0])==3 && String(data,1,6,StandardCharsets.US_ASCII)=="vorbis") return comments(data,7,data.size)
                    if(data.size>=8 && String(data,0,8,StandardCharsets.US_ASCII)=="OpusTags") return comments(data,8,data.size)
                    packet.reset()
                }
            }
            p=pos
        }
        return null
    }
}
