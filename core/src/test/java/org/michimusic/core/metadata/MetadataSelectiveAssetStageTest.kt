package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

/** Synthetic media fixtures only. Real Android providers remain NO-GO. */
class MetadataSelectiveAssetStageTest {
    private val audio=ByteArray(4096) { ((it*13+7)%256).toByte() }
    private fun sha(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    private fun be(i:Int)=byteArrayOf((i ushr 24).toByte(),(i ushr 16).toByte(),(i ushr 8).toByte(),i.toByte())
    private fun syn(i:Int)=byteArrayOf((i ushr 21 and 127).toByte(),(i ushr 14 and 127).toByte(),
        (i ushr 7 and 127).toByte(),(i and 127).toByte())
    private fun png(width:Int=16,height:Int=24, marker:Int=0):ByteArray =
        byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)+be(13)+"IHDR".toByteArray()+
        be(width)+be(height)+byteArrayOf(8,2,0,0,0)+ByteArray(4){marker.toByte()}+
        be(0)+"IEND".toByteArray()+ByteArray(4){marker.toByte()}
    private fun jpeg(width:Int=12,height:Int=9):ByteArray =
        byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xc0.toByte(),0,17,8,
            (height ushr 8).toByte(),height.toByte(),(width ushr 8).toByte(),width.toByte(),3,
            1,0x11,0,2,0x11,0,3,0x11,0,0xff.toByte(),0xd9.toByte())
    private fun apic(image:ByteArray,kind:Int=3,mime:String="image/png") =
        byteArrayOf(3)+mime.toByteArray(StandardCharsets.US_ASCII)+byteArrayOf(0,kind.toByte(),0)+image
    private fun v23Apic(image:ByteArray,kind:Int=3) = byteArrayOf(0)+
        "image/png".toByteArray()+byteArrayOf(0,kind.toByte(),0)+image
    private fun described(version:Int,_kind:MetadataId3DescribedTagCodec.Kind,
        language:String,desc:String,text:String):ByteArray = if(version==4)
            byteArrayOf(3)+language.toByteArray()+desc.toByteArray()+byteArrayOf(0)+text.toByteArray()
        else byteArrayOf(1)+language.toByteArray()+desc.toByteArray(StandardCharsets.UTF_16)+
            byteArrayOf(0,0)+text.toByteArray(StandardCharsets.UTF_16)
    private fun frame(version:Int,id:String,payload:ByteArray,flags:Boolean=false):ByteArray =
        id.toByteArray()+ (if(version==4)syn(payload.size) else be(payload.size))+
        byteArrayOf(0,if(flags)1 else 0)+payload
    private fun id3(version:Int,frames:List<ByteArray>):ByteArray {
        val bytes=ByteArrayOutputStream();frames.forEach{bytes.write(it)};bytes.write(ByteArray(3))
        val body=bytes.toByteArray()
        return "ID3".toByteArray()+byteArrayOf(version.toByte(),0,0)+syn(body.size)+body+audio
    }
    private fun picture(image:ByteArray,kind:Int=3,mime:String="image/png",w:Int=16,h:Int=24):ByteArray{
        val mimeBytes=mime.toByteArray();val desc="Studio cover".toByteArray()
        return be(kind)+be(mimeBytes.size)+mimeBytes+be(desc.size)+desc+
            be(w)+be(h)+be(24)+be(0)+be(image.size)+image
    }
    private fun block(kind:Int,last:Boolean,data:ByteArray):ByteArray =
        byteArrayOf((kind or if(last)128 else 0).toByte(),(data.size ushr 16).toByte(),
            (data.size ushr 8).toByte(),data.size.toByte())+data
    private fun flac(pictures:List<ByteArray>,extra:Boolean=true):ByteArray{
        val blocks=mutableListOf<Pair<Int,ByteArray>>(0 to ByteArray(34))
        if(extra)blocks+=4 to byteArrayOf(1,2,3,4,5)
        for(p in pictures)blocks+=6 to p
        blocks+=2 to byteArrayOf(5,9,7)
        return "fLaC".toByteArray()+blocks.mapIndexed{idx,(kind,raw)->
            block(kind,idx==blocks.lastIndex,raw)
        }.fold(ByteArray(0)){a,b->a+b}+audio
    }
    private fun snapshot(file:File):MetadataArtworkLyricsProbe.Snapshot{
        val bytes=file.readBytes()
        val region=if(bytes.copyOfRange(0,3).contentEquals("ID3".toByteArray())){
            val len=((bytes[6].toInt() and 127) shl 21) or ((bytes[7].toInt() and 127) shl 14) or
                ((bytes[8].toInt() and 127) shl 7) or (bytes[9].toInt() and 127)
            bytes.copyOfRange(0,10+len)
        }else{
            var p=4;do {
                val size=((bytes[p+1].toInt() and 255) shl 16) or ((bytes[p+2].toInt() and 255) shl 8) or (bytes[p+3].toInt() and 255)
                val last=(bytes[p].toInt() and 128)!=0
                p+=4+size
                if(last)break
            }while(true)
            bytes.copyOfRange(0,p)
        }
        return (MetadataArtworkLyricsProbe.inspect(region) as MetadataArtworkLyricsProbe.Result.Ready).snapshot
    }
    private inline fun withFile(source:ByteArray,action:(File,File,MetadataArtworkLyricsProbe.Snapshot)->Unit){
        val dir=Files.createTempDirectory("michi-me10g-")
        try {
            val input=dir.resolve("source.private").toFile();input.writeBytes(source)
            action(input,dir.resolve("stage.private").toFile(),snapshot(input))
        }finally { Files.list(dir).use { stream ->stream.forEach {Files.deleteIfExists(it)} };Files.deleteIfExists(dir) }
    }
    private fun proposal(snapshot:MetadataArtworkLyricsProbe.Snapshot, index:Int, target:ByteArray,mime:String="image/png") =
        (MetadataArtworkReplacementPreview.review(snapshot,index,target,mime)
            as MetadataArtworkReplacementPreview.Result.Ready).proposal
    private fun stage(src:File,dst:File,snapshot:MetadataArtworkLyricsProbe.Snapshot,
        op:MetadataSelectiveAssetStage.Edit)=MetadataSelectiveAssetStage.stage(src,dst,snapshot,op,sha(src.readBytes()))
    private fun rejects(code:MetadataSelectiveAssetStage.Code, action:()->Unit){
        try {action();fail("Expected $code")}catch(e:MetadataSelectiveAssetStage.Rejected){assertEquals(code,e.code)}
    }

    @Test fun v24ChangesOnlyFrontArtAndPreservesBackFramesAndAudio(){
        val first=png(12,12);val back=png(11,11,4)
        val content=id3(4,listOf(frame(4,"TIT2",byteArrayOf(3)+"Jazz".toByteArray()),
            frame(4,"APIC",apic(first)),frame(4,"PRIV",byteArrayOf(5,7,9)),
            frame(4,"APIC",apic(back,4)),frame(4,"COMM",described(4,MetadataId3DescribedTagCodec.Kind.COMMENT,"spa","toma","Directo"))))
        withFile(content){src,dst,snap ->
            val image=png(20,30,2)
            val result=stage(src,dst,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,image)))
            assertFalse(result.canCommitToProvider)
            assertEquals(sha(audio),result.audioSha256)
            assertArrayEquals(content,src.readBytes())
            val updated=snapshot(dst)
            assertEquals(sha(image),updated.artworks[0].info.sha256)
            assertEquals(snap.artworks[1].info,updated.artworks[1].info)
            assertEquals(snap.texts,updated.texts)
            assertTrue(dst.readBytes().toList().windowed(3).any {it==listOf(5.toByte(),7.toByte(),9.toByte())})
        }
    }
    @Test fun v23ReplacesArtWithoutChangingHeaderVersion(){
        withFile(id3(3,listOf(frame(3,"APIC",v23Apic(png()))))){s,d,snap->
            val next=png(30,20,5)
            stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,next)))
            assertEquals(3,d.readBytes()[3].toInt())
            assertEquals(sha(next),snapshot(d).artworks.single().info.sha256)
        }
    }
    @Test fun textInSpanishChangesOnlyMatchingDescriptorAndKeepsEnglish(){
        val k=MetadataId3DescribedTagCodec.Kind.COMMENT
        val src=id3(4,listOf(frame(4,"COMM",described(4,k,"eng","","Original")),
            frame(4,"COMM",described(4,k,"spa","Notas","Viejo")),
            frame(4,"USLT",described(4,MetadataId3DescribedTagCodec.Kind.LYRICS,"spa","letra","Hola")),
            frame(4,"APIC",apic(png()))))
        withFile(src){s,d,snap ->
            val id=MetadataDescribedTextWorkbench.Identity(k,"spa","Notas")
            stage(s,d,snap,MetadataSelectiveAssetStage.Edit.DescribedText(MetadataDescribedTextWorkbench.Change.Replace(id,"Nuevo")))
            val after=snapshot(d)
            assertEquals("Original",after.texts.single {it.identity.language=="eng"}.text)
            assertEquals("Nuevo",after.texts.single {it.identity==id}.text)
            assertEquals("Hola",after.texts.single {it.identity.kind==MetadataId3DescribedTagCodec.Kind.LYRICS}.text)
            assertEquals(snap.artworks.single().info,after.artworks.single().info)
        }
    }
    @Test fun clearSpanishCommentDoesNotClearEnglishComment(){
        val k=MetadataId3DescribedTagCodec.Kind.COMMENT
        withFile(id3(4,listOf(frame(4,"COMM",described(4,k,"eng","","Keep")),
                frame(4,"COMM",described(4,k,"spa","Nota","Clear"))))){s,d,snap->
            stage(s,d,snap,MetadataSelectiveAssetStage.Edit.DescribedText(
                MetadataDescribedTextWorkbench.Change.Clear(MetadataDescribedTextWorkbench.Identity(k,"spa","Nota"))))
            assertEquals(1,snapshot(d).texts.size)
            assertEquals("Keep",snapshot(d).texts.single().text)
        }
    }
    @Test fun v23Utf16SpanishLyricsRoundTripsUnicode(){
        val k=MetadataId3DescribedTagCodec.Kind.LYRICS
        withFile(id3(3,listOf(frame(3,"USLT",described(3,k,"spa","Versión","Letras"))))){s,d,snap->
            val identity=MetadataDescribedTextWorkbench.Identity(k,"spa","Versión")
            stage(s,d,snap,MetadataSelectiveAssetStage.Edit.DescribedText(
                MetadataDescribedTextWorkbench.Change.Replace(identity,"Canción en vivo 🎵")))
            assertEquals("Canción en vivo 🎵",snapshot(d).texts.single().text)
            assertEquals(identity,snapshot(d).texts.single().identity)
        }
    }
    @Test fun addsAbsentV24CommentWithoutTouchingObservedEntries(){
        val k=MetadataId3DescribedTagCodec.Kind.COMMENT
        val source=id3(4,listOf(
            frame(4,"TIT2",byteArrayOf(3)+"Jazz".toByteArray()),
            frame(4,"APIC",apic(png())),
            frame(4,"COMM",described(4,k,"eng","","Keep")),
        ))
        withFile(source){s,d,snap ->
            val identity=MetadataDescribedTextWorkbench.Identity(k,"spa","principal")
            val result=stage(s,d,snap,MetadataSelectiveAssetStage.Edit.DescribedText(
                MetadataDescribedTextWorkbench.Change.Add(identity,"Comentario nuevo 🎵")))
            assertEquals(sha(audio),result.audioSha256)
            assertFalse(result.canCommitToProvider)
            val after=snapshot(d)
            assertEquals("Keep",after.texts.single {it.identity.language=="eng"}.text)
            assertEquals("Comentario nuevo 🎵",after.texts.single {it.identity==identity}.text)
            assertEquals(snap.artworks.single().info,after.artworks.single().info)
            assertEquals(4,d.readBytes()[3].toInt())
            assertArrayEquals(audio,d.readBytes().copyOfRange(
                d.readBytes().size-audio.size,d.readBytes().size))
        }
    }
    @Test fun addsAbsentV23LyricsWithDescriptor(){
        val k=MetadataId3DescribedTagCodec.Kind.LYRICS
        withFile(id3(3,listOf(frame(3,"TIT2",byteArrayOf(0)+"Jazz".toByteArray())))){s,d,snap->
            val identity=MetadataDescribedTextWorkbench.Identity(k,"spa","Versión")
            stage(s,d,snap,MetadataSelectiveAssetStage.Edit.DescribedText(
                MetadataDescribedTextWorkbench.Change.Add(identity,"Letra en vivo")))
            assertEquals(3,d.readBytes()[3].toInt())
            val after=snapshot(d)
            assertEquals(listOf(identity),after.texts.map {it.identity})
            assertEquals("Letra en vivo",after.texts.single().text)
            assertEquals(sha(audio),sha(d.readBytes().copyOfRange(
                d.readBytes().size-audio.size,d.readBytes().size)))
        }
    }
    @Test fun addRefusesEntryAlreadyObserved(){
        val k=MetadataId3DescribedTagCodec.Kind.COMMENT
        withFile(id3(4,listOf(frame(4,"COMM",described(4,k,"spa","Notas","Viejo"))))){s,d,snap->
            rejects(MetadataSelectiveAssetStage.Code.INVALID_REPLACEMENT){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.DescribedText(
                    MetadataDescribedTextWorkbench.Change.Add(
                        MetadataDescribedTextWorkbench.Identity(k,"spa","Notas"),"Nuevo")))
            }
            assertFalse(d.exists())
        }
    }
    @Test fun flacPngReplacementUpdatesDimensionsPreservesOtherBlocks(){
        val first=png(16,24);val back=png(13,12,4)
        val source=flac(listOf(picture(first),picture(back,4,w=13,h=12)))
        withFile(source){s,d,snap->
            val next=png(45,23,1)
            val r=stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,next)))
            assertEquals(MetadataArtworkLyricsProbe.Format.FLAC,r.format)
            assertEquals(sha(audio),r.audioSha256)
            assertEquals(45,snapshot(d).artworks[0].info.width)
            assertEquals(23,snapshot(d).artworks[0].info.height)
            assertEquals(snap.artworks[1].info,snapshot(d).artworks[1].info)
            assertArrayEquals(source,s.readBytes())
        }
    }
    @Test fun flacJpegReplacementReadsSofDimensions(){
        withFile(flac(listOf(picture(png())))){s,d,snap->
            val next=jpeg(97,52)
            val p=proposal(snap,0,next,"image/jpeg")
            stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(p))
            val art=snapshot(d).artworks.single().info
            assertEquals("image/jpeg",art.mime)
            assertEquals(97,art.width);assertEquals(52,art.height)
        }
    }
    @Test fun badDigestFailsBeforeCreatingStage(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png()))))){s,d,snap->
            rejects(MetadataSelectiveAssetStage.Code.SOURCE_CHANGED){
                MetadataSelectiveAssetStage.stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,png(17,25))),"0".repeat(64))
            }
            assertFalse(d.exists())
        }
    }
    @Test fun oldObservationNotUsedToRewriteNewOriginal(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png()))))){s,d,snap->
            val expected=sha(s.readBytes())
            s.writeBytes(id3(4,listOf(frame(4,"APIC",apic(png(23,20,9))))))
            rejects(MetadataSelectiveAssetStage.Code.SOURCE_CHANGED){
                MetadataSelectiveAssetStage.stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,png(17,17))),expected)
            }
        }
    }
    @Test fun tamperedProposalPayloadMustNotPassSha(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png()))))){s,d,snap->
            val p=proposal(snap,0,png(17,17));p.proposedBytes[p.proposedBytes.lastIndex]++
            rejects(MetadataSelectiveAssetStage.Code.INVALID_REPLACEMENT){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(p))
            }
        }
    }
    @Test fun duplicateFrontCoverIsNotEditableEvenWithForgedProposal(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png())),frame(4,"APIC",apic(png(17,21)))))){s,d,snap->
            val proposed=png(25,22)
            val forged=MetadataArtworkReplacementPreview.Proposal(0,snap.artworks[0].info.sha256,
                sha(proposed),"image/png",proposed.size,proposed)
            rejects(MetadataSelectiveAssetStage.Code.AMBIGUOUS_TARGET){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(forged))
            }
        }
    }
    @Test fun stageExistingDestinationNeverOverwritten(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png()))))){s,d,snap->
            d.writeText("existing")
            rejects(MetadataSelectiveAssetStage.Code.STAGE_EXISTS){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,png(17,17))))
            }
            assertEquals("existing",d.readText())
        }
    }
    @Test fun symlinkSourceIsRejected(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png()))))){s,d,snap->
            val link=File(s.parentFile,"source-link");Files.createSymbolicLink(link.toPath(),s.toPath())
            rejects(MetadataSelectiveAssetStage.Code.UNSAFE_PRIVATE_SOURCE){
                stage(link,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,png(17,17))))
            }
        }
    }
    @Test fun flaggedId3FramesAreRejectedWithNoOutput(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png()))))){s,d,snap->
            val bytes=s.readBytes(); val firstFrameStart=10; bytes[firstFrameStart+9]=1
            s.writeBytes(bytes)
            rejects(MetadataSelectiveAssetStage.Code.MALFORMED_METADATA){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,png(17,17))))
            }
            assertFalse(d.exists())
        }
    }
    @Test fun flacRejectsTextEditBecauseNoId3Frame(){
        withFile(flac(listOf(picture(png())))){s,d,snap->
            rejects(MetadataSelectiveAssetStage.Code.UNSUPPORTED_FORMAT){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.DescribedText(
                    MetadataDescribedTextWorkbench.Change.Clear(MetadataDescribedTextWorkbench.Identity(
                        MetadataId3DescribedTagCodec.Kind.COMMENT,"eng",""))))
            }
        }
    }
    @Test fun malformedJpegCannotBeCommittedAsFlacPicture(){
        withFile(flac(listOf(picture(png())))){s,d,snap->
            val invalid=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xe0.toByte())+ByteArray(30)+
                byteArrayOf(0xff.toByte(),0xd9.toByte())
            rejects(MetadataSelectiveAssetStage.Code.UNSUPPORTED_IMAGE){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,invalid,"image/jpeg")))
            }
            assertFalse(d.exists())
        }
    }
    @Test fun textNoOpIsRejected(){
        val k=MetadataId3DescribedTagCodec.Kind.COMMENT
        withFile(id3(4,listOf(frame(4,"COMM",described(4,k,"spa","A","Same"))))){s,d,snap->
            rejects(MetadataSelectiveAssetStage.Code.INVALID_REPLACEMENT){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.DescribedText(
                    MetadataDescribedTextWorkbench.Change.Replace(MetadataDescribedTextWorkbench.Identity(k,"spa","A"),"Same")))
            }
        }
    }
    @Test fun sameLengthChangedSourceStillRejectsStaleSnapshot(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png()))))){s,d,snap->
            val content=s.readBytes();content[content.lastIndex]++
            s.writeBytes(content)
            rejects(MetadataSelectiveAssetStage.Code.SOURCE_CHANGED){
                // Same artwork, different audio; the supplied digest must be the OLD version
                // to prevent laundering a new source into the old user's session.
                val original=id3(4,listOf(frame(4,"APIC",apic(png()))))
                MetadataSelectiveAssetStage.stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(
                    proposal(snap,0,png(17,17))),sha(original))
            }
        }
    }
    @Test fun exactArtworkBytesChangedWithNewMatchingFullHashStillBlockedByObservation(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png()))))){s,d,snap->
            s.writeBytes(id3(4,listOf(frame(4,"APIC",apic(png(17,17))))))
            rejects(MetadataSelectiveAssetStage.Code.OBSERVATION_CONFLICT){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,png(19,19))))
            }
            assertFalse(d.exists())
        }
    }
    @Test fun duplicateTextIdentitiesNeverProduceStaging(){
        val k=MetadataId3DescribedTagCodec.Kind.COMMENT
        withFile(id3(4,listOf(frame(4,"COMM",described(4,k,"spa","mismo","Primero"))))){s,d,snap->
            s.writeBytes(id3(4,listOf(frame(4,"COMM",described(4,k,"spa","mismo","Primero")),
                frame(4,"COMM",described(4,k,"spa","mismo","Segundo")))))
            rejects(MetadataSelectiveAssetStage.Code.MALFORMED_METADATA){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.DescribedText(
                    MetadataDescribedTextWorkbench.Change.Replace(
                        MetadataDescribedTextWorkbench.Identity(k,"spa","mismo"),"Otro")))
            }
        }
    }
    @Test fun unrelatedOriginalImageAndAlbumDataAreUnchangedInFlac(){
        val content=flac(listOf(picture(png()),picture(png(11,12),4,w=11,h=12)))
        withFile(content){s,d,snap->
            stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,png(45,33))))
            val original=content.toList()
            val staged=d.readBytes().toList()
            assertTrue(staged.windowed(9).any {it==original.takeLast(4096).take(9)})
            assertEquals(sha(audio),sha(d.readBytes().copyOfRange(d.readBytes().size-audio.size,d.readBytes().size)))
        }
    }
    @Test fun id3v1SuffixIsRefusedToPreventContradictoryCommentTags(){
        val original=id3(4,listOf(frame(4,"APIC",apic(png()))))
        withFile(original+"TAG".toByteArray()+ByteArray(125)){s,d,snap->
            rejects(MetadataSelectiveAssetStage.Code.ID3V1_CONFLICT){
                stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,png(17,17))))
            }
            assertFalse(d.exists())
        }
    }
    @Test fun noLongRunningTasksAreScheduledAndNoCommitGrantIsEverProduced(){
        withFile(id3(4,listOf(frame(4,"APIC",apic(png()))))){s,d,snap->
            val result=stage(s,d,snap,MetadataSelectiveAssetStage.Edit.Picture(proposal(snap,0,png(17,17))))
            assertFalse(result.canCommitToProvider)
            assertEquals(sha(s.readBytes()),result.originalSha256)
            assertEquals(sha(d.readBytes()),result.stagedSha256)
        }
    }
}
