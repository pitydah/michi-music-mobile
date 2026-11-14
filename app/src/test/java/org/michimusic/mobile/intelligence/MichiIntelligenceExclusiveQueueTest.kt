package org.michimusic.mobile.intelligence

import java.lang.ref.WeakReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.intelligence.MichiHomeIntelligencePolicy
import org.michimusic.core.models.Track

/** MI-16 regression: recommendation presses must never route the whole library. */
class MichiIntelligenceExclusiveQueueTest {
    private fun t(i: Int) = Track(id="id-$i", title="Track", filepath="/local/$i.flac")

    @Test fun singleNextListenTrackDoesNotEnqueueTheEntireCatalog() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val all = (0..499).map(::t)
        val selected = all[300]
        val section = MichiHomeIntelligenceAdapter.Section(
            "Tu próxima escucha", "Por afinidad", listOf(selected),
            mapOf(MichiIntelligencePolicy.TrackKey("",selected.id) to 300),
            WeakReference(all),
            kind = MichiHomeIntelligencePolicy.CardKind.NEXT_LISTEN,
        )
        val plan = section.verifiedRecommendedQueue(all, selected)!!
        assertEquals(listOf(selected), plan.first)
        assertEquals(0, plan.second)
    }

    @Test fun mixQueueRemainsBoundedAndPreservesRecommendedOrder() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val all = (0..5000).map(::t)
        val chosen = listOf(all[18], all[1], all[4760])
        val section = MichiHomeIntelligenceAdapter.Section(
            "Michi Mix", "Por afinidad", chosen,
            chosen.associate { MichiIntelligencePolicy.TrackKey("",it.id) to all.indexOf(it) },
            WeakReference(all),
            kind = MichiHomeIntelligencePolicy.CardKind.MICHI_MIX,
        )
        val (queue,index) = section.verifiedRecommendedQueue(all,all[1])!!
        assertEquals(chosen,queue)
        assertEquals(1,index)
    }

    @Test fun oldSnapshotAndSyntheticTrackAreRejectedBeforeAnyDispatch() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val all = listOf(t(1),t(2))
        val section = MichiHomeIntelligenceAdapter.Section(
            "Next", "Affinity", listOf(all[0]),
            mapOf(MichiIntelligencePolicy.TrackKey("",all[0].id) to 0),
            WeakReference(all),
        )
        assertNull(section.verifiedRecommendedQueue(all.toMutableList(),all[0]))
        assertNull(section.verifiedRecommendedQueue(all,all[0].copy()))
        MichiIntelligenceConsentBarrier.revoke()
        assertNull(section.verifiedRecommendedQueue(all,all[0]))
    }
}
