package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class EmbeddedId3ExtendedTest {
    private fun be(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(),
        (n ushr 8).toByte(), n.toByte())
    private fun ss(n: Int) = byteArrayOf(((n ushr 21) and 127).toByte(),
        ((n ushr 14) and 127).toByte(), ((n ushr 7) and 127).toByte(), (n and 127).toByte())
    private fun frame(version: Int, type: String, text: String): ByteArray {
        val value = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
        return type.toByteArray(Charsets.US_ASCII) +
            (if (version == 4) ss(value.size) else be(value.size)) + byteArrayOf(0,0) + value
    }
    private fun id3(version: Int, invalid: Boolean = false): ByteArray {
        val extended = if (version == 3) be(if (invalid) 100 else 6) + ByteArray(6)
            else ss(if (invalid) 100 else 6) + byteArrayOf(1,0)
        val content = extended + frame(version,"TIT2","Ext Header") +
            frame(version,"TCON","(17)\u0000Jazz")
        return byteArrayOf(73,68,51,version.toByte(),0,0x40.toByte()) + ss(content.size) + content
    }
    @Test fun version23ExtendedHeaderIsSkippedSafely() {
        val tags = EmbeddedTagFieldsReader.read(ByteArrayInputStream(id3(3)))
        assertEquals("Ext Header", tags?.title)
        assertEquals("Rock; Jazz", tags?.genre)
    }
    @Test fun version24ExtendedHeaderIsSkippedSafely() {
        val tags = EmbeddedTagFieldsReader.read(ByteArrayInputStream(id3(4)))
        assertEquals("Ext Header", tags?.title)
        assertEquals("Rock; Jazz", tags?.genre)
    }
    private fun unsynchronisedTag(version: Int, frameUnsync: Boolean): ByteArray {
        val bytes = byteArrayOf(0, 0xFF.toByte(), 0, 0xE0.toByte()) // "ÿà" in Latin-1
        val frame = "TIT2".toByteArray() +
            (if (version == 4) ss(bytes.size) else be(bytes.size)) +
            byteArrayOf(0, if (frameUnsync) 0x02 else 0) + bytes
        return byteArrayOf(73,68,51,version.toByte(),0,
            if (frameUnsync) 0 else 0x80.toByte()) + ss(frame.size) + frame
    }
    @Test fun id3v23TagUnsynchronisationRestoresText() {
        assertEquals("ÿà", EmbeddedTagFieldsReader.read(
            ByteArrayInputStream(unsynchronisedTag(3, false)))?.title)
    }
    @Test fun id3v24FrameUnsynchronisationRestoresText() {
        assertEquals("ÿà", EmbeddedTagFieldsReader.read(
            ByteArrayInputStream(unsynchronisedTag(4, true)))?.title)
    }

    @Test fun invalidExtendedHeaderIsRejected() {
        assertNull(EmbeddedTagFieldsReader.read(ByteArrayInputStream(id3(4,true))))
    }
}
