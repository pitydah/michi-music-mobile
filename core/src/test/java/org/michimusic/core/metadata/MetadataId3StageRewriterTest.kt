package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataId3StageRewriterTest {
    private val mp3Bytes = ByteArray(2700) { (it%239).toByte() }
    private fun sync(n:Int)=byteArrayOf(((n ushr 21) and 127).toByte(),
        ((n ushr 14) and 127).toByte(),((n ushr 7) and 127).toByte(),(n and 127).toByte())
    private fun frame(id:String, value:String, version:Int):ByteArray{
        val payload=byteArrayOf(if(version==3)1 else 3)+value.toByteArray(
            if(version==3)StandardCharsets.UTF_16 else StandardCharsets.UTF_8)
        val head=ByteArrayOutputStream()
        head.write(id.toByteArray(StandardCharsets.US_ASCII))
        head.write(if(version==3)byteArrayOf((payload.size ushr 24).toByte(),
            (payload.size ushr 16).toByte(),(payload.size ushr 8).toByte(),payload.size.toByte())
            else sync(payload.size))
        head.write(0);head.write(0);head.write(payload)
        return head.toByteArray()
    }
    private fun sample(version:Int=4,title:String="Original", genre:String="Jazz; Fusion"):ByteArray{
        val body=ByteArrayOutputStream()
        body.write(frame("TIT2",title,version))
        body.write(frame("TCON",genre,version))
        body.write(frame("TXXX","KEEP PRIVATE",version))
        val content=body.toByteArray()
        val out=ByteArrayOutputStream()
        out.write("ID3".toByteArray(StandardCharsets.US_ASCII))
        out.write(version);out.write(0);out.write(0);out.write(sync(content.size))
        out.write(content);out.write(mp3Bytes)
        return out.toByteArray()
    }
    private fun review(change:MetadataChange=MetadataChange.Replace(MetadataField.TITLE,
        listOf("Edited"))):MetadataReview{
        val data=mapOf(MetadataField.TITLE to listOf("Original"),
            MetadataField.GENRE to listOf("Jazz; Fusion"))
        val source=MetadataSnapshot(MetadataTarget("","id","content://media/1","verified"),true,data,
            verifiedFields=data.keys)
        return MetadataEditPreview.review(source,listOf(change))
    }
    private fun inTemp(action:(File,File)->Unit){
        val dir=Files.createTempDirectory("me03-id3").toFile()
        try{action(File(dir,"cache.mp3"),File(dir,"stage.mp3"))}finally{dir.deleteRecursively()}
    }
    @Test fun v24EditsOneFrameAndPreservesOthersAndAudio()=inTemp{source,stage->
        source.writeBytes(sample())
        val receipt=MetadataId3StageRewriter.stage(source,stage,review(),source.length())
        assertEquals(MetadataAudioContainer.ID3_V24,receipt.format)
        assertFalse(receipt.canCommitToProvider)
        assertArrayEquals(mp3Bytes,stage.readBytes().takeLast(mp3Bytes.size).toByteArray())
        assertTrue(stage.readBytes().containsRaw("KEEP PRIVATE".toByteArray()))
        assertTrue(stage.readBytes().containsRaw("Edited".toByteArray()))
        assertArrayEquals(sample(),source.readBytes())
    }
    @Test fun v23StagesUtf16Safely()=inTemp{source,stage->
        source.writeBytes(sample(3))
        val result=MetadataId3StageRewriter.stage(source,stage,review(),source.length())
        assertEquals(MetadataAudioContainer.ID3_V23,result.format)
        assertArrayEquals(mp3Bytes,stage.readBytes().takeLast(mp3Bytes.size).toByteArray())
    }
    @Test fun stagesExpandedSubtitleAsNewFrameAndPreservesPrivateAudio()=inTemp{ source,stage->
        source.writeBytes(sample())
        val snap=MetadataSnapshot(MetadataTarget("", "t", "content://fixture/t", "cold"), true,
            emptyMap(), verifiedFields=setOf(MetadataField.SUBTITLE))
        val proposal=MetadataEditPreview.review(snap,
            listOf(MetadataChange.Replace(MetadataField.SUBTITLE,listOf("En vivo"))))
        val result=MetadataId3StageRewriter.stage(source,stage,proposal,source.length())
        assertFalse(result.canCommitToProvider)
        assertTrue(stage.readBytes().containsRaw("TIT3".toByteArray()))
        assertTrue(stage.readBytes().containsRaw("KEEP PRIVATE".toByteArray()))
        assertArrayEquals(mp3Bytes,stage.readBytes().takeLast(mp3Bytes.size).toByteArray())
        assertArrayEquals(sample(),source.readBytes())
    }
    @Test fun ambiguousTagIsBlocked()=inTemp{source,stage->
        source.writeBytes(sample(title="Different"))
        try{MetadataId3StageRewriter.stage(source,stage,review(),source.length());throw AssertionError()}
        catch(e:MetadataId3StageRewriter.Rejected){
            assertEquals(MetadataId3StageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS,e.code)
        }
        assertFalse(stage.exists())
    }
    @Test fun v24MultiGenreStagesAsOneNulSeparatedFrameAndColdReadsExactly()=inTemp{source,stage->
        source.writeBytes(sample())
        val proposal=review(MetadataChange.Replace(MetadataField.GENRE,listOf("Jazz", "Bebop")))
        val result=MetadataId3StageRewriter.stage(source,stage,proposal,source.length())
        assertEquals(MetadataAudioContainer.ID3_V24,result.format)
        val report=java.nio.channels.FileChannel.open(stage.toPath(),
            java.nio.file.StandardOpenOption.READ).use(MetadataColdFieldParser::observe)
        assertEquals(listOf("Jazz","Bebop"),
            (report.observation(MetadataField.GENRE) as MetadataFieldObservation.Present).values)
        assertArrayEquals(mp3Bytes,stage.readBytes().takeLast(mp3Bytes.size).toByteArray())
        assertArrayEquals(sample(),source.readBytes())
    }
    @Test fun v23MultiGenreFailsClosed()=inTemp{source,stage->
        source.writeBytes(sample(3))
        val proposal=review(MetadataChange.Replace(MetadataField.GENRE,listOf("Jazz", "Bebop")))
        try{MetadataId3StageRewriter.stage(source,stage,proposal,source.length());throw AssertionError()}
        catch(e:MetadataId3StageRewriter.Rejected){
            assertEquals(MetadataId3StageRewriter.Code.TOO_MANY_VALUES,e.code)
        }
        assertFalse(stage.exists())
    }
    @Test fun ambiguousDuplicateId3TextFramesFailClosed()=inTemp{source,stage->
        val original=sample()
        val headerSize=10
        val extra=frame("TCON","Jazz; Fusion",4)
        val size=((original[6].toInt() and 127) shl 21) or
            ((original[7].toInt() and 127) shl 14) or
            ((original[8].toInt() and 127) shl 7) or (original[9].toInt() and 127)
        source.writeBytes(original.copyOfRange(0,6)+sync(size+extra.size)+
            original.copyOfRange(10,10+size)+extra+original.copyOfRange(10+size,original.size))
        try {MetadataId3StageRewriter.stage(source,stage,
            review(MetadataChange.Replace(MetadataField.GENRE,listOf("Bebop"))),source.length())
            throw AssertionError()
        } catch(e:MetadataId3StageRewriter.Rejected){
            assertEquals(MetadataId3StageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS,e.code)
        }
        assertFalse(stage.exists())
    }
    @Test fun sourceID3v1IsRejected()=inTemp{source,stage->
        val bytes=sample()+ByteArray(128).also{"TAG".toByteArray().copyInto(it)}
        source.writeBytes(bytes)
        try{MetadataId3StageRewriter.stage(source,stage,review(),source.length());throw AssertionError()}
        catch(e:MetadataId3StageRewriter.Rejected){
            assertEquals(MetadataId3StageRewriter.Code.ID3V1_CONFLICT,e.code)
        }
    }
    @Test fun existingStageNeverOverwritten()=inTemp{source,stage->
        source.writeBytes(sample());stage.writeText("SENTINEL")
        try{MetadataId3StageRewriter.stage(source,stage,review(),source.length());throw AssertionError()}
        catch(e:MetadataId3StageRewriter.Rejected){
            assertEquals(MetadataId3StageRewriter.Code.STAGE_EXISTS,e.code)
        }
        assertEquals("SENTINEL",stage.readText())
    }
    private fun ByteArray.containsRaw(needle:ByteArray):Boolean=indices.any{i->
        i+needle.size<=size && needle.indices.all{this[i+it]==needle[it]}}
}
