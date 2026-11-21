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

class MetadataApeStageRewriterTest {
    private fun ascii(s:String)=s.toByteArray(Charsets.US_ASCII)
    private fun le(n:Int)=byteArrayOf(n.toByte(),(n ushr 8).toByte(),
        (n ushr 16).toByte(),(n ushr 24).toByte())
    private fun item(key:String,bytes:ByteArray,flags:Int=0):ByteArray =
        le(bytes.size)+le(flags)+ascii(key)+byteArrayOf(0)+bytes
    private val prefix=ascii("wvpk")+ByteArray(160){((it*19)%251).toByte()}
    private val unknown=item("PRIVATE_DATA",byteArrayOf(0,1,2,0,3),2)
    private fun build(vararg items:ByteArray,legacy:Boolean=false,headerFlags:Int=0):ByteArray {
        val data=items.fold(ByteArray(0)){a,b->a+b}
        val footer=ascii("APETAGEX")+le(2000)+le(data.size+32)+le(items.size)+
            le(headerFlags)+ByteArray(8)
        return prefix+data+footer+(if(legacy) ascii("TAG")+ByteArray(125){it.toByte()}
            else ByteArray(0))
    }
    private fun review(field:MetadataField, before:List<String>, after:List<String>):MetadataReview.Ready {
        val source=MetadataSnapshot(MetadataTarget("","ape-track","content://saf/ape","evidence"),
            true,mapOf(field to before),verifiedFields=setOf(field))
        return MetadataEditPreview.review(source,listOf(
            if(after.isEmpty()) MetadataChange.Clear(field)
            else MetadataChange.Replace(field,after))) as MetadataReview.Ready
    }
    private inline fun inTemp(action:(File,File)->Unit){
        val dir=Files.createTempDirectory("michi-ape-stage").toFile()
        try{action(File(dir,"backup.bin"),File(dir,"candidate.bin"))}
        finally{dir.deleteRecursively()}
    }
    private fun inspect(file:File):MetadataFieldReadReport =
        FileChannel.open(file.toPath(),StandardOpenOption.READ).use { MetadataApeColdReader.observe(it) }
    private fun expect(code:MetadataApeStageRewriter.Code,block:()->Unit){
        try {block();throw AssertionError("expected $code")}
        catch (r:MetadataApeStageRewriter.Rejected){assertEquals(code,r.code)}
    }

    @Test fun modifiesTitleButPreservesBinaryUnknownAndAudio()=inTemp { a,b ->
        val original=build(item("TITLE",ascii("Old")),unknown)
        a.writeBytes(original)
        val result=MetadataApeStageRewriter.stage(a,b,review(MetadataField.TITLE,
            listOf("Old"),listOf("New")),a.length())
        assertFalse(result.canCommitToProvider)
        assertEquals(MetadataAudioContainer.WAVPACK,result.container)
        assertArrayEquals(prefix,b.readBytes().copyOfRange(0,prefix.size))
        assertTrue(b.readBytes().containsBytes(unknown))
        assertEquals(MetadataFieldObservation.Present(listOf("New")),
            inspect(b).observation(MetadataField.TITLE))
        assertArrayEquals(original,a.readBytes())
    }
    @Test fun preservesFinalId3v1Bytes()=inTemp { a,b ->
        a.writeBytes(build(item("TITLE",ascii("Old")),legacy=true))
        MetadataApeStageRewriter.stage(a,b,review(MetadataField.TITLE,
            listOf("Old"),listOf("Fresh")),a.length())
        assertArrayEquals(a.readBytes().takeLast(128).toByteArray(),
            b.readBytes().takeLast(128).toByteArray())
    }
    @Test fun writesOrderedMultivalueIncludingLiteralSemicolon()=inTemp { a,b ->
        a.writeBytes(build(item("GENRE",ascii("Jazz; Fusion")),unknown))
        MetadataApeStageRewriter.stage(a,b,review(MetadataField.GENRE,
            listOf("Jazz; Fusion"),listOf("Jazz; Fusion","Bebop","Bebop")),a.length())
        assertEquals(MetadataFieldObservation.Present(listOf("Jazz; Fusion","Bebop","Bebop")),
            inspect(b).observation(MetadataField.GENRE))
    }
    @Test fun clearRemovesOnlyTargetItem()=inTemp { a,b ->
        a.writeBytes(build(item("TITLE",ascii("Old")),item("ARTIST",ascii("Original")),unknown))
        MetadataApeStageRewriter.stage(a,b,review(MetadataField.TITLE,
            listOf("Old"),emptyList()),a.length())
        assertEquals(MetadataFieldObservation.VerifiedAbsent,inspect(b).observation(MetadataField.TITLE))
        assertEquals(MetadataFieldObservation.Present(listOf("Original")),inspect(b).observation(MetadataField.ARTIST))
        assertTrue(b.readBytes().containsBytes(unknown))
    }
    @Test fun rejectsChangedPhysicalValue()=inTemp { a,b ->
        a.writeBytes(build(item("TITLE",ascii("DIFFERENT"))))
        expect(MetadataApeStageRewriter.Code.SOURCE_CHANGED){
            MetadataApeStageRewriter.stage(a,b,review(MetadataField.TITLE,
                listOf("Old"),listOf("New")),a.length())}
        assertFalse(b.exists())
    }
    @Test fun rejectsHeaderPresentWithoutTouchingBackup()=inTemp { a,b ->
        a.writeBytes(build(item("TITLE",ascii("Old")),headerFlags=0x40000000.toInt() or 0x80000000.toInt()))
        expect(MetadataApeStageRewriter.Code.UNSUPPORTED_TAG_LAYOUT){
            MetadataApeStageRewriter.stage(a,b,review(MetadataField.TITLE,
                listOf("Old"),listOf("New")),a.length())}
        assertFalse(b.exists())
    }
    @Test fun rejectsDuplicateFieldItems()=inTemp { a,b ->
        a.writeBytes(build(item("TITLE",ascii("Old")),item("TITLE",ascii("Old"))))
        expect(MetadataApeStageRewriter.Code.UNVERIFIED_FIELD){
            MetadataApeStageRewriter.stage(a,b,review(MetadataField.TITLE,
                listOf("Old"),listOf("New")),a.length())}
    }
    @Test fun doesNotOverwriteExistingCandidate()=inTemp { a,b ->
        a.writeBytes(build(item("TITLE",ascii("Old"))))
        b.writeText("DO NOT DELETE")
        expect(MetadataApeStageRewriter.Code.UNSAFE_PRIVATE_SOURCE){
            MetadataApeStageRewriter.stage(a,b,review(MetadataField.TITLE,
                listOf("Old"),listOf("New")),a.length())}
        assertEquals("DO NOT DELETE",b.readText())
    }
    @Test fun refusesUnsupportedFieldRatherThanForgingTags()=inTemp { a,b ->
        a.writeBytes(build(item("TITLE",ascii("Old"))))
        expect(MetadataApeStageRewriter.Code.UNSUPPORTED_FORMAT){
            MetadataApeStageRewriter.stage(a,b,review(MetadataField.WEBSITE,
                emptyList(),listOf("https://example.org")),a.length())}
    }
    @Test fun refusesMissingApeFooter()=inTemp { a,b ->
        a.writeBytes(prefix+ByteArray(128))
        expect(MetadataApeStageRewriter.Code.UNSUPPORTED_TAG_LAYOUT){
            MetadataApeStageRewriter.stage(a,b,review(MetadataField.TITLE,
                listOf("Old"),listOf("New")),a.length())}
        assertFalse(b.exists())
    }
    private fun ByteArray.containsBytes(needle:ByteArray):Boolean =
        indices.any { at->at+needle.size<=size && copyOfRange(at,at+needle.size).contentEquals(needle) }
}
