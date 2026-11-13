package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SeekableFlacId3v1Test {
    private fun little(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun comments(vararg tags: String): ByteArray {
        val vendor = "Michi Test".toByteArray(Charsets.UTF_8)
        val entries = tags.map { it.toByteArray(Charsets.UTF_8) }
        return little(vendor.size) + vendor + little(entries.size) +
            entries.fold(ByteArray(0)) { acc, item -> acc + little(item.size) + item }
    }
    private fun writeFlacBlock(file: RandomAccessFile, kind: Int, last: Boolean, size: Int) {
        file.write(if (last) kind or 128 else kind)
        file.write((size ushr 16) and 255)
        file.write((size ushr 8) and 255)
        file.write(size and 255)
    }
    @Test fun flacCommentsAfterLargePictureAreRecoveredBySeek() {
        val temp = File.createTempFile("michi-large-cover-", ".flac")
        try {
            RandomAccessFile(temp, "rw").use { file ->
                file.write("fLaC".toByteArray())
                writeFlacBlock(file, 6, false, 3 * 1024 * 1024)
                file.seek(file.filePointer + 3 * 1024 * 1024)
                val data = comments("GENRE=Jazz", "GENRE=Bossa Nova", "TITLE=Título", "DISCNUMBER=2/3")
                writeFlacBlock(file, 4, true, data.size)
                file.write(data)
            }
            FileInputStream(temp).channel.use { channel ->
                val tags = SeekableEmbeddedTags.read(channel)
                assertEquals("Jazz; Bossa Nova", tags?.genre)
                assertEquals("Título", tags?.title)
                assertEquals(2, tags?.discNumber)
                assertEquals(3, tags?.discTotal)
            }
        } finally { temp.delete() }
    }
    @Test fun oldMp3Id3v1OnlyFileHasGenreAndDiscFieldsAreNotInvented() {
        val temp = File.createTempFile("michi-id3v1-", ".mp3")
        try {
            RandomAccessFile(temp, "rw").use { file ->
                file.setLength(3L * 1024 * 1024)
                file.seek(file.length())
                val tag = ByteArray(128)
                "TAG".toByteArray().copyInto(tag, 0)
                "Only Old Tags".toByteArray().copyInto(tag, 3)
                "Real Artist".toByteArray().copyInto(tag, 33)
                "1987".toByteArray().copyInto(tag, 93)
                tag[125] = 0
                tag[126] = 4
                tag[127] = 17  // official ID3v1 Rock
                file.write(tag)
            }
            FileInputStream(temp).channel.use { channel ->
                val tags = SeekableEmbeddedTags.read(channel)
                assertEquals("Rock", tags?.genre)
                assertEquals("Only Old Tags", tags?.title)
                assertEquals("Real Artist", tags?.artist)
                assertEquals(4, tags?.trackNumber)
                assertEquals("1987", tags?.date)
                assertNull(tags?.discNumber)
            }
        } finally { temp.delete() }
    }
    @Test fun invalidId3v1GenreNeverCreatesStyle() {
        val temp = File.createTempFile("michi-id3v1-unknown-", ".mp3")
        try {
            RandomAccessFile(temp, "rw").use { file ->
                val tag = ByteArray(128)
                "TAG".toByteArray().copyInto(tag, 0)
                "Unclassified".toByteArray().copyInto(tag, 3)
                tag[127] = 255.toByte()
                file.write(ByteArray(96))
                file.write(tag)
            }
            FileInputStream(temp).channel.use { channel ->
                assertEquals("Unclassified", SeekableEmbeddedTags.read(channel)?.title)
                assertNull(SeekableEmbeddedTags.read(channel)?.genre)
            }
        } finally { temp.delete() }
    }
    @Test fun fakeFlacLengthIsRejected() {
        val temp = File.createTempFile("michi-flac-invalid-", ".flac")
        try {
            RandomAccessFile(temp, "rw").use { file ->
                file.write("fLaC".toByteArray())
                writeFlacBlock(file, 4, true, 512)
                file.write(byteArrayOf(0, 1))
            }
            FileInputStream(temp).channel.use { channel ->
                assertNull(SeekableEmbeddedTags.read(channel))
            }
        } finally { temp.delete() }
    }
}
