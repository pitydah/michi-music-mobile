package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ArtistDetailAttributionPolicyTest {
    private fun album(id: String, artist: String, selected: List<String>, all: List<String>?) =
        ArtistDetailAlbumCreditRow(id, artist, selected, all)
    private fun summary(credits: List<String> = listOf("A-ha"), vararg rows: ArtistDetailAlbumCreditRow) =
        ArtistDetailAttributionPolicy.summarize(credits, rows.toList())

    @Test fun completeAlbumCredit() {
        val result = requireNotNull(summary(rows = *arrayOf(album("a", "A-ha", listOf("t1", "t2"), listOf("t1", "t2")))))
        assertEquals(ArtistDetailAlbumCreditKind.ALBUM_CREDIT, result.albumLabels["a"]?.kind)
        assertEquals(1, result.albumCreditCount)
    }
    @Test fun guestOnlyOneOfTenTracks() {
        val all = (1..10).map { "t$it" }
        val result = requireNotNull(summary(rows = *arrayOf(album("g", "Various Artists", listOf("t5"), all))))
        assertEquals("Participación en canciones · 1 de 10", result.albumLabels["g"]?.displayLabel)
        assertEquals(1, result.guestParticipationCount)
    }
    @Test fun albumCreditWithPartialTracksIsNotWholeAlbum() {
        val result = requireNotNull(summary(rows = *arrayOf(album("a", "A-ha", listOf("t2"), listOf("t1", "t2")))))
        assertEquals(ArtistDetailAlbumCreditKind.PARTIAL_ALBUM_CREDIT, result.albumLabels["a"]?.kind)
    }
    @Test fun mergedVerifiedCreditCountsBothSpellingVariants() {
        val result = requireNotNull(summary(listOf("A-ha", "a-ha"), album("a", "a-ha", listOf("t"), listOf("t"))))
        assertEquals(1, result.albumCreditCount)
    }
    @Test fun emptyAlbumOnlyOnExactCredit() {
        assertEquals(ArtistDetailAlbumCreditKind.EMPTY_ALBUM_CREDIT,
            summary(rows = *arrayOf(album("e", "A-ha", emptyList(), emptyList())))?.albumLabels?.get("e")?.kind)
        assertNull(summary(rows = *arrayOf(album("e", "Someone Else", emptyList(), emptyList()))))
    }
    @Test fun absentSourceAlbumFailsClosed() { assertNull(summary(rows = *arrayOf(album("a", "A-ha", listOf("t"), null)))) }
    @Test fun inventedTrackFailsClosed() { assertNull(summary(rows = *arrayOf(album("a", "A-ha", listOf("intruder"), listOf("t"))))) }
    @Test fun duplicateVisibleTrackFailsClosed() { assertNull(summary(rows = *arrayOf(album("a", "A-ha", listOf("t", "t"), listOf("t"))))) }
    @Test fun duplicateSourceTrackFailsClosed() { assertNull(summary(rows = *arrayOf(album("a", "A-ha", listOf("t"), listOf("t", "t"))))) }
    @Test fun duplicateAlbumIdFailsClosed() { assertNull(summary(rows = *arrayOf(album("a", "A-ha", listOf("t1"), listOf("t1")),album("a", "A-ha", listOf("t2"), listOf("t2"))))) }
    @Test fun blankCreditsFailClosed() { assertNull(summary(listOf(" "), album("a", "A-ha", listOf("t"), listOf("t")))) }
    @Test fun aliasesMustBeDistinct() { assertNull(summary(listOf("A-ha", "A-ha"), album("a", "A-ha", listOf("t"), listOf("t")))) }
    @Test fun noAlbumsFailClosed() { assertNull(summary()) }
    @Test fun missingVisibleTrackOnNonemptySourceFailsClosed() { assertNull(summary(rows = *arrayOf(album("a", "A-ha", emptyList(), listOf("t"))))) }
    @Test fun noIdentityIsGrantedBySummary() {
        val result = requireNotNull(summary(rows = *arrayOf(album("x", "Another Artist", listOf("song"), listOf("song")))))
        assertEquals(0, result.albumCreditCount)
        assertEquals(1, result.guestParticipationCount)
        assertNotNull(result.displaySummary)
    }
}
