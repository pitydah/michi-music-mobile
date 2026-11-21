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

class MetadataFlacStageRewriterTest {
    private val audio = ByteArray(2048) { ((it * 47) % 251).toByte() }
    private val vendor = "Michi test vendor".toByteArray(StandardCharsets.UTF_8)
    private fun put32(out: ByteArrayOutputStream, value: Int) {
        repeat(4) { out.write(value ushr (8 * it) and 255) }
    }
    private fun comments(vararg values: String): ByteArray {
        val out=ByteArrayOutputStream()
        put32(out,vendor.size);out.write(vendor);put32(out,values.size)
        values.forEach { value ->
            val encoded=value.toByteArray(StandardCharsets.UTF_8)
            put32(out,encoded.size);out.write(encoded)
        }
        return out.toByteArray()
    }
    private fun block(kind: Int, last: Boolean, bytes: ByteArray): ByteArray {
        val out=ByteArrayOutputStream()
        out.write(kind or if(last) 128 else 0)
        out.write((bytes.size ushr 16) and 255)
        out.write((bytes.size ushr 8) and 255)
        out.write(bytes.size and 255)
        out.write(bytes)
        return out.toByteArray()
    }
    private fun file(withComment: Boolean = true, duplicateComment: Boolean = false,
                     title: String = "Original"): ByteArray {
        val out=ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        out.write(block(0,false,ByteArray(34)))
        out.write(block(6,false,ByteArray(128){(it%101).toByte()})) // picture block left untouched
        if (withComment) {
            out.write(block(4,!duplicateComment,comments("TITLE=$title", "GENRE=Jazz; Fusion", "X-CUSTOM=keep=")))
            if (duplicateComment) out.write(block(4,true,comments("TITLE=Duplicate")))
        } else {
            out.write(block(1,true,ByteArray(5)))
        }
        out.write(audio)
        return out.toByteArray()
    }
    private fun review(
        known: Map<MetadataField,List<String>> = mapOf(
            MetadataField.TITLE to listOf("Original"), MetadataField.GENRE to listOf("Jazz; Fusion")),
        change: MetadataChange = MetadataChange.Replace(MetadataField.TITLE,listOf("Revised")),
    ): MetadataReview {
        val snapshot = MetadataSnapshot(
            MetadataTarget("", "track", "content://media/1", "verified-cold-proof"),
            true, known, verifiedFields=known.keys)
        return MetadataEditPreview.review(snapshot,listOf(change))
    }
    private fun inTemp(action: (File, File)->Unit) {
        val dir=Files.createTempDirectory("me03-flac").toFile()
        try { action(File(dir,"input.flac"), File(dir,"stage.flac")) }
        finally { dir.deleteRecursively() }
    }
    @Test fun stagesModifiedTitlePreservingUnknownAndAudio() = inTemp { source, target ->
        source.writeBytes(file())
        val result=MetadataFlacStageRewriter.stage(source,target,review(),source.length())
        val staged=target.readBytes()
        assertFalse(result.canCommitToProvider)
        assertTrue(staged.containsSubsequence("TITLE=Revised".toByteArray(StandardCharsets.UTF_8)))
        assertTrue(staged.containsSubsequence("X-CUSTOM=keep=".toByteArray(StandardCharsets.UTF_8)))
        assertArrayEquals(audio,staged.copyOfRange(staged.size-audio.size, staged.size))
        assertEquals(source.length(), result.originalBytes)
        assertArrayEquals(file(),source.readBytes())
    }
    @Test fun appendsSecondGenreWithoutSplittingLiteral() = inTemp { source,target ->
        source.writeBytes(file())
        val changed=review(change=MetadataChange.Replace(MetadataField.GENRE,
            listOf("Jazz; Fusion", "Bebop")))
        MetadataFlacStageRewriter.stage(source,target,changed,source.length())
        val bytes=target.readBytes()
        assertTrue(bytes.containsSubsequence("GENRE=Jazz; Fusion".toByteArray()))
        assertTrue(bytes.containsSubsequence("GENRE=Bebop".toByteArray()))
    }
    @Test fun extendedOrchestraAndDateStageWithoutDamagingUnknownData() = inTemp { source, target ->
        source.writeBytes(file())
        val original = MetadataSnapshot(MetadataTarget("", "t", "content://fixture/t", "cold"), true,
            mapOf(MetadataField.TITLE to listOf("Original")),
            verifiedFields = setOf(MetadataField.TITLE, MetadataField.ORCHESTRA,
                MetadataField.RECORDING_DATE))
        val updated = MetadataEditPreview.review(original, listOf(
            MetadataChange.Replace(MetadataField.ORCHESTRA, listOf("Orquesta A", "Orquesta B")),
            MetadataChange.Replace(MetadataField.RECORDING_DATE, listOf("1982-05-11"))))
        val staged = MetadataFlacStageRewriter.stage(source, target, updated, source.length())
        assertFalse(staged.canCommitToProvider)
        val bytes = target.readBytes()
        assertTrue(bytes.containsSubsequence("ORCHESTRA=Orquesta A".toByteArray()))
        assertTrue(bytes.containsSubsequence("ORCHESTRA=Orquesta B".toByteArray()))
        assertTrue(bytes.containsSubsequence("RECORDINGDATE=1982-05-11".toByteArray()))
        assertTrue(bytes.containsSubsequence("X-CUSTOM=keep=".toByteArray()))
        assertArrayEquals(audio, bytes.copyOfRange(bytes.size-audio.size, bytes.size))
        assertArrayEquals(file(), source.readBytes())
    }
    @Test fun absentExpandedFieldCannotOverwriteExistingUnknownAlias() = inTemp { source, target ->
        source.writeBytes(file())
        val snap = MetadataSnapshot(MetadataTarget("", "t", "content://fixture/t", "cold"), true,
            emptyMap(), verifiedFields = setOf(MetadataField.ORCHESTRA))
        val proposal = MetadataEditPreview.review(snap, listOf(
            MetadataChange.Replace(MetadataField.ORCHESTRA, listOf("One"))))
        val result = MetadataFlacStageRewriter.stage(source, target, proposal, source.length())
        assertFalse(result.canCommitToProvider)
    }
    @Test fun staleTitleRejectsWithoutCreatingOutput() = inTemp { source,target ->
        source.writeBytes(file(title="Changed Externally"))
        try {
            MetadataFlacStageRewriter.stage(source,target,review(),source.length())
            throw AssertionError("must reject")
        } catch (error: MetadataFlacStageRewriter.Rejected) {
            assertEquals(MetadataFlacStageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS,error.code)
        }
        assertFalse(target.exists())
    }
    @Test fun duplicateVorbisBlocksReject() = inTemp { source,target ->
        source.writeBytes(file(duplicateComment=true))
        try { MetadataFlacStageRewriter.stage(source,target,review(),source.length());throw AssertionError() }
        catch (error: MetadataFlacStageRewriter.Rejected) {
            assertEquals(MetadataFlacStageRewriter.Code.DUPLICATE_COMMENT_BLOCK,error.code)
        }
    }
    @Test fun missingVorbisCommentsReject() = inTemp { source,target ->
        source.writeBytes(file(withComment=false))
        try { MetadataFlacStageRewriter.stage(source,target,review(),source.length());throw AssertionError() }
        catch (error: MetadataFlacStageRewriter.Rejected) {
            assertEquals(MetadataFlacStageRewriter.Code.MISSING_COMMENT_BLOCK,error.code)
        }
    }
    @Test fun destinationNeverOverwritten() = inTemp { source,target ->
        source.writeBytes(file()); target.writeText("SENTINEL")
        try { MetadataFlacStageRewriter.stage(source,target,review(),source.length());throw AssertionError() }
        catch(error: MetadataFlacStageRewriter.Rejected) {
            assertEquals(MetadataFlacStageRewriter.Code.STAGING_ALREADY_EXISTS,error.code)
        }
        assertEquals("SENTINEL",target.readText())
    }
    @Test fun sizeMismatchRejectedBeforeOutput() = inTemp { source,target ->
        source.writeBytes(file())
        try { MetadataFlacStageRewriter.stage(source,target,review(),source.length()+1);throw AssertionError() }
        catch(error: MetadataFlacStageRewriter.Rejected) {
            assertEquals(MetadataFlacStageRewriter.Code.SOURCE_NOT_SAFE,error.code)
        }
        assertFalse(target.exists())
    }
    @Test fun unsupportedSyncedLyricsEditRejected() = inTemp { source,target ->
        source.writeBytes(file())
        val snap=MetadataSnapshot(MetadataTarget("", "id", "content://media/1", "proof"),true,
            mapOf(MetadataField.SYNCED_LYRICS to listOf("A")),verifiedFields=setOf(MetadataField.SYNCED_LYRICS))
        val review=MetadataEditPreview.review(snap,listOf(
            MetadataChange.Replace(MetadataField.SYNCED_LYRICS,listOf("B"))))
        try { MetadataFlacStageRewriter.stage(source,target,review,source.length());throw AssertionError() }
        catch(error: MetadataFlacStageRewriter.Rejected) {
            assertEquals(MetadataFlacStageRewriter.Code.FIELD_UNSUPPORTED,error.code)
        }
    }
    private fun ByteArray.containsSubsequence(expected: ByteArray): Boolean =
        indices.any { i -> i+expected.size <= size && (expected.indices).all { this[i+it] == expected[it] } }
}
