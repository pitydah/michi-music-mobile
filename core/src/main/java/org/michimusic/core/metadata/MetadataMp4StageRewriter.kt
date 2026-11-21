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

/** Private, byte-length-preserving MP4/M4A text atom staging. Never shifts mdat or stco. */
object MetadataMp4StageRewriter {
    enum class Code { INVALID_REVIEW, UNSAFE_PATH, SOURCE_CHANGED, INVALID_MP4,
        UNSUPPORTED_BOX, DUPLICATE_BOX, NOT_PRESENT, UNVERIFIED_FIELD, UNSUPPORTED_VALUE,
        SIZE_CHANGE, READBACK_FAILED }
    class Refused(val code:Code):IOException(code.name)
    data class Result(val originalBytes:Long,val candidateBytes:Long,
        val changedFields:Set<MetadataField>,val canCommitToSource:Boolean=false)
    private const val MAX_FILE=1024L*1024*1024
    private const val MAX_BOXES=8192
    private val map=linkedMapOf("©nam" to MetadataField.TITLE,
        "©ART" to MetadataField.ARTIST,"aART" to MetadataField.ALBUM_ARTIST,
        "©alb" to MetadataField.ALBUM,"©gen" to MetadataField.GENRE,
        "©wrt" to MetadataField.COMPOSER,"©day" to MetadataField.RELEASE_DATE,
        "©cmt" to MetadataField.COMMENT,"©lyr" to MetadataField.LYRICS,
        "©too" to MetadataField.MEDIA_FORMAT,"©grp" to MetadataField.GROUPING)
    val supportedFields:Set<MetadataField> = map.values.toSet()
    private fun u(b:Byte)=b.toInt() and 255
    private fun be32(b:ByteArray,i:Int)=(u(b[i]).toLong() shl 24) or
        (u(b[i+1]).toLong() shl 16) or (u(b[i+2]).toLong() shl 8) or u(b[i+3]).toLong()
    private fun be64(b:ByteArray,i:Int):Long{
        val hi=be32(b,i);val lo=be32(b,i+4)
        if(hi>0x7fffffffL)throw Refused(Code.UNSUPPORTED_BOX)
        return (hi shl 32) or lo
    }
    private fun read(ch:FileChannel,at:Long,len:Int):ByteArray{
        if(len<0||at<0||at>ch.size()||len.toLong()>ch.size()-at)
            throw Refused(Code.UNSUPPORTED_BOX)
        val b=ByteBuffer.allocate(len);var p=at
        while(b.hasRemaining()){val n=ch.read(b,p);if(n<=0)throw Refused(Code.SOURCE_CHANGED);p+=n}
        return b.array()
    }
    private data class Box(val type:String,val at:Long,val payload:Long,val end:Long)
    private fun box(ch:FileChannel,at:Long,end:Long):Box{
        if(at+8>end)throw Refused(Code.UNSUPPORTED_BOX)
        val h=read(ch,at,8);val size=be32(h,0)
        val head=if(size==1L)16 else 8
        if(at+head>end)throw Refused(Code.UNSUPPORTED_BOX)
        val total=when(size){0L->end-at;1L->be64(read(ch,at+8,8),0);else->size}
        if(total<head||total>end-at)throw Refused(Code.UNSUPPORTED_BOX)
        return Box(String(h,4,4,StandardCharsets.ISO_8859_1),at,at+head,at+total)
    }
    private data class EditSlot(val field:MetadataField,val offset:Long,val length:Int,
        val original:String)
    private fun slots(ch:FileChannel):Map<MetadataField,EditSlot>{
        if(ch.size() !in 24L..MAX_FILE)throw Refused(Code.INVALID_MP4)
        val items=mutableMapOf<MetadataField,EditSlot>();var count=0;var seenIlst=false
        fun walk(at0:Long,end:Long,depth:Int,withinMeta:Boolean){
            if(depth>7)throw Refused(Code.UNSUPPORTED_BOX)
            var at=at0
            while(at<end){
                if(++count>MAX_BOXES)throw Refused(Code.UNSUPPORTED_BOX)
                val b=box(ch,at,end)
                when{
                    b.type=="moov"&&depth==0 -> walk(b.payload,b.end,depth+1,false)
                    b.type=="udta"&&depth>0 -> walk(b.payload,b.end,depth+1,false)
                    b.type=="meta"&&depth>0 ->{
                        if(b.payload+4>b.end)throw Refused(Code.UNSUPPORTED_BOX)
                        walk(b.payload+4,b.end,depth+1,true)
                    }
                    b.type=="ilst"&&withinMeta ->{
                        if(seenIlst)throw Refused(Code.DUPLICATE_BOX)
                        seenIlst=true;var pos=b.payload
                        while(pos<b.end){
                            if(++count>MAX_BOXES)throw Refused(Code.UNSUPPORTED_BOX)
                            val item=box(ch,pos,b.end);val field=map[item.type]
                            if(field!=null){
                                if(items.containsKey(field))throw Refused(Code.DUPLICATE_BOX)
                                // Exactly one UTF-8 `data` atom, no free-form/binary siblings.
                                val d=box(ch,item.payload,item.end)
                                if(d.type!="data"||d.end!=item.end||d.payload+8>d.end)
                                    throw Refused(Code.UNSUPPORTED_BOX)
                                val header=read(ch,d.payload,8)
                                if(be32(header,0)!=1L)throw Refused(Code.UNSUPPORTED_BOX)
                                val size=(d.end-(d.payload+8)).toInt()
                                if(size !in 1..65536)throw Refused(Code.UNSUPPORTED_BOX)
                                val text=try{
                                    StandardCharsets.UTF_8.newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                                        .decode(ByteBuffer.wrap(read(ch,d.payload+8,size))).toString()
                                }catch(_:Exception){throw Refused(Code.UNSUPPORTED_VALUE)}
                                if(text.isBlank()||'\u0000' in text||text.length>field.maxChars)
                                    throw Refused(Code.UNSUPPORTED_VALUE)
                                items[field]=EditSlot(field,d.payload+8,size,text)
                            }
                            pos=item.end
                        }
                    }
                }
                at=b.end
            }
            if(at!=end)throw Refused(Code.UNSUPPORTED_BOX)
        }
        walk(0,ch.size(),0,false)
        if(!seenIlst)throw Refused(Code.INVALID_MP4)
        return items
    }
    private fun copy(src:FileChannel,dst:FileChannel){
        val buf=ByteBuffer.allocate(65536);var at=0L
        while(at<src.size()){
            buf.clear();buf.limit(minOf(src.size()-at,65536L).toInt())
            val n=src.read(buf,at);if(n<=0)throw Refused(Code.SOURCE_CHANGED)
            buf.flip();while(buf.hasRemaining())if(dst.write(buf)<=0)throw Refused(Code.SOURCE_CHANGED)
            at+=n
        }
    }
    private fun put(ch:FileChannel,at:Long,bytes:ByteArray){
        val b=ByteBuffer.wrap(bytes);var p=at
        while(b.hasRemaining()){val n=ch.write(b,p);if(n<=0)throw Refused(Code.READBACK_FAILED);p+=n}
    }
    private fun equalExcept(src:FileChannel,dst:FileChannel,slots:List<EditSlot>):Boolean{
        val x=ByteBuffer.allocate(65536);val y=ByteBuffer.allocate(65536)
        fun range(at:Long,size:Long):Boolean{
            var done=0L
            while(done<size){val n=minOf(65536L,size-done).toInt()
                x.clear();x.limit(n);y.clear();y.limit(n)
                if(src.read(x,at+done)!=n||dst.read(y,at+done)!=n||
                    !x.array().copyOfRange(0,n).contentEquals(y.array().copyOfRange(0,n)))return false
                done+=n
            };return true
        }
        var at=0L
        for(slot in slots.sortedBy{it.offset}){
            if(slot.offset<at||!range(at,slot.offset-at))return false
            at=slot.offset+slot.length
        }
        return range(at,src.size()-at)
    }
    fun stage(backup:File,stage:File,review:MetadataReview.Ready,expectedBytes:Long):Result{
        if(MetadataReviewIntegrityPolicy.inspect(review)!=MetadataReviewIntegrityPolicy.Verdict.Exact)
            throw Refused(Code.INVALID_REVIEW)
        if(expectedBytes !in 24L..MAX_FILE||backup==stage||stage.exists()||
            Files.isSymbolicLink(backup.toPath())||Files.isSymbolicLink(stage.toPath())||
            !Files.isRegularFile(backup.toPath(),LinkOption.NOFOLLOW_LINKS)||
            backup.parentFile?.canonicalFile!=stage.parentFile?.canonicalFile)
            throw Refused(Code.UNSAFE_PATH)
        val changes=review.diffs.associateBy{it.field}
        if(changes.isEmpty()||changes.keys.any{it !in supportedFields})throw Refused(Code.UNSUPPORTED_VALUE)
        var made=false
        try{
            FileChannel.open(backup.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use{src->
                if(src.size()!=expectedBytes)throw Refused(Code.SOURCE_CHANGED)
                val original=MetadataMp4ColdReader.observe(src)
                if(original.container!=MetadataPhysicalContainer.MP4_M4A)
                    throw Refused(Code.INVALID_MP4)
                val all=slots(src)
                val edited=mutableListOf<EditSlot>()
                val replacements=mutableMapOf<MetadataField,ByteArray>()
                for((field,diff) in changes){
                    val slot=all[field]?:throw Refused(Code.NOT_PRESENT)
                    if(diff.before!=listOf(slot.original)||diff.after.size!=1||
                        (original.observation(field) as? MetadataFieldObservation.Present)?.values!=diff.before)
                        throw Refused(Code.UNVERIFIED_FIELD)
                    val bytes=diff.after.single().toByteArray(Charsets.UTF_8)
                    if(bytes.size!=slot.length)throw Refused(Code.SIZE_CHANGE)
                    replacements[field]=bytes;edited+=slot
                }
                FileChannel.open(stage.toPath(),StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use{dst->
                    made=true;copy(src,dst)
                    for(slot in edited)put(dst,slot.offset,replacements.getValue(slot.field))
                    dst.force(true)
                }
                FileChannel.open(stage.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use{dst->
                    if(dst.size()!=src.size()||!equalExcept(src,dst,edited))
                        throw Refused(Code.READBACK_FAILED)
                    val newer=slots(dst)
                    if(newer.keys!=all.keys)throw Refused(Code.READBACK_FAILED)
                    val after=MetadataMp4ColdReader.observe(dst)
                    for((field,diff) in changes)if(
                        (after.observation(field) as? MetadataFieldObservation.Present)?.values!=diff.after)
                        throw Refused(Code.READBACK_FAILED)
                    for(field in MetadataField.entries)if(field !in changes &&
                        original.observation(field)!=after.observation(field))
                        throw Refused(Code.READBACK_FAILED)
                }
                return Result(expectedBytes,expectedBytes,changes.keys)
            }
        }catch(e:Exception){if(made)stage.delete();throw e}
    }
}
