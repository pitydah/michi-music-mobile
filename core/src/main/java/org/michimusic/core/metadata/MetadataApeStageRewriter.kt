package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale

/**
 * ME-11F: APEv2 **private candidate** writer for existing footer-only tags in
 * WavPack/Monkey's Audio. It copies the source audio prefix and optional ID3v1
 * trailer byte-for-byte, preserving every opaque APE item unmodified.
 * It never receives a ContentResolver/URI, never mutates an original, and grants
 * NO permission to commit. Unsupported APE headers/flags are refused.
 */
object MetadataApeStageRewriter {
    enum class Code {
        INVALID_REVIEW, UNSAFE_PRIVATE_SOURCE, SOURCE_CHANGED, ALREADY_EXISTS,
        UNSUPPORTED_FORMAT, UNSUPPORTED_TAG_LAYOUT, INVALID_ITEMS,
        AMBIGUOUS_FIELD, UNVERIFIED_FIELD, INVALID_VALUE, RESOURCE_LIMIT,
        PRIVATE_READBACK_FAILED,
    }
    class Rejected(val code: Code): IOException(code.name)
    data class Result(val container: MetadataAudioContainer, val originalBytes: Long,
        val candidateBytes: Long, val audioBytesPreserved: Long,
        val editedFields: Set<MetadataField>, val canCommitToProvider: Boolean = false)

    private const val MAX_FILE = 1024L * 1024L * 1024L
    private const val MAX_TAG = 4 * 1024 * 1024
    private const val MAX_ITEMS = 4096
    private fun u(b: Byte) = b.toInt() and 255
    private fun le(b: ByteArray, at: Int): Long = u(b[at]).toLong() or
        (u(b[at+1]).toLong() shl 8) or (u(b[at+2]).toLong() shl 16) or
        (u(b[at+3]).toLong() shl 24)
    private fun putLe(b: ByteArray, at: Int, number: Int) {
        for (i in 0..3) b[at+i] = (number ushr (8*i)).toByte()
    }
    private fun read(channel: FileChannel, at: Long, size: Int): ByteArray {
        if (at < 0L || size < 0 || size > MAX_TAG ||
            at > channel.size() || size.toLong() > channel.size()-at)
            throw Rejected(Code.UNSUPPORTED_TAG_LAYOUT)
        val buf=ByteBuffer.allocate(size)
        var position=at
        while (buf.hasRemaining()) {
            val n=channel.read(buf,position)
            if (n<=0) throw Rejected(Code.SOURCE_CHANGED)
            position+=n
        }
        return buf.array()
    }
    private data class Item(val key: String, val raw: ByteArray, val flags: Long)
    private data class Footer(val start: Long, val end: Long,
        val original: ByteArray, val items: List<Item>, val id3v1: ByteArray?)

    private fun footer(channel: FileChannel): Footer {
        val size=channel.size()
        if (size !in 64L..MAX_FILE) throw Rejected(Code.RESOURCE_LIMIT)
        val id3v1 = if (size >= 128L &&
            String(read(channel,size-128L,3),StandardCharsets.US_ASCII)=="TAG")
            read(channel,size-128L,128) else null
        val end=size-(id3v1?.size ?: 0)
        val tail=read(channel,end-32L,32)
        if (String(tail,0,8,StandardCharsets.US_ASCII)!="APETAGEX" || le(tail,8)!=2000L ||
            tail.sliceArray(24..31).any { it!=0.toByte() })
            throw Rejected(Code.UNSUPPORTED_TAG_LAYOUT)
        val bytes=le(tail,12); val count=le(tail,16); val flags=le(tail,20)
        // Header-present and other unsupported footer variants are deliberately rejected.
        if (bytes !in 32L..MAX_TAG.toLong() || count>MAX_ITEMS ||
            flags !in listOf(0L,0x40000000L) || end-bytes<4L)
            throw Rejected(Code.UNSUPPORTED_TAG_LAYOUT)
        val body=read(channel,end-bytes,(bytes-32L).toInt())
        val items=ArrayList<Item>()
        var at=0
        repeat(count.toInt()) {
            if (at+8>body.size) throw Rejected(Code.INVALID_ITEMS)
            val valueLength=le(body,at)
            val itemFlags=le(body,at+4)
            val keyStart=at+8
            var keyEnd=keyStart
            while (keyEnd<body.size && keyEnd-keyStart<=255 && body[keyEnd]!=0.toByte()) keyEnd++
            if (keyEnd<=keyStart || keyEnd>=body.size || keyEnd-keyStart>255 ||
                (keyStart until keyEnd).any { u(body[it]) !in 0x20..0x7e } ||
                valueLength>body.size.toLong()-keyEnd-1L)
                throw Rejected(Code.INVALID_ITEMS)
            val key=String(body,keyStart,keyEnd-keyStart,StandardCharsets.US_ASCII)
            val stop=(keyEnd+1L+valueLength).toInt()
            items+=Item(key,body.copyOfRange(at,stop),itemFlags)
            at=stop
        }
        if (at!=body.size) throw Rejected(Code.INVALID_ITEMS)
        return Footer(end-bytes,end,tail,items,id3v1)
    }

    private val supported = setOf(MetadataField.TITLE,MetadataField.ARTIST,
        MetadataField.ALBUM,MetadataField.ALBUM_ARTIST,MetadataField.GENRE,
        MetadataField.COMPOSER,MetadataField.RELEASE_DATE,
        MetadataField.TRACK_NUMBER,MetadataField.DISC_NUMBER,MetadataField.LABEL,
        MetadataField.ISRC,MetadataField.BPM)

    fun supports(field: MetadataField): Boolean = field in supported &&
        MetadataFlacStageRewriter.preservationAliases(field).isNotEmpty()

    private fun owner(key:String):MetadataField? {
        val normalized=key.uppercase(Locale.ROOT)
        return supported.firstOrNull { field ->
            normalized in MetadataFlacStageRewriter.preservationAliases(field)
        }
    }
    private fun encodeText(values: List<String>): ByteArray {
        if (values.isEmpty() || values.size>32 || values.any { it.isBlank() ||
            '\u0000' in it || it.any { c -> c.isSurrogate() } })
            throw Rejected(Code.INVALID_VALUE)
        return try {
            val encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(values.joinToString("\u0000")))
            val bytes=ByteArray(encoded.remaining())
            encoded.get(bytes)
            bytes
        } catch (_:Exception) { throw Rejected(Code.INVALID_VALUE) }
    }
    private fun newItem(key:String,values:List<String>):Item {
        if (key.length !in 1..255 || key.any { it.code !in 0x20..0x7e })
            throw Rejected(Code.INVALID_VALUE)
        val text=encodeText(values)
        val out=ByteArrayOutputStream()
        val header=ByteArray(8)
        putLe(header,0,text.size)
        out.write(header)
        out.write(key.toByteArray(StandardCharsets.US_ASCII))
        out.write(0)
        out.write(text)
        return Item(key,out.toByteArray(),0L)
    }
    private fun copyPrefix(source:FileChannel,out:FileChannel,bytes:Long) {
        val buffer=ByteBuffer.allocate(64*1024)
        var at=0L
        while (at<bytes) {
            buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(),bytes-at).toInt())
            val read=source.read(buffer,at)
            if (read<=0) throw Rejected(Code.SOURCE_CHANGED)
            buffer.flip()
            while(buffer.hasRemaining()) out.write(buffer)
            at+=read
        }
    }
    private fun digest(channel:FileChannel,bytes:Long):ByteArray {
        val hash=MessageDigest.getInstance("SHA-256")
        val buffer=ByteBuffer.allocate(64*1024)
        var offset=0L
        while(offset<bytes){
            buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(),bytes-offset).toInt())
            val n=channel.read(buffer,offset)
            if(n<=0)throw Rejected(Code.SOURCE_CHANGED)
            hash.update(buffer.array(),0,n)
            offset+=n
        }
        return hash.digest()
    }

    /** Both files MUST be private siblings owned by the journal; output must not exist. */
    fun stage(backup:File,stage:File,review:MetadataReview.Ready,expectedBytes:Long):Result {
        if (MetadataReviewIntegrityPolicy.inspect(review)!=MetadataReviewIntegrityPolicy.Verdict.Exact)
            throw Rejected(Code.INVALID_REVIEW)
        if (expectedBytes !in 64L..MAX_FILE || backup==stage ||
            Files.isSymbolicLink(backup.toPath()) || Files.isSymbolicLink(stage.toPath()) ||
            backup.parentFile?.canonicalFile!=stage.parentFile?.canonicalFile ||
            !Files.isRegularFile(backup.toPath(),LinkOption.NOFOLLOW_LINKS) || stage.exists())
            throw Rejected(Code.UNSAFE_PRIVATE_SOURCE)
        var created=false
        try {
            FileChannel.open(backup.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use { src ->
                if(src.size()!=expectedBytes)throw Rejected(Code.SOURCE_CHANGED)
                val format=when(String(read(src,0,4),StandardCharsets.US_ASCII)) {
                    "wvpk" -> MetadataAudioContainer.WAVPACK
                    "MAC " -> MetadataAudioContainer.APE
                    else -> throw Rejected(Code.UNSUPPORTED_FORMAT)
                }
                val tail=footer(src)
                val pre=MetadataApeColdReader.observe(src)
                if (pre.container.name!=format.name) throw Rejected(Code.UNVERIFIED_FIELD)
                val changes=review.diffs.associateBy { it.field }
                if (changes.keys.any { !supports(it) }) throw Rejected(Code.UNSUPPORTED_FORMAT)
                for ((field,diff) in changes) {
                    if(diff.after.size>1 && !field.multiple) throw Rejected(Code.INVALID_VALUE)
                    val observed=pre.observation(field)
                    val actual=when(observed){
                        is MetadataFieldObservation.Present -> observed.values
                        MetadataFieldObservation.VerifiedAbsent -> emptyList()
                        else -> throw Rejected(Code.UNVERIFIED_FIELD)
                    }
                    if(actual!=diff.before) throw Rejected(Code.SOURCE_CHANGED)
                }
                // Only one physical item may own any edited field: aliases cannot be
                // silently merged or dropped. Unknown items keep exact raw bytes.
                for(field in changes.keys) if(tail.items.count { owner(it.key)==field }>1)
                    throw Rejected(Code.AMBIGUOUS_FIELD)
                val rebuilt=ArrayList<Item>(tail.items.size+changes.size)
                val replaced=mutableSetOf<MetadataField>()
                for(item in tail.items) {
                    val field=owner(item.key)
                    if(field==null || field !in changes) {rebuilt+=item;continue}
                    if ((item.flags and 0x6L)!=0L || (item.flags and 0xfffffff8L)!=0L)
                        throw Rejected(Code.AMBIGUOUS_FIELD)
                    if(replaced.add(field) && changes.getValue(field).after.isNotEmpty())
                        rebuilt+=newItem(item.key,changes.getValue(field).after)
                }
                for((field,diff) in changes) if(field !in replaced && diff.after.isNotEmpty()) {
                    val canonical=MetadataFlacStageRewriter.preservationAliases(field).first()
                    rebuilt+=newItem(canonical,diff.after)
                }
                if(rebuilt.size>MAX_ITEMS)throw Rejected(Code.RESOURCE_LIMIT)
                val body=ByteArrayOutputStream()
                for(item in rebuilt){
                    if (body.size().toLong()+item.raw.size+32L>MAX_TAG)
                        throw Rejected(Code.RESOURCE_LIMIT)
                    body.write(item.raw)
                }
                val footer=tail.original.clone()
                putLe(footer,12,body.size()+32)
                putLe(footer,16,rebuilt.size)
                FileChannel.open(stage.toPath(),StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { out ->
                    created=true
                    copyPrefix(src,out,tail.start)
                    fun put(bytes: ByteArray) {
                        val buffer=ByteBuffer.wrap(bytes)
                        while(buffer.hasRemaining()) {
                            if(out.write(buffer)<=0) throw Rejected(Code.SOURCE_CHANGED)
                        }
                    }
                    put(body.toByteArray())
                    put(footer)
                    tail.id3v1?.let(::put)
                    out.force(true)
                }
                if(src.size()!=expectedBytes)throw Rejected(Code.SOURCE_CHANGED)
                FileChannel.open(stage.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use { staged ->
                    if(!digest(src,tail.start).contentEquals(digest(staged,tail.start)))
                        throw Rejected(Code.PRIVATE_READBACK_FAILED)
                    val parsed=MetadataApeColdReader.observe(staged)
                    if(parsed.container!=pre.container)throw Rejected(Code.PRIVATE_READBACK_FAILED)
                    for((field,diff) in changes){
                        val actual=when(val obs=parsed.observation(field)){
                            is MetadataFieldObservation.Present -> obs.values
                            MetadataFieldObservation.VerifiedAbsent -> emptyList()
                            else -> throw Rejected(Code.PRIVATE_READBACK_FAILED)
                        }
                        if(actual!=diff.after)throw Rejected(Code.PRIVATE_READBACK_FAILED)
                    }
                    // An optional legacy tag is always copied unmodified.
                    val trailer=tail.id3v1
                    if(trailer!=null && !read(staged,staged.size()-128L,128).contentEquals(trailer))
                        throw Rejected(Code.PRIVATE_READBACK_FAILED)
                    return Result(format,expectedBytes,staged.size(),tail.start,changes.keys)
                }
            }
        } catch (error:Exception) {
            if (created) stage.delete()
            throw error
        }
    }
}
