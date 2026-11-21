package org.michimusic.core.metadata

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Only an independent PRIVATE AIFF candidate; never a provider or source mutation. */
object MetadataAiffStageRewriter {
    enum class Code { INVALID_REVIEW, SOURCE_CHANGED, UNSAFE_PATH, UNSUPPORTED_FORM,
        UNSAFE_CHUNK, MISSING_FIELD, DUPLICATE_FIELD, VALUE_NOT_ASCII, UNVERIFIABLE,
        TOO_LARGE, READBACK_FAILED }
    class Refused(val code: Code): IOException(code.name)
    data class Result(val originalBytes: Long, val candidateBytes: Long,
        val changedFields: Set<MetadataField>, val canCommitToSource: Boolean = false)

    private const val MAX_FILE = 1024L * 1024 * 1024
    private const val MAX_CHUNKS = 4096
    private val keys = linkedMapOf(
        "NAME" to MetadataField.TITLE,
        "AUTH" to MetadataField.ARTIST,
        "ANNO" to MetadataField.COMMENT,
        "(c) " to MetadataField.COPYRIGHT)
    val supportedFields: Set<MetadataField> = keys.values.toSet()
    private fun u(x:Byte)=x.toInt() and 255
    private fun num(b:ByteArray, at:Int)=((u(b[at]).toLong() shl 24) or
        (u(b[at+1]).toLong() shl 16) or (u(b[at+2]).toLong() shl 8) or u(b[at+3]).toLong())
    private fun be(x:Long)=byteArrayOf((x ushr 24).toByte(),(x ushr 16).toByte(),
        (x ushr 8).toByte(),x.toByte())
    private fun read(ch:FileChannel, at:Long, len:Int):ByteArray {
        if(at<0||len<0||at>ch.size()||len.toLong()>ch.size()-at)throw Refused(Code.UNSAFE_CHUNK)
        val b=ByteBuffer.allocate(len);var pos=at
        while(b.hasRemaining()){val n=ch.read(b,pos);if(n<=0)throw Refused(Code.SOURCE_CHANGED);pos+=n}
        return b.array()
    }
    private fun name(b:ByteArray)=String(b,0,4,Charsets.US_ASCII)
    private data class Chunk(val name:String,val at:Long,val dataBytes:Long) {
        val total:Long get()=8+dataBytes+(dataBytes and 1L)
    }
    private fun parse(ch:FileChannel):List<Chunk> {
        if(ch.size() !in 42L..MAX_FILE)throw Refused(Code.TOO_LARGE)
        val h=read(ch,0,12)
        if(name(h)!="FORM"||String(h,8,4,Charsets.US_ASCII)!="AIFF"||num(h,4)+8!=ch.size())
            throw Refused(Code.UNSUPPORTED_FORM)
        val all=ArrayList<Chunk>();var at=12L
        while(at<ch.size()){
            if(all.size>=MAX_CHUNKS||at+8>ch.size())throw Refused(Code.UNSAFE_CHUNK)
            val b=read(ch,at,8)
            val c=Chunk(name(b),at,num(b,4))
            if(c.total<=8||c.total>ch.size()-at)throw Refused(Code.UNSAFE_CHUNK)
            if(c.name in setOf("ID3 ","id3 ","APPL"))throw Refused(Code.UNSAFE_CHUNK)
            all+=c;at+=c.total
        }
        if(at!=ch.size()||all.count{it.name=="COMM"}!=1||all.count{it.name=="SSND"}!=1)
            throw Refused(Code.UNSAFE_CHUNK)
        return all
    }
    private fun digest(ch:FileChannel):ByteArray {
        val md=MessageDigest.getInstance("SHA-256");val b=ByteBuffer.allocate(65536);var at=0L
        while(at<ch.size()){
            b.clear();b.limit(minOf(65536L,ch.size()-at).toInt())
            val n=ch.read(b,at);if(n<=0)throw Refused(Code.SOURCE_CHANGED)
            md.update(b.array(),0,n);at+=n
        }
        return md.digest()
    }
    private fun emit(dst:FileChannel,bytes:ByteArray){val b=ByteBuffer.wrap(bytes)
        while(b.hasRemaining())if(dst.write(b)<=0)throw Refused(Code.SOURCE_CHANGED)}
    private fun copy(src:FileChannel,at:Long,length:Long,dst:FileChannel){
        val b=ByteBuffer.allocate(65536);var done=0L
        while(done<length){
            b.clear();b.limit(minOf(65536L,length-done).toInt())
            val n=src.read(b,at+done);if(n<=0)throw Refused(Code.SOURCE_CHANGED)
            b.flip();while(b.hasRemaining())if(dst.write(b)<=0)throw Refused(Code.SOURCE_CHANGED)
            done+=n
        }
    }
    private fun same(src:FileChannel,a:Long,dst:FileChannel,b:Long,size:Long):Boolean {
        val x=ByteBuffer.allocate(65536);val y=ByteBuffer.allocate(65536);var done=0L
        while(done<size){val n=minOf(size-done,65536L).toInt()
            x.clear();x.limit(n);y.clear();y.limit(n)
            if(src.read(x,a+done)!=n||dst.read(y,b+done)!=n)return false
            if(!x.array().copyOfRange(0,n).contentEquals(y.array().copyOfRange(0,n)))return false
            done+=n
        };return true
    }
    fun stage(backup:File, stage:File, review:MetadataReview.Ready, expectedBytes:Long):Result {
        if(MetadataReviewIntegrityPolicy.inspect(review)!=MetadataReviewIntegrityPolicy.Verdict.Exact)
            throw Refused(Code.INVALID_REVIEW)
        if(expectedBytes !in 42L..MAX_FILE||backup==stage||stage.exists()||
            Files.isSymbolicLink(backup.toPath())||Files.isSymbolicLink(stage.toPath())||
            !Files.isRegularFile(backup.toPath(),LinkOption.NOFOLLOW_LINKS)||
            backup.parentFile?.canonicalFile!=stage.parentFile?.canonicalFile)
            throw Refused(Code.UNSAFE_PATH)
        val diffs=review.diffs.associateBy{it.field}
        if(diffs.isEmpty()||diffs.keys.any{it !in supportedFields})throw Refused(Code.INVALID_REVIEW)
        var made=false
        try{
            FileChannel.open(backup.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use{src->
                if(src.size()!=expectedBytes)throw Refused(Code.SOURCE_CHANGED)
                val beforeSha=digest(src)
                val chunks=parse(src)
                if(chunks.filter{it.name in keys}.groupBy{it.name}.any{it.value.size>1})
                    throw Refused(Code.DUPLICATE_FIELD)
                val before=MetadataRiffAiffColdReader.observe(src)
                if(before.container!=MetadataPhysicalContainer.AIFF)throw Refused(Code.UNVERIFIABLE)
                for((field,diff) in diffs){
                    val present=before.observation(field) as? MetadataFieldObservation.Present
                        ?:throw Refused(Code.UNVERIFIABLE)
                    if(present.values!=diff.before||diff.before.size!=1||diff.after.size!=1||
                        chunks.none{keys[it.name]==field})throw Refused(Code.MISSING_FIELD)
                    if(diff.after.single().isBlank()||diff.after.single().length>65535||
                        diff.after.single().any{it.code !in 0x20..0x7e})
                        throw Refused(Code.VALUE_NOT_ASCII)
                }
                val encoded=diffs.mapValues{it.value.after.single().toByteArray(Charsets.US_ASCII)}
                val newSize=12L+chunks.sumOf{c->
                    val field=keys[c.name];val bytes=field?.let{encoded[it]}
                    if(bytes==null)c.total else 8L+bytes.size+(bytes.size and 1)
                }
                if(newSize !in 42L..MAX_FILE||newSize-8>0xffffffffL)throw Refused(Code.TOO_LARGE)
                FileChannel.open(stage.toPath(),StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use{out->
                    made=true;emit(out,"FORM".toByteArray(Charsets.US_ASCII)+be(newSize-8)+
                        "AIFF".toByteArray(Charsets.US_ASCII))
                    for(c in chunks){
                        val field=keys[c.name];val replacement=field?.let{encoded[it]}
                        if(replacement==null)copy(src,c.at,c.total,out)
                        else{
                            emit(out,c.name.toByteArray(Charsets.US_ASCII)+be(replacement.size.toLong()))
                            emit(out,replacement)
                            if(replacement.size%2==1)emit(out,byteArrayOf(0))
                        }
                    }
                    out.force(true)
                }
                if(src.size()!=expectedBytes||!beforeSha.contentEquals(digest(src)))
                    throw Refused(Code.SOURCE_CHANGED)
                FileChannel.open(stage.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use{out->
                    if(out.size()!=newSize)throw Refused(Code.READBACK_FAILED)
                    val newer=parse(out)
                    if(newer.size!=chunks.size)throw Refused(Code.READBACK_FAILED)
                    for(i in chunks.indices){
                        val old=chunks[i];val fresh=newer[i]
                        if(old.name!=fresh.name)throw Refused(Code.READBACK_FAILED)
                        if(old.name !in keys || keys[old.name] !in diffs) {
                            if(old.total!=fresh.total||!same(src,old.at,out,fresh.at,old.total))
                                throw Refused(Code.READBACK_FAILED)
                        }
                    }
                    val after=MetadataRiffAiffColdReader.observe(out)
                    if(after.container!=MetadataPhysicalContainer.AIFF)throw Refused(Code.READBACK_FAILED)
                    for((field,diff) in diffs)if((after.observation(field) as?
                        MetadataFieldObservation.Present)?.values!=diff.after)
                        throw Refused(Code.READBACK_FAILED)
                    for(field in MetadataField.entries)if(field !in diffs &&
                        before.observation(field)!=after.observation(field))throw Refused(Code.READBACK_FAILED)
                }
                return Result(expectedBytes,newSize,diffs.keys)
            }
        }catch(e:Exception){if(made)stage.delete();throw e}
    }
}
