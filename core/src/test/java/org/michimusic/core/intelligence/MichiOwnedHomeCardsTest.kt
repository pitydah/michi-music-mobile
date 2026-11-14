package org.michimusic.core.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiOwnedHomeCardsTest {
    private val policy = MichiIntelligencePolicy
    private fun c(id: String, plays: Int, known: Boolean, last: Long? = null) =
        MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("local", id), "camel", "album-$id", "rock",
            playCount = plays, playCountKnown = known, lastCompletedEpochDay = last,
            isLocal = true, isAvailable = true)

    @Test fun oneVerifiedCompletionCanBeBarelyExploredButNotRediscover() {
        val proposal = MichiHomeIntelligencePolicy.propose(
            listOf(c("next", 4, true, 95), c("one", 1, true, 98), c("more", 2, true, 60)),
            artistAffinity = mapOf("camel" to 30), genreAffinity = emptyMap(), nowEpochDay = 100,
        )
        assertTrue(proposal.cards.any { it.kind == MichiHomeIntelligencePolicy.CardKind.HIDDEN_GEMS &&
            it.tracks.single() == MichiIntelligencePolicy.TrackKey("local", "one") })
        assertTrue(proposal.cards.any { it.kind == MichiHomeIntelligencePolicy.CardKind.REDISCOVER &&
            it.tracks.single() == MichiIntelligencePolicy.TrackKey("local", "more") })
    }

    @Test fun missingCountIsNeverMislabelledAsNeverPlayed() {
        val proposal = MichiHomeIntelligencePolicy.propose(
            listOf(c("a", 0, false), c("b", 0, false), c("c", 0, false)),
            artistAffinity = mapOf("camel" to 10), genreAffinity = emptyMap(),
        )
        assertFalse(proposal.cards.any { it.kind == MichiHomeIntelligencePolicy.CardKind.HIDDEN_GEMS ||
            it.kind == MichiHomeIntelligencePolicy.CardKind.REDISCOVER })
    }
}

// Extension cases are kept separate to protect the semantic contract of Redescubre.
class MichiRediscoverFreshnessTest {
    private fun c(id: String, last: Long?) = MichiIntelligencePolicy.Candidate(
        MichiIntelligencePolicy.TrackKey("local", id), "camel", "album-$id", "rock",
        playCount = 3, playCountKnown = true, lastCompletedEpochDay = last,
        isLocal = true, isAvailable = true,
    )
    @Test fun recentCompletionsCannotAppearAsRediscovery() {
        val proposal = MichiHomeIntelligencePolicy.propose(
            listOf(c("fresh", 95), c("old", 80), c("ancient", 30)),
            mapOf("camel" to 30), emptyMap(), nowEpochDay = 100,
        )
        assertTrue(proposal.cards.any { it.kind == MichiHomeIntelligencePolicy.CardKind.REDISCOVER &&
            it.tracks.single().trackId == "ancient" })
    }
    @Test fun missingOrFutureDateCannotTriggerRediscovery() {
        val proposal = MichiHomeIntelligencePolicy.propose(
            listOf(c("no-date", null), c("future", 110), c("recent", 98)),
            mapOf("camel" to 30), emptyMap(), nowEpochDay = 100,
        )
        assertFalse(proposal.cards.any { it.kind == MichiHomeIntelligencePolicy.CardKind.REDISCOVER })
    }
}
