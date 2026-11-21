package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * ME-11H: conservative, PRIVATE-only WAV RIFF/INFO transformer.
 * It edits an existing single-valued ASCII INFO entry, rebuilds RIFF/LIST sizes,
 * and preserves every other top-level chunk byte-for-byte. No SAF/MediaStore
 * write, renaming, Room publication, new INFO insertion or physical grant.
 * RF64, RIFX, embedded ID3, multiple INFO lists, malformed padding and
 * unsupported encodings are refused instead of silently reserialized.
 */
object MetadataRiffStageRewriter {
    enum class Code { INVALID_REVIEW, UNSAFE_PRIVATE_SOURCE, SOURCE_CHANGED,
        UNSUPPORTED_CONTAINER, UNSUPPORTED_LAYOUT, DUPLICATE_INFO, MISSING_FIELD,
        UNVERIFIED_FIELD, INVALID_VALUE, RESOURCE_LIMIT, READBACK_FAILED }
    class Refused(val code: Code) : IOException(code.name)
    data class Result(val originalBytes: Long, val candidateBytes: Long,
        val changedFields: Set<MetadataField>, val canCommitToSource: Boolean = false)

    private const val MAX_FILE = 1024L * 1024 * 1024
    private const val MAX_INFO = 2 * 1024 * 1024
    private const val MAX_CHUNKS = 4096
    private val writable = mapOf("INAM" to MetadataField.TITLE,
        "IART" to MetadataField.ARTIST, "IPRD" to MetadataField.ALBUM,
        "IGNR" to MetadataField.GENRE, "ICMT" to MetadataField.COMMENT,
        "ICRD" to MetadataField.RELEASE_DATE, "IENG" to MetadataField.ENGINEER)
    val supportedFields: Set<MetadataField> = writable.values.toSet()
    private fun n(b: Byte) = b.toInt() and 255
    private fun number(raw: ByteArray, at: Int) = n(raw[at]).toLong() or
        (n(raw[at+1]).toLong() shl 8) or (n(raw[at+2]).toLong() shl 16) or
        (n(raw[at+3]).toLong() shl 24)
    private fun le(n: Long) = byteArrayOf(n.toByte(), (n ushr 8).toByte(),
        (n ushr 16).toByte(), (n ushr 24).toByte())
    private fun ascii(bytes: ByteArray, pos: Int) = bytes.copyOfRange(pos,pos+4).toString(Charsets.US_ASCII)
    private fun read(ch: FileChannel, pos: Long, size: Int): ByteArray {
        if (pos < 0 || size < 0 || pos > ch.size() || size.toLong() > ch.size()-pos)
            throw Refused(Code.UNSUPPORTED_LAYOUT)
        val buffer = ByteBuffer.allocate(size)
        var at = pos
        while (buffer.hasRemaining()) {
            val count = ch.read(buffer,at)
            if (count <= 0) throw Refused(Code.SOURCE_CHANGED)
            at += count
        }
        return buffer.array()
    }
    private data class Chunk(val name: String, val pos: Long, val payload: Long) {
        val length get() = 8 + payload + (payload and 1L)
    }
    private fun layout(ch: FileChannel): List<Chunk> {
        if (ch.size() !in 44L..MAX_FILE) throw Refused(Code.RESOURCE_LIMIT)
        val h = read(ch,0,12)
        if (ascii(h,0)!="RIFF" || ascii(h,8)!="WAVE")
            throw Refused(Code.UNSUPPORTED_CONTAINER)
        if (number(h,4)+8 != ch.size()) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val chunks = ArrayList<Chunk>()
        var at = 12L
        while(at < ch.size()) {
            if(chunks.size >= MAX_CHUNKS || at+8 > ch.size())
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            val header = read(ch,at,8)
            val item = Chunk(ascii(header,0),at,number(header,4))
            if (item.length > ch.size()-at || item.length <= 8L)
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            // Shadow metadata cannot be proved equivalent to INFO.
            if(item.name in setOf("id3 ","ID3 ","bext","iXML","axml"))
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            chunks += item
            at += item.length
        }
        if (at != ch.size() || chunks.count{it.name=="fmt "}!=1 ||
            chunks.count{it.name=="data"}!=1) throw Refused(Code.UNSUPPORTED_LAYOUT)
        return chunks
    }
    private data class Sub(val name: String, val bytes: ByteArray)
    private fun info(ch: FileChannel, list: Chunk): List<Sub> {
        if (list.payload !in 4..MAX_INFO.toLong()) throw Refused(Code.UNSUPPORTED_LAYOUT)
        val body=read(ch,list.pos+8,list.payload.toInt())
        if(ascii(body,0)!="INFO")throw Refused(Code.UNSUPPORTED_LAYOUT)
        val parts=ArrayList<Sub>()
        var at=4
        while(at<body.size){
            if(parts.size>=MAX_CHUNKS || at+8>body.size)
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            val length=number(body,at+4)
            val end=at.toLong()+8+length+(length and 1L)
            if(end>body.size || end<=at+8) throw Refused(Code.UNSUPPORTED_LAYOUT)
            val name=ascii(body,at)
            // Any duplicate, even an unsupported alias, must not be coalesced.
            parts+=Sub(name,body.copyOfRange(at,end.toInt()))
            at=end.toInt()
        }
        if(at!=body.size)throw Refused(Code.UNSUPPORTED_LAYOUT)
        return parts
    }
    private fun encode(value: String): ByteArray {
        if (value.isBlank() || value.length>65534 || value.any { it.code !in 0x20..0x7e })
            throw Refused(Code.INVALID_VALUE)
        return value.toByteArray(Charsets.US_ASCII)+byteArrayOf(0)
    }
    private fun rewriteInfo(ch:FileChannel, list:Chunk, diffs: Map<MetadataField,MetadataFieldDiff>): ByteArray {
        val subs=info(ch,list)
        if(subs.groupBy { it.name }.any { (key,values) -> key in writable && values.size>1 })
            throw Refused(Code.DUPLICATE_INFO)
        if(diffs.keys.any{ field -> subs.none { writable[it.name]==field } })
            throw Refused(Code.MISSING_FIELD)
        val out=ByteArrayOutputStream()
        out.write("INFO".toByteArray(Charsets.US_ASCII))
        for(part in subs){
            val field=writable[part.name]
            val diff=field?.let { diffs[it] }
            if(diff==null){out.write(part.bytes);continue}
            if(diff.after.size!=1)throw Refused(Code.INVALID_VALUE)
            val content=encode(diff.after.single())
            out.write(part.name.toByteArray(Charsets.US_ASCII))
            out.write(le(content.size.toLong()))
            out.write(content)
            if(content.size % 2 != 0)out.write(0)
            if(out.size()>MAX_INFO) throw Refused(Code.RESOURCE_LIMIT)
        }
        return out.toByteArray().also { if(it.size>MAX_INFO)throw Refused(Code.RESOURCE_LIMIT) }
    }
    private fun copy(src: FileChannel, from: Long, count: Long, dst: FileChannel) {
        val buffer=ByteBuffer.allocate(65536)
        var read=0L
        while(read<count){
            buffer.clear();buffer.limit(minOf(count-read,buffer.capacity().toLong()).toInt())
            val n=src.read(buffer,from+read)
            if(n<=0)throw Refused(Code.SOURCE_CHANGED)
            buffer.flip()
            while(buffer.hasRemaining())if(dst.write(buffer)<=0)throw Refused(Code.SOURCE_CHANGED)
            read+=n
        }
    }
    private fun put(ch:FileChannel,bytes:ByteArray){
        val buf=ByteBuffer.wrap(bytes)
        while(buf.hasRemaining())if(ch.write(buf)<=0)throw Refused(Code.SOURCE_CHANGED)
    }
    private fun sha(ch:FileChannel):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        val buffer=ByteBuffer.allocate(65536)
        var at=0L
        while(at<ch.size()){
            buffer.clear();buffer.limit(minOf(ch.size()-at,buffer.capacity().toLong()).toInt())
            val n=ch.read(buffer,at)
            if(n<=0)throw Refused(Code.SOURCE_CHANGED)
            digest.update(buffer.array(),0,n)
            at+=n
        }
        return digest.digest()
    }
    private fun equalRange(a:FileChannel,aAt:Long,b:FileChannel,bAt:Long,length:Long):Boolean {
        val x=ByteBuffer.allocate(65536);val y=ByteBuffer.allocate(65536)
        var done=0L
        while(done<length){
            val n=minOf(length-done,65536L).toInt()
            x.clear();y.clear();x.limit(n);y.limit(n)
            if(a.read(x,aAt+done)!=n || b.read(y,bAt+done)!=n)return false
            if(!x.array().copyOfRange(0,n).contentEquals(y.array().copyOfRange(0,n)))return false
            done+=n
        }
        return true
    }
    fun stage(backup:File,stage:File,review:MetadataReview.Ready,expectedBytes:Long):Result {
        if(MetadataReviewIntegrityPolicy.inspect(review)!=MetadataReviewIntegrityPolicy.Verdict.Exact)
            throw Refused(Code.INVALID_REVIEW)
        if(expectedBytes !in 44L..MAX_FILE || backup==stage || stage.exists() ||
            Files.isSymbolicLink(backup.toPath()) || Files.isSymbolicLink(stage.toPath()) ||
            !Files.isRegularFile(backup.toPath(),LinkOption.NOFOLLOW_LINKS) ||
            backup.parentFile?.canonicalFile!=stage.parentFile?.canonicalFile)
            throw Refused(Code.UNSAFE_PRIVATE_SOURCE)
        val diffs=review.diffs.associateBy { it.field }
        if(diffs.keys.any{it !in supportedFields})throw Refused(Code.UNSUPPORTED_CONTAINER)
        var created=false
        try {
            FileChannel.open(backup.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use { src ->
                if(src.size()!=expectedBytes)throw Refused(Code.SOURCE_CHANGED)
                val originalSha=sha(src)
                val chunks=layout(src)
                val infoChunks=chunks.filter{it.name=="LIST" && it.payload>=4L &&
                    ascii(read(src,it.pos+8,4),0)=="INFO"}
                if(infoChunks.size!=1)throw Refused(Code.UNSUPPORTED_LAYOUT)
                val metadataChunk=infoChunks.single()
                val observed=MetadataRiffAiffColdReader.observe(src)
                if(observed.container!=MetadataPhysicalContainer.WAV)throw Refused(Code.UNVERIFIED_FIELD)
                for((field,diff) in diffs){
                    val present=observed.observation(field) as? MetadataFieldObservation.Present
                        ?:throw Refused(Code.UNVERIFIED_FIELD)
                    if(present.values!=diff.before || diff.before.size!=1)
                        throw Refused(Code.SOURCE_CHANGED)
                }
                val data=rewriteInfo(src,metadataChunk,diffs)
                val newChunkBytes=8L+data.size+(data.size and 1)
                val newSize=src.size()-metadataChunk.length+newChunkBytes
                if(newSize !in 44L..MAX_FILE || newSize-8>0xffffffffL)
                    throw Refused(Code.RESOURCE_LIMIT)
                FileChannel.open(stage.toPath(),StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { out ->
                    created=true
                    put(out,"RIFF".toByteArray(Charsets.US_ASCII)+le(newSize-8)+"WAVE".toByteArray(Charsets.US_ASCII))
                    for(chunk in chunks){
                        if(chunk==metadataChunk){
                            put(out,"LIST".toByteArray(Charsets.US_ASCII)+le(data.size.toLong()))
                            put(out,data)
                            if(data.size%2!=0)put(out,byteArrayOf(0))
                        } else copy(src,chunk.pos,chunk.length,out)
                    }
                    out.force(true)
                }
                if(src.size()!=expectedBytes || !sha(src).contentEquals(originalSha))
                    throw Refused(Code.SOURCE_CHANGED)
                FileChannel.open(stage.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use { out ->
                    if(out.size()!=newSize)throw Refused(Code.READBACK_FAILED)
                    val rebuilt=layout(out)
                    if(rebuilt.size!=chunks.size)throw Refused(Code.READBACK_FAILED)
                    for(i in chunks.indices){
                        val old=chunks[i];val fresh=rebuilt[i]
                        if(old.name!=fresh.name)throw Refused(Code.READBACK_FAILED)
                        if(old!=metadataChunk && (old.length!=fresh.length ||
                            !equalRange(src,old.pos,out,fresh.pos,old.length)))
                            throw Refused(Code.READBACK_FAILED)
                    }
                    val after=MetadataRiffAiffColdReader.observe(out)
                    if(after.container!=MetadataPhysicalContainer.WAV)
                        throw Refused(Code.READBACK_FAILED)
                    for((field,diff) in diffs)if(
                        (after.observation(field) as? MetadataFieldObservation.Present)?.values!=diff.after)
                        throw Refused(Code.READBACK_FAILED)
                    for(field in MetadataField.entries)if(field !in diffs &&
                        observed.observation(field)!=after.observation(field))
                        throw Refused(Code.READBACK_FAILED)
                    return Result(expectedBytes,newSize,diffs.keys)
                }
            }
        }catch(error:Exception){if(created)stage.delete();throw error}
    }
}
