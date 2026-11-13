package org.michimusic.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedWavFieldsTest {
    private fun le(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(),
        (value ushr 16).toByte(), (value ushr 24).toByte())
    private fun chunk(id: String, bytes: ByteArray): ByteArray =
        id.toByteArray(Charsets.US_ASCII) + le(bytes.size) + bytes +
            (if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
    @Test fun readsRiffInfo() {
        val info = "INFO".toByteArray() +
            chunk("IGNR", "Jazz".toByteArray() + byteArrayOf(0)) +
            chunk("INAM", "Mi tema".toByteArray() + byteArrayOf(0))
        val wave = "WAVE".toByteArray() + chunk("LIST", info)
        val bytes = "RIFF".toByteArray() + le(wave.size) + wave
        val result = EmbeddedTagFieldsReader.read(ByteArrayInputStream(bytes))
        assertEquals("Jazz", result?.genre)
        assertEquals("Mi tema", result?.title)
    }
}
