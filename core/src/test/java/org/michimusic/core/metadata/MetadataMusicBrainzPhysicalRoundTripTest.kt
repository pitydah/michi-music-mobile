package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Private synthetic media only; deliberately no provider commit paths. */
class MetadataMusicBrainzPhysicalRoundTripTest {
    private val originalId = "12345678-1234-4234-8234-123456789abc"
    private val newId = "87654321-4321-4321-8321-cba987654321"
    private val audio = ByteArray(600) { (it % 137).toByte() }
    private fun sync(n:Int) = byteArrayOf((n ushr 21 and 127).toByte(),
        (n ushr 14 and 127).toByte(),(n ushr 7 and 127).toByte(),(n and 127).toByte())
    private fun big(n:Int) = byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),
        (n ushr 8).toByte(),n.toByte())
    private fun id3frame(v:Int,id:String,payload:ByteArray,flags:Int=0):ByteArray =
        id.toByteArray(StandardCharsets.US_ASCII) + (if(v==3)big(payload.size) else sync(payload.size)) +
            byteArrayOf(0,flags.toByte()) + payload
    private fun txxx(v:Int,name:String,id:String):ByteArray {
        val data=byteArrayOf(if(v==3)1 else 3) + (name + "\u0000" + id).toByteArray(
            if(v==3)StandardCharsets.UTF_16 else StandardCharsets.UTF_8)
        return id3frame(v,"TXXX",data)
    }
    private fun id3(v:Int,vararg frames:ByteArray):ByteArray {
        val payload=frames.fold(ByteArray(0)) { a,b -> a+b }
        return "ID3".toByteArray() + byteArrayOf(v.toByte(),0,0) + sync(payload.size) + payload + audio
    }
    private fun streamInfo():ByteArray=ByteArray(34).also{b->
        b[0]=0x10;b[2]=0x10;b[10]=0x0a;b[11]=0xc4.toByte();b[12]=0x40
    }
    private fun block(kind:Int,content:ByteArray,last:Boolean):ByteArray =
        byteArrayOf((kind or if(last)128 else 0).toByte(),(content.size ushr 16).toByte(),
            (content.size ushr 8).toByte(),content.size.toByte())+content
    private fun little(n:Int)= byteArrayOf(n.toByte(),(n ushr 8).toByte(),
        (n ushr 16).toByte(),(n ushr 24).toByte())
    private fun flac(vararg entries:String):ByteArray{
        val out=ByteArrayOutputStream();val vendor="Michi".toByteArray()
        out.write(little(vendor.size));out.write(vendor);out.write(little(entries.size))
        for (entry in entries){val b=entry.toByteArray(StandardCharsets.UTF_8)
            out.write(little(b.size));out.write(b)}
        return "fLaC".toByteArray()+block(0,streamInfo(),false)+
            block(6,ByteArray(40){it.toByte()},false)+block(4,out.toByteArray(),true)+audio
    }
    private fun observed(file:File)=FileChannel.open(file.toPath(),StandardOpenOption.READ)
        .use(MetadataColdFieldParser::observe)
    private fun ready(field:MetadataField,before:List<String>,after:List<String>):MetadataReview.Ready{
        val known=if(before.isEmpty()) emptyMap() else mapOf(field to before)
        val snapshot=MetadataSnapshot(MetadataTarget("","track","content://fixture/1","proof"),
            true,known,verifiedFields=setOf(field))
        return MetadataEditPreview.review(snapshot,listOf(MetadataChange.Replace(field,after))) as MetadataReview.Ready
    }
    private fun temp(action:(File,File)->Unit){val dir=Files.createTempDirectory("me10c-mbid").toFile()
        try{action(File(dir,"source"),File(dir,"stage"))} finally{dir.deleteRecursively()}}
    private fun ByteArray.has(needle:String)=String(this,Charsets.ISO_8859_1).contains(needle)

    @Test fun id3v24RecordingIdCanBeAddedWithoutTouchingAudioOrPrivateFrames()=temp{source,stage->
        source.writeBytes(id3(4,txxx(4,"PRIVATE-KEEP","This is not a MusicBrainz ID")))
        assertEquals(MetadataFieldObservation.VerifiedAbsent,
            observed(source).observation(MetadataField.MUSICBRAINZ_RECORDING_ID))
        val result=MetadataId3StageRewriter.stage(source,stage,ready(
            MetadataField.MUSICBRAINZ_RECORDING_ID,emptyList(),listOf(newId)),source.length())
        assertFalse(result.canCommitToProvider)
        assertEquals(listOf(newId),(observed(stage).observation(
            MetadataField.MUSICBRAINZ_RECORDING_ID) as MetadataFieldObservation.Present).values)
        assertTrue(stage.readBytes().has("PRIVATE-KEEP"))
        assertArrayEquals(audio,stage.readBytes().takeLast(audio.size).toByteArray())
        assertArrayEquals(id3(4,txxx(4,"PRIVATE-KEEP","This is not a MusicBrainz ID")),source.readBytes())
    }
    @Test fun id3v23ReplacesExistingRecordingMbidSafely()=temp{source,stage->
        source.writeBytes(id3(3,txxx(3,"MusicBrainz Track Id",originalId),
            id3frame(3,"APIC",byteArrayOf(0,2,3,4))))
        assertEquals(listOf(originalId),(observed(source).observation(
            MetadataField.MUSICBRAINZ_RECORDING_ID) as MetadataFieldObservation.Present).values)
        MetadataId3StageRewriter.stage(source,stage,ready(
            MetadataField.MUSICBRAINZ_RECORDING_ID,listOf(originalId),listOf(newId)),source.length())
        assertEquals(listOf(newId),(observed(stage).observation(
            MetadataField.MUSICBRAINZ_RECORDING_ID) as MetadataFieldObservation.Present).values)
        assertTrue(stage.readBytes().has("APIC"))
        assertArrayEquals(audio,stage.readBytes().takeLast(audio.size).toByteArray())
    }
    @Test fun id3DuplicateAliasCannotProveOrStageRecordingId()=temp{source,stage->
        source.writeBytes(id3(4,txxx(4,"MusicBrainz Track Id",originalId),
            txxx(4,"MUSICBRAINZ_RECORDINGID",newId)))
        assertTrue(observed(source).observation(MetadataField.MUSICBRAINZ_RECORDING_ID)
            is MetadataFieldObservation.Conflicted)
        try{MetadataId3StageRewriter.stage(source,stage,ready(MetadataField.MUSICBRAINZ_RECORDING_ID,
            listOf(originalId),listOf(newId)),source.length());throw AssertionError("must reject")}
        catch(e:MetadataId3StageRewriter.Rejected){assertEquals(
            MetadataId3StageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS,e.code)}
        assertFalse(stage.exists())
    }
    @Test fun malformedUserTextNeverProvesAllMbidsAbsent()=temp{source,_->
        source.writeBytes(id3(4,id3frame(4,"TXXX",byteArrayOf(3,65,66,67))))
        assertTrue(observed(source).observation(MetadataField.MUSICBRAINZ_RELEASE_ID)
            is MetadataFieldObservation.Unverifiable)
    }
    @Test fun flacReleaseIdAndLyricsCanBeStagedPreservingPicturesAndUnknowns()=temp{source,stage->
        source.writeBytes(flac("TITLE=Original", "PRIVATE-KEEP=value", "LYRICS=Old lyrics"))
        val snap=MetadataSnapshot(MetadataTarget("","track","content://fixture/1","proof"),true,
            mapOf(MetadataField.LYRICS to listOf("Old lyrics")),
            verifiedFields=setOf(MetadataField.LYRICS,MetadataField.MUSICBRAINZ_RELEASE_ID))
        val changes=listOf(MetadataChange.Replace(MetadataField.LYRICS,listOf("New lyrics")),
            MetadataChange.Replace(MetadataField.MUSICBRAINZ_RELEASE_ID,listOf(newId)))
        val review=MetadataEditPreview.review(snap,changes)
        val result=MetadataFlacStageRewriter.stage(source,stage,review,source.length())
        assertFalse(result.canCommitToProvider)
        assertEquals(listOf("New lyrics"),(observed(stage).observation(
            MetadataField.LYRICS) as MetadataFieldObservation.Present).values)
        assertEquals(listOf(newId),(observed(stage).observation(
            MetadataField.MUSICBRAINZ_RELEASE_ID) as MetadataFieldObservation.Present).values)
        assertTrue(stage.readBytes().has("PRIVATE-KEEP=value"))
        assertArrayEquals(audio,stage.readBytes().takeLast(audio.size).toByteArray())
    }
    @Test fun id3UfidMusicBrainzOwnerNeverGetsHiddenByTxxxEdit()=temp{source,stage->
        val ufid="http://musicbrainz.org".toByteArray() + byteArrayOf(0) +
            originalId.toByteArray()
        source.writeBytes(id3(4,id3frame(4,"UFID",ufid)))
        assertTrue(observed(source).observation(MetadataField.MUSICBRAINZ_RECORDING_ID)
            is MetadataFieldObservation.Unverifiable)
        try{MetadataId3StageRewriter.stage(source,stage,ready(
            MetadataField.MUSICBRAINZ_RECORDING_ID,emptyList(),listOf(newId)),source.length())
            throw AssertionError("UFID owner must block competing ID")}
        catch(e:MetadataId3StageRewriter.Rejected){assertEquals(
            MetadataId3StageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS,e.code)}
        assertFalse(stage.exists())
    }
    @Test fun id3MalformedUnrelatedUserTextBlocksTargetedMbidStage()=temp{source,stage->
        source.writeBytes(id3(4,id3frame(4,"TXXX",byteArrayOf(3,65,66,67))))
        try{MetadataId3StageRewriter.stage(source,stage,ready(
            MetadataField.MUSICBRAINZ_RELEASE_ID,emptyList(),listOf(newId)),source.length())
            throw AssertionError("must reject undecodable descriptor")}
        catch(e:MetadataId3StageRewriter.Rejected){assertEquals(
            MetadataId3StageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS,e.code)}
        assertFalse(stage.exists())
    }
    @Test fun id3CannotInsertMultipleArtistIdsIntoSingleUserTextFrame()=temp{source,stage->
        source.writeBytes(id3(4))
        val change=ready(MetadataField.MUSICBRAINZ_ARTIST_ID,emptyList(),listOf(originalId,newId))
        try{MetadataId3StageRewriter.stage(source,stage,change,source.length());throw AssertionError()}
        catch(e:MetadataId3StageRewriter.Rejected){assertEquals(
            MetadataId3StageRewriter.Code.TOO_MANY_VALUES,e.code)}
        assertFalse(stage.exists())
    }
    @Test fun flacMbidAliasConflictCannotBeOverwrittenByStager()=temp{source,stage->
        source.writeBytes(flac("MUSICBRAINZ_TRACKID=$originalId", "MUSICBRAINZ_RECORDINGID=$newId"))
        try{MetadataFlacStageRewriter.stage(source,stage,ready(
            MetadataField.MUSICBRAINZ_RECORDING_ID,listOf(originalId),listOf(newId)),source.length())
            throw AssertionError("must reject alias conflict")}
        catch(e:MetadataFlacStageRewriter.Rejected){assertEquals(
            MetadataFlacStageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS,e.code)}
        assertFalse(stage.exists())
    }
    @Test fun flacConflictingMbidAliasesBlockRead()=temp{source,_->
        source.writeBytes(flac("MUSICBRAINZ_TRACKID=$originalId", "MUSICBRAINZ_RECORDINGID=$newId"))
        assertTrue(observed(source).observation(MetadataField.MUSICBRAINZ_RECORDING_ID)
            is MetadataFieldObservation.Conflicted)
    }
    @Test fun flacInvalidMbidDoesNotBecomeValidEvidence()=temp{source,stage->
        source.writeBytes(flac("MUSICBRAINZ_ALBUMID=not-a-uuid"))
        assertTrue(observed(source).observation(MetadataField.MUSICBRAINZ_RELEASE_ID)
            is MetadataFieldObservation.Unverifiable)
        try{MetadataFlacStageRewriter.stage(source,stage,ready(MetadataField.MUSICBRAINZ_RELEASE_ID,
            listOf("not-a-uuid"),listOf(newId)),source.length());throw AssertionError("must reject")}
        catch(e:MetadataFlacStageRewriter.Rejected){assertEquals(
            MetadataFlacStageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS,e.code)}
        assertFalse(stage.exists())
    }
}
