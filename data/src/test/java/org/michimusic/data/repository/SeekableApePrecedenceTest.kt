package org.michimusic.data.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekableApePrecedenceTest {
    private fun le(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    @Test fun apeGenreOverridesId3PrefixWithoutLosingTitle() {
        val titleValue = byteArrayOf(3) + "Tema original".toByteArray(Charsets.UTF_8)
        val frame = "TIT2".toByteArray() +
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(titleValue.size).array() +
            byteArrayOf(0, 0) + titleValue
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, frame.size.toByte()) + frame
        val genre = "Jazz".toByteArray(Charsets.UTF_8)
        val item = le(genre.size) + le(0) + "Genre".toByteArray() + byteArrayOf(0) + genre
        val ape = item + "APETAGEX".toByteArray() + le(2000) + le(item.size + 32) + le(1) + ByteArray(12)
        val file = Files.createTempFile("michi-p10-", ".mp3")
        try {
            Files.write(file, tag + ByteArray(3 * 1024 * 1024) + ape)
            val result = java.io.RandomAccessFile(file.toFile(), "r").channel.use(SeekableEmbeddedTags::read)
            assertEquals("Jazz", result?.genre)
            assertEquals("Tema original", result?.title)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
