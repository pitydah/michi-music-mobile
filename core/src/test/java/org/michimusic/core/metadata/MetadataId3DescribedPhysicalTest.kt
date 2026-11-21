package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataId3DescribedPhysicalTest {
    private val audio = ByteArray(2048) { (it % 171).toByte() }
    private fun sync(n: Int) = byteArrayOf(((n ushr 21) and 127).toByte(),
        ((n ushr 14) and 127).toByte(), ((n ushr 7) and 127).toByte(), (n and 127).toByte())
    private fun frame(id: String, payload: ByteArray, version: Int): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(id.toByteArray(StandardCharsets.US_ASCII))
        header.write(if (version == 4) sync(payload.size) else byteArrayOf(
            (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte()))
        header.write(0); header.write(0); header.write(payload)
        return header.toByteArray()
    }
    private fun song(version: Int = 4, comment: ByteArray? = null, lyrics: ByteArray? = null,
                     extraComment: ByteArray? = null, extraLyrics: ByteArray? = null): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.write(frame("TIT2", byteArrayOf(3) + "Original".toByteArray(), version))
        if (comment != null) frames.write(frame("COMM", comment, version))
        if (extraComment != null) frames.write(frame("COMM", extraComment, version))
        if (lyrics != null) frames.write(frame("USLT", lyrics, version))
        if (extraLyrics != null) frames.write(frame("USLT", extraLyrics, version))
        frames.write(frame("PRIV", "PRIVATE".toByteArray(), version))
        frames.write(frame("APIC", byteArrayOf(0, 1, 2, 3, 4, 5), version))
        val tag = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray()); out.write(version); out.write(0); out.write(0)
        out.write(sync(tag.size)); out.write(tag); out.write(audio)
        return out.toByteArray()
    }
    private fun review(field: MetadataField, before: List<String>, after: List<String>): MetadataReview {
        val original = MetadataSnapshot(MetadataTarget("", "one", "content://media/1", "cold"),
            true, if (before.isEmpty()) emptyMap() else mapOf(field to before),
            verifiedFields = setOf(field))
        return MetadataEditPreview.review(original,
            listOf(if (after.isEmpty()) MetadataChange.Clear(field) else MetadataChange.Replace(field, after)))
    }
    private fun withTemp(test: (File, File) -> Unit) {
        val dir = Files.createTempDirectory("me10d-id3").toFile()
        try { test(File(dir, "source.mp3"), File(dir, "stage.mp3")) }
        finally { dir.deleteRecursively() }
    }
    private fun containsBytes(input: ByteArray, part: ByteArray): Boolean =
        input.indices.any { it + part.size <= input.size &&
            part.indices.all { j -> input[it + j] == part[j] } }
    private fun report(f: File) = FileChannel.open(f.toPath(), StandardOpenOption.READ).use {
        MetadataColdFieldParser.observe(it)
    }
    @Test fun v24ObservedCommentAndLyricsIndependent() = withTemp { source, _ ->
        source.writeBytes(song(comment = MetadataId3DescribedTagCodec.encodeCanonical(
            MetadataId3DescribedTagCodec.Kind.COMMENT, 4, "Notas"),
            lyrics = MetadataId3DescribedTagCodec.encodeCanonical(
                MetadataId3DescribedTagCodec.Kind.LYRICS, 4, "Canta\nConmigo")))
        assertEquals(listOf("Notas"), (report(source).observations[MetadataField.COMMENT]
            as MetadataFieldObservation.Present).values)
        assertEquals(listOf("Canta\nConmigo"), (report(source).observations[MetadataField.LYRICS]
            as MetadataFieldObservation.Present).values)
    }
    @Test fun v23CommentStagesWithoutTouchingPrivateOrAudio() = withTemp { source, stage ->
        val payload = MetadataId3DescribedTagCodec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.COMMENT, 3, "Antes")
        val before = song(version = 3, comment = payload)
        source.writeBytes(before)
        val receipt = MetadataId3StageRewriter.stage(source, stage,
            review(MetadataField.COMMENT, listOf("Antes"), listOf("Después")), source.length())
        assertFalse(receipt.canCommitToProvider)
        assertArrayEquals(before, source.readBytes())
        assertArrayEquals(audio, stage.readBytes().takeLast(audio.size).toByteArray())
        assertTrue(containsBytes(stage.readBytes(), "PRIVATE".toByteArray()))
        assertTrue(containsBytes(stage.readBytes(), "APIC".toByteArray()))
        assertEquals(listOf("Después"), (report(stage).observations[MetadataField.COMMENT]
            as MetadataFieldObservation.Present).values)
    }
    @Test fun v24LyricsStagesAndPreservesComment() = withTemp { source, stage ->
        source.writeBytes(song(comment = MetadataId3DescribedTagCodec.encodeCanonical(
            MetadataId3DescribedTagCodec.Kind.COMMENT, 4, "Notas"),
            lyrics = MetadataId3DescribedTagCodec.encodeCanonical(
                MetadataId3DescribedTagCodec.Kind.LYRICS, 4, "Old")))
        MetadataId3StageRewriter.stage(source, stage,
            review(MetadataField.LYRICS, listOf("Old"), listOf("New\nLyrics")), source.length())
        assertEquals(listOf("New\nLyrics"), (report(stage).observations[MetadataField.LYRICS]
            as MetadataFieldObservation.Present).values)
        assertEquals(listOf("Notas"), (report(stage).observations[MetadataField.COMMENT]
            as MetadataFieldObservation.Present).values)
        assertArrayEquals(audio, stage.readBytes().takeLast(audio.size).toByteArray())
    }
    @Test fun verifiedAbsentCanAddCanonicalComment() = withTemp { source, stage ->
        source.writeBytes(song())
        assertEquals(MetadataFieldObservation.VerifiedAbsent, report(source).observations[MetadataField.COMMENT])
        MetadataId3StageRewriter.stage(source, stage,
            review(MetadataField.COMMENT, emptyList(), listOf("Added")), source.length())
        assertEquals(listOf("Added"), (report(stage).observations[MetadataField.COMMENT]
            as MetadataFieldObservation.Present).values)
    }
    @Test fun explicitClearRemovesOnlyCanonicalLyrics() = withTemp { source, stage ->
        source.writeBytes(song(lyrics = MetadataId3DescribedTagCodec.encodeCanonical(
            MetadataId3DescribedTagCodec.Kind.LYRICS, 4, "Lyrics")))
        MetadataId3StageRewriter.stage(source, stage,
            review(MetadataField.LYRICS, listOf("Lyrics"), emptyList()), source.length())
        assertEquals(MetadataFieldObservation.VerifiedAbsent, report(stage).observations[MetadataField.LYRICS])
        assertTrue(containsBytes(stage.readBytes(), "PRIVATE".toByteArray()))
    }
    @Test fun foreignLanguageCommentNeverGetsOverwritten() = withTemp { source, stage ->
        val other = byteArrayOf(3) + "spa".toByteArray() + byteArrayOf(0) + "Comentario".toByteArray()
        source.writeBytes(song(comment = other))
        assertTrue(report(source).observations[MetadataField.COMMENT] !is MetadataFieldObservation.VerifiedAbsent)
        try { MetadataId3StageRewriter.stage(source, stage,
            review(MetadataField.COMMENT, emptyList(), listOf("Overwrite")), source.length()); throw AssertionError() }
        catch (e: MetadataId3StageRewriter.Rejected) {
            assertEquals(MetadataId3StageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS, e.code)
        }
        assertFalse(stage.exists())
    }
    @Test fun multiCommentIsRejectedEvenWhenSameLanguage() = withTemp { source, stage ->
        val one = MetadataId3DescribedTagCodec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.COMMENT,4,"Same")
        source.writeBytes(song(comment=one,extraComment=one))
        assertTrue(report(source).observations[MetadataField.COMMENT] is MetadataFieldObservation.Conflicted)
        try { MetadataId3StageRewriter.stage(source,stage,
            review(MetadataField.COMMENT,listOf("Same"),listOf("Other")),source.length()); throw AssertionError() }
        catch(e:MetadataId3StageRewriter.Rejected) { assertEquals(MetadataId3StageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS,e.code) }
        assertFalse(stage.exists())
    }
    @Test fun unsupportedSyncLyricsAreNotTargeted() = withTemp { source, stage ->
        source.writeBytes(song())
        val rev = review(MetadataField.SYNCED_LYRICS, emptyList(), listOf("[00:01]Song"))
        try { MetadataId3StageRewriter.stage(source,stage,rev,source.length()); throw AssertionError() }
        catch(e:MetadataId3StageRewriter.Rejected) { assertEquals(MetadataId3StageRewriter.Code.UNSUPPORTED_FIELD,e.code) }
    }
}
