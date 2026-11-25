package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * ME-03 conservative ID3v2.3/2.4 staging from a PRIVATE media cache. Preserves
 * unknown frames, APIC, TXXX and audio bytes as raw binary. Does not write any
 * Android provider or grant permission; crash recovery and physical certification
 * are separate and mandatory. Rejects unsync, extended headers, frame flags,
 * encrypted/compressed frames, duplicate targeted frames and existing ID3v1.
 */
object MetadataId3StageRewriter {
    class Rejected(val code: Code) : IOException(code.name)
    enum class Code {
        NOT_READY, NOT_VERIFIED, UNSUPPORTED_FIELD, UNSAFE_CACHE, STAGE_EXISTS,
        INVALID_ID3, ID3V1_CONFLICT, UNSUPPORTED_VERSION_OR_FLAGS,
        BROKEN_FRAME, AMBIGUOUS_OR_STALE_TAGS, TOO_MANY_VALUES, TAG_OVERFLOW,
        STAGING_SPACE_UNAVAILABLE, CACHE_CHANGED, INVALID_NUMBER_PAIR, INVALID_DATE,
    }
    data class Result(
        val format: MetadataAudioContainer,
        val changedFields: Set<MetadataField>,
        val originalBytes: Long,
        val stagedBytes: Long,
        val audioBytesCopied: Long,
        val canCommitToProvider: Boolean = false,
    )

    private const val MAX_FILE = 1024L * 1024 * 1024
    private const val MAX_ID3 = 16 * 1024 * 1024
    private val keys = linkedMapOf(
        MetadataField.TITLE to "TIT2",
        MetadataField.ARTIST to "TPE1",
        MetadataField.ALBUM to "TALB",
        MetadataField.ALBUM_ARTIST to "TPE2",
        MetadataField.GENRE to "TCON",
        MetadataField.COMPOSER to "TCOM",
        MetadataField.SUBTITLE to "TIT3",
        MetadataField.LABEL to "TPUB",
        MetadataField.ISRC to "TSRC",
        MetadataField.BPM to "TBPM",
        MetadataField.MUSICAL_KEY to "TKEY",
        MetadataField.GROUPING to "TIT1",
        MetadataField.COPYRIGHT to "TCOP",
        MetadataField.LANGUAGE to "TLAN",
        MetadataField.ORIGINAL_ARTIST to "TOPE",
        MetadataField.LYRICIST to "TEXT",
        MetadataField.CONDUCTOR to "TPE3",
        MetadataField.REMIXER to "TPE4",
    )
    private val dateFrames = mapOf(3 to "TYER", 4 to "TDRC")
    private data class Composite(
        val number: MetadataField,
        val total: MetadataField,
        val frameId: String,
    )
    private val composites = listOf(
        Composite(MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL, "TRCK"),
        Composite(MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL, "TPOS"),
    )
    private val compositeFields =
        composites.flatMap { listOf(it.number, it.total) }.toSet()
    private val compositeByFrame = composites.associateBy { it.frameId }
    /** The stage writer owns the authoritative single-frame mapping. Other frame kinds require a richer audit. */
    fun preservationFrameId(field: MetadataField, version: Int? = null): String? =
        when {
            field == MetadataField.RELEASE_DATE -> version?.let(dateFrames::get)
            field in compositeFields ->
                composites.first { field == it.number || field == it.total }.frameId
            else -> keys[field]
        }
    private data class Frame(val id: String, val raw: ByteArray, val payload: ByteArray)
    private fun u(b: Byte) = b.toInt() and 255
    private fun big(bytes: ByteArray, at: Int) =
        (u(bytes[at]) shl 24) or (u(bytes[at + 1]) shl 16) or
        (u(bytes[at + 2]) shl 8) or u(bytes[at + 3])
    private fun sync(bytes: ByteArray, at: Int): Int {
        if ((0..3).any { u(bytes[at+it]) >= 128 }) throw Rejected(Code.BROKEN_FRAME)
        return (0..3).fold(0) { n, i -> (n shl 7) or u(bytes[at+i]) }
    }
    private fun syncBytes(n: Int): ByteArray {
        if (n < 0 || n >= (1 shl 28)) throw Rejected(Code.TAG_OVERFLOW)
        return byteArrayOf(((n ushr 21) and 127).toByte(), ((n ushr 14) and 127).toByte(),
            ((n ushr 7) and 127).toByte(), (n and 127).toByte())
    }
    private fun bigBytes(n: Int) = byteArrayOf((n ushr 24).toByte(),
        (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    private fun textValues(frame: Frame, version: Int): List<String> =
        MetadataId3TextListCodec.decode(version, frame.payload)
            ?: throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)

    private val canonicalNumber = Regex("[1-9][0-9]{0,3}")
    private fun checkedNumber(values: List<String>): String? {
        if (values.isEmpty()) return null
        val value = values.singleOrNull() ?: throw Rejected(Code.INVALID_NUMBER_PAIR)
        if (!canonicalNumber.matches(value) || value.toInt() !in 1..9999)
            throw Rejected(Code.INVALID_NUMBER_PAIR)
        return value
    }
    private fun checkedDate(version: Int, values: List<String>): String? {
        if (values.isEmpty()) return null
        val value = values.singleOrNull() ?: throw Rejected(Code.INVALID_DATE)
        val pattern = if (version == 3) Regex("[0-9]{4}")
            else Regex("[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?")
        if (!pattern.matches(value)) throw Rejected(Code.INVALID_DATE)
        return value
    }
    private fun parsePair(frame: Frame?, version: Int): Pair<String?, String?> {
        if (frame == null) return null to null
        val values = textValues(frame, version)
        if (values.size != 1) throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
        val parts = values.single().split('/')
        if (parts.size !in 1..2 || parts.any { it.isEmpty() })
            throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
        fun part(raw: String): String {
            if (!Regex("[0-9]{1,4}").matches(raw) || raw.toInt() !in 1..9999)
                throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            return raw.toInt().toString()
        }
        return part(parts[0]) to parts.getOrNull(1)?.let(::part)
    }

    private fun encodedFrame(version: Int, id: String, values: List<String>): Frame {
        val bytes = MetadataId3TextListCodec.encode(version, values)
            ?: throw Rejected(Code.TOO_MANY_VALUES)
        if (bytes.size > MAX_ID3) throw Rejected(Code.TAG_OVERFLOW)
        val frame=ByteArrayOutputStream()
        frame.write(id.toByteArray(StandardCharsets.US_ASCII))
        frame.write(if (version == 3) bigBytes(bytes.size) else syncBytes(bytes.size))
        frame.write(0); frame.write(0); frame.write(bytes)
        return Frame(id, frame.toByteArray(), bytes)
    }

    private fun describedFrame(version: Int, field: MetadataField, value: String): Frame {
        val kind = MetadataId3DescribedTagCodec.Kind.entries.single { it.field == field }
        val body = MetadataId3DescribedTagCodec.encodeCanonical(kind, version, value)
        val out = ByteArrayOutputStream()
        out.write(kind.frameId.toByteArray(StandardCharsets.US_ASCII))
        out.write(if (version == 3) bigBytes(body.size) else syncBytes(body.size))
        out.write(0); out.write(0); out.write(body)
        return Frame(kind.frameId, out.toByteArray(), body)
    }

    private fun userTextFrame(version: Int, field: MetadataField, value: String): Frame {
        val body = MetadataMusicBrainzTagMap.encodeId3(version, field, value)
        val out = ByteArrayOutputStream()
        out.write("TXXX".toByteArray(StandardCharsets.US_ASCII))
        out.write(if (version == 3) bigBytes(body.size) else syncBytes(body.size))
        out.write(0); out.write(0); out.write(body)
        return Frame("TXXX", out.toByteArray(), body)
    }

    private fun readFrames(bytes: ByteArray, version: Int): List<Frame> {
        val frames = mutableListOf<Frame>()
        var pos=0
        while (pos < bytes.size) {
            if (bytes.size-pos < 10) {
                if (bytes.copyOfRange(pos,bytes.size).any { it != 0.toByte() }) throw Rejected(Code.BROKEN_FRAME)
                break
            }
            if ((0..3).all { bytes[pos+it] == 0.toByte() }) {
                if (bytes.copyOfRange(pos,bytes.size).any { it != 0.toByte() }) throw Rejected(Code.BROKEN_FRAME)
                break
            }
            val id = String(bytes,pos,4,StandardCharsets.US_ASCII)
            if (!id.matches(Regex("[A-Z0-9]{4}"))) throw Rejected(Code.BROKEN_FRAME)
            val size=if(version==3) big(bytes,pos+4) else sync(bytes,pos+4)
            if (size <= 0 || pos.toLong()+10+size>bytes.size ||
                bytes[pos+8]!=0.toByte() || bytes[pos+9]!=0.toByte())
                throw Rejected(Code.BROKEN_FRAME)
            val next=pos+10+size
            frames += Frame(id,bytes.copyOfRange(pos,next),bytes.copyOfRange(pos+10,next))
            pos=next
        }
        return frames
    }

    fun stage(sourceCache: File, staging: File, review: MetadataReview,
              expectedCacheBytes: Long, checkCancelled: () -> Unit = {}): Result {
        checkCancelled()
        val ready = review as? MetadataReview.Ready ?: throw Rejected(Code.NOT_READY)
        if (MetadataReviewIntegrityPolicy.inspect(ready) !=
            MetadataReviewIntegrityPolicy.Verdict.Exact) throw Rejected(Code.NOT_VERIFIED)
        if (!ready.original.tagsVerified || ready.diffs.isEmpty() ||
            ready.diffs.any { it.field !in ready.original.verifiedFields })
            throw Rejected(Code.NOT_VERIFIED)
        if (ready.diffs.any { it.field !in keys && it.field !in MetadataMusicBrainzTagMap.id3Fields &&
            it.field !in MetadataId3DescribedTagCodec.fields &&
            it.field != MetadataField.RELEASE_DATE && it.field !in compositeFields })
            throw Rejected(Code.UNSUPPORTED_FIELD)
        if (ready.diffs.any { it.after.size > 1 &&
            (it.field !in keys || !it.field.multiple) }) throw Rejected(Code.TOO_MANY_VALUES)
        if (expectedCacheBytes !in 12..MAX_FILE || !sourceCache.isFile ||
            sourceCache.length()!=expectedCacheBytes ||
            Files.isSymbolicLink(sourceCache.toPath()) || Files.isSymbolicLink(staging.toPath()) ||
            sourceCache.canonicalPath == staging.canonicalPath)
            throw Rejected(Code.UNSAFE_CACHE)
        if (staging.exists()) throw Rejected(Code.STAGE_EXISTS)
        val originalSize=sourceCache.length()
        val originalModified=sourceCache.lastModified()
        // A pre-existing ID3v1 footer is preserved byte-for-byte in the audio tail.
        // ID3v2 remains the authoritative edited tag; the certified server profile
        // documents this coexistence instead of silently deleting the legacy footer.
        val header=ByteArray(10)
        FileInputStream(sourceCache).use { input ->
            if(input.read(header)!=10) throw Rejected(Code.INVALID_ID3)
        }
        if(String(header,0,3,StandardCharsets.US_ASCII)!="ID3") throw Rejected(Code.INVALID_ID3)
        val version=u(header[3])
        if(version !in 3..4 || u(header[5])!=0 || u(header[4])!=0)
            throw Rejected(Code.UNSUPPORTED_VERSION_OR_FLAGS)
        val tagSize=sync(header,6)
        if(tagSize>MAX_ID3 || 10L+tagSize>originalSize) throw Rejected(Code.INVALID_ID3)
        val bytes=ByteArray(tagSize)
        FileInputStream(sourceCache).use { input ->
            var skip=10L
            while(skip>0){checkCancelled();val n=input.skip(skip);if(n<=0)throw Rejected(Code.INVALID_ID3);skip-=n}
            var at=0
            while(at<bytes.size){checkCancelled();val n=input.read(bytes,at,bytes.size-at);if(n<=0)throw Rejected(Code.INVALID_ID3);at+=n}
        }
        val inputFrames=readFrames(bytes,version)
        if (version == 3 && ready.diffs.any { it.after.size > 1 })
            throw Rejected(Code.TOO_MANY_VALUES)
        val changes=ready.diffs.associateBy { it.field }
        val compositeBefore = mutableMapOf<Composite, Pair<String?, String?>>()
        for (composite in composites) {
            if (composite.number !in changes && composite.total !in changes) continue
            val frames = inputFrames.filter { it.id == composite.frameId }
            if (frames.size > 1) throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            val parsed = parsePair(frames.singleOrNull(), version)
            if (listOfNotNull(parsed.first) !=
                    ready.original.known[composite.number].orEmpty() ||
                listOfNotNull(parsed.second) !=
                    ready.original.known[composite.total].orEmpty())
                throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            changes[composite.number]?.let { diff ->
                if (diff.before != listOfNotNull(parsed.first))
                    throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            }
            changes[composite.total]?.let { diff ->
                if (diff.before != listOfNotNull(parsed.second))
                    throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            }
            val number = checkedNumber(
                changes[composite.number]?.after ?: listOfNotNull(parsed.first))
            val total = checkedNumber(
                changes[composite.total]?.after ?: listOfNotNull(parsed.second))
            if (number == null && total != null)
                throw Rejected(Code.INVALID_NUMBER_PAIR)
            compositeBefore[composite] = parsed
        }
        if (MetadataField.RELEASE_DATE in changes) {
            val frames = inputFrames.filter { it.id == dateFrames.getValue(version) }
            if (frames.size > 1) throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            val current = frames.singleOrNull()?.let { textValues(it, version) }.orEmpty()
            val diff = changes.getValue(MetadataField.RELEASE_DATE)
            if (current != diff.before ||
                current != ready.original.known[MetadataField.RELEASE_DATE].orEmpty())
                throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            checkedDate(version, diff.after)
        }
        val newFrames=mutableListOf<Frame>()
        val emitted=mutableSetOf<MetadataField>()
        val owner=keys.entries.associate { (field,key) -> key to field }
        // If targeting TXXX, every TXXX descriptor must be readable to prove
        // that an undecodable alias is not a second instance of the same ID.
        if (MetadataField.MUSICBRAINZ_RECORDING_ID in changes && inputFrames.any {
                it.id == "UFID" && MetadataMusicBrainzTagMap.ufidMayBeMusicBrainz(it.payload) != false
            }) throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
        val userText = if (changes.keys.any { it in MetadataMusicBrainzTagMap.id3Fields }) {
            inputFrames.filter { it.id == "TXXX" }.associateWith { frame ->
                MetadataMusicBrainzTagMap.decodeId3(frame.payload, version)
                    ?: throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            }
        } else emptyMap()
        val describedKinds = MetadataId3DescribedTagCodec.Kind.entries
            .filter { it.field in changes }.associateBy { it.frameId }
        val describedFrames = inputFrames.filter { it.id in describedKinds }.associateWith { frame ->
            val kind = describedKinds.getValue(frame.id)
            val entry = MetadataId3DescribedTagCodec.decode(kind, version, frame.payload)
                ?: throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            if (!entry.canonical) throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            entry
        }
        fun selectedField(frame: Frame): MetadataField? = when (frame.id) {
            "COMM", "USLT" -> describedKinds[frame.id]?.field
            "TXXX" -> userText[frame]?.let {
                MetadataMusicBrainzTagMap.fromId3Description(it.description)
            }
            else -> owner[frame.id]
        }
        for(diff in ready.diffs){
            if (diff.field == MetadataField.RELEASE_DATE ||
                diff.field in compositeFields) continue
            val mbid = diff.field in MetadataMusicBrainzTagMap.id3Fields
            val described = diff.field in MetadataId3DescribedTagCodec.fields
            val current = if (described) {
                inputFrames.filter { selectedField(it) == diff.field }
                    .map { describedFrames.getValue(it).text }
            } else if (mbid) {
                if (!MetadataMusicBrainzTagMap.valid(diff.field, diff.after))
                    throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
                inputFrames.filter { it.id == "TXXX" && selectedField(it) == diff.field }
                    .map { userText.getValue(it).value }
            } else {
                val matches = inputFrames.filter { it.id == keys.getValue(diff.field) }
                if (matches.size > 1) throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
                matches.singleOrNull()?.let { textValues(it, version) }.orEmpty()
            }
            if (mbid && !MetadataMusicBrainzTagMap.valid(diff.field, current))
                throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            if(current!=diff.before || current!=ready.original.known[diff.field].orEmpty())
                throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
        }
        fun emit(field: MetadataField){
            if(field in compositeFields)return
            if(!emitted.add(field))return
            val diff=changes[field] ?: return
            if(diff.after.isNotEmpty()) newFrames += when {
                field == MetadataField.RELEASE_DATE ->
                    encodedFrame(version, dateFrames.getValue(version),
                        listOf(checkedDate(version, diff.after)!!))
                field in MetadataId3DescribedTagCodec.fields -> describedFrame(version,field,diff.after.single())
                field in MetadataMusicBrainzTagMap.id3Fields -> userTextFrame(version,field,diff.after.single())
                else -> encodedFrame(version, keys.getValue(field), diff.after)
            }
        }
        val emittedComposites = mutableSetOf<String>()
        fun emitComposite(composite: Composite){
            if(!emittedComposites.add(composite.frameId))return
            val before = compositeBefore.getValue(composite)
            val number = checkedNumber(
                changes[composite.number]?.after ?: listOfNotNull(before.first))
            val total = checkedNumber(
                changes[composite.total]?.after ?: listOfNotNull(before.second))
            if(number == null && total != null) throw Rejected(Code.INVALID_NUMBER_PAIR)
            if(number == null) return
            newFrames += encodedFrame(version, composite.frameId,
                listOf(if(total == null) number else "$number/$total"))
        }
        for(frame in inputFrames){
            checkCancelled()
            val composite = compositeByFrame[frame.id]
            if (composite != null &&
                (composite.number in changes || composite.total in changes)) {
                emitComposite(composite)
                continue
            }
            if (frame.id == dateFrames[version] &&
                MetadataField.RELEASE_DATE in changes) {
                emit(MetadataField.RELEASE_DATE)
                continue
            }
            val field=selectedField(frame)
            if(field!=null && field in changes)emit(field) else newFrames+=frame
        }
        for(field in changes.keys)emit(field)
        for(composite in composites)
            if(composite.number in changes || composite.total in changes)
                emitComposite(composite)
        val replacement=ByteArrayOutputStream()
        newFrames.forEach{replacement.write(it.raw)}
        if(replacement.size()>MAX_ID3)throw Rejected(Code.TAG_OVERFLOW)
        val newHeader=header.copyOfRange(0,6)+syncBytes(replacement.size())
        val outputEstimated=originalSize-tagSize+replacement.size()
        if(staging.parentFile?.usableSpace?.let { it >= outputEstimated+32L*1024*1024 } !=true)
            throw Rejected(Code.STAGING_SPACE_UNAVAILABLE)
        var created=false
        try{
            FileChannel.open(staging.toPath(),StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { channel ->
                created=true
                val output=Channels.newOutputStream(channel)
                output.write(newHeader)
                output.write(replacement.toByteArray())
                FileInputStream(sourceCache).use { input ->
                    var remaining=10L+tagSize
                    while(remaining>0){checkCancelled();val n=input.skip(remaining);if(n<=0)throw Rejected(Code.CACHE_CHANGED);remaining-=n}
                    val buffer=ByteArray(64*1024)
                    while(true){checkCancelled();val n=input.read(buffer);if(n<0)break;if(n>0)output.write(buffer,0,n)}
                }
                channel.force(true)
            }
            checkCancelled()
            if(sourceCache.length()!=originalSize || sourceCache.lastModified()!=originalModified)
                throw Rejected(Code.CACHE_CHANGED)
            return Result(if(version==3)MetadataAudioContainer.ID3_V23 else MetadataAudioContainer.ID3_V24,
                ready.diffs.mapTo(linkedSetOf()){it.field},originalSize,staging.length(),
                originalSize-10L-tagSize)
        }catch(e:Exception){if(created)staging.delete();throw e}
    }
}
