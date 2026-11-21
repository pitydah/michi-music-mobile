package org.michimusic.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.data.cache.CachedTrack

class MetadataTrackIndexProjectionTest {
    private val target=MetadataTarget("","song","content://media/external/audio/1","reader")
    private val row=CachedTrack(id="song",filepath=target.uri,size=120,
        title="Original",artist="Artist",album="Album",genre="Jazz",year=2020,trackNumber=1)
    private fun delta(values:Map<MetadataField,List<String>>)=MetadataSourceIndexDelta.Delta(
        target,"12345678-1234-4123-8123-123456789abc","a".repeat(64),
        values,emptySet(),121)
    @Test fun unchangedScalarsAreNotBlanked() {
        val result=MetadataTrackIndexProjection.propose(row,delta(mapOf(
            MetadataField.TITLE to listOf("New"))))!!
        assertEquals("New",result.title)
        assertEquals("Artist",result.artist)
        assertEquals("Jazz",result.genre)
    }
    @Test fun cannotFlattenMultivaluedArtist() {
        assertNull(MetadataTrackIndexProjection.propose(row,delta(mapOf(
            MetadataField.ARTIST to listOf("One","Two")))))
    }
    @Test fun cannotFlattenMultivaluedGenre() {
        assertNull(MetadataTrackIndexProjection.propose(row,delta(mapOf(
            MetadataField.GENRE to listOf("Jazz","Fusion")))))
    }
    @Test fun validTrackNumbersRetainIntegerSemantics() {
        assertEquals(4,MetadataTrackIndexProjection.propose(row,delta(mapOf(
            MetadataField.TRACK_NUMBER to listOf("04"))))!!.trackNumber)
    }
    @Test fun rejectedAmbiguousDates() {
        assertNull(MetadataTrackIndexProjection.propose(row,delta(mapOf(
            MetadataField.RELEASE_DATE to listOf("next year")))))
    }
    @Test fun noUnverifiedOwnerIsAccepted() {
        val wrong=delta(mapOf(MetadataField.TITLE to listOf("After")))
            .copy(target=MetadataTarget("other","song",target.uri,"reader"))
        assertNull(MetadataTrackIndexProjection.propose(row,wrong))
    }
    @Test fun explicitVerifiedClearDoesNotInferAbsentElsewhere() {
        val result=MetadataTrackIndexProjection.propose(row,delta(mapOf(
            MetadataField.ALBUM to emptyList())))!!
        assertEquals("",result.album)
        assertTrue(result.artist.isNotEmpty())
    }
}
