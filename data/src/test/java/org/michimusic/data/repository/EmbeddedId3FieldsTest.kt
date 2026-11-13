package org.michimusic.data.repository

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedId3FieldsTest {
    private fun synchsafe(n: Int) = byteArrayOf(
        ((n ushr 21) and 127).toByte(), ((n ushr 14) and 127).toByte(),
        ((n ushr 7) and 127).toByte(), (n and 127).toByte())
    private fun frame(version: Int, genre: String): ByteArray {
        val contents = byteArrayOf(3) + genre.toByteArray(Charsets.UTF_8)
        return when(version) {
            2 -> "TCO".toByteArray() + byteArrayOf(0, 0, contents.size.toByte()) + contents
            3 -> "TCON".toByteArray() + byteArrayOf(0, 0, 0, contents.size.toByte(), 0, 0) + contents
            else -> "TCON".toByteArray() + synchsafe(contents.size) + byteArrayOf(0,0) + contents
        }
    }
    private fun read(version: Int, genre: String): String? {
        val f = frame(version, genre)
        val tag = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + synchsafe(f.size) + f
        return EmbeddedTagFieldsReader.read(ByteArrayInputStream(tag))?.genre
    }
    @Test fun version22() = assertEquals("Jazz", read(2, "Jazz"))
    @Test fun version23() = assertEquals("Rock", read(3, "Rock"))
    @Test fun version24AndCompositeValue() = assertEquals("R&B/Soul", read(4, "R&B/Soul"))
    @Test fun rejectsGarbage() = assertNull(EmbeddedTagFieldsReader.read(ByteArrayInputStream(byteArrayOf(1,2,3))))
}
