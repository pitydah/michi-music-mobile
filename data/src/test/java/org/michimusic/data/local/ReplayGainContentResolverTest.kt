package org.michimusic.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReplayGainContentResolverTest {

    @Test
    fun `id3v24 tag read via SeekableAudioSource extracts correct gain`() {
        val tag = buildId3v24Tag(mapOf("REPLAYGAIN_TRACK_GAIN" to "-7.50 dB"))
        val source = ByteArraySeekableSource(tag + byteArrayOf(0x00, 0x00, 0x00, 0x01))

        val result = ReplayGainReader.read(source)
        assertEquals(-7.50f, result.trackGain, 0.01f)
        assertTrue(result.albumGain.isNaN())
    }

    @Test
    fun `flac vorbis comment read via SeekableAudioSource extracts both gains`() {
        val flac = buildFlacWithReplayGain(
            mapOf(
                "REPLAYGAIN_TRACK_GAIN" to "-5.20 dB",
                "REPLAYGAIN_ALBUM_GAIN" to "-6.10 dB",
            )
        )
        val source = ByteArraySeekableSource(flac)

        val result = ReplayGainReader.read(source)
        assertEquals(-5.20f, result.trackGain, 0.01f)
        assertEquals(-6.10f, result.albumGain, 0.01f)
    }

    @Test
    fun `malformed id3 tag with huge size does not OOM and returns NaN`() {
        // Tag with size header indicating 500 MB (exceeds MAX_TAG_SIZE)
        val header = "ID3".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0x04, 0x00, 0x00) +
                byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F) // huge syncsafe size
        val source = ByteArraySeekableSource(header + ByteArray(100))

        val result = ReplayGainReader.read(source)
        assertTrue(result.trackGain.isNaN())
        assertTrue(result.albumGain.isNaN())
    }

    private fun buildId3v24Tag(entries: Map<String, String>): ByteArray {
        val frames = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val descBytes = key.toByteArray(Charsets.ISO_8859_1)
            val valBytes = value.toByteArray(Charsets.ISO_8859_1)
            val frameData = byteArrayOf(0x03) + descBytes + byteArrayOf(0x00) + valBytes
            frames.write("TXXX".toByteArray(Charsets.ISO_8859_1))
            val size = frameData.size
            frames.write(
                byteArrayOf(
                    (size shr 21 and 0x7F).toByte(),
                    (size shr 14 and 0x7F).toByte(),
                    (size shr 7 and 0x7F).toByte(),
                    (size and 0x7F).toByte(),
                )
            )
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
