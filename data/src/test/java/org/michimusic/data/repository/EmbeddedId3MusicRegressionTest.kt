package org.michimusic.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic ID3v2.4 metadata-only regression. No user audio or cover bytes are retained. */
class EmbeddedId3MusicRegressionTest {
    private fun synchsafe(value: Int): ByteArray {
        require(value in 0..0x0FFFFFFF)
        return byteArrayOf(
            ((value ushr 21) and 0x7f).toByte(),
            ((value ushr 14) and 0x7f).toByte(),
            ((value ushr 7) and 0x7f).toByte(),
            (value and 0x7f).toByte(),
        )
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        require(id.length == 4)
        return id.toByteArray(Charsets.US_ASCII) + synchsafe(payload.size) +
            byteArrayOf(0, 0) + payload
    }

    private fun textFrame(id: String, value: String): ByteArray =
        frame(id, byteArrayOf(3) + value.toByteArray(Charsets.UTF_8))

    /** The APIC has a large fake payload to test safe skipping, never actual artwork. */
    private fun realisticPopRockTag(): ByteArray {
        val fakePicture = ByteArray(118_177) { (it % 251).toByte() }
        val apic = byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 3, 0) + fakePicture
        val frames = listOf(
            textFrame("TIT2", "You Win Again"),
            textFrame("TPE1", "Bee Gees"),
            textFrame("TALB", "E.S.P."),
            textFrame("TPE2", "Bee Gees"),
            textFrame("TRCK", "2/11"),
            textFrame("TPOS", "1/1"),
            textFrame("TDRC", "1987"),
            textFrame("TCOM", "Barry, Robin y Maurice Gibb"),
            textFrame("TSSE", "encoder"),
            textFrame("TENC", "tagger"),
            frame("COMM", byteArrayOf(3, 101, 110, 103, 0)),
            frame("PRIV", byteArrayOf(1, 2, 3, 4)),
            frame("APIC", apic),
            // Deliberately after APIC: parser must not mistake artwork for text tags.
            textFrame("TCON", "Pop rock"),
        )
        assertEquals(14, frames.size)
        val payload = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        return "ID3".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(4, 0, 0) + synchsafe(payload.size) + payload
    }

    @Test fun id3v24GenreSurvivesLargeApicAndMapsToStructuredEvidence() {
        val fields = EmbeddedTagFieldsReader.read(ByteArrayInputStream(realisticPopRockTag()))!!
        assertEquals("You Win Again", fields.title)
        assertEquals("Bee Gees", fields.artist)
        assertEquals("E.S.P.", fields.album)
        assertEquals("Bee Gees", fields.albumArtist)
        assertEquals(listOf("Pop rock"), fields.genres)
        assertEquals("Pop rock", fields.genre)
        assertEquals("Barry, Robin y Maurice Gibb", fields.composer)
        assertEquals(2, fields.trackNumber)
        assertEquals(11, fields.trackTotal)
        assertEquals(1, fields.discNumber)
        assertEquals(1, fields.discTotal)
        assertEquals("1987", fields.date)

        val evidence = TagEvidencePolicy.record("", "local_777", 300_000L, 123L, fields)
        assertEquals("Pop rock", evidence.genre)
        assertEquals("Pop rock", evidence.genreValues)
        assertEquals("Pop rock", GenreMetadataMerge.choose(evidence.genre, "Other", null))
        assertEquals(1987, TagEvidencePolicy.exactYear(evidence.date))
        assertTrue(evidence.matchesMediaStore(300_000L, 123L))
        assertFalse(evidence.matchesMediaStore(300_001L, 123L))
    }

    @Test fun providerCompleteMetadataIsNotProofOfEmbeddedTcon() {
        // Explicitly records why automatic first-load verification is not certified:
        // foreground probe budget is reserved for missing provider metadata.
        assertFalse(TagEvidencePolicy.needsProbe("Other", "You Win Again", "Bee Gees", "E.S.P."))
        assertTrue(TagEvidencePolicy.needsProbe("<unknown>", "You Win Again", "Bee Gees", "E.S.P."))
    }
}
