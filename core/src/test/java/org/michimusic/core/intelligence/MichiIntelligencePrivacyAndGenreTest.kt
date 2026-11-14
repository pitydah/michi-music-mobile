package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiIntelligencePrivacyAndGenreTest {
    private val genre = MichiGenreIntelligencePolicy
    private val scan = MichiLibraryScanPrivacyPolicy
    private val privacy = MichiExternalPrivacyGate
    private val policy = MichiIntelligencePolicy

    @Test fun semicolonAndNulGenresAreDistinctAndSlashIsPreserved() {
        // MI-26: ';' is part of the literal legacy label; only NUL/U+001F split values.
        assertEquals(listOf("rock/pop ; rock progresivo", "hip-hop; hiphop"),
            genre.keys(" Rock/Pop ; Rock progresivo\u0000Hip-Hop; hiphop "))
    }

    @Test fun unknownAndOversizedGenreLabelsProduceNoSpeculativeTags() {
        assertTrue(genre.keys("Unknown\u0000Sin clasificar\u0000<unknown>\u0000unspecified").isEmpty())
        assertTrue(genre.keys("x".repeat(513)).isEmpty())
        assertEquals(listOf("jazz"), genre.keys("Jazz\u0000jazz\u0000JAZZ"))
    }

    @Test fun genreEvidenceMustBeSourceVerifiedAndAvailable() {
        assertTrue(genre.verifiedKeys("Jazz", ownerVerified = false, available = true).isEmpty())
        assertTrue(genre.verifiedKeys("Jazz", ownerVerified = true, available = false).isEmpty())
        assertEquals(listOf("jazz"), genre.verifiedKeys("Jazz", true, true))
    }

    @Test fun multiTagGenreAffinityPromotesCorrectLocalSong() {
        val result = policy.recommend(
            candidates = listOf(
                MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("local", "a"), "unrelated", genreKey = "Pop",
                    isLocal = true, isAvailable = true),
                MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("local", "b"), "unrelated", genreKey = "Rock; Jazz",
                    verifiedGenreValues = listOf("Rock", "Jazz"), isLocal = true, isAvailable = true),
            ),
            artistAffinity = emptyMap(), genreAffinity = mapOf("jazz" to 9), limit = 1,
        )
        assertEquals("b", result.single().key.trackId)
        assertEquals(MichiIntelligencePolicy.Reason.GENRE_AFFINITY, result.single().reason)
    }

    @Test fun homeColdStartUsesExistingEditorialFallback() {
        val candidates = listOf(MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("", "a"), "camel",
            isLocal = true, isAvailable = true))
        val result = MichiHomeIntelligencePolicy.propose(candidates, emptyMap(), emptyMap())
        assertTrue(result.useEditorialFallback)
        assertTrue(result.cards.isEmpty())
    }

    @Test fun homeDoesNotRecommendCurrentOrUnavailableTrack() {
        val candidates = listOf(
            MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("local", "1"), "camel", isLocal = true, isAvailable = true),
            MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("remote", "2"), "camel", isLocal = false, isAvailable = true),
            MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("local", "3"), "camel", isLocal = true, isAvailable = false),
            MichiIntelligencePolicy.Candidate(MichiIntelligencePolicy.TrackKey("local", "4"), "camel", isLocal = true, isAvailable = true),
        )
        val result = MichiHomeIntelligencePolicy.propose(
            candidates, artistAffinity = mapOf("camel" to 9), genreAffinity = emptyMap(),
            nowPlaying = MichiIntelligencePolicy.TrackKey("local", "1"),
        )
        val all = result.cards.flatMap { it.tracks }
        assertEquals(listOf(MichiIntelligencePolicy.TrackKey("local", "4")), all)
        assertFalse(result.useEditorialFallback)
    }

    @Test fun backgroundOrExternalScanNeverStarts() {
        val base = MichiLibraryScanPrivacyPolicy.Evidence(MichiLibraryScanPrivacyPolicy.Trigger.USER_REQUEST, true, true, true, true, true, true)
        assertEquals(MichiLibraryScanPrivacyPolicy.Action.DENY, scan.decide(base.copy(trigger = MichiLibraryScanPrivacyPolicy.Trigger.PERIODIC_TICK)))
        assertEquals(MichiLibraryScanPrivacyPolicy.Action.DENY, scan.decide(base.copy(trigger = MichiLibraryScanPrivacyPolicy.Trigger.EXTERNAL_SERVICE)))
        assertEquals(MichiLibraryScanPrivacyPolicy.Action.DENY, scan.decide(base.copy(permissionGranted = false)))
        assertEquals(MichiLibraryScanPrivacyPolicy.Action.DENY, scan.decide(base.copy(ownerVerified = false)))
        assertEquals(MichiLibraryScanPrivacyPolicy.Action.CONSUME_AUTHORIZED_METADATA, scan.decide(base))
        assertEquals(MichiLibraryScanPrivacyPolicy.Action.USE_CACHED, scan.decide(base.copy(mediaStoreGenerationChanged = false)))
        assertEquals(MichiLibraryScanPrivacyPolicy.Action.REQUEST_EXISTING_LIBRARY_REFRESH,
            scan.decide(base.copy(mediaStoreVersionMatches = false)))
    }

    @Test fun externalPromptRequiresExplicitOneShotPermissionAndAlwaysPublicPayload() {
        val request = MichiExternalPrivacyGate.Request(MichiExternalPrivacyGate.PublicTopic.WHAT_IS_FLAC, true, true, true)
        assertTrue(privacy.evaluate(request.copy(providerEnabled = false)) is MichiExternalPrivacyGate.Decision.Denied)
        assertTrue(privacy.evaluate(request.copy(oneShotApproval = false)) is MichiExternalPrivacyGate.Decision.Denied)
        assertTrue(privacy.evaluate(request.copy(userInitiated = false)) is MichiExternalPrivacyGate.Decision.Denied)
        val allowed = privacy.evaluate(request) as MichiExternalPrivacyGate.Decision.Approved
        assertEquals("Explain the public audio format FLAC in general terms.", allowed.fixedPublicPrompt)
        assertFalse(allowed.fixedPublicPrompt.contains("playlist", ignoreCase = true))
    }

    @Test fun genreTagCountIsHardLimited() {
        assertEquals(genre.MAX_TAGS_PER_TRACK,
            genre.keys("A\u0000B\u0000C\u0000D\u0000E\u0000F\u0000G").size)
    }

    @Test fun c12VerifiedLiteralSemicolonIsNeverSplitIntoFakeGenres() {
        assertEquals(listOf("rock; jazz"),
            genre.keys("Rock; Jazz", listOf("Rock; Jazz")))
        assertEquals(listOf("rock; jazz"), genre.keys("Rock; Jazz"))
        assertEquals(listOf("rock; jazz", "ambient"),
            genre.keys("legacy", listOf("Rock; Jazz", "Ambient", "Ambient")))
    }

    @Test fun c12VerifiedTagIsUsedForRankingInsteadOfLegacySemicolonSplit() {
        val a = MichiIntelligencePolicy.Candidate(
            MichiIntelligencePolicy.TrackKey("", "a"), "unknown", genreKey = "Rock; Jazz",
            verifiedGenreValues = listOf("Rock; Jazz"), isLocal = true, isAvailable = true,
        )
        val b = MichiIntelligencePolicy.Candidate(
            MichiIntelligencePolicy.TrackKey("", "b"), "unknown", genreKey = "Jazz",
            verifiedGenreValues = listOf("Jazz"), isLocal = true, isAvailable = true,
        )
        val result = policy.recommend(listOf(a, b), emptyMap(), mapOf("jazz" to 12), limit = 1)
        assertEquals("b", result.single().key.trackId)
    }
}
