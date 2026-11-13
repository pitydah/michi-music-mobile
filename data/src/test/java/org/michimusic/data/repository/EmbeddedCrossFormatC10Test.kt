package org.michimusic.data.repository

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic container fixtures: exercises actual EmbeddedTagFieldsReader, not Android providers. */
class EmbeddedCrossFormatC10Test {
    private fun le(n: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun be(n: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.BIG_ENDIAN).putInt(n).array()
    private fun comments(vararg fields: String): ByteArray {
        val vendor = "Michi synthetic".toByteArray(Charsets.UTF_8)
        return le(vendor.size) + vendor + le(fields.size) + fields.fold(byteArrayOf()) { acc, field ->
            val text = field.toByteArray(Charsets.UTF_8)
            acc + le(text.size) + text
        }
    }
    private fun atom(name: String, body: ByteArray): ByteArray =
        be(body.size + 8) + name.toByteArray(Charsets.ISO_8859_1) + body

    @Test fun flacPreservesMultipleGenreFieldsAndExtendedMetadata() {
        val body = comments("GENRE=Jazz", "GENRE=Fusion", "COMPOSER=John Doe",
            "TRACKNUMBER=2/11", "DATE=1987")
        val bytes = "fLaC".toByteArray() + byteArrayOf(0x84.toByte(),
            (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
        val tags = EmbeddedTagFieldsReader.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("Jazz", "Fusion"), tags?.genres)
        assertEquals("John Doe", tags?.composer)
        assertEquals(2, tags?.trackNumber)
        assertEquals(11, tags?.trackTotal)
        assertEquals("1987", tags?.date)
    }

    @Test fun opusTagsReadWithoutSplittingLiteralSemicolon() {
        val payload = "OpusTags".toByteArray() + comments("GENRE=Jazz; Fusion", "TITLE=Song")
        require(payload.size < 255)
        val header = ByteArray(27)
        "OggS".toByteArray().copyInto(header, 0)
        header[4] = 0 // Ogg version
        header[26] = 1 // one lacing segment
        val bytes = header + byteArrayOf(payload.size.toByte()) + payload
        val tags = EmbeddedTagFieldsReader.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("Jazz; Fusion"), tags?.genres)
        assertEquals("Song", tags?.title)
    }

    @Test fun numericMp4GenreAboveOriginal80IsRecognized() {
        // MP4 gnre is one-based: 86 means ID3v1 genre 85 = Bebop.
        val data = atom("data", ByteArray(8) + byteArrayOf(0, 86))
        val bytes = atom("ftyp", "M4A ".toByteArray()) +
            atom("moov", atom("udta", atom("meta", ByteArray(4) + atom("ilst", atom("gnre", data)))))
        val tags = EmbeddedTagFieldsReader.read(ByteArrayInputStream(bytes))
        assertEquals("Bebop", tags?.genre)
    }

    @Test fun flacTruncationCannotPublishPartialGenre() {
        val bytes = "fLaC".toByteArray() + byteArrayOf(0x84.toByte(), 0, 1, 0) + ByteArray(12)
        assertNull(EmbeddedTagFieldsReader.read(ByteArrayInputStream(bytes)))
    }

    @Test fun malformedOggSegmentCannotPublishTags() {
        val header = ByteArray(27)
        "OggS".toByteArray().copyInto(header)
        header[26] = 1
        assertNull(EmbeddedTagFieldsReader.read(ByteArrayInputStream(header + byteArrayOf(255.toByte()))))
    }
}
