package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Test
import org.junit.Assert.*

/** Synthetic fixture only. The production provider and Android APK remain NO-GO. */
class MetadataPrivateBundleStageTest {
    private val audio=ByteArray(700){(it*17).toByte()}
    private fun be(n:Int)=byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),(n ushr 8).toByte(),n.toByte())
    private fun sync(n:Int)=byteArrayOf((n ushr 21 and 127).toByte(),(n ushr 14 and 127).toByte(),(n ushr 7 and 127).toByte(),(n and 127).toByte())
    private fun png(mark:Int=0)=byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)+be(13)+"IHDR".toByteArray()+be(16)+be(20)+byteArrayOf(8,2,0,0,0)+ByteArray(4){mark.toByte()}+be(0)+"IEND".toByteArray()+ByteArray(4){mark.toByte()}
    private fun frame(id:String,body:ByteArray) = id.toByteArray()+sync(body.size)+byteArrayOf(0,0)+body
    private fun tag(vararg frames:ByteArray):ByteArray {
        val body=ByteArrayOutputStream().apply { frames.forEach{write(it)};write(ByteArray(3)) }.toByteArray()
        return "ID3".toByteArray()+byteArrayOf(4,0,0)+sync(body.size)+body+audio
    }
    private fun apic(image:ByteArray)=byteArrayOf(0)+"image/png".toByteArray()+byteArrayOf(0,3,0)+image
    private fun comment(lang:String,desc:String,body:String)=byteArrayOf(3)+lang.toByteArray()+desc.toByteArray()+byteArrayOf(0)+body.toByteArray()
    private fun fixture()=tag(frame("TIT2",byteArrayOf(3)+"Original".toByteArray()),
        frame("APIC",apic(png())),frame("COMM",comment("eng","","Keep")),
        frame("COMM",comment("spa","Notas","Antes")),frame("PRIV",byteArrayOf(5,2,1)))
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    private fun observed(bytes:ByteArray):MetadataArtworkLyricsProbe.Snapshot {
        val n=((bytes[6].toInt() and 127) shl 21) or ((bytes[7].toInt() and 127) shl 14) or
            ((bytes[8].toInt() and 127) shl 7) or (bytes[9].toInt() and 127)
        val result=MetadataArtworkLyricsProbe.inspect(bytes.copyOfRange(0,10+n))
        if(result !is MetadataArtworkLyricsProbe.Result.Ready)error("Probe: $result")
        return result.snapshot
    }
    private fun review():MetadataReview.Ready {
        val original=MetadataSnapshot(MetadataTarget("","track","content://media/external/audio/1","proof"),true,
            mapOf(MetadataField.TITLE to listOf("Original")),verifiedFields=setOf(MetadataField.TITLE))
        return MetadataEditPreview.review(original,listOf(MetadataChange.Replace(MetadataField.TITLE,listOf("Nuevo")))) as MetadataReview.Ready
    }
    private fun <T> withSource(work:(File,File,MetadataArtworkLyricsProbe.Snapshot)->T):T{
        val dir=Files.createTempDirectory("me10h-test-")
        try {
            val src=dir.resolve("original.bin").toFile();src.writeBytes(fixture())
            return work(src,dir.resolve("final.bin").toFile(),observed(src.readBytes()))
        } finally { Files.list(dir).use { it.forEach { file->Files.deleteIfExists(file) } }; Files.delete(dir) }
    }
    private fun picture(s:MetadataArtworkLyricsProbe.Snapshot)=
        (MetadataArtworkReplacementPreview.review(s,0,png(5),"image/png") as MetadataArtworkReplacementPreview.Result.Ready).proposal
    private fun text()=MetadataSelectiveAssetStage.Edit.DescribedText(
        MetadataDescribedTextWorkbench.Change.Replace(
            MetadataDescribedTextWorkbench.Identity(MetadataId3DescribedTagCodec.Kind.COMMENT,"spa","Notas"),"Después"))
    private fun run(src:File,dst:File,s:MetadataArtworkLyricsProbe.Snapshot,fields:MetadataReview.Ready?=null,
        edits:List<MetadataSelectiveAssetStage.Edit>):MetadataPrivateBundleStage.Receipt =
        MetadataPrivateBundleStage.stage(src,dst,MetadataPrivateBundleStage.Request(sha(src.readBytes()),s,fields,edits))
    @Test fun bundledTitleCoverAndSpanishCommentMakeOneCandidate(){
        withSource { src,dst,s ->
            val original=src.readBytes()
            val receipt=run(src,dst,s,review(),listOf(MetadataSelectiveAssetStage.Edit.Picture(picture(s)),text()))
            assertFalse(receipt.canCommitToProvider)
            assertArrayEquals(original,src.readBytes())
            assertEquals(sha(audio),receipt.audioSha256)
            assertEquals(3,receipt.changes.size)
            val bytes=dst.readBytes();val end=observed(bytes)
            assertEquals("Después",end.texts.single {it.identity.language=="spa"}.text)
            assertEquals("Keep",end.texts.single {it.identity.language=="eng"}.text)
            assertEquals(sha(png(5)),end.artworks.single().info.sha256)
            assertTrue(bytes.toList().windowed(3).any {it==listOf(5.toByte(),2.toByte(),1.toByte())})
            assertEquals(2,Files.list(src.parentFile.toPath()).use {it.count().toInt()})
        }
    }
    @Test fun assetOnlyWithoutFieldsWorks(){
        withSource {src,dst,s ->
            val r=run(src,dst,s,null,listOf(text()))
            assertEquals(1,r.changes.size)
            assertEquals("Después",observed(dst.readBytes()).texts.single{it.identity.language=="spa"}.text)
        }
    }
    @Test fun duplicateTargetIsRejectedBeforeOutput(){
        withSource {src,dst,s ->
            val op=MetadataSelectiveAssetStage.Edit.Picture(picture(s))
            try {run(src,dst,s,null,listOf(op,op)); fail("duplicate") }
            catch(e:MetadataPrivateBundleStage.Rejected){assertEquals(MetadataPrivateBundleStage.Reason.DUPLICATE_TARGET,e.reason)}
            assertFalse(dst.exists())
        }
    }
    @Test fun originalChangedAtSameLengthBlocks(){
        withSource {src,dst,s ->
            val digest=sha(src.readBytes())
            val bytes=src.readBytes();bytes[bytes.size-1]++ ;src.writeBytes(bytes)
            try {MetadataPrivateBundleStage.stage(src,dst,MetadataPrivateBundleStage.Request(digest,s,null,listOf(text())));fail("changed") }
            catch(e:MetadataPrivateBundleStage.Rejected){assertEquals(MetadataPrivateBundleStage.Reason.SOURCE_CHANGED,e.reason)}
            assertFalse(dst.exists())
        }
    }
    @Test fun existingDestinationIsUntouched(){
        withSource {src,dst,s ->
            dst.writeText("do-not-overwrite")
            try {run(src,dst,s,null,listOf(text())); fail("existing") }
            catch(e:MetadataPrivateBundleStage.Rejected){assertEquals(MetadataPrivateBundleStage.Reason.DESTINATION_EXISTS,e.reason)}
            assertEquals("do-not-overwrite",dst.readText())
        }
    }
    @Test fun emptyBundleIsRejected(){
        withSource {src,dst,s ->
            try {run(src,dst,s,null,emptyList()); fail("empty") }
            catch(e:MetadataPrivateBundleStage.Rejected){assertEquals(MetadataPrivateBundleStage.Reason.EMPTY_BUNDLE,e.reason)}
            assertFalse(dst.exists())
        }
    }
    @Test fun textFieldAndTypedEntryConflictIsRejected(){
        withSource {src,dst,s ->
            val meta=MetadataSnapshot(MetadataTarget("","x","content://media/1","p"),true,
                mapOf(MetadataField.COMMENT to listOf("Keep")), verifiedFields=setOf(MetadataField.COMMENT))
            val r=MetadataEditPreview.review(meta,listOf(MetadataChange.Replace(MetadataField.COMMENT,listOf("N")))) as MetadataReview.Ready
            try {run(src,dst,s,r,listOf(text()));fail("conflict")}
            catch(e:MetadataPrivateBundleStage.Rejected){assertEquals(MetadataPrivateBundleStage.Reason.CONFLICTING_TEXT_FIELDS,e.reason)}
            assertFalse(dst.exists())
        }
    }
    @Test fun noUnapprovedTextChangeAllowed(){
        withSource {src,dst,s ->
            val missing=MetadataDescribedTextWorkbench.Identity(MetadataId3DescribedTagCodec.Kind.COMMENT,"fra","none")
            val edit=MetadataSelectiveAssetStage.Edit.DescribedText(MetadataDescribedTextWorkbench.Change.Replace(missing,"bad"))
            try {run(src,dst,s,null,listOf(edit));fail("unverified")}
            catch(e:MetadataPrivateBundleStage.Rejected){assertEquals(MetadataPrivateBundleStage.Reason.INVALID_ASSET,e.reason)}
            assertFalse(dst.exists())
        }
    }
    @Test fun fieldOnlyBundlePreservesAllImagesAndDescribedTexts(){
        withSource {src,dst,s ->
            val receipt=run(src,dst,s,review(),emptyList())
            assertFalse(receipt.canCommitToProvider)
            assertEquals(1,receipt.changes.size)
            assertEquals(sha(audio),receipt.audioSha256)
            val after=observed(dst.readBytes())
            assertEquals(s.texts,after.texts)
            assertEquals(s.artworks.single().info,after.artworks.single().info)
        }
    }
    @Test fun changedOriginalObservationIsRejectedBeforeStaging(){
        withSource {src,dst,s ->
            val different=s.copy(texts=s.texts.drop(1))
            try {run(src,dst,different,null,listOf(text()));fail("stale observation")}
            catch(e:MetadataPrivateBundleStage.Rejected){
                assertEquals(MetadataPrivateBundleStage.Reason.ORIGINAL_OBSERVATION_CHANGED,e.reason)
            }
            assertFalse(dst.exists())
        }
    }

    @Test fun cancellationBeforePreparingDoesNotCreateCandidate(){
        withSource { src,dst,s ->
            val original=src.readBytes()
            try {
                MetadataPrivateBundleStage.stage(src,dst,
                    MetadataPrivateBundleStage.Request(sha(original),s,null,listOf(text()))) {
                    throw java.util.concurrent.CancellationException("cancel before begin")
                }
                fail("Expected cancellation")
            }catch(_:java.util.concurrent.CancellationException) { /* expected */ }
            assertFalse(dst.exists())
            assertArrayEquals(original,src.readBytes())
        }
    }

    @Test fun cancellationAfterOutputCreationDiscardsPartialCandidate(){
        withSource { src,dst,s ->
            val original=src.readBytes()
            var sawCandidate=false
            try {
                MetadataPrivateBundleStage.stage(src,dst,
                    MetadataPrivateBundleStage.Request(sha(original),s,null,listOf(text()))) {
                    if(dst.exists()) {
                        sawCandidate=true
                        throw java.util.concurrent.CancellationException("cancel during copy")
                    }
                }
                fail("Expected cancellation")
            }catch(_:java.util.concurrent.CancellationException) { /* expected */ }
            assertTrue(sawCandidate)
            assertFalse(dst.exists())
            assertArrayEquals(original,src.readBytes())
        }
    }

}
