package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreGenreRecoveryPolicyTest {
    @Test fun oneAudioIdCanHaveMultipleRealGenres() {
        val index = MediaStoreGenreRecoveryIndex(setOf(10L, 20L))
        assertTrue(index.record(10L, "Jazz"))
        assertTrue(index.record(10L, "Funk"))
        assertTrue(index.record(10L, "Jazz"))
        assertEquals("Jazz;Funk", index.result()[10L])
    }

    @Test fun membershipFromOtherRootNeverLeaksIn() {
        val index = MediaStoreGenreRecoveryIndex(setOf(10L))
        assertTrue(index.record(123L, "Classical"))
        assertTrue(index.record(10L, "Blues"))
        assertEquals(mapOf(10L to "Blues"), index.result())
    }

    @Test fun slashInOriginalGenreIsNeverSplit() {
        val index = MediaStoreGenreRecoveryIndex(setOf(1L))
        assertTrue(index.record(1L, "R&B/Soul"))
        assertEquals("R&B/Soul", index.result()[1L])
    }

    @Test fun primaryColumnAlwaysWinsOverRecovery() {
        assertEquals("New Wave", preserveMediaStoreGenre("New Wave", "Rock"))
        assertEquals("Funk", preserveMediaStoreGenre("", "Funk"))
        assertEquals("", preserveMediaStoreGenre("", null))
    }

    @Test fun membershipRowsHaveHardAbortLimit() {
        val index = MediaStoreGenreRecoveryIndex(setOf(1L), maxMembershipRows = 2)
        assertTrue(index.record(12L, "Other"))
        assertTrue(index.record(1L, "Rock"))
        assertEquals(false, index.record(1L, "Jazz"))
    }

    @Test fun whitespaceDifferencesDoNotConsumeGenreQuota() {
        val index = MediaStoreGenreRecoveryIndex(setOf(1L), maxGenresPerAudio = 1)
        assertTrue(index.record(1L, " Jazz "))
        assertTrue(index.record(1L, "Jazz"))
        assertEquals("Jazz", index.result()[1L])
    }

    @Test fun ambiguousGenreDelimiterIsNotSilentlySplit() {
        val index = MediaStoreGenreRecoveryIndex(setOf(1L))
        assertTrue(index.record(1L, "Fusion;World"))
        assertEquals(emptyMap<Long, String>(), index.result())
    }

    @Test fun noAudioCanAccumulateUnboundedLabels() {
        val index = MediaStoreGenreRecoveryIndex(setOf(1L), maxGenresPerAudio = 2)
        assertTrue(index.record(1L, "Rock"))
        assertTrue(index.record(1L, "Rock"))
        assertTrue(index.record(1L, "Jazz"))
        assertEquals(false, index.record(1L, "Funk"))
    }

    @Test fun emptyMembershipRemainsUnclassified() {
        val index = MediaStoreGenreRecoveryIndex(setOf(1L))
        assertTrue(index.record(1L, ""))
        assertEquals(emptyMap<Long, String>(), index.result())
    }

    @Test fun elapsedBudgetIsMonotonicAndFailsClosed() {
        assertEquals(false, genreRecoveryBudgetExceeded(1_000L, 1_999L, 1_000L))
        assertEquals(true, genreRecoveryBudgetExceeded(1_000L, 2_000L, 1_000L))
        assertEquals(true, genreRecoveryBudgetExceeded(10L, 10L, 0L))
    }

}
