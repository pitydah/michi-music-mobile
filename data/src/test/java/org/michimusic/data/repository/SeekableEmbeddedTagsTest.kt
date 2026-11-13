package org.michimusic.data.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekableEmbeddedTagsTest {
    private fun be(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun atom(type: String, bytes: ByteArray) =
        be(bytes.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + bytes
    private fun field(type: String, value: String) = atom(type,
        atom("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + value.toByteArray(Charsets.UTF_8)))
    private fun apeItem(key: String, value: ByteArray, flags: Int = 0) =
        le(value.size) + le(flags) + key.toByteArray() + byteArrayOf(0) + value
    private fun ape(items: List<ByteArray>): ByteArray {
        val content = items.fold(byteArrayOf()) { a, b -> a + b }
        return content + "APETAGEX".toByteArray() + le(2000) + le(content.size + 32) +
            le(items.size) + ByteArray(12)
    }
    private fun read(data: ByteArray): EmbeddedTagFields? {
        val file = Files.createTempFile("ui7-embedded-", ".bin")
        return try {
            Files.write(file, data)
            java.io.RandomAccessFile(file.toFile(), "r").channel.use(SeekableEmbeddedTags::read)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun findsMoovAfterLargeAudioBlock() {
        val source = atom("ftyp", "M4A ".toByteArray()) +
            atom("mdat", ByteArray(3 * 1024 * 1024)) +
            atom("moov", atom("udta", atom("meta", ByteArray(4) + atom("ilst",
                field("©gen", "Jazz") + field("©nam", "Una canción") + field("aART", "Artista")))))
        val actual = read(source)
        assertEquals("Jazz", actual?.genre)
        assertEquals("Una canción", actual?.title)
        assertEquals("Artista", actual?.albumArtist)
    }

    @Test fun seekableMp4RecognizesItunesNumericGenre() {
        val gnre = atom("gnre", atom("data", byteArrayOf(0,0,0,0,0,0,0,0,0,18)))
        val source = atom("ftyp", "M4A ".toByteArray()) +
            atom("mdat", ByteArray(2 * 1024 * 1024)) +
            atom("moov", atom("udta", atom("meta", ByteArray(4) + atom("ilst",gnre))))
        assertEquals("Rock", read(source)?.genre)
    }

    @Test fun readsMultipleApeGenresAndDiscFromWavPackTail() {
        val source = "wvpk".toByteArray() + ByteArray(3 * 1024 * 1024) + ape(listOf(
            apeItem("Genre", "Jazz\u0000Blues".toByteArray()),
            apeItem("Title", "Canción APE".toByteArray()),
            apeItem("Disc", "2/3".toByteArray()),
        ))
        val actual = read(source)
        assertEquals(listOf("Jazz", "Blues"), actual?.genres)
        assertEquals("Canción APE", actual?.title)
        assertEquals(2, actual?.discNumber)
        assertEquals(3, actual?.discTotal)
    }

    @Test fun findsApeFooterBeforeId3v1() {
        val source = "wvpk".toByteArray() + ByteArray(256) +
            ape(listOf(apeItem("Genre", "Rock".toByteArray()))) +
            "TAG".toByteArray() + ByteArray(125)
        assertEquals("Rock", read(source)?.genre)
    }

    @Test fun ignoresBinaryApeFieldsAndRejectsOversizedFooter() {
        assertNull(read("wvpk".toByteArray() + ByteArray(80) +
            ape(listOf(apeItem("Genre", "bad".toByteArray(), flags = 2)))))
        assertNull(read("wvpk".toByteArray() + ByteArray(80) +
            "APETAGEX".toByteArray() + le(2000) + le(Int.MAX_VALUE) + le(1) + ByteArray(12)))
    }

    private fun riffChunk(id: String, bytes: ByteArray) = id.toByteArray() + le(bytes.size) + bytes +
        (if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
    private fun iffChunk(id: String, bytes: ByteArray) = id.toByteArray() + be(bytes.size) + bytes +
        (if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf())

    @Test fun findsRiffInfoAfterLargeDataChunk() {
        val info = "INFO".toByteArray() + riffChunk("IGNR", "Bossa Nova".toByteArray()) +
            riffChunk("INAM", "Tema WAV".toByteArray())
        val payload = "WAVE".toByteArray() + riffChunk("data", ByteArray(3 * 1024 * 1024)) +
            riffChunk("LIST", info)
        val source = "RIFF".toByteArray() + le(payload.size) + payload
        assertEquals("Bossa Nova", read(source)?.genre)
        assertEquals("Tema WAV", read(source)?.title)
    }

    @Test fun findsAiffId3AfterLargeSoundChunk() {
        val value = byteArrayOf(3) + "Classical".toByteArray()
        val frame = "TCON".toByteArray() + be(value.size) + byteArrayOf(0, 0) + value
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, frame.size.toByte()) + frame
        val payload = "AIFF".toByteArray() + iffChunk("SSND", ByteArray(3 * 1024 * 1024)) +
            iffChunk("NAME", "Pieza".toByteArray()) + iffChunk("ID3 ", tag)
        val source = "FORM".toByteArray() + be(payload.size) + payload
        assertEquals("Classical", read(source)?.genre)
        assertEquals("Pieza", read(source)?.title)
    }
}
