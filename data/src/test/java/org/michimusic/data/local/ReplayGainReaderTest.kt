package org.michimusic.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReplayGainReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fileNotFound_returnsNaN() {
        val result = ReplayGainReader.read("/nonexistent/file.mp3")
        assertTrue(result.trackGain.isNaN())
        assertTrue(result.albumGain.isNaN())
    }

    @Test
    fun emptyFile_returnsNaN() {
        val f = tempFolder.newFile("empty.mp3")
        val result = ReplayGainReader.read(f.absolutePath)
        assertTrue(result.trackGain.isNaN())
        assertTrue(result.albumGain.isNaN())
    }

    @Test
    fun noHeader_returnsNaN() {
        val f = tempFolder.newFile("raw.mp3")
        f.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        val result = ReplayGainReader.read(f.absolutePath)
        assertTrue(result.trackGain.isNaN())
    }

    @Test
    fun id3v24_trackGainOnly() {
        val f = tempFolder.newFile("id3v24_track.mp3")
        val tag = buildId3v24Tag(mapOf("REPLAYGAIN_TRACK_GAIN" to "-6.50 dB"))
        f.writeBytes(tag + byteArrayOf(0x00, 0x00, 0x00, 0x01))
        val result = ReplayGainReader.read(f.absolutePath)
        assertEquals(-6.50f, result.trackGain, 0.01f)
        assertTrue(result.albumGain.isNaN())
    }

    @Test
    fun id3v24_bothGains() {
        val f = tempFolder.newFile("id3v24_both.mp3")
        val tag = buildId3v24Tag(mapOf(
            "REPLAYGAIN_TRACK_GAIN" to "-8.20 dB",
            "REPLAYGAIN_ALBUM_GAIN" to "-7.10 dB",
        ))
        f.writeBytes(tag + byteArrayOf(0x00, 0x00, 0x00, 0x01))
        val result = ReplayGainReader.read(f.absolutePath)
        assertEquals(-8.20f, result.trackGain, 0.01f)
        assertEquals(-7.10f, result.albumGain, 0.01f)
    }

    @Test
    fun id3v24_positiveGain() {
        val f = tempFolder.newFile("id3v24_positive.mp3")
        val tag = buildId3v24Tag(mapOf("REPLAYGAIN_TRACK_GAIN" to "+3.00 dB"))
        f.writeBytes(tag + byteArrayOf(0x00, 0x00, 0x00, 0x01))
        val result = ReplayGainReader.read(f.absolutePath)
        assertEquals(3.00f, result.trackGain, 0.01f)
    }

    @Test
    fun id3v24_unknownDescription_ignored() {
        val f = tempFolder.newFile("id3v24_unknown.mp3")
        val tag = buildId3v24Tag(mapOf("SOMETHING_ELSE" to "-5.00 dB"))
        f.writeBytes(tag + byteArrayOf(0x00, 0x00, 0x00, 0x01))
        val result = ReplayGainReader.read(f.absolutePath)
        assertTrue(result.trackGain.isNaN())
        assertTrue(result.albumGain.isNaN())
    }

    @Test
    fun flac_vorbisComment() {
        val f = tempFolder.newFile("test.flac")
        val flac = buildFlacWithReplayGain(mapOf(
            "REPLAYGAIN_TRACK_GAIN" to "-9.30 dB",
            "REPLAYGAIN_ALBUM_GAIN" to "-8.00 dB",
        ))
        f.writeBytes(flac)
        val result = ReplayGainReader.read(f.absolutePath)
        assertEquals(-9.30f, result.trackGain, 0.01f)
        assertEquals(-8.00f, result.albumGain, 0.01f)
    }

    @Test
    fun flac_noReplayGain_returnsNaN() {
        val f = tempFolder.newFile("plain.flac")
        val flac = buildFlacWithReplayGain(emptyMap())
        f.writeBytes(flac)
        val result = ReplayGainReader.read(f.absolutePath)
        assertTrue(result.trackGain.isNaN())
        assertTrue(result.albumGain.isNaN())
    }

    @Test
    fun parseGain_invalidFormat_returnsNaN() {
        val f = tempFolder.newFile("invalid.mp3")
        val tag = buildId3v24Tag(mapOf("REPLAYGAIN_TRACK_GAIN" to "not-a-number"))
        f.writeBytes(tag)
        val result = ReplayGainReader.read(f.absolutePath)
        assertTrue(result.trackGain.isNaN())
    }

    private fun buildId3v24Tag(entries: Map<String, String>): ByteArray {
        val frames = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val descBytes = key.toByteArray(Charsets.ISO_8859_1)
            val valBytes = value.toByteArray(Charsets.ISO_8859_1)
            val frameData = byteArrayOf(0x03) + descBytes + byteArrayOf(0x00) + valBytes
            frames.write("TXXX".toByteArray(Charsets.ISO_8859_1))
            val size = frameData.size
            frames.write(byteArrayOf(
                (size shr 21 and 0x7F).toByte(),
                (size shr 14 and 0x7F).toByte(),
                (size shr 7 and 0x7F).toByte(),
                (size and 0x7F).toByte(),
            ))
            frames.write(byteArrayOf(0x00, 0x00))
            frames.write(frameData)
        }

        val framesBytes = frames.toByteArray()
        val tagSize = framesBytes.size
        val header = "ID3".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0x04, 0x00, 0x00) +
                byteArrayOf(
                    (tagSize shr 21 and 0x7F).toByte(),
                    (tagSize shr 14 and 0x7F).toByte(),
                    (tagSize shr 7 and 0x7F).toByte(),
                    (tagSize and 0x7F).toByte(),
                )
        return header + framesBytes
    }

    private fun buildFlacWithReplayGain(entries: Map<String, String>): ByteArray {
        val comments = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            comments.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(entry.size).array())
            comments.write(entry)
        }

        val commentsBytes = comments.toByteArray()
        val vendorString = "reference libFLAC".toByteArray(Charsets.UTF_8)
        val blockBody = ByteArrayOutputStream()
        blockBody.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendorString.size).array())
        blockBody.write(vendorString)
        blockBody.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(entries.size).array())
        blockBody.write(commentsBytes)

        val body = blockBody.toByteArray()
        val header = byteArrayOf(
            0x84.toByte(), // last-metadata-block flag + block type 4 (VORBIS_COMMENT)
            0x00,
            0x00,
            (body.size and 0xFF).toByte(),
        )

        return "fLaC".toByteArray(Charsets.ISO_8859_1) + header + body
    }
}

private class ByteArrayOutputStream : java.io.ByteArrayOutputStream() {
    override fun write(b: ByteArray) = write(b, 0, b.size)
}
