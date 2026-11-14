package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

class MichiHomeScopedLibraryPolicyTest {
    private fun track(id: String, source: TrackSource = TrackSource.LOCAL,
        filepath: String = "/music/$id.flac") = Track(
        id = id, title = id, artist = "Artist", album = "Album", source = source,
        filepath = filepath, sourceId = "storage-owner", duration = 1000L,
    )

    @Test fun onlyMembershipIdsFromSelectedLibraryAreEligible() {
        val all = listOf(track("a"), track("b"), track("c"))
        assertEquals(listOf("a", "c"), MichiHomeScopedLibraryPolicy
            .filterUniqueOwned(all, setOf("a", "c")).map { it.id })
    }

    @Test fun ownerSourceIdIsNotLibraryMembership() {
        val all = listOf(track("a"), track("b"))
        assertEquals(listOf("b"), MichiHomeScopedLibraryPolicy
            .filterUniqueOwned(all, setOf("b")).map { it.id })
    }

    @Test fun duplicateIdsAreExcludedFailClosed() {
        val all = listOf(track("duplicate"), track("unique"), track("duplicate"))
        assertEquals(listOf("unique"), MichiHomeScopedLibraryPolicy
            .filterUniqueOwned(all, setOf("duplicate", "unique")).map { it.id })
    }

    @Test fun invalidMembershipSamplesAreRejected() {
        assertTrue(MichiHomeScopedLibraryPolicy.filterUniqueOwned(
            listOf(track("a")), emptySet()).isEmpty())
        assertTrue(MichiHomeScopedLibraryPolicy.filterUniqueOwned(
            listOf(track("a")), (1..257).map { "$it" }.toSet()).isEmpty())
    }

    @Test fun broadSampleReachesStartMiddleAndEndOfFiftyThousand() {
        val all = (0 until 50_000).map { track("track-$it") }
        val result = MichiHomeScopedLibraryPolicy.sampleDistinctCandidates(all)
        assertTrue(result.size <= MichiHomeScopedLibraryPolicy.MAX_MEMBER_SAMPLE)
        assertEquals("track-0", result.first().id)
        assertEquals("track-49999", result.last().id)
        assertTrue(result.any { it.id == "track-25097" } ||
            result.any { it.id.removePrefix("track-").toInt() in 24_000..26_000 })
    }

    @Test fun duplicateOutsideSampleIsRejectedEvenWhenNotAdjacent() {
        val tracks = (0 until 1_000).map { track("id-$it") }.toMutableList()
        tracks[500] = track("id-0")
        val result = MichiHomeScopedLibraryPolicy.sampleDistinctCandidates(tracks)
        assertFalse(result.any { it.id == "id-0" })
    }

    @Test fun remoteAndMissingFilesAreNeverCandidates() {
        val all = listOf(track("remote", TrackSource.STREAMING),
            track("missing", filepath = ""), track("local"))
        assertEquals(listOf("local"), MichiHomeScopedLibraryPolicy
            .sampleDistinctCandidates(all).map { it.id })
    }

    @Test fun sparseLibraryMembersRetainedOutsideStratifiedPositions() {
        val all = (0 until 1_000).map { track("sparse-$it") }
        val result = MichiHomeScopedLibraryPolicy
            .sampleDistinctCandidates(all, setOf("sparse-951"))
        assertTrue(result.any { it.id == "sparse-951" })
        assertTrue(result.size <= MichiHomeScopedLibraryPolicy.MAX_CANDIDATES)
    }

    @Test fun oversizedOwnerPrefixFailsClosed() {
        val all = listOf(track("a"))
        assertTrue(MichiHomeScopedLibraryPolicy.sampleDistinctCandidates(
            all, (1..257).map { "id-$it" }.toSet()).isEmpty())
    }
}
