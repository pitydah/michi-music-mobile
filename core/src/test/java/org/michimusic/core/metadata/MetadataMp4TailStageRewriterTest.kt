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

/** All input audio is a synthetic private fixture, never a library song. */
class MetadataMp4TailStageRewriterTest {
    private fun be(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(),
        (n ushr 8).toByte(), n.toByte())
    private fun atom(name: String, body: ByteArray) = be(body.size + 8) +
        name.toByteArray(Charsets.ISO_8859_1) + body
    private val audio = ByteArray(4096) { (it * 29 + 13).toByte() }
    private fun fixture(title: String = "Alpha", tailMoov: Boolean = true,
        duplicate: Boolean = false): ByteArray {
        val titleAtom = atom("©nam", atom("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) +
            title.toByteArray(Charsets.UTF_8)))
        val artistAtom = atom("©ART", atom("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) +
            "Cat".toByteArray(Charsets.UTF_8)))
        val moov = atom("moov", atom("udta", atom("meta", ByteArray(4) +
            atom("ilst", titleAtom + artistAtom + if (duplicate) titleAtom else byteArrayOf()))))
        val ftyp = atom("ftyp", "M4A ".toByteArray() + ByteArray(4) + "M4A ".toByteArray())
        val mdat = atom("mdat", audio)
        return if (tailMoov) ftyp + mdat + moov else ftyp + moov + mdat
    }
    private fun review(value: String): MetadataReview.Ready {
        val snapshot = MetadataSnapshot(
            MetadataTarget("", "track", "content://fixture/track", "token"), true,
            mapOf(MetadataField.TITLE to listOf("Alpha")),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        return MetadataEditPreview.review(snapshot, listOf(
            MetadataChange.Replace(MetadataField.TITLE, listOf(value)))) as MetadataReview.Ready
    }
    private inline fun files(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("michi-mp4-tail").toFile()
        try { block(File(root, "backup"), File(root, "stage")) }
        finally { root.deleteRecursively() }
    }
    private fun report(file: File): MetadataFieldReadReport =
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { MetadataMp4ColdReader.observe(it) }
    private fun verifyCandidate(a: File, b: File, updated: String) {
        assertEquals(MetadataFieldObservation.Present(listOf(updated)),
            report(b).observation(MetadataField.TITLE))
        assertEquals(MetadataFieldObservation.Present(listOf("Cat")),
            report(b).observation(MetadataField.ARTIST))
        assertArrayEquals(fixture(), a.readBytes())
        assertTrue(b.readBytes().indexOfSubarray(audio) >= 0)
    }
    private fun ByteArray.indexOfSubarray(other: ByteArray): Int {
        for (i in 0..size - other.size) if (other.indices.all { this[i + it] == other[it] }) return i
        return -1
    }

    @Test fun terminalMoovLongerTitlePreservesMdatExactly() = files { a, b ->
        a.writeBytes(fixture())
        val outcome = MetadataMp4TailStageRewriter.stage(a, b, review("Longer than Alpha"), a.length())
        assertFalse(outcome.canCommitToSource)
        assertTrue(outcome.candidateBytes > outcome.originalBytes)
        verifyCandidate(a, b, "Longer than Alpha")
    }
    @Test fun terminalMoovShorterTitlePreservesMedia() = files { a, b ->
        a.writeBytes(fixture())
        val outcome = MetadataMp4TailStageRewriter.stage(a, b, review("B"), a.length())
        assertTrue(outcome.candidateBytes < outcome.originalBytes)
        verifyCandidate(a, b, "B")
    }
    @Test fun moovBeforeMdatIsRefused() = files { a, b ->
        val raw = fixture(tailMoov = false)
        a.writeBytes(raw)
        try { MetadataMp4TailStageRewriter.stage(a, b, review("Longer"), a.length())
            throw AssertionError("expected refusal")
        } catch (e: MetadataMp4TailStageRewriter.Refused) {
            assertEquals(MetadataMp4TailStageRewriter.Code.UNSUPPORTED_LAYOUT, e.code)
        }
        assertFalse(b.exists()); assertArrayEquals(raw, a.readBytes())
    }
    @Test fun duplicateTitleAndCorruptLengthRejectWithoutStage() = files { a, b ->
        val duplicate = fixture(duplicate = true)
        a.writeBytes(duplicate)
        try { MetadataMp4TailStageRewriter.stage(a, b, review("Longer"), a.length())
            throw AssertionError("expected refusal")
        } catch (e: MetadataMp4TailStageRewriter.Refused) {
            assertEquals(MetadataMp4TailStageRewriter.Code.UNSUPPORTED_CHANGE, e.code)
        }
        assertFalse(b.exists()); assertArrayEquals(duplicate, a.readBytes())
        val malformed = fixture().also { it[0] = 0x7f }
        a.writeBytes(malformed)
        try { MetadataMp4TailStageRewriter.stage(a, b, review("Longer"), a.length())
            throw AssertionError("expected refusal")
        } catch (e: MetadataMp4TailStageRewriter.Refused) {
            assertEquals(MetadataMp4TailStageRewriter.Code.UNSUPPORTED_LAYOUT, e.code)
        }
        assertFalse(b.exists()); assertArrayEquals(malformed, a.readBytes())
    }
    @Test fun inconsistentBeforeAndForgedReadyAreRejected() = files { a, b ->
        a.writeBytes(fixture())
        val forged = review("Bravo").copy(diffs = listOf(MetadataFieldDiff(
            MetadataField.TITLE, listOf("Wrong"), listOf("Bravo"))))
        try { MetadataMp4TailStageRewriter.stage(a, b, forged, a.length())
            throw AssertionError("expected refusal")
        } catch (e: MetadataMp4TailStageRewriter.Refused) {
            assertEquals(MetadataMp4TailStageRewriter.Code.INVALID_REVIEW, e.code)
        }
        assertFalse(b.exists())
        try { MetadataMp4TailStageRewriter.stage(a, b, review("Bravo"), a.length() - 1)
            throw AssertionError("expected refusal")
        } catch (e: MetadataMp4TailStageRewriter.Refused) {
            assertEquals(MetadataMp4TailStageRewriter.Code.SOURCE_CHANGED, e.code)
        }
        assertFalse(b.exists())
    }
}
