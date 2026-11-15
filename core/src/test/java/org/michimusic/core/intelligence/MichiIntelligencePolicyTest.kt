package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiIntelligencePolicyTest {
    private val policy = MichiIntelligencePolicy

    @Test fun interruptedAndForeignSessionsDoNotTrain() {
        assertNull(policy.observe(null, MichiIntelligencePolicy.PlaybackOutcome.INTERRUPTED, 1000, 100, true))
        assertNull(policy.observe(null, MichiIntelligencePolicy.PlaybackOutcome.COMPLETED, 1000, 100, false))
        assertNull(policy.observe(null, MichiIntelligencePolicy.PlaybackOutcome.COMPLETED, 1001, 100, true))
    }

    @Test fun completionAndExplicitSkipAreNotEquivalent() {
        val liked = policy.observe(null, MichiIntelligencePolicy.PlaybackOutcome.COMPLETED, 950, 100, true)
        assertEquals(3, liked?.value)
        val skipped = policy.observe(liked, MichiIntelligencePolicy.PlaybackOutcome.EXPLICIT_SKIP, 100, 100, true)
        assertEquals(2, skipped?.value)
        assertEquals(liked, policy.observe(liked, MichiIntelligencePolicy.PlaybackOutcome.EXPLICIT_SKIP, 800, 100, true))
    }

    @Test fun affinityScoresAreClampedAtTheSharedBoundary() {
        // Legacy alias counters can add up beyond the nominal range; the boundary
        // that returns a score map must clamp once, for every consumer.
        assertEquals(255, policy.clampAffinity(255))
        assertEquals(255, policy.clampAffinity(510))
        assertEquals(255, policy.clampAffinity(Int.MAX_VALUE))
        assertEquals(-32, policy.clampAffinity(-32))
        assertEquals(-32, policy.clampAffinity(-64))
        assertEquals(0, policy.clampAffinity(0))
        assertEquals(7, policy.clampAffinity(7))
        // Individual decay still happens before clamping.
        assertEquals(31, policy.decayed(MichiIntelligencePolicy.PreferenceCounter(255, 10), 100))
    }

    @Test fun preferenceDecaysOnlyWhenAsked() {
        val saved = MichiIntelligencePolicy.PreferenceCounter(16, 10)
        assertEquals(16, policy.decayed(saved, 39))
        assertEquals(8, policy.decayed(saved, 40))
        assertEquals(4, policy.decayed(saved, 70))
        assertEquals(16, saved.value) // no mutation, timer, or background writes
    }

    @Test fun filtersUnavailableRemoteAndDuplicateKeys() {
        fun candidate(source: String, id: String, local: Boolean, available: Boolean) =
            MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey(source, id), "camel", "mirage",
                isLocal = local, isAvailable = available)
        val results = policy.recommend(
            listOf(
                candidate("remote", "a", false, true),
                candidate("local", "a", true, false),
                candidate("local", "b", true, true),
                candidate("local", "b", true, true),
                candidate("other", "b", true, true),
            ),
            artistAffinity = mapOf("camel" to 5), genreAffinity = emptyMap(),
        )
        assertEquals(2, results.size)
        assertTrue(results.any { it.key == MichiIntelligencePolicy.TrackKey("local", "b") })
        assertTrue(results.any { it.key == MichiIntelligencePolicy.TrackKey("other", "b") })
        assertFalse(results.any { it.key.sourceId == "remote" })
    }

    @Test fun promotesDifferentArtistsInsteadOfRepeatingSameArtist() {
        val results = policy.recommend(
            listOf(
                MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("", "1"), "camel", "a", isLocal = true, isAvailable = true),
                MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("", "2"), "camel", "b", isLocal = true, isAvailable = true),
                MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("", "3"), "yes", "c", isLocal = true, isAvailable = true),
            ),
            artistAffinity = mapOf("camel" to 8, "yes" to 7),
            genreAffinity = emptyMap(),
            limit = 2,
        )
        assertEquals(listOf("1", "3"), results.map { it.key.trackId })
    }

    @Test fun limitsCandidateProcessingAndOutput() {
        val all = (0 until 1000).map {
            MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("", "$it"), "artist$it",
                isLocal = true, isAvailable = true)
        }
        val selected = policy.recommend(all, emptyMap(), emptyMap(), limit = 1000)
        assertEquals(policy.MAX_RESULTS, selected.size)
        assertTrue(selected.all { it.key.trackId.toInt() < policy.MAX_CANDIDATES })
    }

    @Test fun unknownHistoryReceivesSmallExplorationWeightWithoutFalseUnplayedClaim() {
        fun c(id: String, known: Boolean) = MichiIntelligencePolicy.Candidate(
            MichiIntelligencePolicy.TrackKey("", id), "camel", "mirage", "rock",
            playCount = if (known) 1 else 0, playCountKnown = known,
            isLocal = true, isAvailable = true,
        )
        val picks = policy.recommend(
            listOf(c("known", true), c("unknown", false)),
            artistAffinity = mapOf("camel" to 8), genreAffinity = emptyMap(), limit = 1,
        )
        assertEquals("unknown", picks.single().key.trackId)
        assertEquals(MichiIntelligencePolicy.Reason.ARTIST_AFFINITY, picks.single().reason)
    }

    @Test fun excludedTrackIsNeverRecommended() {
        val key = MichiIntelligencePolicy.TrackKey("", "playing")
        val selected = policy.recommend(
            listOf(MichiIntelligencePolicy.Candidate(key, "camel", isLocal = true, isAvailable = true)),
            emptyMap(), emptyMap(), excluded = setOf(key),
        )
        assertTrue(selected.isEmpty())
    }
}
