package org.michimusic.core.metadata

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * ME-10H: one reviewed PRIVATE candidate from a verified field diff plus bounded
 * artwork / described-text edits. Intermediate copies exist only in a caller-owned
 * private directory. No provider IO, no Room, no permission elevation, no COMMIT.
 * The Android owner must serialize use of this directory and verify app-private scope.
 */
object MetadataPrivateBundleStage {
    private const val MAX_BUNDLE_BYTES = 256L * 1024 * 1024
    private const val MAX_METADATA_BYTES = MetadataArtworkLyricsProbe.MAX_TAG_BYTES
    private const val MAX_ASSETS = 4
    private const val CHUNK = 64 * 1024
    private val digestPattern = Regex("[a-f0-9]{64}")

    enum class Reason {
        UNSAFE_SOURCE, DESTINATION_EXISTS, INVALID_DIGEST, SOURCE_CHANGED, EMPTY_BUNDLE,
        TOO_MANY_OPERATIONS, TOO_LARGE, FORMAT_UNSUPPORTED, ORIGINAL_OBSERVATION_CHANGED,
        DUPLICATE_TARGET, CONFLICTING_TEXT_FIELDS, INVALID_REVIEW, INVALID_ASSET,
        STAGING_FAILED, AUDIO_CHANGED, FINAL_OBSERVATION_MISMATCH,
    }
    class Rejected(val reason: Reason) : IllegalStateException(reason.name)
    data class Request(
        val originalDigest: String,
        val observed: MetadataArtworkLyricsProbe.Snapshot,
        val fields: MetadataReview.Ready? = null,
        val assets: List<MetadataSelectiveAssetStage.Edit> = emptyList(),
    )
    data class Receipt(
        val sourceSha256: String,
        val candidateSha256: String,
        val audioSha256: String,
        val candidateBytes: Long,
        val changes: List<String>,
        val canCommitToProvider: Boolean = false,
    )
    private data class Region(val metadata: ByteArray, val audioOffset: Long)
    private fun u(byte: Byte) = byte.toInt() and 255
    private fun sha(): MessageDigest = MessageDigest.getInstance("SHA-256")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { u(it).toString(16).padStart(2,'0') }
    private fun shaFile(file: File, checkCancelled: () -> Unit = {}): String {
        val md=sha()
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer=ByteArray(CHUNK)
            while(true) { checkCancelled(); val n=input.read(buffer); if(n<0)break; if(n>0)md.update(buffer,0,n) }
        }
        return hex(md.digest())
    }
    private fun metadataRegion(file: File): Region = RandomAccessFile(file,"r").use { raf ->
        if(raf.length() !in 20..MAX_BUNDLE_BYTES)throw Rejected(Reason.TOO_LARGE)
        val header=ByteArray(10);raf.readFully(header)
        if(header.copyOfRange(0,3).contentEquals("ID3".toByteArray())) {
            val length=MetadataArtworkLyricsProbe.id3DeclaredLength(header)
                ?:throw Rejected(Reason.FORMAT_UNSUPPORTED)
            if(length>=raf.length() || length>MAX_METADATA_BYTES)
                throw Rejected(Reason.FORMAT_UNSUPPORTED)
            val bytes=ByteArray(length);raf.seek(0);raf.readFully(bytes)
            Region(bytes,length.toLong())
        } else if(header.copyOfRange(0,4).contentEquals("fLaC".toByteArray())) {
            var at=4L;var count=0;var last=false
            while(!last) {
                if(++count>128 || at+4>MAX_METADATA_BYTES || at+4>raf.length())
                    throw Rejected(Reason.FORMAT_UNSUPPORTED)
                raf.seek(at);val h=ByteArray(4);raf.readFully(h)
                val kind=u(h[0]) and 127
                val length=(u(h[1]) shl 16) or (u(h[2]) shl 8) or u(h[3])
                if(count==1 && (kind!=0 || length!=34))throw Rejected(Reason.FORMAT_UNSUPPORTED)
                at+=4+length
                if(at>MAX_METADATA_BYTES || at>=raf.length())throw Rejected(Reason.FORMAT_UNSUPPORTED)
                last=(u(h[0]) and 128)!=0
            }
            val bytes=ByteArray(at.toInt());raf.seek(0);raf.readFully(bytes)
            Region(bytes,at)
        } else throw Rejected(Reason.FORMAT_UNSUPPORTED)
    }
    private fun metadataRegion(channel: FileChannel): Region {
        val size = channel.size()
        if (size !in 20..MAX_BUNDLE_BYTES) throw Rejected(Reason.TOO_LARGE)
        fun readExact(at: Long, bytes: ByteArray) {
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            var position = at
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read <= 0) throw Rejected(Reason.FORMAT_UNSUPPORTED)
                position += read
            }
        }
        val header = ByteArray(10)
        readExact(0, header)
        if (header.copyOfRange(0, 3).contentEquals("ID3".toByteArray())) {
            val length = MetadataArtworkLyricsProbe.id3DeclaredLength(header)
                ?: throw Rejected(Reason.FORMAT_UNSUPPORTED)
            if (length >= size || length > MAX_METADATA_BYTES)
                throw Rejected(Reason.FORMAT_UNSUPPORTED)
            return Region(ByteArray(length).also { readExact(0, it) }, length.toLong())
        }
        if (header.copyOfRange(0, 4).contentEquals("fLaC".toByteArray())) {
            var at = 4L
            var count = 0
            var last = false
            while (!last) {
                if (++count > 128 || at + 4 > MAX_METADATA_BYTES || at + 4 > size)
                    throw Rejected(Reason.FORMAT_UNSUPPORTED)
                val block = ByteArray(4)
                readExact(at, block)
                val kind = u(block[0]) and 127
                val length = (u(block[1]) shl 16) or (u(block[2]) shl 8) or u(block[3])
                if (count == 1 && (kind != 0 || length != 34))
                    throw Rejected(Reason.FORMAT_UNSUPPORTED)
                at += 4 + length
                if (at > MAX_METADATA_BYTES || at >= size)
                    throw Rejected(Reason.FORMAT_UNSUPPORTED)
                last = (u(block[0]) and 128) != 0
            }
            return Region(ByteArray(at.toInt()).also { readExact(0, it) }, at)
        }
        throw Rejected(Reason.FORMAT_UNSUPPORTED)
    }
    private fun observe(file: File): Pair<MetadataArtworkLyricsProbe.Snapshot,Long> {
        val region=metadataRegion(file)
        val result=MetadataArtworkLyricsProbe.inspect(region.metadata)
            as? MetadataArtworkLyricsProbe.Result.Ready
            ?:throw Rejected(Reason.FORMAT_UNSUPPORTED)
        return result.snapshot to region.audioOffset
    }
    /**
     * Structurally observes the bounded metadata region of a local private file.
     *
     * This exposes neither the audio offset nor staging internals. Callers use
     * the returned snapshot to select a format capability before constructing a
     * candidate; this method never writes the source.
     */
    fun observeFile(file: File): MetadataArtworkLyricsProbe.Snapshot =
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel -> observeChannel(channel) }
    /** Bounded positional observation from an already-open read-only descriptor. */
    fun observeChannel(channel: FileChannel): MetadataArtworkLyricsProbe.Snapshot {
        if (isMp4(channel)) return observeMp4(channel)
        val result = MetadataArtworkLyricsProbe.inspect(metadataRegion(channel).metadata)
            as? MetadataArtworkLyricsProbe.Result.Ready
            ?: throw Rejected(Reason.FORMAT_UNSUPPORTED)
        return result.snapshot
    }
    /**
     * M4A artwork lives in `moov/udta/meta/ilst/covr`, which a header prefix
     * cannot bound. The positional probe walks the atom tree and skips `mdat`
     * arithmetically, so no audio byte is read as metadata.
     */
    private fun observeMp4(channel: FileChannel): MetadataArtworkLyricsProbe.Snapshot {
        val observation = MetadataArtworkLyricsProbe.inspectChannel(channel)
            as? MetadataArtworkLyricsProbe.ChannelResult.Ready
            ?: throw Rejected(Reason.FORMAT_UNSUPPORTED)
        val snapshot = observation.observation.snapshot
        if (snapshot.format != MetadataArtworkLyricsProbe.Format.MP4)
            throw Rejected(Reason.FORMAT_UNSUPPORTED)
        return snapshot
    }
    /** ISO base media detection: `ftyp` is the first top-level box type. */
    private fun isMp4(channel: FileChannel): Boolean {
        if (channel.size() < 12) return false
        val header = ByteArray(12)
        val buffer = java.nio.ByteBuffer.wrap(header)
        var at = 0L
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read <= 0) return false
            at += read
        }
        return header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
    }
    private fun same(a: MetadataArtworkLyricsProbe.Snapshot,b: MetadataArtworkLyricsProbe.Snapshot):Boolean =
        a.format==b.format && a.texts==b.texts && a.artworks.size==b.artworks.size &&
            a.artworks.zip(b.artworks).all { (x,y) ->
                x.ordinal==y.ordinal && x.info==y.info && x.image.contentEquals(y.image)
            }
    private fun shaAudio(file: File,offset: Long, checkCancelled: () -> Unit = {}):String = RandomAccessFile(file,"r").use { input ->
        input.seek(offset);val md=sha();val buffer=ByteArray(CHUNK)
        while(true){checkCancelled();val n=input.read(buffer);if(n<0)break;if(n>0)md.update(buffer,0,n)}
        hex(md.digest())
    }
    /** Read-only byte and audio fingerprints for an explicitly requested private audit.
     * These prove digest consistency, NOT field correctness or permission to commit. */
    internal data class AuditDigests(
        val bytes: Long, val fullSha256: String, val audioSha256: String,
        val format: MetadataArtworkLyricsProbe.Format,
    )
    internal fun auditDigests(file: File, checkCancelled: () -> Unit = {}): AuditDigests {
        checkCancelled()
        val path=file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path) || file.length() !in 20..MAX_BUNDLE_BYTES)
            throw Rejected(Reason.UNSAFE_SOURCE)
        val (snapshot, offset) = observe(file)
        // One streaming pass for BOTH SHA-256 values (entire file and audio
        // payload beyond the bounded tag region). No large ByteArray copies.
        val full = sha()
        val audio = sha()
        var position = 0L
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val chunk = ByteArray(CHUNK)
            var stalled = 0
            while (true) {
                checkCancelled()
                val n = input.read(chunk)
                if (n < 0) break
                if (n == 0) {
                    if (++stalled > 8) throw Rejected(Reason.STAGING_FAILED)
                    continue
                }
                stalled = 0
                full.update(chunk, 0, n)
                val start = (offset-position).coerceIn(0L,n.toLong()).toInt()
                if (start < n) audio.update(chunk, start, n-start)
                position += n
                if (position > MAX_BUNDLE_BYTES) throw Rejected(Reason.TOO_LARGE)
            }
        }
        if (position != file.length() || position <= offset)
            throw Rejected(Reason.SOURCE_CHANGED)
        checkCancelled()
        return AuditDigests(position, hex(full.digest()), hex(audio.digest()), snapshot.format)
    }

    private fun preflight(observed:MetadataArtworkLyricsProbe.Snapshot,req:Request):List<String> {
        if(req.assets.size>MAX_ASSETS)throw Rejected(Reason.TOO_MANY_OPERATIONS)
        if(req.fields==null && req.assets.isEmpty())throw Rejected(Reason.EMPTY_BUNDLE)
        val names=mutableListOf<String>()
        req.fields?.let { review ->
            val ops=review.diffs.map { diff ->
                if(diff.after.isEmpty())MetadataChange.Clear(diff.field)
                else MetadataChange.Replace(diff.field,diff.after)
            }
            if(ops.isEmpty() || MetadataEditPreview.review(review.original,ops)!=review ||
                !review.original.tagsVerified || review.diffs.size>32)
                throw Rejected(Reason.INVALID_REVIEW)
            names+=review.diffs.map { "FIELD:${it.field.name}" }
        }
        if(observed.format==MetadataArtworkLyricsProbe.Format.FLAC &&
            req.assets.any { it is MetadataSelectiveAssetStage.Edit.DescribedText })
            throw Rejected(Reason.FORMAT_UNSUPPORTED)
        if(req.fields!=null && req.assets.any { it is MetadataSelectiveAssetStage.Edit.DescribedText } &&
            req.fields.diffs.any { it.field in setOf(MetadataField.COMMENT,MetadataField.LYRICS) })
            throw Rejected(Reason.CONFLICTING_TEXT_FIELDS)
        val pictureKeys=req.assets.mapNotNull {
            when(it) {
                is MetadataSelectiveAssetStage.Edit.Picture -> it.proposal.index
                is MetadataSelectiveAssetStage.Edit.PictureAdd -> it.proposal.ordinal
                is MetadataSelectiveAssetStage.Edit.PictureRemove -> it.ordinal
                is MetadataSelectiveAssetStage.Edit.DescribedText -> null
            }
        }
        val textKeys=req.assets.filterIsInstance<MetadataSelectiveAssetStage.Edit.DescribedText>()
            .map { it.change.identity }
        if(pictureKeys.distinct().size!=pictureKeys.size || textKeys.distinct().size!=textKeys.size)
            throw Rejected(Reason.DUPLICATE_TARGET)
        for(op in req.assets) when(op) {
            is MetadataSelectiveAssetStage.Edit.Picture -> {
                val p=op.proposal
                val checked=MetadataArtworkReplacementPreview.review(observed,p.index,p.proposedBytes,p.proposedMime)
                    as? MetadataArtworkReplacementPreview.Result.Ready
                    ?:throw Rejected(Reason.INVALID_ASSET)
                if(checked.proposal.sourceSha256!=p.sourceSha256 ||
                    checked.proposal.proposedSha256!=p.proposedSha256)
                    throw Rejected(Reason.INVALID_ASSET)
                names+="PICTURE:${p.index}"
            }
            is MetadataSelectiveAssetStage.Edit.PictureAdd -> {
                val p=op.proposal
                val checked=MetadataArtworkAdditionPreview.review(
                    observed,p.proposedBytes,p.proposedMime,p.type,p.description,
                ) as? MetadataArtworkAdditionPreview.Result.Ready
                    ?:throw Rejected(Reason.INVALID_ASSET)
                if(checked.proposal.ordinal!=p.ordinal ||
                    checked.proposal.proposedSha256!=p.proposedSha256)
                    throw Rejected(Reason.INVALID_ASSET)
                names+="PICTURE_ADD:${p.ordinal}"
            }
            is MetadataSelectiveAssetStage.Edit.PictureRemove -> {
                val selected=observed.artworks.singleOrNull {it.ordinal==op.ordinal}
                    ?:throw Rejected(Reason.INVALID_ASSET)
                if(selected.info.sha256!=op.sourceSha256)
                    throw Rejected(Reason.INVALID_ASSET)
                names+="PICTURE_REMOVE:${op.ordinal}"
            }
            is MetadataSelectiveAssetStage.Edit.DescribedText -> {
                names+="${op.change.identity.kind.frameId}:${op.change.identity.language}:${op.change.identity.description}"
            }
        }
        val texts=req.assets.filterIsInstance<MetadataSelectiveAssetStage.Edit.DescribedText>()
            .map { it.change }
        if(texts.isNotEmpty()) {
            val reviewed=MetadataDescribedTextWorkbench.review(true,observed.texts,texts)
                as? MetadataDescribedTextWorkbench.Result.Ready
                ?:throw Rejected(Reason.INVALID_ASSET)
            if(reviewed.differences.size!=texts.size)throw Rejected(Reason.INVALID_ASSET)
        }
        return names
    }
    fun stage(source:File,output:File,req:Request,checkCancelled: () -> Unit = {}):Receipt {
        checkCancelled()
        if(!req.originalDigest.matches(digestPattern))throw Rejected(Reason.INVALID_DIGEST)
        val a=source.toPath();val b=output.toPath();val parent=a.parent
        if(parent==null || parent!=b.parent || Files.isSymbolicLink(parent) ||
            !Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(a,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(b))
            throw Rejected(Reason.UNSAFE_SOURCE)
        if(Files.exists(b,LinkOption.NOFOLLOW_LINKS))throw Rejected(Reason.DESTINATION_EXISTS)
        if(source.length() !in 20..MAX_BUNDLE_BYTES)throw Rejected(Reason.TOO_LARGE)
        if(shaFile(source,checkCancelled)!=req.originalDigest)throw Rejected(Reason.SOURCE_CHANGED)
        val initial=observe(source)
        if(!same(initial.first,req.observed))throw Rejected(Reason.ORIGINAL_OBSERVATION_CHANGED)
        val names=preflight(initial.first,req)
        val audio=shaAudio(source,initial.second,checkCancelled)
        val temps=mutableListOf<File>()
        var current=source
        var lastOutputAttempted=false
        try {
            val count=(if(req.fields!=null)1 else 0)+req.assets.size
            for(index in 0 until count) {
                checkCancelled()
                val next=if(index==count-1)output else File(source.parentFile,
                    ".me10h-${UUID.randomUUID()}.tmp")
                if(next!=output)temps+=next
                if(next==output) lastOutputAttempted=true
                if(index==0 && req.fields!=null){
                    when(initial.first.format){
                        MetadataArtworkLyricsProbe.Format.FLAC ->
                            MetadataFlacStageRewriter.stage(current,next,req.fields,current.length(),checkCancelled)
                        MetadataArtworkLyricsProbe.Format.ID3_V23,MetadataArtworkLyricsProbe.Format.ID3_V24 ->
                            MetadataId3StageRewriter.stage(current,next,req.fields,current.length(),checkCancelled)
                        MetadataArtworkLyricsProbe.Format.MP4 ->
                            throw Rejected(Reason.FORMAT_UNSUPPORTED)
                    }
                } else {
                    val assetIndex=index-(if(req.fields!=null)1 else 0)
                    val edit=req.assets[assetIndex]
                    val observed=observe(current).first
                    MetadataSelectiveAssetStage.stage(current,next,observed,edit,shaFile(current,checkCancelled),checkCancelled)
                }
                checkCancelled()
                if(next.length() !in 20..MAX_BUNDLE_BYTES ||
                    shaAudio(next,observe(next).second,checkCancelled)!=audio)
                    throw Rejected(Reason.AUDIO_CHANGED)
                current=next
            }
            checkCancelled()
            if(shaFile(source,checkCancelled)!=req.originalDigest)throw Rejected(Reason.SOURCE_CHANGED)
            val end=observe(output).first
            if(end.format!=initial.first.format)
                throw Rejected(Reason.FINAL_OBSERVATION_MISMATCH)
            // Check the full expected net result; no operation may alter an unselected asset.
            val pictureFormat=if(initial.first.format==MetadataArtworkLyricsProbe.Format.FLAC)
                MetadataArtworkEvidence.Format.FLAC_PICTURE
                else MetadataArtworkEvidence.Format.ID3_APIC
            val expectedPictures=initial.first.artworks.toMutableList()
            for(asset in req.assets) when(asset) {
                is MetadataSelectiveAssetStage.Edit.Picture -> {
                    val p=asset.proposal
                    val index=expectedPictures.indexOfFirst {it.ordinal==p.index}
                    if(index<0)throw Rejected(Reason.FINAL_OBSERVATION_MISMATCH)
                    val before=expectedPictures[index].info
                    expectedPictures[index]=MetadataArtworkLyricsProbe.Artwork(
                        p.index,
                        MetadataArtworkEvidence.Picture(
                            pictureFormat, before.type, p.proposedMime, p.proposedSize,
                            p.proposedSha256, description=before.description,
                        ),
                        p.proposedBytes,
                    )
                }
                is MetadataSelectiveAssetStage.Edit.PictureAdd -> {
                    val p=asset.proposal
                    if(p.ordinal!=expectedPictures.size)
                        throw Rejected(Reason.FINAL_OBSERVATION_MISMATCH)
                    expectedPictures+=MetadataArtworkLyricsProbe.Artwork(
                        p.ordinal,
                        MetadataArtworkEvidence.Picture(
                            pictureFormat, p.type, p.proposedMime, p.proposedSize,
                            p.proposedSha256, description=p.description,
                        ),
                        p.proposedBytes,
                    )
                }
                is MetadataSelectiveAssetStage.Edit.PictureRemove -> {
                    val index=expectedPictures.indexOfFirst {it.ordinal==asset.ordinal}
                    if(index<0)throw Rejected(Reason.FINAL_OBSERVATION_MISMATCH)
                    expectedPictures.removeAt(index)
                }
                is MetadataSelectiveAssetStage.Edit.DescribedText -> Unit
            }
            val normalized=expectedPictures.mapIndexed { index,artwork ->
                if(artwork.ordinal==index)artwork else artwork.copy(ordinal=index)
            }
            if(end.artworks.size!=normalized.size)
                throw Rejected(Reason.FINAL_OBSERVATION_MISMATCH)
            normalized.forEachIndexed { index,expected ->
                val actual=end.artworks[index]
                if(actual.ordinal!=index || actual.info.type!=expected.info.type ||
                    actual.info.mime!=expected.info.mime ||
                    actual.info.description!=expected.info.description ||
                    actual.info.sha256!=expected.info.sha256 ||
                    !actual.image.contentEquals(expected.image))
                    throw Rejected(Reason.FINAL_OBSERVATION_MISMATCH)
            }
            if(req.fields?.diffs?.any {it.field in setOf(MetadataField.COMMENT,MetadataField.LYRICS)} != true) {
                val texts=req.assets.filterIsInstance<MetadataSelectiveAssetStage.Edit.DescribedText>()
                    .map {it.change}
                val expected=MetadataDescribedTextWorkbench.review(true,initial.first.texts,texts)
                    as? MetadataDescribedTextWorkbench.Result.Ready
                    ?:throw Rejected(Reason.FINAL_OBSERVATION_MISMATCH)
                if(end.texts!=expected.values)throw Rejected(Reason.FINAL_OBSERVATION_MISMATCH)
            }
            if(shaAudio(output,observe(output).second,checkCancelled)!=audio)
                throw Rejected(Reason.AUDIO_CHANGED)
            // An independent cold parser must confirm EVERY changed ordinary field;
            // the transformation's own success report is not evidence of readback.
            req.fields?.let { reviewed ->
                val cold=FileChannel.open(output.toPath(),StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS).use(MetadataColdFieldParser::observe)
                if(MetadataFieldReadbackPolicy.check(reviewed,cold)!=MetadataFieldReadbackPolicy.Result.VERIFIED)
                    throw Rejected(Reason.FINAL_OBSERVATION_MISMATCH)
            }
            checkCancelled()
            return Receipt(req.originalDigest,shaFile(output,checkCancelled),audio,output.length(),names)
        }catch(e:Exception){
            // Only our own candidate; pre-existing destination was rejected above.
            // A stager may have created candidate.bin before throwing. Only this
            // call owns a destination which was absent at entry; never keep a
            // partial candidate after failure, including cancellation.
            if(lastOutputAttempted && Files.isRegularFile(b,LinkOption.NOFOLLOW_LINKS))
                try { Files.deleteIfExists(b) } catch (_:Exception) {}
            throw e
        }finally{
            temps.forEach { try { Files.deleteIfExists(it.toPath()) } catch (_:Exception) {} }
        }
    }
}
