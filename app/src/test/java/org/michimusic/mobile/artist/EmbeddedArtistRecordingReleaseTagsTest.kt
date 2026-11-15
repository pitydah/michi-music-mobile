package org.michimusic.mobile.artist

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** User-initiated probe only; these bytes never produce identity writes by themselves. */
class EmbeddedArtistRecordingReleaseTagsTest {
    private val recording = "11111111-1111-4111-8111-111111111111"
    private val release = "22222222-2222-4222-8222-222222222222"

    private fun be(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(n).array()
    private fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun concat(vararg items: ByteArray) = items.fold(byteArrayOf()) { all, b -> all + b }
    private fun sync(n: Int) = byteArrayOf((n shr 21).toByte(), ((n shr 14) and 127).toByte(),
        ((n shr 7) and 127).toByte(), (n and 127).toByte())
    private fun tag(name: String, id: String): ByteArray {
        val payload = concat(byteArrayOf(3), "$name\u0000$id".toByteArray(Charsets.UTF_8))
        return concat("TXXX".toByteArray(), be(payload.size), byteArrayOf(0,0), payload)
    }
    @Test fun id3v23ExtractsRecordingAndReleaseButNotArtist() {
        val frames = concat(tag("MusicBrainz Track Id", recording),tag("MusicBrainz Album Id", release))
        val data = concat("ID3".toByteArray(),byteArrayOf(3,0,0),sync(frames.size),frames)
        val actual = ArtistEmbeddedTagCodec.parse(data)
        assertEquals(setOf(recording),actual.recordingMbids)
        assertEquals(setOf(release),actual.releaseMbids)
        assertEquals(emptySet<String>(),actual.artistMbids)
    }
    @Test fun vorbisIdsAreKeptSeparate() {
        val vendor = "test".toByteArray()
        val records = listOf("MUSICBRAINZ_RECORDINGID=$recording", "MUSICBRAINZ_RELEASEID=$release")
            .map { it.toByteArray() }
        val block = concat(le(vendor.size),vendor,le(records.size),
            *records.map { concat(le(it.size),it) }.toTypedArray())
        val header = byteArrayOf(0x84.toByte(),(block.size shr 16).toByte(),
            (block.size shr 8).toByte(),block.size.toByte())
        val actual = ArtistEmbeddedTagCodec.parse(concat("fLaC".toByteArray(),header,block))
        assertEquals(setOf(recording),actual.recordingMbids)
        assertEquals(setOf(release),actual.releaseMbids)
    }
    @Test fun garbageDoesNotProduceStructuredEvidence() {
        val actual = ArtistEmbeddedTagCodec.parse(ByteArray(512) { 0xff.toByte() })
        assertEquals(emptySet<String>(),actual.recordingMbids)
        assertEquals(emptySet<String>(),actual.releaseMbids)
    }
}
