package org.michimusic.data.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic metadata-only fixtures; no copyrighted audio is stored or read. */
class SeekableMultiformatC12Test {
    private fun be(n: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.BIG_ENDIAN).putInt(n).array()
    private fun le(n: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun atom(type: String, bytes: ByteArray) =
        be(bytes.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + bytes
    private fun riffChunk(type: String, bytes: ByteArray) =
        type.toByteArray(Charsets.US_ASCII) + le(bytes.size) + bytes +
            (if (bytes.size % 2 != 0) byteArrayOf(0) else byteArrayOf())
    private fun aiffChunk(type: String, bytes: ByteArray) =
        type.toByteArray(Charsets.US_ASCII) + be(bytes.size) + bytes +
            (if (bytes.size % 2 != 0) byteArrayOf(0) else byteArrayOf())
    private fun comment(vararg entries: String): ByteArray {
        val vendor = "Michi C12 test".toByteArray(Charsets.UTF_8)
        return le(vendor.size) + vendor + le(entries.size) + entries.fold(byteArrayOf()) { a, e ->
            val data = e.toByteArray(Charsets.UTF_8)
            a + le(data.size) + data
        }
    }
    private fun seek(bytes: ByteArray): EmbeddedTagFields? {
        val file = Files.createTempFile("michi-c12-fixture", ".bin")
        return try {
            Files.write(file, bytes)
            FileChannel.open(file, StandardOpenOption.READ).use(SeekableEmbeddedTags::read)
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun lateMoovAfterLargeMdatStillFindsOriginalGenreAndComposer() {
        val text = atom("data", ByteArray(8) + "Pop rock".toByteArray())
        val composer = atom("data", ByteArray(8) + "Composer C".toByteArray())
        val metadata = atom("moov", atom("udta", atom("meta", ByteArray(4) + atom("ilst",
            atom("©gen", text) + atom("©wrt", composer)))))
        val bytes = atom("ftyp", "M4A ".toByteArray()) +
            atom("mdat", ByteArray(2 * 1024 * 1024 + 200)) + metadata
        val tags = seek(bytes)
        assertEquals(listOf("Pop rock"), tags?.genres)
        assertEquals("Composer C", tags?.composer)
    }
    @Test fun flacPictureLargerThanStreamBudgetDoesNotHideLaterVorbisComments() {
        val largePicture = ByteArray(2 * 1024 * 1024 + 120)
        val picture = byteArrayOf(6, (largePicture.size ushr 16).toByte(),
            (largePicture.size ushr 8).toByte(), largePicture.size.toByte()) + largePicture
        val values = comment("GENRE=Jazz", "GENRE=Fusion", "COMPOSER=Composer D")
        val vorbis = byteArrayOf(0x84.toByte(), (values.size ushr 16).toByte(),
            (values.size ushr 8).toByte(), values.size.toByte()) + values
        val tags = seek("fLaC".toByteArray() + picture + vorbis)
        assertEquals(listOf("Jazz", "Fusion"), tags?.genres)
        assertEquals("Composer D", tags?.composer)
    }
    @Test fun wavTrailingInfoIsReadWithoutLoadingLargeDataChunk() {
        val info = riffChunk("IGNR", "Jazz; Fusion".toByteArray()) +
            riffChunk("INAM", "Song".toByteArray())
        val chunks = riffChunk("data", ByteArray(2 * 1024 * 1024 + 100)) +
            riffChunk("LIST", "INFO".toByteArray() + info)
        val tags = seek("RIFF".toByteArray() + le(chunks.size + 4) + "WAVE".toByteArray() + chunks)
        assertEquals(listOf("Jazz; Fusion"), tags?.genres)
        assertEquals("Song", tags?.title)
    }
    @Test fun aiffTrailingAuthorAndTitleRemainReadable() {
        val chunks = aiffChunk("SSND", ByteArray(2 * 1024 * 1024 + 10)) +
            aiffChunk("AUTH", "Artist".toByteArray()) +
            aiffChunk("NAME", "Recording".toByteArray())
        val tags = seek("FORM".toByteArray() + be(chunks.size + 4) + "AIFF".toByteArray() + chunks)
        assertEquals("Artist", tags?.artist)
        assertEquals("Recording", tags?.title)
    }
    @Test fun wavPackApeV2TailReadsGenreButSkipsBinaryTagValues() {
        fun item(key: String, value: ByteArray, flags: Int = 0): ByteArray =
            le(value.size) + le(flags) + key.toByteArray() + byteArrayOf(0) + value
        val valid = item("Genre", "R&B/Soul".toByteArray())
        val binary = item("Cover Art", byteArrayOf(1, 2, 3, 4), 2)
        val items = valid + binary
        val footer = "APETAGEX".toByteArray() + le(2000) + le(items.size + 32) +
            le(2) + le(0) + ByteArray(8)
        val tags = seek("wvpk".toByteArray() + ByteArray(96) + items + footer)
        assertEquals(listOf("R&B/Soul"), tags?.genres)
    }
    @Test fun id3v1TailRemainsAvailableWithoutModernId3Prefix() {
        val tail = ByteArray(128)
        "TAG".toByteArray().copyInto(tail)
        "Old recording".toByteArray().copyInto(tail, 3)
        "Artist".toByteArray().copyInto(tail, 33)
        "1990".toByteArray().copyInto(tail, 93)
        tail[125] = 0
        tail[126] = 2
        tail[127] = 85.toByte() // Extended table: Bebop
        val tags = seek(ByteArray(1024) + tail)
        assertEquals("Old recording", tags?.title)
        assertEquals(listOf("Bebop"), tags?.genres)
        assertEquals(2, tags?.trackNumber)
    }
    @Test fun malformedApeV2FooterNeverProducesGenre() {
        val footer = "APETAGEX".toByteArray() + le(2000) + le(0x7FFFFFFF) +
            le(2049) + le(0) + ByteArray(8)
        assertNull(seek("wvpk".toByteArray() + ByteArray(16) + footer))
    }
    @Test fun malformedLateMoovDoesNotPublishInventedGenre() {
        val forgedMoov = be(0x7FFFFFFF) + "moov".toByteArray() + "bad".toByteArray()
        assertNull(seek(atom("ftyp", "M4A ".toByteArray()) + forgedMoov))
    }
}
