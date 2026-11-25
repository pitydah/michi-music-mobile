package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * ME-10G. A single, EXACT asset mutation in a new PRIVATE staging file only.
 * No Android provider, Room, production commit or generic "save" operation.
 * The full private source hash and the complete metadata evidence must match.
 * All other metadata objects are copied verbatim and audio is copied in chunks.
 */
object MetadataSelectiveAssetStage {
    private const val MAX_FILE = 1024L * 1024 * 1024
    private const val MAX_TAG = MetadataArtworkLyricsProbe.MAX_TAG_BYTES
    private const val MAX_IMAGE = MetadataArtworkReplacementPreview.MAX_IMAGE_BYTES
    private const val CHUNK = 64 * 1024
    private val hex64 = Regex("[a-f0-9]{64}")

    enum class Code {
        UNSAFE_PRIVATE_SOURCE, STAGE_EXISTS, INVALID_SOURCE_DIGEST, SOURCE_CHANGED,
        UNSUPPORTED_FORMAT, ID3V1_CONFLICT, MALFORMED_METADATA, OBSERVATION_CONFLICT,
        MISSING_TARGET, AMBIGUOUS_TARGET, INVALID_REPLACEMENT, UNSUPPORTED_IMAGE,
        METADATA_BUDGET, INSUFFICIENT_SPACE, AUDIO_MISMATCH, STAGE_READBACK_FAILED,
    }
    class Rejected(val code: Code) : IOException(code.name)
    sealed interface Edit {
        data class Picture(val proposal: MetadataArtworkReplacementPreview.Proposal) : Edit
        data class PictureAdd(
            val proposal: MetadataArtworkAdditionPreview.Proposal,
        ) : Edit
        data class PictureRemove(
            val ordinal: Int,
            val sourceSha256: String,
        ) : Edit
        data class DescribedText(val change: MetadataDescribedTextWorkbench.Change) : Edit
    }
    data class Result(
        val format: MetadataArtworkLyricsProbe.Format,
        val originalBytes: Long,
        val stagedBytes: Long,
        val originalSha256: String,
        val stagedSha256: String,
        val audioSha256: String,
        val changedItem: String,
        /** This result is NEVER a provider-write capability. */
        val canCommitToProvider: Boolean = false,
    )
    private data class Region(val bytes: ByteArray, val audioOffset: Long)
    private fun u(b: Byte) = b.toInt() and 255
    private fun be(b: ByteArray, at: Int): Int =
        (u(b[at]) shl 24) or (u(b[at + 1]) shl 16) or (u(b[at + 2]) shl 8) or u(b[at + 3])
    private fun sync(b: ByteArray, i: Int): Int {
        if ((0..3).any { u(b[i+it]) > 127 }) throw Rejected(Code.MALFORMED_METADATA)
        return (0..3).fold(0) { acc, k -> (acc shl 7) or u(b[i+k]) }
    }
    private fun encodeSync(n: Int): ByteArray {
        if (n !in 0..MAX_TAG) throw Rejected(Code.METADATA_BUDGET)
        return byteArrayOf((n ushr 21 and 127).toByte(), (n ushr 14 and 127).toByte(),
            (n ushr 7 and 127).toByte(), (n and 127).toByte())
    }
    private fun intBytes(n: Int) = byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),
        (n ushr 8).toByte(),n.toByte())
    private fun sha(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { (u(it)).toString(16).padStart(2,'0') }
    private fun shaFile(file: File, checkCancelled: () -> Unit = {}): String {
        val digest=MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer=ByteArray(CHUNK)
            while(true) { checkCancelled(); val n=input.read(buffer); if(n<0) break; if(n>0)digest.update(buffer,0,n) }
        }
        return digest.digest().joinToString("") { u(it).toString(16).padStart(2,'0') }
    }
    private fun region(file: File): Region = RandomAccessFile(file,"r").use { raf ->
        if(raf.length()<12 || raf.length()>MAX_FILE) throw Rejected(Code.UNSAFE_PRIVATE_SOURCE)
        val magic=ByteArray(4)
        raf.readFully(magic)
        if(magic.copyOfRange(0,3).contentEquals("ID3".toByteArray(StandardCharsets.US_ASCII))) {
            raf.seek(0)
            val header=ByteArray(10);raf.readFully(header)
            val length=MetadataArtworkLyricsProbe.id3DeclaredLength(header)
                ?: throw Rejected(Code.UNSUPPORTED_FORMAT)
            if(length >= raf.length() || length>MAX_TAG) throw Rejected(Code.MALFORMED_METADATA)
            val data=ByteArray(length);raf.seek(0);raf.readFully(data)
            Region(data,length.toLong())
        } else if(magic.contentEquals("fLaC".toByteArray(StandardCharsets.US_ASCII))) {
            var pos=4
            var last:Boolean
            var first=true
            do {
                if(pos+4>raf.length() || pos+4>MAX_TAG)throw Rejected(Code.MALFORMED_METADATA)
                raf.seek(pos.toLong())
                val h=ByteArray(4);raf.readFully(h)
                val kind=u(h[0]) and 127
                val n=(u(h[1]) shl 16) or (u(h[2]) shl 8) or u(h[3])
                if(first && (kind!=0 || n!=34))throw Rejected(Code.MALFORMED_METADATA)
                first=false
                if(pos.toLong()+4+n>raf.length() || pos.toLong()+4+n>MAX_TAG)
                    throw Rejected(Code.METADATA_BUDGET)
                pos+=4+n
                last=(u(h[0]) and 128)!=0
            } while(!last)
            if(pos >= raf.length()) throw Rejected(Code.MALFORMED_METADATA)
            val data=ByteArray(pos);raf.seek(0);raf.readFully(data)
            Region(data,pos.toLong())
        } else throw Rejected(Code.UNSUPPORTED_FORMAT)
    }
    private fun requireSameSnapshot(
        actual: MetadataArtworkLyricsProbe.Snapshot, original: MetadataArtworkLyricsProbe.Snapshot,
    ) {
        if(actual.format != original.format || actual.texts != original.texts ||
            actual.artworks.size != original.artworks.size ||
            actual.artworks.zip(original.artworks).any { (a,b) ->
                a.ordinal != b.ordinal || a.info != b.info || !a.image.contentEquals(b.image)
            }) throw Rejected(Code.OBSERVATION_CONFLICT)
    }
    private data class Dimensions(val width: Int,val height: Int,val bits: Int)
    private fun imageDimensions(mime: String, b: ByteArray): Dimensions? {
        if(mime=="image/png") {
            if(b.size<33 || !b.copyOfRange(0,8).contentEquals(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)) ||
                be(b,8)!=13 || !b.copyOfRange(12,16).contentEquals("IHDR".toByteArray()))return null
            val w=be(b,16);val h=be(b,20);val depth=u(b[24]);val color=u(b[25])
            val channels=when(color){0->1;2->3;4->2;6->4;else->return null}
            if(w !in 1..16384 || h !in 1..16384 || depth !in setOf(1,2,4,8,16) ||
                (color!=0 && color!=2 && depth<8))return null
            return Dimensions(w,h,depth*channels)
        }
        if(mime!="image/jpeg" || b.size<10 || b[0]!=0xff.toByte() || b[1]!=0xd8.toByte())return null
        var at=2
        while(at<minOf(b.size-2,256*1024)) {
            if(u(b[at++])!=255)return null
            while(at<b.size && u(b[at])==255)at++
            if(at>=b.size)return null
            val marker=u(b[at++])
            if(marker==0xd9 || marker==0xda)return null
            if(marker==0x01 || marker in 0xd0..0xd7)continue
            if(at+2>b.size)return null
            val segment=(u(b[at]) shl 8) or u(b[at+1])
            if(segment<2 || segment>b.size-at)return null
            if(marker in setOf(0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf)) {
                if(segment<8)return null
                val bits=u(b[at+2]);val height=(u(b[at+3]) shl 8) or u(b[at+4])
                val width=(u(b[at+5]) shl 8) or u(b[at+6]);val components=u(b[at+7])
                if(width !in 1..16384 || height !in 1..16384 || bits !in 1..16 || components !in 1..4)return null
                return Dimensions(width,height,bits*components)
            }
            at+=segment
        }
        return null
    }
    private fun id3ArtworkPayload(old: ByteArray,
        proposal: MetadataArtworkReplacementPreview.Proposal): ByteArray {
        if(old.size<13 || u(old[0]) !in setOf(0,3))throw Rejected(Code.UNSUPPORTED_IMAGE)
        val mimeEnd=(1 until minOf(old.size,80)).firstOrNull { old[it]==0.toByte() }
            ?: throw Rejected(Code.MALFORMED_METADATA)
        val typeAt=mimeEnd+1
        val descEnd=(typeAt+1 until old.size).firstOrNull {old[it]==0.toByte()}
            ?: throw Rejected(Code.MALFORMED_METADATA)
        return byteArrayOf(old[0]) + proposal.proposedMime.toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(0,old[typeAt]) + old.copyOfRange(typeAt+1,descEnd+1) + proposal.proposedBytes
    }
    private fun flacPicturePayload(old: ByteArray,
        proposal: MetadataArtworkReplacementPreview.Proposal): ByteArray {
        val dims=imageDimensions(proposal.proposedMime,proposal.proposedBytes)
            ?:throw Rejected(Code.UNSUPPORTED_IMAGE)
        if(old.size<36)throw Rejected(Code.MALFORMED_METADATA)
        val type=be(old,0);val mlen=be(old,4)
        if(mlen !in 9..64 || 8+mlen+4>old.size)throw Rejected(Code.MALFORMED_METADATA)
        val descLengthAt=8+mlen;val dlen=be(old,descLengthAt)
        if(dlen !in 0..2048 || descLengthAt+4+dlen+20>old.size)
            throw Rejected(Code.MALFORMED_METADATA)
        val desc=old.copyOfRange(descLengthAt+4,descLengthAt+4+dlen)
        val mime=proposal.proposedMime.toByteArray(StandardCharsets.US_ASCII)
        return intBytes(type)+intBytes(mime.size)+mime+intBytes(desc.size)+desc+
            intBytes(dims.width)+intBytes(dims.height)+intBytes(dims.bits)+intBytes(0)+
            intBytes(proposal.proposedBytes.size)+proposal.proposedBytes
    }
    private fun encodeDescribed(
        version: Int, identity: MetadataDescribedTextWorkbench.Identity, text: String,
    ): ByteArray {
        val language=identity.language.toByteArray(StandardCharsets.US_ASCII)
        val payload=if(version==3) {
            byteArrayOf(1)+language+identity.description.toByteArray(StandardCharsets.UTF_16)+
                byteArrayOf(0,0)+text.toByteArray(StandardCharsets.UTF_16)
        } else {
            byteArrayOf(3)+language+identity.description.toByteArray(StandardCharsets.UTF_8)+
                byteArrayOf(0)+text.toByteArray(StandardCharsets.UTF_8)
        }
        val parsed=MetadataId3DescribedTagCodec.decode(identity.kind,version,payload)
        if(parsed?.language!=identity.language || parsed.description!=identity.description || parsed.text!=text)
            throw Rejected(Code.INVALID_REPLACEMENT)
        return payload
    }
    private fun id3Frame(id: String, payload: ByteArray, version: Int): ByteArray {
        val n=if(version==3)intBytes(payload.size) else encodeSync(payload.size)
        return id.toByteArray(StandardCharsets.US_ASCII)+n+byteArrayOf(0,0)+payload
    }
    private fun id3ArtworkAdditionPayload(
        version: Int, p: MetadataArtworkAdditionPreview.Proposal,
    ): ByteArray {
        val encoding=if(version==3)0 else 3
        val out=ByteArrayOutputStream()
        out.write(encoding)
        out.write(p.proposedMime.toByteArray(StandardCharsets.US_ASCII));out.write(0)
        out.write(p.type)
        out.write(p.description.toByteArray(
            if(encoding==3)StandardCharsets.UTF_8 else StandardCharsets.ISO_8859_1))
        out.write(0)
        out.write(p.proposedBytes)
        return out.toByteArray()
    }
    private fun flacPictureAdditionPayload(
        p: MetadataArtworkAdditionPreview.Proposal,
    ): ByteArray {
        val dims=imageDimensions(p.proposedMime,p.proposedBytes)
            ?:throw Rejected(Code.UNSUPPORTED_IMAGE)
        val out=ByteArrayOutputStream()
        out.write(intBytes(3))
        val mime=p.proposedMime.toByteArray(StandardCharsets.US_ASCII)
        out.write(intBytes(mime.size));out.write(mime)
        val description=p.description.toByteArray(StandardCharsets.UTF_8)
        out.write(intBytes(description.size));out.write(description)
        out.write(intBytes(dims.width));out.write(intBytes(dims.height))
        out.write(intBytes(dims.bits));out.write(intBytes(0))
        out.write(intBytes(p.proposedBytes.size));out.write(p.proposedBytes)
        return out.toByteArray()
    }
    private fun rewriteId3(source: ByteArray, edit: Edit): ByteArray {
        val version=u(source[3]);val out=ByteArrayOutputStream()
        var at=10;var ordinal=0;var found=0
        while(at<source.size) {
            if(source.size-at<10 || (0..3).all {source[at+it]==0.toByte()}) {
                if((at until source.size).any {source[it]!=0.toByte()})throw Rejected(Code.MALFORMED_METADATA)
                out.write(source,at,source.size-at);break
            }
            val id=String(source,at,4,StandardCharsets.US_ASCII)
            if(!id.matches(Regex("[A-Z0-9]{4}")))throw Rejected(Code.MALFORMED_METADATA)
            val len=if(version==3)be(source,at+4) else sync(source,at+4)
            if(len<=0 || len>source.size-at-10 || source[at+8]!=0.toByte() || source[at+9]!=0.toByte())
                throw Rejected(Code.MALFORMED_METADATA)
            val payload=source.copyOfRange(at+10,at+10+len)
            val replace=when(edit) {
                is Edit.Picture -> if(id=="APIC" && ordinal++==edit.proposal.index) {
                    found++
                    val previous=MetadataArtworkEvidence.id3ApicFrame(payload, version)?.picture
                        ?:throw Rejected(Code.UNSUPPORTED_IMAGE)
                    if(previous.sha256!=edit.proposal.sourceSha256)throw Rejected(Code.OBSERVATION_CONFLICT)
                    id3Frame(id,id3ArtworkPayload(payload,edit.proposal),version)
                }else null
                is Edit.PictureAdd -> {
                    if(id=="APIC")ordinal++
                    null
                }
                is Edit.PictureRemove -> if(id=="APIC" && ordinal++==edit.ordinal) {
                    found++
                    val previous=MetadataArtworkEvidence.id3ApicFrame(payload, version)?.picture
                        ?:throw Rejected(Code.UNSUPPORTED_IMAGE)
                    if(previous.sha256!=edit.sourceSha256)throw Rejected(Code.OBSERVATION_CONFLICT)
                    byteArrayOf()
                }else null
                is Edit.DescribedText -> {
                    val identity=edit.change.identity
                    if(id==identity.kind.frameId) {
                        val existing=MetadataId3DescribedTagCodec.decode(identity.kind,version,payload)
                            ?:throw Rejected(Code.MALFORMED_METADATA)
                        if(existing.language==identity.language && existing.description==identity.description) {
                            found++
                            when(val change=edit.change) {
                                is MetadataDescribedTextWorkbench.Change.Clear -> byteArrayOf()
                                is MetadataDescribedTextWorkbench.Change.Replace -> id3Frame(id,
                                    encodeDescribed(version,identity,change.text),version)
                            }
                        }else null
                    }else null
                }
            }
            if(replace!=null)out.write(replace) else out.write(source,at,10+len)
            at+=10+len
        }
        if(edit is Edit.PictureAdd) {
            val p=edit.proposal
            if(ordinal!=p.ordinal)throw Rejected(Code.OBSERVATION_CONFLICT)
            out.write(id3Frame("APIC", id3ArtworkAdditionPayload(version,p), version))
            found=1
        }
        if(found!=1)throw Rejected(if(found==0)Code.MISSING_TARGET else Code.AMBIGUOUS_TARGET)
        val body=out.toByteArray()
        if(body.size+10>MAX_TAG)throw Rejected(Code.METADATA_BUDGET)
        return source.copyOfRange(0,6)+encodeSync(body.size)+body
    }
    private fun rewriteFlac(source: ByteArray, edit: Edit): ByteArray {
        data class Block(val type: Int, val payload: ByteArray)
        val blocks=mutableListOf<Block>()
        var at=4
        while(at<source.size) {
            if(at+4>source.size)throw Rejected(Code.MALFORMED_METADATA)
            val type=u(source[at]) and 127
            val length=(u(source[at+1]) shl 16) or (u(source[at+2]) shl 8) or u(source[at+3])
            val next=at+4+length
            if(next>source.size)throw Rejected(Code.MALFORMED_METADATA)
            blocks+=Block(type,source.copyOfRange(at+4,next))
            at=next
        }
        fun pictureIndex(ordinal: Int): Int {
            var seen=0
            blocks.forEachIndexed { index, block ->
                if(block.type==6) {
                    if(seen==ordinal)return index
                    seen++
                }
            }
            return -1
        }
        when(edit) {
            is Edit.Picture -> {
                val index=pictureIndex(edit.proposal.index)
                if(index<0)throw Rejected(Code.MISSING_TARGET)
                val old=blocks[index]
                val picture=MetadataArtworkEvidence.flacPicture(old.payload)
                    ?:throw Rejected(Code.UNSUPPORTED_IMAGE)
                if(picture.sha256!=edit.proposal.sourceSha256)
                    throw Rejected(Code.OBSERVATION_CONFLICT)
                blocks[index]=Block(6,flacPicturePayload(old.payload,edit.proposal))
            }
            is Edit.PictureAdd -> {
                val p=edit.proposal
                if(blocks.count {it.type==6}!=p.ordinal)
                    throw Rejected(Code.OBSERVATION_CONFLICT)
                blocks+=Block(6,flacPictureAdditionPayload(p))
            }
            is Edit.PictureRemove -> {
                val index=pictureIndex(edit.ordinal)
                if(index<0)throw Rejected(Code.MISSING_TARGET)
                val old=blocks[index]
                val picture=MetadataArtworkEvidence.flacPicture(old.payload)
                    ?:throw Rejected(Code.UNSUPPORTED_IMAGE)
                if(picture.sha256!=edit.sourceSha256)
                    throw Rejected(Code.OBSERVATION_CONFLICT)
                blocks.removeAt(index)
            }
            is Edit.DescribedText -> throw Rejected(Code.UNSUPPORTED_FORMAT)
        }
        val out=ByteArrayOutputStream();out.write(source,0,4)
        blocks.forEachIndexed { index, block ->
            if(block.payload.size>0xffffff)throw Rejected(Code.METADATA_BUDGET)
            out.write(block.type or if(index==blocks.lastIndex)0x80 else 0)
            out.write(byteArrayOf((block.payload.size ushr 16).toByte(),
                (block.payload.size ushr 8).toByte(),block.payload.size.toByte()))
            out.write(block.payload)
        }
        val data=out.toByteArray()
        if(data.size>MAX_TAG)throw Rejected(Code.METADATA_BUDGET)
        return data
    }
    private fun validateExpected(
        original: MetadataArtworkLyricsProbe.Snapshot, edit: Edit,
    ): String = when(edit) {
        is Edit.Picture -> {
            val p=edit.proposal
            val selected=original.artworks.singleOrNull {it.ordinal==p.index}
                ?:throw Rejected(Code.MISSING_TARGET)
            if(original.artworks.count {it.info.type==selected.info.type}!=1)
                throw Rejected(Code.AMBIGUOUS_TARGET)
            if(p.proposedBytes.size !in 16..MAX_IMAGE || p.proposedSize!=p.proposedBytes.size ||
                p.sourceSha256!=selected.info.sha256 || sha(p.proposedBytes)!=p.proposedSha256 ||
                !((MetadataArtworkReplacementPreview.review(original,p.index,p.proposedBytes,p.proposedMime)
                    as? MetadataArtworkReplacementPreview.Result.Ready)?.proposal?.proposedSha256==p.proposedSha256))
                throw Rejected(Code.INVALID_REPLACEMENT)
            "PICTURE:${p.index}"
        }
        is Edit.PictureAdd -> {
            val p=edit.proposal
            if(p.ordinal!=original.artworks.size ||
                original.artworks.size>=MetadataArtworkAdditionPreview.MAX_ART ||
                p.proposedBytes.size !in 16..MAX_IMAGE || p.proposedSize!=p.proposedBytes.size ||
                sha(p.proposedBytes)!=p.proposedSha256 ||
                (MetadataArtworkAdditionPreview.review(
                    original,p.proposedBytes,p.proposedMime,p.type,p.description,
                ) as? MetadataArtworkAdditionPreview.Result.Ready)
                    ?.proposal?.proposedSha256!=p.proposedSha256)
                throw Rejected(Code.INVALID_REPLACEMENT)
            "PICTURE_ADD:${p.ordinal}"
        }
        is Edit.PictureRemove -> {
            val selected=original.artworks.singleOrNull {it.ordinal==edit.ordinal}
                ?:throw Rejected(Code.MISSING_TARGET)
            if(selected.info.sha256!=edit.sourceSha256)
                throw Rejected(Code.OBSERVATION_CONFLICT)
            "PICTURE_REMOVE:${edit.ordinal}"
        }
        is Edit.DescribedText -> {
            if(original.format==MetadataArtworkLyricsProbe.Format.FLAC)throw Rejected(Code.UNSUPPORTED_FORMAT)
            val result=MetadataDescribedTextWorkbench.review(true,original.texts,listOf(edit.change))
            val ready=result as? MetadataDescribedTextWorkbench.Result.Ready
                ?:throw Rejected(Code.INVALID_REPLACEMENT)
            if(ready.differences.size!=1)throw Rejected(Code.INVALID_REPLACEMENT)
            "TEXT:${edit.change.identity.kind.frameId}:${edit.change.identity.language}"
        }
    }
    private fun checkExpectedAfter(
        before: MetadataArtworkLyricsProbe.Snapshot, after: MetadataArtworkLyricsProbe.Snapshot,
        edit: Edit,
    ) {
        if(before.format!=after.format)throw Rejected(Code.STAGE_READBACK_FAILED)
        when(edit) {
            is Edit.Picture -> {
                val p=edit.proposal
                if(before.texts!=after.texts || before.artworks.size!=after.artworks.size)
                    throw Rejected(Code.STAGE_READBACK_FAILED)
                before.artworks.zip(after.artworks).forEach { (b,a) ->
                    if(b.ordinal!=a.ordinal || b.info.type!=a.info.type ||
                        (b.ordinal==p.index && (a.info.sha256!=p.proposedSha256 ||
                            a.info.mime!=p.proposedMime || !a.image.contentEquals(p.proposedBytes))) ||
                        (b.ordinal!=p.index && (b.info!=a.info || !b.image.contentEquals(a.image))))
                        throw Rejected(Code.STAGE_READBACK_FAILED)
                }
            }
            is Edit.PictureAdd -> {
                val p=edit.proposal
                if(before.texts!=after.texts || after.artworks.size!=before.artworks.size+1)
                    throw Rejected(Code.STAGE_READBACK_FAILED)
                before.artworks.zip(after.artworks).forEach { (b,a) ->
                    if(b.ordinal!=a.ordinal || b.info!=a.info || !b.image.contentEquals(a.image))
                        throw Rejected(Code.STAGE_READBACK_FAILED)
                }
                val added=after.artworks.last()
                if(added.ordinal!=p.ordinal || added.info.sha256!=p.proposedSha256 ||
                    added.info.mime!=p.proposedMime || added.info.bytes!=p.proposedSize ||
                    added.info.type!=p.type || added.info.description!=p.description ||
                    !added.image.contentEquals(p.proposedBytes))
                    throw Rejected(Code.STAGE_READBACK_FAILED)
            }
            is Edit.PictureRemove -> {
                if(before.texts!=after.texts || after.artworks.size!=before.artworks.size-1)
                    throw Rejected(Code.STAGE_READBACK_FAILED)
                val expected=before.artworks.filterNot {it.ordinal==edit.ordinal}
                if(expected.size!=after.artworks.size)
                    throw Rejected(Code.STAGE_READBACK_FAILED)
                expected.forEachIndexed { index,e ->
                    val a=after.artworks[index]
                    if(a.ordinal!=index || e.info!=a.info || !e.image.contentEquals(a.image))
                        throw Rejected(Code.STAGE_READBACK_FAILED)
                }
            }
            is Edit.DescribedText -> {
                if(before.artworks.size!=after.artworks.size || before.artworks.zip(after.artworks).any { (b,a) ->
                    b.info!=a.info || !b.image.contentEquals(a.image)
                })throw Rejected(Code.STAGE_READBACK_FAILED)
                val result=MetadataDescribedTextWorkbench.review(true,before.texts,listOf(edit.change))
                    as? MetadataDescribedTextWorkbench.Result.Ready ?:throw Rejected(Code.STAGE_READBACK_FAILED)
                if(result.values!=after.texts)throw Rejected(Code.STAGE_READBACK_FAILED)
            }
        }
    }
    /**
     * Requires two files in a private caller-owned directory. The path boundary
     * is NOT authentication: the Android adapter must prove this is app-private.
     */
    fun stage(sourceCache: File, staging: File, snapshot: MetadataArtworkLyricsProbe.Snapshot,
        edit: Edit, expectedSourceSha256: String, checkCancelled: () -> Unit = {}): Result {
        checkCancelled()
        if(!expectedSourceSha256.matches(hex64))throw Rejected(Code.INVALID_SOURCE_DIGEST)
        val a=sourceCache.toPath();val b=staging.toPath();val parent=a.parent
        if(parent==null || parent!=b.parent || !Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(parent) || !Files.isRegularFile(a,LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(b,LinkOption.NOFOLLOW_LINKS) ||
            sourceCache.canonicalPath==staging.canonicalPath ||
            sourceCache.length() !in 12..MAX_FILE)
            throw Rejected(if(Files.exists(b,LinkOption.NOFOLLOW_LINKS))Code.STAGE_EXISTS else Code.UNSAFE_PRIVATE_SOURCE)
        val length=sourceCache.length();val modified=sourceCache.lastModified()
        if(shaFile(sourceCache,checkCancelled)!=expectedSourceSha256)throw Rejected(Code.SOURCE_CHANGED)
        checkCancelled()
        val originalRegion=region(sourceCache)
        if(originalRegion.bytes.copyOfRange(0,3).contentEquals("ID3".toByteArray()) &&
            length>=128) RandomAccessFile(sourceCache,"r").use { source ->
            source.seek(length-128)
            val marker=ByteArray(3);source.readFully(marker)
            if(marker.contentEquals("TAG".toByteArray(StandardCharsets.US_ASCII)))
                throw Rejected(Code.ID3V1_CONFLICT)
        }
        val observed=(MetadataArtworkLyricsProbe.inspect(originalRegion.bytes)
            as? MetadataArtworkLyricsProbe.Result.Ready)?.snapshot
            ?:throw Rejected(Code.MALFORMED_METADATA)
        requireSameSnapshot(observed,snapshot)
        val changedItem=validateExpected(snapshot,edit)
        val stagedRegion=when(snapshot.format) {
            MetadataArtworkLyricsProbe.Format.ID3_V23, MetadataArtworkLyricsProbe.Format.ID3_V24 ->
                rewriteId3(originalRegion.bytes,edit)
            MetadataArtworkLyricsProbe.Format.FLAC -> rewriteFlac(originalRegion.bytes,edit)
        }
        val estimated=length-originalRegion.audioOffset+stagedRegion.size
        if(estimated>MAX_FILE || staging.parentFile?.usableSpace?.let{it>estimated+32L*1024*1024}!=true)
            throw Rejected(Code.INSUFFICIENT_SPACE)
        val audioDigest=MessageDigest.getInstance("SHA-256")
        var stageCreated=false
        try {
            FileChannel.open(b,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { dst ->
                stageCreated=true
                val out=Channels.newOutputStream(dst)
                out.write(stagedRegion)
                RandomAccessFile(sourceCache,"r").use { input ->
                    input.seek(originalRegion.audioOffset)
                    val buffer=ByteArray(CHUNK)
                    while(true) {
                        checkCancelled()
                        val n=input.read(buffer)
                        if(n<0)break
                        if(n>0) {out.write(buffer,0,n);audioDigest.update(buffer,0,n)}
                    }
                }
                dst.force(true)
            }
            checkCancelled()
            if(sourceCache.length()!=length || sourceCache.lastModified()!=modified ||
                shaFile(sourceCache,checkCancelled)!=expectedSourceSha256)
                throw Rejected(Code.SOURCE_CHANGED)
            if(staging.length()!=estimated)throw Rejected(Code.STAGE_READBACK_FAILED)
            val after=(MetadataArtworkLyricsProbe.inspect(region(staging).bytes)
                as? MetadataArtworkLyricsProbe.Result.Ready)?.snapshot
                ?:throw Rejected(Code.STAGE_READBACK_FAILED)
            checkExpectedAfter(snapshot,after,edit)
            // Rehash the copied audio from its exact new boundary after staging.
            val check=MessageDigest.getInstance("SHA-256")
            RandomAccessFile(staging,"r").use { input ->
                input.seek(stagedRegion.size.toLong())
                val buffer=ByteArray(CHUNK)
                while(true) {checkCancelled();val n=input.read(buffer);if(n<0)break;if(n>0)check.update(buffer,0,n)}
            }
            val copied=audioDigest.digest().joinToString("") {u(it).toString(16).padStart(2,'0')}
            val reread=check.digest().joinToString("") {u(it).toString(16).padStart(2,'0')}
            if(copied!=reread)throw Rejected(Code.AUDIO_MISMATCH)
            return Result(snapshot.format,length,estimated,expectedSourceSha256,shaFile(staging,checkCancelled),copied,changedItem)
        } catch(e: Exception) {
            if(stageCreated)try{Files.deleteIfExists(b)}catch(_:Exception){}
            throw e
        }
    }
}
