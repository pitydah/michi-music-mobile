package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiGenreContextPolicyTest {
    private fun track(id: String, artist: String = "Artist", genre: String = "Rock",
                      local: Boolean = true) = MichiIntelligencePolicy.Candidate(
        key = MichiIntelligencePolicy.TrackKey("", id),
        artistKey = artist.lowercase(), genreKey = genre,
        isLocal = local, isAvailable = true,
    )
    @Test fun noAffinityProducesNoFalsePersonalization() {
        val result = MichiGenreContextPolicy.select(listOf(track("a")), emptyMap(), emptyMap())
        assertFalse(result.hasEvidence)
        assertTrue(result.picks.isEmpty())
    }
    @Test fun genreEvidenceProducesBoundedRecommendations() {
        val tracks = (0..30).map { track("t$it") }
        val result = MichiGenreContextPolicy.select(tracks, emptyMap(), mapOf("rock" to 9))
        assertTrue(result.hasEvidence)
        assertEquals(4, result.picks.size)
        assertTrue(result.picks.all { it.reason == MichiIntelligencePolicy.Reason.GENRE_AFFINITY })
    }
    @Test fun excludesNowPlayingAndRemoteWithoutInventingFavorites() {
        val tracks = listOf(track("playing"),track("remote", local=false),track("next"))
        val result = MichiGenreContextPolicy.select(tracks, mapOf("artist" to 5), emptyMap(),
            excluded = setOf(MichiIntelligencePolicy.TrackKey("", "playing")))
        assertEquals(listOf("next"), result.picks.map { it.key.trackId })
    }
    @Test fun negativeEvidenceAloneNeverCreatesPersonalizedCard() {
        val result = MichiGenreContextPolicy.select(listOf(track("a")),
            mapOf("artist" to -9), emptyMap())
        assertTrue(result.picks.isEmpty())
    }
    @Test fun neutralSongsNeverMasqueradeAsLearned() {
        val result = MichiGenreContextPolicy.select(listOf(track("liked", artist="liked"),
            track("neutral", artist="neutral")), mapOf("liked" to 12), emptyMap())
        assertTrue(result.hasEvidence)
        assertTrue(result.picks.all { it.key.trackId == "liked" })
    }
    @Test fun sameTitleAcrossOwnersDoesNotConflateIdentity() {
        val a = track("same")
        val b = track("same").copy(key = MichiIntelligencePolicy.TrackKey("another", "same"))
        val result = MichiGenreContextPolicy.select(listOf(a,b), emptyMap(), mapOf("rock" to 3))
        assertEquals(2, result.picks.map { it.key }.distinct().size)
    }
    @Test fun intelligenceAcceptsTheSameUnitSeparatorAsUi7() {
        assertEquals(listOf("rock", "jazz"),
            MichiGenreIntelligencePolicy.keys("Rock\u001fJazz"))
    }

    @Test fun neutralDiversityCannotDisplaceLearnedSongsFromFourSlots() {
        val list = (0..70).map { track("neutral-$it", artist = "artist-$it", genre = "Unknown") } +
            (0..4).map { track("learned-$it", artist = "favorite", genre = "Unknown") }
        val result = MichiGenreContextPolicy.select(list, mapOf("favorite" to 25), emptyMap())
        assertEquals(4, result.picks.size)
        assertTrue(result.picks.all { it.key.trackId.startsWith("learned-") })
    }

}
