package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRiffStageRewriterTest {
    private fun four(text:String)=text.toByteArray(Charsets.US_ASCII)
    private fun le(n:Int)=byteArrayOf(n.toByte(),(n ushr 8).toByte(),
        (n ushr 16).toByte(),(n ushr 24).toByte())
    private fun chunk(id:String,payload:ByteArray):ByteArray =
        four(id)+le(payload.size)+payload+(if(payload.size%2==1)byteArrayOf(0) else byteArrayOf())
    private fun info(vararg entries:ByteArray):ByteArray =
        chunk("LIST",four("INFO")+entries.fold(ByteArray(0)){acc,bytes->acc+bytes})
    private val pcm=ByteArray(4000){((it*71)%255).toByte()}
    private val fmt=four("fmt ")+le(16)+byteArrayOf(1,0,1,0,0x44,0xac.toByte(),0,0,0x88.toByte(),0x58,1,0,2,0,16,0)
    private val unknown=chunk("JUNK",byteArrayOf(42,41,40,39,38,37))
    private fun wav(vararg extras:ByteArray):ByteArray {
        val data=fmt+unknown+extras.fold(ByteArray(0)){a,b->a+b}+chunk("data",pcm)
        return four("RIFF")+le(data.size+4)+four("WAVE")+data
    }
    private fun review(field:MetadataField=MetadataField.TITLE,before:String="Old",after:String="New"):
        MetadataReview.Ready {
        val snapshot=MetadataSnapshot(MetadataTarget("","riff-1","content://saf/riff","token"),
            true,mapOf(field to listOf(before)),verifiedFields=setOf(field))
        return MetadataEditPreview.review(snapshot,
            listOf(MetadataChange.Replace(field,listOf(after)))) as MetadataReview.Ready
    }
    private inline fun temp(run:(File,File)->Unit){
        val dir=Files.createTempDirectory("michi-riff").toFile()
        try {run(File(dir,"backup.wav"),File(dir,"stage.wav"))}
        finally {dir.deleteRecursively()}
    }
    private fun inspect(f:File)=FileChannel.open(f.toPath(),StandardOpenOption.READ).use {
        MetadataRiffAiffColdReader.observe(it)
    }
    private fun refuses(code:MetadataRiffStageRewriter.Code,block:()->Unit){
        try {block(); throw AssertionError("Expected $code")}
        catch (e:MetadataRiffStageRewriter.Refused){assertEquals(code,e.code)}
    }
    @Test fun changesExistingWavTitleWithoutTouchingOtherChunks()=temp { a,b ->
        val original=wav(info(chunk("INAM",four("Old")+byteArrayOf(0)),chunk("IART",four("Artist")+byteArrayOf(0))))
        a.writeBytes(original)
        val result=MetadataRiffStageRewriter.stage(a,b,review(after="Longer name"),a.length())
        assertFalse(result.canCommitToSource)
        assertEquals(MetadataFieldObservation.Present(listOf("Longer name")),
            inspect(b).observation(MetadataField.TITLE))
        assertEquals(MetadataFieldObservation.Present(listOf("Artist")),
            inspect(b).observation(MetadataField.ARTIST))
        assertTrue(b.readBytes().containsBytes(unknown))
        assertTrue(b.readBytes().containsBytes(chunk("data",pcm)))
        assertArrayEquals(original,a.readBytes())
    }
    @Test fun acceptsSmallerReplacementAndRebuildsLength()=temp{a,b->
        a.writeBytes(wav(info(chunk("INAM",four("Old title")+byteArrayOf(0)))))
        MetadataRiffStageRewriter.stage(a,b,review(before="Old title",after="X"),a.length())
        assertEquals(MetadataFieldObservation.Present(listOf("X")),inspect(b).observation(MetadataField.TITLE))
    }
    @Test fun rejectsUnknownTagInsertionWithoutProofOfAbsence()=temp{a,b->
        a.writeBytes(wav(info(chunk("IART",four("Someone")+byteArrayOf(0)))))
        refuses(MetadataRiffStageRewriter.Code.UNVERIFIED_FIELD){
            MetadataRiffStageRewriter.stage(a,b,review(),a.length())}
        assertFalse(b.exists())
    }
    @Test fun rejectsDuplicateInfoList()=temp{a,b->
        a.writeBytes(wav(info(chunk("INAM",four("Old"))),info(chunk("INAM",four("Old")))))
        refuses(MetadataRiffStageRewriter.Code.UNSUPPORTED_LAYOUT){
            MetadataRiffStageRewriter.stage(a,b,review(),a.length())}
    }
    @Test fun rejectsChangedPhysicalSource()=temp{a,b->
        a.writeBytes(wav(info(chunk("INAM",four("Other")))))
        refuses(MetadataRiffStageRewriter.Code.SOURCE_CHANGED){
            MetadataRiffStageRewriter.stage(a,b,review(),a.length())}
        assertFalse(b.exists())
    }
    @Test fun rejectsUnsafeNonAsciiOrUnsupportedField()=temp{a,b->
        a.writeBytes(wav(info(chunk("INAM",four("Old")))))
        refuses(MetadataRiffStageRewriter.Code.INVALID_VALUE){
            MetadataRiffStageRewriter.stage(a,b,review(after="Señal"),a.length())}
        refuses(MetadataRiffStageRewriter.Code.UNSUPPORTED_CONTAINER){
            MetadataRiffStageRewriter.stage(a,b,
                review(MetadataField.MUSICBRAINZ_RELEASE_ID,
                    "11111111-1111-4111-8111-111111111111",
                    "22222222-2222-4222-8222-222222222222"),a.length())}
    }
    @Test fun rejectsAmbiguousDuplicateInfoEntry()=temp{a,b->
        a.writeBytes(wav(info(chunk("INAM",four("Old")),chunk("INAM",four("Old")))))
        refuses(MetadataRiffStageRewriter.Code.UNVERIFIED_FIELD){
            MetadataRiffStageRewriter.stage(a,b,review(),a.length())}
        assertFalse(b.exists())
    }
    @Test fun rejectsForeignEmbeddedId3Shadow()=temp{a,b->
        a.writeBytes(wav(info(chunk("INAM",four("Old"))),chunk("ID3 ",byteArrayOf(1,2))))
        refuses(MetadataRiffStageRewriter.Code.UNSUPPORTED_LAYOUT){
            MetadataRiffStageRewriter.stage(a,b,review(),a.length())}
        assertFalse(b.exists())
    }
    @Test fun doesNotOverwriteExistingStage()=temp{a,b->
        a.writeBytes(wav(info(chunk("INAM",four("Old")))))
        b.writeText("PROTECTED")
        refuses(MetadataRiffStageRewriter.Code.UNSAFE_PRIVATE_SOURCE){
            MetadataRiffStageRewriter.stage(a,b,review(),a.length())}
        assertEquals("PROTECTED",b.readText())
    }
    private fun ByteArray.containsBytes(needle:ByteArray)=indices.any{ p ->
        p+needle.size<=size && copyOfRange(p,p+needle.size).contentEquals(needle)
    }
}
