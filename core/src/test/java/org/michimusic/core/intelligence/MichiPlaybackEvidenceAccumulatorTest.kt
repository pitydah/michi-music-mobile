package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiPlaybackEvidenceAccumulatorTest {
    private fun track() = MichiPlaybackEvidenceAccumulator.LocalTrack("", "one", "Camel", "Rock; Jazz", 100_000L)
    private fun policy() = MichiPlaybackEvidenceAccumulator()

    @Test fun autoAfterAudibleSeventyPercentCountsOnce() {
        val events = policy()
        events.begin(track(), 0, 9, true)
        val result = events.finish(MichiPlaybackEvidenceAccumulator.Transition.AUTO, 80_000, 9)
        assertEquals(MichiIntelligencePolicy.PlaybackOutcome.COMPLETED, result?.outcome)
        assertEquals(800, result?.completionPermille)
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.END, 80_001, 9))
    }

    @Test fun pauseDoesNotCountAsAudibleAndSeekAheadCannotFakeCompletion() {
        val events = policy()
        events.begin(track(), 0, 5, true)
        events.playing(false, 15_000, 5)
        events.playing(true, 80_000, 5)
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.AUTO, 90_000, 5))
    }

    @Test fun shortExplicitSkipIsNegativeOnlyWhenCommandCausedTransition() {
        val events = policy()
        events.begin(track(), 0, 4, true)
        events.explicitSkip(5_000, 4)
        val result = events.finish(MichiPlaybackEvidenceAccumulator.Transition.SEEK, 5_080, 4)
        assertEquals(MichiIntelligencePolicy.PlaybackOutcome.EXPLICIT_SKIP, result?.outcome)
        assertTrue((result?.completionPermille ?: 1000) < 300)
        events.begin(track(), 0, 4, true)
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.SEEK, 5_000, 4))
    }

    @Test fun remoteHandoffDropsAccumulatedListening() {
        val events = policy()
        events.begin(track(), 0, 1, true)
        events.playing(true, 60_000, 2)
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.AUTO, 80_000, 2))
    }

    @Test fun errorAndPlaylistReplacementDoNotTrain() {
        val events = policy()
        events.begin(track(), 0, 1, true)
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.ERROR, 80_000, 1))
        events.begin(track(), 0, 1, true)
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.REPLACED, 80_000, 1))
    }

    @Test fun repeatCreatesIndependentEventGeneration() {
        val events = policy()
        events.begin(track(), 0, 1, true)
        val first = events.finish(MichiPlaybackEvidenceAccumulator.Transition.REPEAT, 90_000, 1)!!
        events.begin(track(), 90_000, 1, true)
        val second = events.finish(MichiPlaybackEvidenceAccumulator.Transition.REPEAT, 180_000, 1)!!
        assertTrue(first.sequence != second.sequence)
    }

    @Test fun staleSkipIntentIsNotReused() {
        val events = policy()
        events.begin(track(), 0, 1, true)
        events.explicitSkip(2_000, 1)
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.SEEK, 9_000, 1))
    }

    @Test fun zeroDurationAndBackwardClockAreRejected() {
        val events = policy()
        events.begin(track().copy(durationMs = 0), 100, 1, true)
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.END, 100_000, 1))
        events.begin(track(), 100, 1, true)
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.AUTO, 20, 1))
    }
    @Test fun consentEpochIsFrozenAtBeginningOfTrack() {
        val events = policy()
        events.begin(track(), 0, 1, true, consentEpoch = 17L)
        val completed = events.finish(MichiPlaybackEvidenceAccumulator.Transition.AUTO, 85_000, 1)!!
        assertEquals(17L, completed.consentEpoch)
        assertEquals(1L, completed.ownerRevision)
        // A new opt-in after this track started must NOT reassign its generation.
        assertTrue(completed.consentEpoch != 18L)
    }

    @Test fun consentEpochSurvivesPauseResumeAndDoesNotBorrowNewPermission() {
        val events = policy()
        events.begin(track(), 0, 1, true, consentEpoch = 5L)
        events.playing(false, 45_000, 1)
        events.playing(true, 55_000, 1)
        val completed = events.finish(MichiPlaybackEvidenceAccumulator.Transition.AUTO, 95_000, 1)!!
        assertEquals(5L, completed.consentEpoch)
        assertEquals(850, completed.completionPermille)
    }

    @Test fun seekWithinTrackDiscardsPreviouslyAccruedListening() {
        val events = policy()
        events.begin(track(), 0, 1, true, consentEpoch = 8L)
        events.playing(true, 20_000, 1)
        // Media3's same-item DISCONTINUITY_REASON_SEEK invokes discard().
        events.discard()
        assertNull(events.finish(MichiPlaybackEvidenceAccumulator.Transition.AUTO, 100_000, 1))
    }

}
