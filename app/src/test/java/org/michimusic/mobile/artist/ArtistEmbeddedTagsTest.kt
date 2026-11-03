package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class ArtistEmbeddedTagsTest {
    private val first = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val second = "e0c5c6e5-6e03-43b6-a894-928d38d0db95"
    private fun ss(value: Int) = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(),
        ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )
    private fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun le32(value: Int) = be32(value).reversedArray()
    private fun frame(key: String, payload: ByteArray, version: Int = 3): ByteArray =
        key.toByteArray() + (if (version == 4) ss(payload.size) else be32(payload.size)) + byteArrayOf(0, 0) + payload
    private fun txxx(name: String, vararg values: String): ByteArray =
        byteArrayOf(3) + (listOf(name) + values).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)
    private fun id3(vararg frames: ByteArray, version: Int = 3, flags: Byte = 0): ByteArray {
        val body = frames.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, flags) + ss(body.size) + body
    }
    private fun flac(vararg comments: String): ByteArray {
        val vendor = "Michi".toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(le32(vendor.size)); write(vendor)
            write(le32(comments.size))
            for (c in comments) {
                val b = c.toByteArray(StandardCharsets.UTF_8)
                write(le32(b.size)); write(b)
            }
        }.toByteArray()
        val header = byteArrayOf((0x80 or 4).toByte(),0, (body.size ushr 8).toByte(),body.size.toByte())
        return "fLaC".toByteArray() + header + body
    }

    @Test fun id3v23ReadsEmbeddedArtistAndAlbumArtistWithoutSplittingCredit() {
        val result = ArtistEmbeddedTagCodec.parse(id3(
            frame("TXXX", txxx("MusicBrainz Artist Id", first)),
            frame("TXXX", txxx("ARTISTS", "Patti Austin", "James Ingram")),
            frame("TPE2", byteArrayOf(3) + "Patti Austin & James Ingram".toByteArray()),
        ))
        assertEquals(setOf(first), result.artistMbids)
        assertEquals(listOf("Patti Austin", "James Ingram"), result.creditedNames)
        assertEquals(listOf("Patti Austin & James Ingram"), result.albumArtists)
    }

    @Test fun id3v24SupportsSynchsafeFramesAndTwoIdsAreNotAUniqueMatch() {
        val result = ArtistEmbeddedTagCodec.parse(id3(frame("TXXX", txxx("MUSICBRAINZ_ARTISTID", first, second), 4), version = 4))
        val summary = EmbeddedArtistReview(1, 1, result.artistMbids, emptySet(), emptyList(), emptyList())
        assertTrue(summary.hasConflict)
        assertNull(summary.suggestedMbid)
    }

    @Test fun flacVorbisSupportsRepeatedArtistCreditFields() {
        val tags = ArtistEmbeddedTagCodec.parse(flac(
            "MUSICBRAINZ_ARTISTID=$first", "ARTISTS=Patti Austin", "ARTISTS=James Ingram",
            "ALBUMARTIST=Patti Austin & James Ingram", "MUSICBRAINZ_ALBUMARTISTID=$second",
        ))
        assertEquals(setOf(first), tags.artistMbids)
        assertEquals(setOf(second), tags.albumArtistMbids)
        assertEquals(listOf("Patti Austin", "James Ingram"), tags.creditedNames)
        assertEquals(listOf("Patti Austin & James Ingram"), tags.albumArtists)
    }

    @Test fun invalidAndOversizedTagsFailClosedWithoutInventingAnIdentity() {
        assertEquals(emptySet<String>(), ArtistEmbeddedTagCodec.parse(byteArrayOf()).artistMbids)
        assertEquals(emptySet<String>(), ArtistEmbeddedTagCodec.parse(ByteArray(ArtistEmbeddedTagCodec.MAX_TAG_BYTES + 1)).artistMbids)
        assertEquals(emptySet<String>(), ArtistEmbeddedTagCodec.parse(id3(frame("TXXX", txxx("MUSICBRAINZ_ARTISTID", first)), flags = 0x80.toByte())).artistMbids)
        assertEquals(emptySet<String>(), ArtistEmbeddedTagCodec.parse(byteArrayOf(73,68,51,3,0,0,127,127,127,127)).artistMbids)
    }

    @Test fun anArtistNameDoesNotCreateAnMbidWithoutARealTag() {
        val tag = ArtistEmbeddedTagCodec.parse(id3(frame("TPE1", byteArrayOf(3) + "Patti Austin & James Ingram".toByteArray())))
        assertTrue(tag.artistMbids.isEmpty())
        assertTrue(tag.creditedNames.isEmpty())
    }

    @Test fun uniqueIdIsOnlyAProposalAndNotEvidenceThatOneStringMeansOnePerson() {
        val review = EmbeddedArtistReview(1, 1, setOf(first), emptySet(), emptyList(), emptyList())
        assertFalse(review.hasConflict)
        assertEquals(first, review.suggestedMbid)
    }

    private fun atom(name: String, payload: ByteArray): ByteArray =
        be32(payload.size + 8) + name.toByteArray(StandardCharsets.ISO_8859_1) + payload

    private fun itunesFreeform(key: String, value: String): ByteArray {
        val mean = atom("mean", be32(0) + "com.apple.iTunes".toByteArray())
        val name = atom("name", be32(0) + key.toByteArray())
        val data = atom("data", be32(1) + be32(0) + value.toByteArray())
        return atom("----", mean + name + data)
    }

    private fun m4a(vararg tags: ByteArray): ByteArray =
        atom("ftyp", "M4A ".toByteArray() + be32(0)) +
            atom("moov", atom("udta", atom("meta", be32(0) + atom("ilst",
                tags.fold(byteArrayOf()) { acc, tag -> acc + tag }))))

    @Test fun m4aItunesFreeformReadsMbidAndAlbumArtistWithoutInventingCredit() {
        val result = ArtistEmbeddedTagCodec.parse(m4a(
            itunesFreeform("MusicBrainz Artist Id", first),
            itunesFreeform("MusicBrainz Album Artist Id", second),
            itunesFreeform("ARTISTS", "Patti Austin & James Ingram"),
            atom("aART", atom("data", be32(1) + be32(0) + "Varios".toByteArray())),
        ))
        assertEquals(setOf(first), result.artistMbids)
        assertEquals(setOf(second), result.albumArtistMbids)
        assertEquals(listOf("Patti Austin & James Ingram"), result.creditedNames)
        assertEquals(listOf("Varios"), result.albumArtists)
    }

    @Test fun m4aRejectsUntrustedNamespaceAndMalformedAtomWithoutAFalseMbid() {
        val malicious = atom("----", atom("mean", be32(0) + "not.apple".toByteArray()) +
            atom("name", be32(0) + "MusicBrainz Artist Id".toByteArray()) +
            atom("data", be32(1) + be32(0) + first.toByteArray()))
        assertTrue(ArtistEmbeddedTagCodec.parse(m4a(malicious)).artistMbids.isEmpty())
        assertTrue(ArtistEmbeddedTagCodec.parse(m4a(itunesFreeform("MusicBrainz Artist Id", first))
            .copyOfRange(0, 22)).artistMbids.isEmpty())
    }
}
