package org.michimusic.core.metadata

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Private Ogg Vorbis/Opus comment edit: packet and page sizes MUST remain identical. */
object MetadataOggStageRewriter {
    enum class Code { INVALID_REVIEW, UNSAFE_PATH, NOT_OGG, BAD_CRC, MULTIPAGE_COMMENT,
        UNSUPPORTED_LAYOUT, UNVERIFIED_FIELD, UNSUPPORTED_CHANGE, LENGTH_CHANGED,
        SOURCE_CHANGED, READBACK_FAILED }
    class Refused(val code:Code):IOException(code.name)
    data class Result(val originalBytes:Long,val candidateBytes:Long,
        val changedFields:Set<MetadataField>,val canCommitToSource:Boolean=false)
    private const val MAX_FILE=1024L*1024*1024
    private fun u(b:Byte)=b.toInt() and 255
    private fun le(b:ByteArray,at:Int)=u(b[at]).toLong() or (u(b[at+1]).toLong() shl 8) or
        (u(b[at+2]).toLong() shl 16) or (u(b[at+3]).toLong() shl 24)
    private fun le32(n:Long)=byteArrayOf(n.toByte(),(n ushr 8).toByte(),(n ushr 16).toByte(),(n ushr 24).toByte())
    private fun read(ch:FileChannel,at:Long,n:Int):ByteArray{
        if(n<0||at<0||n.toLong()>ch.size()-at)throw Refused(Code.UNSUPPORTED_LAYOUT)
        val b=ByteBuffer.allocate(n);var pos=at
        while(b.hasRemaining()){val x=ch.read(b,pos);if(x<=0)throw Refused(Code.SOURCE_CHANGED);pos+=x}
        return b.array()
    }
    private fun crc(page:ByteArray):Int{
        var c=0
        for(i in page.indices){val octet=if(i in 22..25)0 else u(page[i]);c=c xor (octet shl 24)
            repeat(8){ c=(c shl 1) xor (if((c and Int.MIN_VALUE)!=0)0x04c11db7 else 0) }
        }
        return c
    }
    private fun validatePage(page:ByteArray){
        if(page.size<27||String(page,0,4,Charsets.US_ASCII)!="OggS"||u(page[4])!=0||
            le(page,22).toInt()!=crc(page))throw Refused(Code.BAD_CRC)
    }
    private data class Slot(val pageAt:Long,val page:ByteArray,val payloadAt:Int,
        val packetAt:Int,val packet:ByteArray,val opus:Boolean)
    private fun commentSlot(src:FileChannel):Slot {
        var fileAt=0L;var sequence=0L;var serial:Long?=null;var packets=0
        var continuation=false;var payload=java.io.ByteArrayOutputStream();var start=-1
        var kind:Boolean?=null
        repeat(128){
            if(fileAt+27>src.size())throw Refused(Code.UNSUPPORTED_LAYOUT)
            val h=read(src,fileAt,27)
            if(String(h,0,4,Charsets.US_ASCII)!="OggS"||u(h[4])!=0)
                throw Refused(Code.NOT_OGG)
            val count=u(h[26]);val segments=read(src,fileAt+27,count)
            val page=read(src,fileAt,27+count+segments.sumOf(::u))
            validatePage(page)
            if((serial!=null && serial!=le(h,14))||le(h,18)!=sequence ||
                (sequence==0L && (u(h[5]) and 0x02)==0) ||
                ((u(h[5]) and 0x01)!=0)!=continuation)
                throw Refused(Code.UNSUPPORTED_LAYOUT)
            serial=le(h,14);sequence++
            var cursor=27+count
            for(s in segments){
                val n=u(s)
                if(payload.size()==0)start=cursor
                if(payload.size()+n>2*1024*1024)throw Refused(Code.UNSUPPORTED_LAYOUT)
                payload.write(page,cursor,n);cursor+=n
                if(n<255){
                    packets++
                    val pkt=payload.toByteArray();payload.reset()
                    if(packets==1){
                        kind=when{
                            String(pkt.take(8).toByteArray(),Charsets.US_ASCII)=="OpusHead"->true
                            pkt.size>=7&&pkt[0]==1.toByte()&&String(pkt,1,6,Charsets.US_ASCII)=="vorbis"->false
                            else->throw Refused(Code.NOT_OGG)
                        }
                    }
                    if(packets==2){
                        if(continuation || start<27+count || cursor-start!=pkt.size)
                            throw Refused(Code.MULTIPAGE_COMMENT)
                        return Slot(fileAt,page,27+count,start,pkt,kind?:false)
                    }
                }
            }
            continuation=payload.size()>0
            if(continuation && packets>=1)throw Refused(Code.MULTIPAGE_COMMENT)
            fileAt+=page.size
        }
        throw Refused(Code.UNSUPPORTED_LAYOUT)
    }
    private fun utf8(b:ByteArray):String=try{
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    }catch(_:Exception){throw Refused(Code.UNSUPPORTED_LAYOUT)}
    private fun edit(packet:ByteArray,opus:Boolean,diffs:Map<MetadataField,MetadataFieldDiff>):ByteArray {
        val prefix=if(opus)"OpusTags".toByteArray(Charsets.US_ASCII)
            else byteArrayOf(3)+"vorbis".toByteArray(Charsets.US_ASCII)
        if(packet.size<prefix.size+8||!packet.copyOfRange(0,prefix.size).contentEquals(prefix))
            throw Refused(Code.UNSUPPORTED_LAYOUT)
        val out=packet.clone();var pos=prefix.size
        fun next():Pair<Int,ByteArray>{
            if(pos+4>packet.size)throw Refused(Code.UNSUPPORTED_LAYOUT)
            val len=le(packet,pos);pos+=4
            if(len>2*1024*1024||len>packet.size-pos)throw Refused(Code.UNSUPPORTED_LAYOUT)
            val start=pos;pos+=len.toInt()
            return start to packet.copyOfRange(start,pos)
        }
        utf8(next().second) // vendor
        if(pos+4>packet.size)throw Refused(Code.UNSUPPORTED_LAYOUT)
        val n=le(packet,pos);pos+=4
        if(n>4096)throw Refused(Code.UNSUPPORTED_LAYOUT)
        val aliases=MetadataReadFieldCatalog.primary.associateWith{MetadataFlacStageRewriter.preservationAliases(it)}
        val owners=mutableMapOf<String,MetadataField>()
        for((field,keys) in aliases)for(key in keys){
            val old=owners.put(key,field)
            if(old!=null&&old!=field)throw Refused(Code.UNSUPPORTED_LAYOUT)
        }
        val seen=mutableMapOf<MetadataField,Int>()
        repeat(n.toInt()){
            val (at,raw)=next();val sep=raw.indexOf('='.code.toByte())
            if(sep !in 1..128)throw Refused(Code.UNSUPPORTED_LAYOUT)
            val key=raw.copyOfRange(0,sep).toString(Charsets.US_ASCII).uppercase(Locale.ROOT)
            val field=owners[key]?:return@repeat
            val diff=diffs[field]?:return@repeat
            val index=seen[field]?:0
            if(index>=diff.before.size||index>=diff.after.size)
                throw Refused(Code.UNSUPPORTED_CHANGE)
            val oldValue=utf8(raw.copyOfRange(sep+1,raw.size))
            if(oldValue!=diff.before[index])throw Refused(Code.SOURCE_CHANGED)
            val replacement=diff.after[index].toByteArray(Charsets.UTF_8)
            if(replacement.size!=raw.size-sep-1)throw Refused(Code.LENGTH_CHANGED)
            replacement.copyInto(out,at+sep+1)
            seen[field]=index+1
        }
        if(seen.size!=diffs.size||diffs.any{seen[it.key]!=it.value.before.size||
                it.value.after.size!=it.value.before.size})throw Refused(Code.UNSUPPORTED_CHANGE)
        if(!opus && (pos+1!=packet.size || u(packet[pos])!=1))
            throw Refused(Code.UNSUPPORTED_LAYOUT)
        return out
    }
    private fun put(ch:FileChannel,at:Long,bytes:ByteArray){val b=ByteBuffer.wrap(bytes);var p=at
        while(b.hasRemaining()){val n=ch.write(b,p);if(n<=0)throw Refused(Code.READBACK_FAILED);p+=n}}
    private fun copy(src:FileChannel,dst:FileChannel){var at=0L;val b=ByteBuffer.allocate(65536)
        while(at<src.size()){b.clear();b.limit(minOf(65536L,src.size()-at).toInt())
            val n=src.read(b,at);if(n<=0)throw Refused(Code.SOURCE_CHANGED)
            b.flip();while(b.hasRemaining())if(dst.write(b)<=0)throw Refused(Code.SOURCE_CHANGED)
            at+=n}
    }
    fun stage(backup:File,stage:File,review:MetadataReview.Ready,expectedBytes:Long):Result{
        if(MetadataReviewIntegrityPolicy.inspect(review)!=MetadataReviewIntegrityPolicy.Verdict.Exact)
            throw Refused(Code.INVALID_REVIEW)
        if(expectedBytes !in 64L..MAX_FILE||backup==stage||stage.exists()||
            Files.isSymbolicLink(backup.toPath())||Files.isSymbolicLink(stage.toPath())||
            !Files.isRegularFile(backup.toPath(),LinkOption.NOFOLLOW_LINKS)||
            backup.parentFile?.canonicalFile!=stage.parentFile?.canonicalFile)
            throw Refused(Code.UNSAFE_PATH)
        val diffs=review.diffs.associateBy{it.field}
        if(diffs.isEmpty()||diffs.keys.any{it !in supportedFields})throw Refused(Code.UNSUPPORTED_CHANGE)
        var made=false
        try{
            FileChannel.open(backup.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use{src->
                if(src.size()!=expectedBytes)throw Refused(Code.SOURCE_CHANGED)
                val original=MetadataOggColdReader.observe(src)
                val slot=commentSlot(src)
                val required=if(slot.opus)MetadataPhysicalContainer.OGG_OPUS
                    else MetadataPhysicalContainer.OGG_VORBIS
                if(original.container!=required)throw Refused(Code.UNVERIFIED_FIELD)
                for((field,diff)in diffs){
                    if((original.observation(field) as? MetadataFieldObservation.Present)?.values!=diff.before)
                        throw Refused(Code.UNVERIFIED_FIELD)
                }
                val altered=edit(slot.packet,slot.opus,diffs)
                val page=slot.page.clone();altered.copyInto(page,slot.packetAt)
                le32(crc(page).toLong()).copyInto(page,22)
                validatePage(page)
                FileChannel.open(stage.toPath(),StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use{dst->
                    made=true;copy(src,dst);put(dst,slot.pageAt,page);dst.force(true)
                }
                FileChannel.open(stage.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use{candidate->
                    if(candidate.size()!=src.size())throw Refused(Code.READBACK_FAILED)
                    val check=commentSlot(candidate)
                    if(check.pageAt!=slot.pageAt||check.packetAt!=slot.packetAt||
                        !check.packet.contentEquals(altered))throw Refused(Code.READBACK_FAILED)
                    // Verify all exact bytes OUTSIDE the single edited page.
                    val a=ByteBuffer.allocate(65536);val b=ByteBuffer.allocate(65536)
                    fun sameRange(first:Long,length:Long):Boolean{var done=0L
                        while(done<length){val len=minOf(65536L,length-done).toInt()
                            a.clear();a.limit(len);b.clear();b.limit(len)
                            if(src.read(a,first+done)!=len||candidate.read(b,first+done)!=len||
                                !a.array().copyOfRange(0,len).contentEquals(b.array().copyOfRange(0,len)))return false
                            done+=len};return true
                    }
                    if(!sameRange(0,slot.pageAt)||
                        !sameRange(slot.pageAt+page.size,src.size()-slot.pageAt-page.size))
                        throw Refused(Code.READBACK_FAILED)
                    val after=MetadataOggColdReader.observe(candidate)
                    if(after.container!=required)throw Refused(Code.READBACK_FAILED)
                    for((field,diff) in diffs)if((after.observation(field) as?
                        MetadataFieldObservation.Present)?.values!=diff.after)throw Refused(Code.READBACK_FAILED)
                    for(field in MetadataField.entries)if(field !in diffs &&
                        original.observation(field)!=after.observation(field))
                        throw Refused(Code.READBACK_FAILED)
                }
                return Result(expectedBytes,expectedBytes,diffs.keys)
            }
        }catch(e:Exception){if(made)stage.delete();throw e}
    }
    val supportedFields: Set<MetadataField> = MetadataReadFieldCatalog.primary.filterTo(linkedSetOf()){
        MetadataFlacStageRewriter.preservationAliases(it).isNotEmpty() }
}
