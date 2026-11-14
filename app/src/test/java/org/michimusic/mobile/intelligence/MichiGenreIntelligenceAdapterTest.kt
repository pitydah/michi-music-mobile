package org.michimusic.mobile.intelligence

import java.lang.ref.WeakReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.models.Track

class MichiGenreIntelligenceAdapterTest {
    private fun song(id: String, owner: String = "") =
        Track(id = id, sourceId = owner, title = "Song $id", filepath = "/music/$id.flac")

    private fun section(library: List<Track>): MichiGenreIntelligenceAdapter.Section =
        MichiGenreIntelligenceAdapter.Section(
            genreId = "rock", scopeKey = "ONE:device:local_phone", scopeGeneration = 42,
            consentEpoch = MichiIntelligenceConsentBarrier.epoch(),
            tracks = listOf(library[2],library[0]),
            reasons = emptyMap(),
            positions = mapOf(
                MichiIntelligencePolicy.TrackKey(library[2].sourceId, library[2].id) to 2,
                MichiIntelligencePolicy.TrackKey(library[0].sourceId, library[0].id) to 0,
            ),
            scopeTracks = WeakReference(library),
        )

    @Test fun playsOnlyOwnSuggestedQueueInSuggestedOrder() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val library = listOf(song("0"), song("1"), song("2"), song("3"))
        val result = section(library).playbackPlan("rock", "ONE:device:local_phone", 42, library,library[0])!!
        assertEquals(listOf("2", "0"),result.first.map { it.id })
        assertEquals(1,result.second)
    }

    @Test fun differentScopeOrGenerationAlwaysRejects() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val library = listOf(song("0"),song("1"),song("2"))
        val s = section(library)
        assertNull(s.playbackPlan("rock", "ALL", 42, library))
        assertNull(s.playbackPlan("rock", "ONE:device:local_phone", 43, library))
        assertNull(s.playbackPlan("jazz", "ONE:device:local_phone", 42, library))
    }

    @Test fun replacedTrackOrCopiedListDoesNotReuseReceipt() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val library = listOf(song("0"),song("1"),song("2"))
        val s = section(library)
        assertNull(s.playbackPlan("rock", "ONE:device:local_phone", 42, library.toList()))
        assertNull(s.playbackPlan("rock", "ONE:device:local_phone", 42, library, song("0")))
    }

    @Test fun consentRevocationImmediatelyInvalidatesSection() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val library = listOf(song("0"),song("1"),song("2"))
        val s = section(library)
        MichiIntelligenceConsentBarrier.revoke()
        assertNull(s.playbackPlan("rock", "ONE:device:local_phone", 42, library))
    }

    @Test fun genreReceiptRejectsGrowingMutablePlayableListAndAmbiguousIds() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val library = mutableListOf(song("0"), song("1"), song("2"))
        val candidate = section(library)
        assertEquals(2, candidate.playbackPlan("rock", "ONE:device:local_phone", 42, library)!!.first.size)
        library.add(song("3"))
        assertNull(candidate.playbackPlan("rock", "ONE:device:local_phone", 42, library))

        val duplicate = listOf(song("0", "A"), song("1"), song("0", "B"))
        val unsafe = section(duplicate)
        assertNull(unsafe.playbackPlan("rock", "ONE:device:local_phone", 42, duplicate))
    }

    @Test fun sourceIdEmptyIsValidCanonicalLocalLibraryIdentity() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val library = listOf(song("0"),song("1"),song("2"))
        assertEquals(2,section(library).playbackPlan("rock","ONE:device:local_phone",42,library)!!.first.size)
    }
}
