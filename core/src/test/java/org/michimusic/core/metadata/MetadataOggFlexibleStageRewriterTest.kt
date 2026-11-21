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

/** Private synthetic fixtures only; no Android provider or original song is opened. */
class MetadataOggFlexibleStageRewriterTest {
    private fun le(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(),
        (n ushr 16).toByte(), (n ushr 24).toByte())
    private fun crc(data: ByteArray): Int {
        var value = 0
        data.indices.forEach { index ->
            value = value xor ((if (index in 22..25) 0 else data[index].toInt() and 255) shl 24)
            repeat(8) {
                value = (value shl 1) xor (if (value < 0) 0x04c11db7 else 0)
            }
        }
        return value
    }
    private fun page(sequence: Int, flags: Int, vararg packets: ByteArray): ByteArray {
        val lacing = mutableListOf<Byte>()
        for (packet in packets) {
            var n = packet.size
            while (n >= 255) { lacing += 255.toByte(); n -= 255 }
            lacing += n.toByte()
        }
        val header = ByteArray(27)
        "OggS".toByteArray().copyInto(header)
        header[4] = 0
        header[5] = flags.toByte()
        le(0x12345678).copyInto(header, 14)
        le(sequence).copyInto(header, 18)
        header[26] = lacing.size.toByte()
        val raw = header + lacing.toByteArray() + packets.fold(byteArrayOf()) { acc, packet -> acc + packet }
        le(crc(raw)).copyInto(raw, 22)
        return raw
    }
    private val head = "OpusHead".toByteArray() + byteArrayOf(1, 2) + ByteArray(9)
    private val audio = byteArrayOf(0x31, 0x11, 0x7f, 0, 1, 42, 17)
    private fun comments(title: String, extras: List<String> = listOf("CUSTOM=X", "ARTIST=Cat")): ByteArray {
        val vendor = "Michi".toByteArray()
        val list = listOf("TITLE=$title") + extras
        return "OpusTags".toByteArray() + le(vendor.size) + vendor + le(list.size) +
            list.fold(byteArrayOf()) { acc, field ->
                val raw = field.toByteArray(Charsets.UTF_8)
                acc + le(raw.size) + raw
            }
    }
    private fun original(title: String = "Alpha", extras: List<String> = listOf("CUSTOM=X", "ARTIST=Cat")) =
        page(0, 2, head) + page(1, 0, comments(title, extras)) + page(2, 4, audio)
    private fun review(value: String): MetadataReview.Ready {
        val snapshot = MetadataSnapshot(
            MetadataTarget("", "track", "content://fixture/track", "token"), true,
            mapOf(MetadataField.TITLE to listOf("Alpha")),
            verifiedFields = setOf(MetadataField.TITLE, MetadataField.GENRE),
        )
        return MetadataEditPreview.review(snapshot, listOf(
            MetadataChange.Replace(MetadataField.TITLE, listOf(value)),
        )) as MetadataReview.Ready
    }
    private inline fun files(block: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("michi-ogg-variable").toFile()
        try { block(File(directory, "backup"), File(directory, "stage")) }
        finally { directory.deleteRecursively() }
    }
    private fun observe(file: File): MetadataFieldReadReport =
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { MetadataOggColdReader.observe(it) }
    private fun checkChanged(a: File, b: File, expected: String) {
        assertEquals(MetadataPhysicalContainer.OGG_OPUS, observe(b).container)
        assertEquals(MetadataFieldObservation.Present(listOf(expected)),
            observe(b).observation(MetadataField.TITLE))
        assertArrayEquals(audio, b.readBytes().takeLast(audio.size).toByteArray())
        assertArrayEquals(original(), a.readBytes())
    }

    @Test fun longerOpusTitleRetainsOriginalAudioAndVendor() = files { a, b ->
        a.writeBytes(original())
        val result = MetadataOggFlexibleStageRewriter.stage(a, b, review("A much longer title"), a.length())
        assertFalse(result.canCommitToSource)
        assertTrue(result.candidateBytes > result.originalBytes)
        checkChanged(a, b, "A much longer title")
        assertEquals(MetadataFieldObservation.Present(listOf("Cat")),
            observe(b).observation(MetadataField.ARTIST))
    }
    @Test fun shorterOpusTitleRetainsFollowingAudioPage() = files { a, b ->
        a.writeBytes(original())
        val result = MetadataOggFlexibleStageRewriter.stage(a, b, review("B"), a.length())
        assertTrue(result.candidateBytes < result.originalBytes)
        checkChanged(a, b, "B")
    }
    @Test fun addingPreviouslyAbsentGenreDoesNotSplitLiteralTag() = files { a, b ->
        a.writeBytes(original())
        val r = MetadataEditPreview.review(review("Beta").original,
            listOf(MetadataChange.Replace(MetadataField.GENRE,
                listOf("Jazz; Fusion", "R&B/Soul")))) as MetadataReview.Ready
        MetadataOggFlexibleStageRewriter.stage(a, b, r, a.length())
        assertEquals(MetadataFieldObservation.Present(listOf("Jazz; Fusion", "R&B/Soul")),
            observe(b).observation(MetadataField.GENRE))
        assertArrayEquals(original(), a.readBytes())
    }
    @Test fun clearOnlyTitlePreservesOpaqueCommentsAndAudio() = files { a, b ->
        a.writeBytes(original())
        val r = MetadataEditPreview.review(review("Beta").original,
            listOf(MetadataChange.Clear(MetadataField.TITLE))) as MetadataReview.Ready
        MetadataOggFlexibleStageRewriter.stage(a, b, r, a.length())
        assertEquals(MetadataFieldObservation.VerifiedAbsent, observe(b).observation(MetadataField.TITLE))
        assertEquals(MetadataFieldObservation.Present(listOf("Cat")), observe(b).observation(MetadataField.ARTIST))
        assertArrayEquals(original(), a.readBytes())
    }
    @Test fun rejectsSharedHeaderAndCommentPageFailClosed() = files { a, b ->
        a.writeBytes(page(0, 2, head, comments("Alpha")) + page(1, 4, audio))
        try { MetadataOggFlexibleStageRewriter.stage(a, b, review("Long"), a.length());
            throw AssertionError("expected refusal")
        } catch (e: MetadataOggFlexibleStageRewriter.Refused) {
            assertEquals(MetadataOggFlexibleStageRewriter.Code.UNSUPPORTED_LAYOUT, e.code)
        }
        assertFalse(b.exists())
    }
    @Test fun rejectsCorruptCrcBeforeCreatingStage() = files { a, b ->
        val raw = original()
        raw[22] = (raw[22].toInt() xor 2).toByte()
        a.writeBytes(raw)
        try { MetadataOggFlexibleStageRewriter.stage(a, b, review("Long"), a.length());
            throw AssertionError("expected refusal")
        } catch (e: MetadataOggFlexibleStageRewriter.Refused) {
            assertEquals(MetadataOggFlexibleStageRewriter.Code.INVALID_PAGE, e.code)
        }
        assertFalse(b.exists()); assertArrayEquals(raw, a.readBytes())
    }
    @Test fun rejectsWrongSizeAndOwnerDiffWithoutCreatingStage() = files { a, b ->
        a.writeBytes(original())
        try { MetadataOggFlexibleStageRewriter.stage(a, b, review("Long"), a.length() + 1);
            throw AssertionError("expected refusal")
        } catch (e: MetadataOggFlexibleStageRewriter.Refused) {
            assertEquals(MetadataOggFlexibleStageRewriter.Code.SOURCE_CHANGED, e.code)
        }
        assertFalse(b.exists())
        val forged = review("Long").copy(diffs = listOf(
            MetadataFieldDiff(MetadataField.TITLE, listOf("Wrong"), listOf("Long"))))
        try { MetadataOggFlexibleStageRewriter.stage(a, b, forged, a.length());
            throw AssertionError("expected refusal")
        } catch (e: MetadataOggFlexibleStageRewriter.Refused) {
            assertEquals(MetadataOggFlexibleStageRewriter.Code.INVALID_REVIEW, e.code)
        }
        assertFalse(b.exists())
    }
}
