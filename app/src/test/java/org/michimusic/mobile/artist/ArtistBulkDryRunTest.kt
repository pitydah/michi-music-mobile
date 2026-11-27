package org.michimusic.mobile.artist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistBulkDryRunTest {
    private val mbid = "e6bb4f90-9f58-4a61-a4dd-67db47038a7b"
    private val snapshot = ArtistBulkReadSnapshot("lib-1", "source-A", 2L, 7L, 8L)
    private val credit = ArtistBulkCredit("lib-1", "Artist", listOf("album-1"), 3)

    private class FakeStore(
        var response: PersistedArtistProfile? = null,
        var failure: Throwable? = null,
        var afterLoad: () -> Unit = {},
    ) : ArtistEnrichmentSelectionStore {
        var reads = 0
        var writes = 0
        override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile? {
            reads++
            afterLoad()
            failure?.let { throw it }
            return response
        }
        override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) {
            writes++
        }
        override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) { writes++ }
    }

    private fun saved(
        mbid: String = this.mbid,
        associationId: String? = "association-1",
        stale: Boolean = false,
        unbound: List<String> = emptyList(),
        profilePersisted: Boolean = true,
    ) = PersistedArtistProfile(
        profile = ArtistExternalProfile(mbid = mbid, name = "Artist"),
        stale = stale, associationGroupKey = associationId,
        unboundAlbumIds = unbound,
        needsConfirmation = unbound.isNotEmpty(),
        profilePersisted = profilePersisted,
    )

    @Test fun completeConfirmedScopeIsOnlyEligibleForExplicitReview() = runBlocking {
        val store = FakeStore(response = saved())
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit))
        assertEquals(1, result.eligibleCount)
        assertEquals(ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW, result.rows.single().disposition)
        assertFalse(result.mayExecuteWithoutConsent)
        assertEquals(0, store.writes)
        assertEquals(64, result.rows.single().diagnosticFingerprint?.length)
    }

    @Test fun absentIdentityNeverGetsManufacturedFromCredit() = runBlocking {
        val store = FakeStore()
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkDisposition.IDENTITY_REQUIRED, result.rows.single().disposition)
        assertEquals(null, result.rows.single().mbid)
        assertEquals(0, store.writes)
    }

    @Test fun incompleteMembershipIsBlocked() = runBlocking {
        val store = FakeStore(response = saved(unbound = listOf("album-1")))
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkDisposition.MEMBERSHIP_RECONFIRMATION, result.rows.single().disposition)
    }

    @Test fun identityFirstWithoutProfileIsNotClaimedReady() = runBlocking {
        val store = FakeStore(response = saved(profilePersisted = false))
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkDisposition.PROFILE_MISSING, result.rows.single().disposition)
    }

    @Test fun missingAssociationIsNotEnoughForBulkWrite() = runBlocking {
        val store = FakeStore(response = saved(associationId = null))
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkDisposition.IDENTITY_CONFLICT, result.rows.single().disposition)
    }

    @Test fun contradictoryMbidBlocks() = runBlocking {
        val store = FakeStore(response = saved(mbid = "not-an-mbid"))
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkDisposition.IDENTITY_CONFLICT, result.rows.single().disposition)
    }

    @Test fun duplicateScopeRowsAreNotMergedOrRead() = runBlocking {
        val store = FakeStore(response = saved())
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit, credit))
        assertEquals(2, result.rows.size)
        assertTrue(result.rows.all { it.disposition == ArtistBulkDisposition.DUPLICATE_SCOPE })
        assertEquals(0, store.reads)
    }

    @Test fun crossLibraryAndEmptyAlbumNeverCallStore() = runBlocking {
        val store = FakeStore(response = saved())
        val mixed = listOf(credit.copy(libraryId = "other"), credit.copy(albumIds = emptyList()),
            credit.copy(albumIds = listOf("album-1", "album-1")), credit.copy(trackCount = 0))
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, mixed)
        assertTrue(result.rows.all { it.disposition == ArtistBulkDisposition.INVALID_SCOPE })
        assertEquals(0, store.reads)
    }

    @Test fun changeDuringRoomReadRevokesEntirePreview() = runBlocking {
        var now = snapshot
        val store = FakeStore(response = saved(), afterLoad = { now = snapshot.copy(libraryRevision = 9) })
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { now }).preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkPreviewBlock.SCOPE_CHANGED, result.blocked)
        assertTrue(result.rows.isEmpty())
    }

    @Test fun rootABAReentryInvalidatesSnapshot() = runBlocking {
        val store = FakeStore(response = saved())
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot.copy(rootEpoch = 4) })
            .preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkPreviewBlock.SCOPE_CHANGED, result.blocked)
        assertEquals(0, store.reads)
    }

    @Test fun exceedingBudgetDoesNotSilentlyTruncate() = runBlocking {
        val store = FakeStore(response = saved())
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }, maxCredits = 2)
            .preview(snapshot, List(3) { credit.copy(credit = "Artist-$it") })
        assertEquals(ArtistBulkPreviewBlock.BUDGET_EXCEEDED, result.blocked)
        assertEquals(0, store.reads)
    }

    @Test fun ambiguousAssociationIsReviewableNotEligible() = runBlocking {
        val store = FakeStore(failure = ArtistAssociationAmbiguousException())
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkDisposition.IDENTITY_CONFLICT, result.rows.single().disposition)
    }

    @Test fun storeErrorIsExplicitAndHasNoAutomaticRetry() = runBlocking {
        val store = FakeStore(failure = IllegalStateException("offline db"))
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkDisposition.READ_ERROR, result.rows.single().disposition)
        assertEquals(1, store.reads)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverSwallowed() = runBlocking<Unit> {
        val store = FakeStore(failure = CancellationException("cancelled"))
        ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot }).preview(snapshot, listOf(credit))
    }

    @Test fun deterministicOrderingAndDigestAreStableAcrossInputOrder() = runBlocking {
        val store = FakeStore(response = saved(stale = true))
        val a = credit.copy(credit = "Zebra", albumIds = listOf("Z"))
        val b = credit.copy(credit = "apple", albumIds = listOf("A"))
        val runner = ArtistBulkDryRun(store, ArtistBulkSnapshotReader { snapshot })
        val first = runner.preview(snapshot, listOf(a,b))
        val second = runner.preview(snapshot, listOf(b,a))
        assertEquals(first.rows, second.rows)
        assertEquals("apple", first.rows.first().credit)
        assertTrue(first.rows.all { it.cachedProfileStale })
        assertEquals(0, store.writes)
    }
    @Test fun snapshotFailureBlocksAndDoesNotReadRoom() = runBlocking {
        val store = FakeStore(response = saved())
        val result = ArtistBulkDryRun(store, ArtistBulkSnapshotReader {
            throw IllegalStateException("root unavailable")
        }).preview(snapshot, listOf(credit))
        assertEquals(ArtistBulkPreviewBlock.SNAPSHOT_UNAVAILABLE, result.blocked)
        assertEquals(0, store.reads)
    }

    @Test(expected = CancellationException::class)
    fun snapshotCancellationPropagates() = runBlocking<Unit> {
        ArtistBulkDryRun(FakeStore(), ArtistBulkSnapshotReader {
            throw CancellationException("cancel snapshot")
        }).preview(snapshot, listOf(credit))
    }

    @Test fun largeLocalPreviewChecksSnapshotAtBoundedIntervalsAndAtTheEnd() = runBlocking {
        val store = FakeStore(response = saved())
        var witnessReads = 0
        val reader = ArtistBulkSnapshotReader { witnessReads++; snapshot }
        val candidates = List(128) { index ->
            credit.copy(credit = "Artist-$index", albumIds = listOf("album-$index"))
        }
        val result = ArtistBulkDryRun(store, reader).preview(snapshot, candidates)
        assertEquals(null, result.blocked)
        assertEquals(128, result.rows.size)
        assertEquals(128, store.reads)
        assertEquals(0, store.writes)
        // 1 initial + 3 intermediate (32/64/96) + 1 terminal = 5.
        assertEquals(5, witnessReads)
    }

}
