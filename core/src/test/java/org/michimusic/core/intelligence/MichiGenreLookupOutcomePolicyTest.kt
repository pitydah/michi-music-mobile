package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MI-23 4.2: no-result, uncertain match, no genres, proposals and errors differ. */
class MichiGenreLookupOutcomePolicyTest {
    private val policy = MichiGenreLookupOutcomePolicy

    @Test
    fun searchStatesAreExplicitAndDistinct() {
        assertEquals(MichiGenreLookupOutcomePolicy.SearchOutcome.NO_MATCH,
            policy.search(matchCount = 0, reviewableCount = 0))
        assertEquals(MichiGenreLookupOutcomePolicy.SearchOutcome.MATCHES_UNCERTAIN,
            policy.search(matchCount = 2, reviewableCount = 0))
        assertEquals(MichiGenreLookupOutcomePolicy.SearchOutcome.MATCHES_REVIEWABLE,
            policy.search(matchCount = 2, reviewableCount = 1))
        assertEquals(MichiGenreLookupOutcomePolicy.SearchOutcome.ERROR,
            policy.search(matchCount = 0, reviewableCount = 0, error = true))
        assertEquals(MichiGenreLookupOutcomePolicy.SearchOutcome.CONSENT_REVOKED,
            policy.search(matchCount = 3, reviewableCount = 3, consentRevoked = true))
    }

    @Test
    fun recordingWithoutGenresIsNotAnErrorAndNotAnUnknownFileGenre() {
        assertEquals(MichiGenreLookupOutcomePolicy.RecordingOutcome.RECORDING_WITHOUT_GENRES,
            policy.recording(proposalCount = 0))
        assertEquals(MichiGenreLookupOutcomePolicy.RecordingOutcome.PROPOSALS_AVAILABLE,
            policy.recording(proposalCount = 1))
        assertEquals(MichiGenreLookupOutcomePolicy.RecordingOutcome.ERROR,
            policy.recording(proposalCount = 0, error = true))
        assertEquals(MichiGenreLookupOutcomePolicy.RecordingOutcome.CONSENT_REVOKED,
            policy.recording(proposalCount = 2, consentRevoked = true))
        assertTrue(policy.message(MichiGenreLookupOutcomePolicy.RecordingOutcome
            .RECORDING_WITHOUT_GENRES).contains("no significa"))
    }

    @Test
    fun noMatchMessageOffersTheOptionalSecondSearchWithoutSendingAnything() {
        val message = policy.message(MichiGenreLookupOutcomePolicy.SearchOutcome.NO_MATCH)
        assertTrue(message.contains("sin álbum"))
        assertTrue(message.contains("autorización nueva"))
        // No outcome message may claim a change to the file.
        MichiGenreLookupOutcomePolicy.SearchOutcome.entries.forEach {
            assertFalse(policy.message(it).contains("se guardó"))
        }
    }
}
