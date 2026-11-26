package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataArtworkLyricsProbeMp4Test {
    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            val size = 8 + payload.size
            write(byteArrayOf(
                (size ushr 24).toByte(), (size ushr 16).toByte(),
                (size ushr 8).toByte(), size.toByte(),
            ))
            write(type.toByteArray(StandardCharsets.US_ASCII))
            write(payload)
        }.toByteArray()

    private fun data(type: Int, image: ByteArray): ByteArray =
        box("data", ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, type.toByte()))
            write(byteArrayOf(0, 0, 0, 0))
            write(image)
        }.toByteArray())

    private fun png(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(20) { (it + 1).toByte() }

    private fun jpeg(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(20) { (it + 3).toByte() } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun mp4(vararg dataBoxes: ByteArray): ByteArray {
        val covr = if (dataBoxes.isEmpty()) ByteArray(0)
            else box("covr", dataBoxes.fold(ByteArray(0)) { acc, bytes -> acc + bytes })
        val ilst = box("ilst", covr)
        val meta = box("meta", ByteArray(4) + ilst)
        val udta = box("udta", meta)
        val moov = box("moov", udta)
        val ftyp = box("ftyp", "M4A ".toByteArray(StandardCharsets.US_ASCII) +
            ByteArray(8))
        return ftyp + moov
    }

    @Test fun readsPngAndJpegCovrInOrder() {
        val png = png()
        val jpeg = jpeg()
        val result = MetadataArtworkLyricsProbe.inspect(
            mp4(data(14, png), data(13, jpeg)),
        )
        assertTrue("result=$result", result is MetadataArtworkLyricsProbe.Result.Ready)
        val snapshot = (result as MetadataArtworkLyricsProbe.Result.Ready).snapshot
        assertEquals(MetadataArtworkLyricsProbe.Format.MP4, snapshot.format)
        assertEquals(2, snapshot.artworks.size)
        assertEquals(0, snapshot.artworks[0].ordinal)
        assertEquals(1, snapshot.artworks[1].ordinal)
        assertEquals(MetadataArtworkEvidence.Format.MP4_COVR,
            snapshot.artworks[0].info.format)
        assertEquals(3, snapshot.artworks[0].info.type)
        assertEquals("image/png", snapshot.artworks[0].info.mime)
        assertEquals("image/jpeg", snapshot.artworks[1].info.mime)
        assertEquals(png.size, snapshot.artworks[0].info.bytes)
        assertTrue(snapshot.artworks[0].info.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(snapshot.artworks[0].image.contentEquals(png))
        assertTrue(snapshot.artworks[1].image.contentEquals(jpeg))
        assertTrue(snapshot.texts.isEmpty())
    }

    @Test fun noCovrReturnsReadyEmpty() {
        val result = MetadataArtworkLyricsProbe.inspect(mp4())
        assertTrue("result=$result", result is MetadataArtworkLyricsProbe.Result.Ready)
        val snapshot = (result as MetadataArtworkLyricsProbe.Result.Ready).snapshot
        assertEquals(MetadataArtworkLyricsProbe.Format.MP4, snapshot.format)
        assertTrue(snapshot.artworks.isEmpty())
    }

    @Test fun rejectsUnknownCovrDataType() {
        val result = MetadataArtworkLyricsProbe.inspect(mp4(data(0, png())))
        assertEquals(
            MetadataArtworkLyricsProbe.Result.Blocked(
                MetadataArtworkLyricsProbe.Reason.ARTWORK_UNSUPPORTED,
            ),
            result,
        )
    }

    @Test fun rejectsMoreThanSixImages() {
        val boxes = (0 until 7).map { data(14, png()) }.toTypedArray()
        val result = MetadataArtworkLyricsProbe.inspect(mp4(*boxes))
        assertEquals(
            MetadataArtworkLyricsProbe.Result.Blocked(
                MetadataArtworkLyricsProbe.Reason.TOO_MANY_IMAGES,
            ),
            result,
        )
    }

    @Test fun rejectsMalformedBox() {
        val malformed = box("ftyp", ByteArray(8)) +
            byteArrayOf(0, 0, 0, 8, 0x6D, 0x6F, 0x6F, 0x76)
        val result = MetadataArtworkLyricsProbe.inspect(malformed)
        assertEquals(
            MetadataArtworkLyricsProbe.Result.Blocked(
                MetadataArtworkLyricsProbe.Reason.INVALID_STRUCTURE,
            ),
            result,
        )
    }
}
