package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiCandidateCardCoverageTest {
    @Test fun dateVerifiedRediscoverOutsideTopTwelveStillAppears() {
        val candidates = (0 until 100).map { i ->
            MichiIntelligencePolicy.Candidate(
                key = MichiIntelligencePolicy.TrackKey("", "track-$i"),
                artistKey = if (i == 99) "rediscover" else "favorite",
                albumKey = "album-$i", isLocal = true, isAvailable = true,
                playCountKnown = i == 99, playCount = if (i == 99) 3 else 0,
                lastCompletedEpochDay = if (i == 99) 900 else null,
            )
        }
        val p = MichiHomeIntelligencePolicy.propose(candidates,
            mapOf("favorite" to 100, "rediscover" to 2), emptyMap(), nowEpochDay = 1000)
        assertEquals("track-99", p.cards.first {
            it.kind == MichiHomeIntelligencePolicy.CardKind.REDISCOVER
        }.tracks.single().trackId)
    }

    @Test fun oneVerifiedPlayOutsideTopTwelveStillGetsHiddenGemCard() {
        val candidates = (0 until 100).map { i ->
            MichiIntelligencePolicy.Candidate(
                key = MichiIntelligencePolicy.TrackKey("", "track-$i"),
                artistKey = if (i == 98) "rare" else "favorite",
                albumKey = "album-$i", isLocal = true, isAvailable = true,
                playCountKnown = i == 98, playCount = if (i == 98) 1 else 0,
            )
        }
        val p = MichiHomeIntelligencePolicy.propose(candidates,
            mapOf("favorite" to 100, "rare" to 2), emptyMap(), nowEpochDay = 1000)
        assertEquals("track-98", p.cards.first {
            it.kind == MichiHomeIntelligencePolicy.CardKind.HIDDEN_GEMS
        }.tracks.single().trackId)
    }

    @Test fun lowQualityOrBlockedOwnedCandidateDoesNotCreateFalseRecommendation() {
        val item = MichiIntelligencePolicy.Candidate(
            key=MichiIntelligencePolicy.TrackKey("", "a"), artistKey="disliked",
            isLocal=true, isAvailable=true, playCountKnown=true, playCount=2,
            lastCompletedEpochDay=50,
        )
        val p = MichiHomeIntelligencePolicy.propose(listOf(item),
            mapOf("disliked" to -4, "other" to 20), emptyMap(), nowEpochDay=100)
        assertTrue(p.cards.none { it.kind == MichiHomeIntelligencePolicy.CardKind.REDISCOVER })
        val blocked = MichiHomeIntelligencePolicy.propose(listOf(item.copy(artistKey="other")),
            mapOf("other" to 20), emptyMap(), recentlyPlayed=setOf(item.key), nowEpochDay=100)
        assertTrue(blocked.cards.none { it.kind == MichiHomeIntelligencePolicy.CardKind.REDISCOVER })
    }
}
