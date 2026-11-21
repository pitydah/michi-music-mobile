package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ME-10C: source tests for strict typed ID3/Vorbis MusicBrainz identities. */
class MetadataMusicBrainzTagMapTest {
    private val id1 = "12345678-1234-4234-8234-123456789abc"
    private val id2 = "87654321-4321-4321-8321-cba987654321"

    @Test fun canonicalLabelsRoundTripAcrossId3Versions() {
        for (v in listOf(3, 4)) for (field in MetadataMusicBrainzTagMap.id3Fields) {
            val body = MetadataMusicBrainzTagMap.encodeId3(v, field, id1)
            val parsed = MetadataMusicBrainzTagMap.decodeId3(body, v)!!
            assertEquals(field, MetadataMusicBrainzTagMap.fromId3Description(parsed.description))
            assertEquals(id1, parsed.value)
        }
    }
    @Test fun flacPhysicalAliasesAreMappedToOneIdentity() {
        assertEquals(MetadataField.MUSICBRAINZ_RECORDING_ID,
            MetadataMusicBrainzTagMap.fromFlacKey("MUSICBRAINZ_TRACKID"))
        assertEquals(MetadataField.MUSICBRAINZ_RECORDING_ID,
            MetadataMusicBrainzTagMap.fromFlacKey("musicbrainz_recordingid"))
        assertEquals(MetadataField.MUSICBRAINZ_RELEASE_ID,
            MetadataMusicBrainzTagMap.fromFlacKey("MUSICBRAINZ_ALBUMID"))
        assertNull(MetadataMusicBrainzTagMap.fromFlacKey("PRIVATE-KEEP"))
    }
    @Test fun unknownDescriptorCannotBeMisinterpretedAsMbid() {
        assertNull(MetadataMusicBrainzTagMap.fromId3Description("PRIVATE:NOT-AN-ID"))
        assertNull(MetadataMusicBrainzTagMap.decodeId3(byteArrayOf(3, 0xff.toByte()),4))
        assertNull(MetadataMusicBrainzTagMap.decodeId3(byteArrayOf(3) + "BAD".toByteArray(),4))
    }
    @Test fun idValidationIsStrict() {
        assertTrue(MetadataMusicBrainzTagMap.valid(MetadataField.MUSICBRAINZ_RECORDING_ID,listOf(id1)))
        assertFalse(MetadataMusicBrainzTagMap.valid(MetadataField.MUSICBRAINZ_RECORDING_ID,listOf("garbage")))
        assertFalse(MetadataMusicBrainzTagMap.valid(MetadataField.MUSICBRAINZ_RECORDING_ID,listOf(id1,id2)))
        assertFalse(MetadataMusicBrainzTagMap.valid(MetadataField.MUSICBRAINZ_RELEASE_ID,listOf(id1.uppercase())))
        assertTrue(MetadataMusicBrainzTagMap.valid(MetadataField.MUSICBRAINZ_ARTIST_ID,listOf(id1,id2)))
        assertFalse(MetadataMusicBrainzTagMap.valid(MetadataField.TITLE,listOf(id1)))
    }
}
