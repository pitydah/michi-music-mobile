package org.michimusic.core.metadata

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataId3DescribedTagCodecTest {
    private val codec = MetadataId3DescribedTagCodec
    @Test fun v23CommentsRoundTripWithUnicode() {
        val content = "Grabación en vivo — São Paulo"
        val parsed = codec.decode(MetadataId3DescribedTagCodec.Kind.COMMENT, 3, codec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.COMMENT, 3, content))!!
        assertEquals(content, parsed.text)
        assertEquals("eng", parsed.language)
        assertEquals("", parsed.description)
        assertTrue(parsed.canonical)
    }
    @Test fun v24LyricsRoundTripWithNewlines() {
        val content = "Línea uno\n歌詞 dos\n🎵"
        val parsed = codec.decode(MetadataId3DescribedTagCodec.Kind.LYRICS, 4, codec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.LYRICS, 4, content))!!
        assertEquals(content, parsed.text)
        assertTrue(parsed.canonical)
    }
    @Test fun v23LyricsSupportUtf16() {
        val entry = codec.decode(MetadataId3DescribedTagCodec.Kind.LYRICS, 3, codec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.LYRICS, 3, "Música antigua"))!!
        assertEquals("Música antigua", entry.text)
    }
    @Test fun rejectUtf8InV23() {
        val wrong = codec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.COMMENT, 4, "Text")
        assertNull(codec.decode(MetadataId3DescribedTagCodec.Kind.COMMENT, 3, wrong))
    }
    @Test fun rejectTruncatedTerminator() {
        val bytes = byteArrayOf(3) + "eng".toByteArray() + "descriptor".toByteArray()
        assertNull(codec.decode(MetadataId3DescribedTagCodec.Kind.COMMENT, 4, bytes))
    }
    @Test fun rejectNonAsciiLanguage() {
        val bytes = codec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.COMMENT, 4, "hello")
        bytes[2] = 0
        assertNull(codec.decode(MetadataId3DescribedTagCodec.Kind.COMMENT, 4, bytes))
    }
    @Test fun rejectsInvalidSurrogateUtf8() {
        val bytes = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0, 0xc3.toByte(), 0x28)
        assertNull(codec.decode(MetadataId3DescribedTagCodec.Kind.COMMENT, 4, bytes))
    }
    @Test fun preservesLanguageAndDescriptorIdentity() {
        val payload = byteArrayOf(3) + "spa".toByteArray(StandardCharsets.US_ASCII) +
            "comentario".toByteArray() + byteArrayOf(0) + "texto".toByteArray()
        val found = codec.decode(MetadataId3DescribedTagCodec.Kind.COMMENT, 4, payload)!!
        assertEquals("spa", found.language)
        assertEquals("comentario", found.description)
        assertFalse(found.canonical)
    }
    @Test fun controlsAreRejected() {
        try { codec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.COMMENT, 4, "bad\u0001text"); throw AssertionError() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun noEmptyTextOrNul() {
        try { codec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.LYRICS, 4, "   "); throw AssertionError() }
        catch (_: IllegalArgumentException) { }
        assertNull(codec.decode(MetadataId3DescribedTagCodec.Kind.LYRICS, 4, byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0)))
    }
    @Test fun rejectsOversizedComments() {
        try { codec.encodeCanonical(MetadataId3DescribedTagCodec.Kind.COMMENT, 4, "x".repeat(8193)); throw AssertionError() }
        catch (_: IllegalArgumentException) { }
    }
}
