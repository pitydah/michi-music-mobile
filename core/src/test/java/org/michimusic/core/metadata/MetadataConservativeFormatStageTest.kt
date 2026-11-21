package org.michimusic.core.metadata

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All tests operate on isolated PRIVATE synthetic fixture files, never source URIs. */
class MetadataConservativeFormatStageTest {
    private fun be(value:Int)=byteArrayOf((value ushr 24).toByte(),(value ushr 16).toByte(),
        (value ushr 8).toByte(),value.toByte())
    private fun le(value:Int)=byteArrayOf(value.toByte(),(value ushr 8).toByte(),
        (value ushr 16).toByte(),(value ushr 24).toByte())
    private fun chunk(id:String,body:ByteArray)=id.toByteArray(Charsets.US_ASCII)+be(body.size)+
        body+(if(body.size%2!=0)byteArrayOf(0) else byteArrayOf())
    private fun aiff(title:String,duplicate:Boolean=false):ByteArray {
        val comm=chunk("COMM",byteArrayOf(0,2,0,0,0,10,0x40,0x0e,0xac.toByte(),0x44,0,0,0,0,0,0))
        val titleChunk=chunk("NAME",title.toByteArray(Charsets.US_ASCII))
        val body="AIFF".toByteArray()+comm+titleChunk+
            (if(duplicate)titleChunk else byteArrayOf())+
            chunk("SSND",ByteArray(8)+ByteArray(400){it.toByte()})
        return "FORM".toByteArray()+be(body.size)+body
    }
    private fun atom(id:ByteArray,payload:ByteArray)=be(payload.size+8)+id+payload
    private fun mp4(title:String):ByteArray {
        val item=atom(byteArrayOf(0xa9.toByte())+"nam".toByteArray(),
            atom("data".toByteArray(),byteArrayOf(0,0,0,1,0,0,0,0)+title.toByteArray()))
        val moov=atom("moov".toByteArray(),atom("udta".toByteArray(),
            atom("meta".toByteArray(),ByteArray(4)+atom("ilst".toByteArray(),item))))
        return atom("ftyp".toByteArray(),"M4A ".toByteArray()+ByteArray(4)+"M4A ".toByteArray())+
            moov+atom("mdat".toByteArray(),ByteArray(1024){it.toByte()})
    }
    private fun ogg(title:String):ByteArray {
        val head="OpusHead".toByteArray()+byteArrayOf(1,2)+ByteArray(9)
        val item="TITLE=$title".toByteArray()
        val vendor="Michi".toByteArray()
        val tags="OpusTags".toByteArray()+le(vendor.size)+vendor+le(1)+le(item.size)+item
        require(head.size<255&&tags.size<255)
        val page=byteArrayOf(79,103,103,83,0,2)+ByteArray(8)+le(1)+le(0)+ByteArray(4)+
            byteArrayOf(2,head.size.toByte(),tags.size.toByte())+head+tags
        var crc=0
        for (index in page.indices) {
            crc=crc xor ((if(index in 22..25)0 else page[index].toInt() and 255) shl 24)
            repeat(8){crc=(crc shl 1) xor (if(crc<0)0x04c11db7 else 0)}
        }
        le(crc).copyInto(page,22)
        return page
    }
    private fun review(after:String="Bravo"):
        MetadataReview.Ready {
        val snapshot=MetadataSnapshot(MetadataTarget("","file","content://local/track","token"),true,
            mapOf(MetadataField.TITLE to listOf("Alpha")),verifiedFields=setOf(MetadataField.TITLE))
        return MetadataEditPreview.review(snapshot,listOf(
            MetadataChange.Replace(MetadataField.TITLE,listOf(after)))) as MetadataReview.Ready
    }
    private inline fun files(run:(File,File)->Unit) {
        val folder=Files.createTempDirectory("michi-staging-v2").toFile()
        try {run(File(folder,"backup"),File(folder,"candidate"))}
        finally {folder.deleteRecursively()}
    }
    private fun report(file:File)=FileChannel.open(file.toPath(),StandardOpenOption.READ).use {
        MetadataColdFieldParser.observe(it)
    }
    private fun assertRewritten(file:File,format:MetadataPhysicalContainer){
        val observed=report(file)
        assertEquals(format,observed.container)
        assertEquals(MetadataFieldObservation.Present(listOf("Bravo")),
            observed.observation(MetadataField.TITLE))
    }
    @Test fun aiffExistingTextUpdatesAndPreservesAudio()=files{a,b->
        val bytes=aiff("Alpha");a.writeBytes(bytes)
        val result=MetadataAiffStageRewriter.stage(a,b,review(),a.length())
        assertFalse(result.canCommitToSource)
        assertRewritten(b,MetadataPhysicalContainer.AIFF)
        assertArrayEquals(bytes,a.readBytes())
        assertTrue(b.readBytes().copyOfRange(b.length().toInt()-400,b.length().toInt())
            .contentEquals(bytes.copyOfRange(bytes.size-400,bytes.size)))
    }
    @Test fun aiffRejectsDuplicateTitleWithoutChangingSource()=files{a,b->
        val bytes=aiff("Alpha",duplicate=true);a.writeBytes(bytes)
        try {MetadataAiffStageRewriter.stage(a,b,review(),a.length());throw AssertionError("expected refusal")}
        catch(e:MetadataAiffStageRewriter.Refused){assertEquals(MetadataAiffStageRewriter.Code.DUPLICATE_FIELD,e.code)}
        assertArrayEquals(bytes,a.readBytes());assertFalse(b.exists())
    }
    @Test fun mp4FixedSizeChangePreservesMdatAndOffsets()=files{a,b->
        val bytes=mp4("Alpha");a.writeBytes(bytes)
        val result=MetadataMp4StageRewriter.stage(a,b,review(),a.length())
        assertFalse(result.canCommitToSource)
        assertRewritten(b,MetadataPhysicalContainer.MP4_M4A)
        assertArrayEquals(bytes,a.readBytes());assertEquals(bytes.size,b.length().toInt())
        assertArrayEquals(bytes.copyOfRange(bytes.size-1024,bytes.size),
            b.readBytes().copyOfRange(bytes.size-1024,bytes.size))
    }
    @Test fun mp4DifferentByteLengthIsRejected()=files{a,b->
        val bytes=mp4("Alpha");a.writeBytes(bytes)
        try {MetadataMp4StageRewriter.stage(a,b,review("Long title"),a.length());throw AssertionError("expected refusal")}
        catch(e:MetadataMp4StageRewriter.Refused){assertEquals(MetadataMp4StageRewriter.Code.SIZE_CHANGE,e.code)}
        assertFalse(b.exists());assertArrayEquals(bytes,a.readBytes())
    }
    @Test fun oggUpdatesSinglePageAndRecomputesCrc()=files{a,b->
        val bytes=ogg("Alpha");a.writeBytes(bytes)
        val result=MetadataOggStageRewriter.stage(a,b,review(),a.length())
        assertFalse(result.canCommitToSource)
        assertRewritten(b,MetadataPhysicalContainer.OGG_OPUS)
        assertEquals(bytes.size,b.length().toInt());assertArrayEquals(bytes,a.readBytes())
    }
    @Test fun oggBadCrcRefusesWithoutStage()=files{a,b->
        val bytes=ogg("Alpha");bytes[22]=(bytes[22].toInt() xor 7).toByte();a.writeBytes(bytes)
        try {MetadataOggStageRewriter.stage(a,b,review(),a.length());throw AssertionError("expected refusal")}
        catch(e:MetadataOggStageRewriter.Refused){assertEquals(MetadataOggStageRewriter.Code.BAD_CRC,e.code)}
        assertFalse(b.exists());assertArrayEquals(bytes,a.readBytes())
    }
    @Test fun oggReplacementLengthChangeRefuses()=files{a,b->
        val bytes=ogg("Alpha");a.writeBytes(bytes)
        try {MetadataOggStageRewriter.stage(a,b,review("Longer"),a.length());throw AssertionError("expected refusal")}
        catch(e:MetadataOggStageRewriter.Refused){assertEquals(MetadataOggStageRewriter.Code.LENGTH_CHANGED,e.code)}
        assertFalse(b.exists())
    }
    private fun compareManifest(original:File,candidate:File,
        format:MetadataAudioContainer){
        val intent=MetadataCommitIntentManifest.create(
            "00000000-0000-4000-8000-000000000001",review(),
            MetadataDocumentProvider.SAF,format,original.length(),"a".repeat(64),
            candidate.length(),"b".repeat(64))
        assertTrue(MetadataCommitIntentManifest.valid(intent))
        val before=report(original)
        val after=report(candidate)
        val result=MetadataCandidateSemanticPolicy.compare(intent,before,after)
        assertTrue("semantic comparison refused: $result",
            result is MetadataCandidateSemanticPolicy.Verdict.ObservedFieldsMatch)
        assertTrue(MetadataPostSourceFieldPolicy.compare(intent,after) is
            MetadataPostSourceFieldPolicy.Verdict.Compared)
    }
    @Test fun aiffJournalIntentAndSemanticReadback()=files{a,b->
        a.writeBytes(aiff("Alpha"));MetadataAiffStageRewriter.stage(a,b,review(),a.length())
        compareManifest(a,b,MetadataAudioContainer.AIFF)
    }
    @Test fun mp4JournalIntentAndSemanticReadback()=files{a,b->
        a.writeBytes(mp4("Alpha"));MetadataMp4StageRewriter.stage(a,b,review(),a.length())
        compareManifest(a,b,MetadataAudioContainer.MP4_M4A)
    }
    @Test fun opusJournalIntentAndSemanticReadback()=files{a,b->
        a.writeBytes(ogg("Alpha"));MetadataOggStageRewriter.stage(a,b,review(),a.length())
        compareManifest(a,b,MetadataAudioContainer.OGG_OPUS)
    }
    @Test fun allThreeRejectUnverifiedReview()=files{a,b->
        val r=review().copy(diffs=listOf(MetadataFieldDiff(MetadataField.TITLE,listOf("Bad"),listOf("Bravo"))))
        a.writeBytes(aiff("Alpha"))
        try{MetadataAiffStageRewriter.stage(a,b,r,a.length());throw AssertionError("expected refusal")}
        catch(e:MetadataAiffStageRewriter.Refused){assertEquals(MetadataAiffStageRewriter.Code.INVALID_REVIEW,e.code)}
    }
}
