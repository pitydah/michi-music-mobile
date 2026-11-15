package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtistArtworkCatalogJournalTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase

    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }

    @After fun tearDown() { db.close() }

    /**
     * K46 requires an explicit source receipt. A textual binding alone is not proof,
     * so fixtures must create the same identity/association/catalog-receipt path
     * production uses before expecting revival or repair of source-scoped work.
     */
    private suspend fun seedProvenBinding(
        album: String,
        source: String,
        associationId: String = "association",
        creditKey: String = "artist",
        confirmedAtMs: Long = 8,
    ) {
        val dao = db.artistEnrichmentDao()
        val journal = db.artistArtworkCatalogJournalDao()
        val identityId = associationId
        if (dao.get("lib", creditKey, identityId) == null) {
            val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
            dao.put(ArtistEnrichmentRecord(
                libraryId = "lib", creditKey = creditKey, albumGroupKey = identityId,
                confirmedMbid = mbid, profileJson = """{"mbid":"$mbid"}""",
                savedAtMs = 1, staleAfterMs = Long.MAX_VALUE, source = "test",
                biographySourceUrl = null, portraitSourceUrl = null,
                portraitLicense = null, portraitLicenseUrl = null, portraitAttribution = null,
            ))
        }
        if (dao.getDurableAssociation("lib", creditKey, associationId) == null) {
            dao.putDurableAssociation(DurableArtistAssociationRecord(
                libraryId = "lib", creditKey = creditKey, associationId = associationId,
                identityAssociationId = identityId, createdAtMs = 1, updatedAtMs = 1,
            ))
        }
        dao.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
            "lib", album, creditKey, associationId, "USER_EXPLICIT_BINDING", confirmedAtMs,
        ))
        journal.insertSourceCatalogCredits(listOf(ArtistArtworkSourceCatalogCreditRecord(
            "lib", source, album, creditKey, observedAtMs = confirmedAtMs - 1,
        )))
        journal.captureBindingSourceProofs(
            "lib", creditKey, associationId, source,
            baselineAtMs = confirmedAtMs - 2, nowMs = confirmedAtMs,
        )
    }

    @Test fun oldRootCannotDeferOrFinishNewRootEvent() = runBlocking {
        val journal = db.artistArtworkCatalogJournalDao()
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "association", "album", "saf-new", updatedAtMs = 5,
        ))
        assertEquals(0, journal.deferPendingForAssociation(
            "lib", "artist", "association", "device-old", 50,
        ))
        assertEquals(0, journal.finish(
            "lib", "artist", "association", "album", "device-old", "STALE_CONTEXT", 50,
        ))
        assertEquals(0, journal.finishMissingAssociation(
            "lib", "artist", "association", "device-old", 50,
        ))
        assertEquals(1, journal.pendingDispatchForRoot("lib", "saf-new", 5, 6).size)
    }

    @Test fun returnedRootOnlyRearmsItsOwnBoundStaleEvent() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "association", "album", "root-a", state = "STALE_SOURCE", updatedAtMs = 5,
        ))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "association", "album", "root-b", updatedAtMs = 6,
        ))
        // Distinct sources preserve independent evidence for an identical album credit.
        assertEquals(0, dao.resumeReturnedSourceBoundRow(
            "lib", "artist", "association", "album", "root-a", 20,
        )) // no confirmed binding yet
        seedProvenBinding("album", "root-a", confirmedAtMs = 8)
        assertEquals(1, dao.resumeReturnedSourceBoundRow(
            "lib", "artist", "association", "album", "root-a", 20,
        ))
        assertEquals(2, dao.pending("lib", "artist", "association", 20).size)
        assertEquals(listOf("root-a", "root-b"),
            dao.pending("lib", "artist", "association", 20).map { it.sourceId }.sorted())
    }

    @Test fun moreThan1024OldRootRowsCannotStarveNewRootsPendingBinding() = runBlocking {
        val journal = db.artistArtworkCatalogJournalDao()
        repeat(1030) { i -> journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "association", "old-$i", "old-root", updatedAtMs = 1,
        )) }
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "association", "fresh", "new-root", updatedAtMs = 5,
        ))
        seedProvenBinding("fresh", "new-root", confirmedAtMs = 5)
        assertEquals(1024, journal.pending("lib", "artist", "association", 6).size)
        val current = journal.pendingForRoot("lib", "artist", "association", "new-root", 6)
        assertEquals(listOf("fresh"), current.map { it.albumKey })
    }

    @Test fun explicitMissingArtworkCanRepairOnlySameRootReadyNotCooldownOrIdentity() = runBlocking {
        val journal = db.artistArtworkCatalogJournalDao()
        for (state in listOf("READY", "CACHED", "PENDING", "NEEDS_IDENTITY", "STALE_CONTEXT")) {
            // A durable PENDING row is a *deferred cooldown*: production stores the
            // future deadline in updatedAtMs (see deferPendingForAssociation), so it is
            // not due at the 200ms repair instant. Seeding it as already due would test
            // a state production never produces and contradict the READY/CACHED result.
            val seededAtMs = if (state == "PENDING") 500L else 100L
            journal.putOutbox(ArtistArtworkAutoOutboxRecord(
                "lib", "artist", "association", "album-$state", "old-root", state, seededAtMs,
            ))
            journal.putOutbox(ArtistArtworkAutoOutboxRecord(
                "lib", "artist", "association", "album-$state", "new-root", state, seededAtMs,
            ))
            seedProvenBinding("album-$state", "new-root", confirmedAtMs = 99)
            val result = journal.rearmCompletedForExplicitMissingArtwork(
                "lib", "artist", "association", "album-$state", "new-root", 200,
            )
            assertEquals(if (state == "READY" || state == "CACHED") 1 else 0, result)
        }
        assertEquals(setOf("album-READY", "album-CACHED"),
            journal.pending("lib", "artist", "association", 200)
                .filter { it.sourceId == "new-root" }.map { it.albumKey }.toSet())
        assertTrue(journal.pending("lib", "artist", "association", 200)
            .none { it.sourceId == "old-root" })
    }

    @Test fun baselineAndHashesRemainLibraryScoped() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.putBaseline(ArtistArtworkCatalogBaselineRecord("library-a", "device", "digest-a", 42))
        dao.insertHashes(listOf(ArtistArtworkCatalogTrackRecord("library-a", "hash-a")))
        assertEquals("digest-a", dao.baseline("library-a")?.snapshotDigest)
        assertEquals(listOf("hash-a"), dao.hashes("library-a"))
        assertEquals(null, dao.baseline("library-b"))
    }

    @Test fun oneCorrectedCreditDeletesOnlyItsOwnOldHash() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.insertHashes(listOf(
            ArtistArtworkCatalogTrackRecord("lib", "old-credit"),
            ArtistArtworkCatalogTrackRecord("lib", "untouched"),
            ArtistArtworkCatalogTrackRecord("other", "old-credit"),
        ))
        assertEquals(1, dao.deleteHashes("lib", listOf("old-credit")))
        dao.insertHashes(listOf(ArtistArtworkCatalogTrackRecord("lib", "new-credit")))
        assertEquals(setOf("untouched", "new-credit"), dao.hashes("lib").toSet())
        assertEquals(setOf("old-credit"), dao.hashes("other").toSet())
    }

    @Test fun outboxIsIdempotentAndExactAssociationScoped() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        val item = ArtistArtworkAutoOutboxRecord("lib", "artist", "association-a", "album-x", "device", updatedAtMs = 1)
        dao.putOutbox(item)
        dao.putOutbox(item)
        dao.putOutbox(item.copy(associationId = "association-b"))
        assertEquals(1, dao.pending("lib", "artist", "association-a", Long.MAX_VALUE).size)
        assertEquals(1, dao.pending("lib", "artist", "association-b", Long.MAX_VALUE).size)
        assertEquals(1, dao.finish("lib", "artist", "association-a", "album-x", "device", "READY", 2))
        assertTrue(dao.pending("lib", "artist", "association-a", Long.MAX_VALUE).isEmpty())
        assertEquals(1, dao.pending("lib", "artist", "association-b", Long.MAX_VALUE).size)
    }

    @Test fun rootSwitchInvalidatesOldWorkButNotOtherLibraries() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "artist", "assoc", "album", "saf-old", updatedAtMs = 1))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("other-lib", "artist", "assoc", "album", "saf-old", updatedAtMs = 1))
        assertEquals(1, dao.invalidateOtherSources("lib", "saf-new", 2))
        assertTrue(dao.pending("lib", "artist", "assoc", Long.MAX_VALUE).isEmpty())
        assertEquals(1, dao.pending("other-lib", "artist", "assoc", Long.MAX_VALUE).size)
    }
    @Test fun cooledDownAssociationCannotStarveReadyPendingWork() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "a", "cooling", "album-a", "root", updatedAtMs = 1))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "b", "ready", "album-b", "root", updatedAtMs = 2))
        assertEquals(1, dao.deferPendingForAssociation("lib", "a", "cooling", "root", 500L))
        val ready = dao.pendingDispatch(limit = 4, nowMs = 50)
        assertEquals(listOf("ready"), ready.map { it.associationId })
        val resumed = dao.pendingDispatch(limit = 4, nowMs = 500)
        assertEquals(setOf("ready", "cooling"), resumed.map { it.associationId }.toSet())
    }

    @Test fun deferralNeverTouchesOtherLibrariesOrCompletedRows() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "a", "same", "album-a", "root", updatedAtMs = 1))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("other", "a", "same", "album-a", "root", updatedAtMs = 1))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "a", "same", "album-b", "root", updatedAtMs = 1))
        dao.finish("lib", "a", "same", "album-b", "root", "READY", 2)
        assertEquals(1, dao.deferPendingForAssociation("lib", "a", "same", "root", 1000))
        assertEquals(listOf("other"), dao.pendingDispatch(10, 2).map { it.libraryId })
        assertEquals(1, dao.pending("lib", "a", "same", Long.MAX_VALUE).size)
    }
    @Test fun manyAlbumEventsForOneArtistDoNotHideOtherArtists() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        repeat(170) { album ->
            dao.putOutbox(ArtistArtworkAutoOutboxRecord(
                "lib", "artist-a", "association-a", "album-$album", "root", updatedAtMs = 1,
            ))
        }
        dao.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist-b", "association-b", "other", "root", updatedAtMs = 2,
        ))
        val jobs = dao.pendingDispatch(10, 10)
        assertEquals(2, jobs.size)
        assertEquals(setOf("association-a", "association-b"), jobs.map { it.associationId }.toSet())
    }
    @Test fun normalDispatcherCannotLeakWorkFromOtherRootsOrLibraries() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "artist", "a", "one", "device", updatedAtMs = 1))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "artist", "b", "two", "saf", updatedAtMs = 1))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("other", "artist", "c", "three", "device", updatedAtMs = 1))
        assertEquals(listOf("a"), dao.pendingDispatchForRoot("lib", "device", 4, 10).map { it.associationId })
        assertEquals(listOf("b"), dao.pendingDispatchForRoot("lib", "saf", 4, 10).map { it.associationId })
        assertEquals(emptyList<ArtistArtworkDispatchRow>(), dao.pendingDispatchForRoot("lib", "device", 4, 0))
    }

    @Test fun manualRetryIsBoundedAndCannotTouchAnotherRoot() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "a", "assoc-a", "album-a", "device", updatedAtMs = 1000))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "b", "assoc-b", "album-b", "device", updatedAtMs = 1001))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "c", "assoc-c", "album-c", "saf", updatedAtMs = 1002))
        assertEquals(listOf("assoc-a"), dao.manualRetryCandidates("lib", "device", 1).map { it.associationId })
        assertEquals(1, dao.activatePendingForAssociation("lib", "device", "a", "assoc-a", 1))
        assertEquals(setOf("assoc-a"), dao.pendingDispatch(8, 1).map { it.associationId }.toSet())
        assertEquals(0, dao.activatePendingForAssociation("lib", "device", "c", "assoc-c", 1))
        assertEquals(listOf("assoc-c"), dao.manualRetryCandidates("lib", "saf", 8).map { it.associationId })
    }

    @Test fun aConfirmedEventIsIdempotentAndDoesNotReopenNegativeCache() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        val event = ArtistArtworkAutoOutboxRecord("lib", "artist", "assoc", "album-a", "root", updatedAtMs = 10)
        assertTrue(dao.insertOutboxIfAbsent(event) >= 0L)
        assertEquals(-1L, dao.insertOutboxIfAbsent(event.copy(updatedAtMs = 30)))
        assertEquals(1, dao.deferPendingForAssociation("lib", "artist", "assoc", "root", 1000))
        assertEquals(-1L, dao.insertOutboxIfAbsent(event.copy(updatedAtMs = 40)))
        assertTrue(dao.pendingDispatchForRoot("lib", "root", 3, 40).isEmpty())
        assertEquals(1, dao.finish("lib", "artist", "assoc", "album-a", "root", "READY", 1000))
        assertEquals(-1L, dao.insertOutboxIfAbsent(event.copy(updatedAtMs = 1100)))
        assertTrue(dao.pending("lib", "artist", "assoc", Long.MAX_VALUE).isEmpty())
    }


    @Test fun verifiedIdentityRearmsOnlyItsOwnSourceAndNeverStaleSource() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        for ((state, shouldRevive) in listOf(
            "NEEDS_IDENTITY" to true, "STALE_SOURCE" to false,
            "STALE_CONTEXT" to true, "READY" to false,
            "PARTIAL" to false, "PENDING" to false,
        )) {
            val album = "album-$state"
            // PENDING means deferred cooldown: production stores a FUTURE deadline
            // in updatedAtMs. A due-at-1 PENDING row would be indistinguishable from
            // work revived at the 99ms observation instant.
            val seededAtMs = if (state == "PENDING") 500L else 1L
            dao.putOutbox(ArtistArtworkAutoOutboxRecord(
                "lib", "artist", "assoc", album, "old-root", state, seededAtMs,
            ))
            seedProvenBinding(album, "old-root", associationId = "assoc", confirmedAtMs = 1)
            assertEquals(if (shouldRevive) 1 else 0,
                dao.reviveVerifiedEvent("lib", "artist", "assoc", album, "old-root", 99))
        }
        // Exactly the two revived rows are due at the 99ms revive instant; the
        // deferred cooldown keeps its own future deadline and stays out of due work.
        assertEquals(2, dao.pending("lib", "artist", "assoc", 99).size)
        assertEquals(0, dao.pending("lib", "artist", "assoc", Long.MAX_VALUE)
            .count { it.sourceId == "new-root" })
        assertEquals(1, dao.pendingDispatchForRoot("lib", "old-root", 8, 99).size)
        assertTrue(dao.pendingDispatchForRoot("lib", "new-root", 8, 99).isEmpty())
    }

    @Test fun albumBindingLookupIsPagedAndDoesNotInventIdentityByArtistName() = runBlocking {
        val dao = db.artistEnrichmentDao()
        val journal = db.artistArtworkCatalogJournalDao()
        val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        dao.put(ArtistEnrichmentRecord(
            libraryId = "lib", creditKey = "artist", albumGroupKey = "assoc", confirmedMbid = mbid,
            profileJson = """{"mbid":"$mbid"}""", savedAtMs = 1, staleAfterMs = 1,
            source = "musicbrainz-manual-selection", biographySourceUrl = null,
            portraitSourceUrl = null, portraitLicense = null, portraitLicenseUrl = null,
            portraitAttribution = null,
        ))
        dao.putDurableAssociation(DurableArtistAssociationRecord(
            libraryId = "lib", creditKey = "artist", associationId = "assoc",
            identityAssociationId = "assoc", createdAtMs = 1, updatedAtMs = 1,
        ))
        repeat(1030) { i ->
            dao.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
                libraryId = "lib", albumKey = "album-$i", creditKey = "artist",
                associationId = "assoc", evidenceType = "USER_EXPLICIT_BINDING", confirmedAtMs = 1,
            ))
        }
        val all = (0 until 1030).map { "album-$it" }
        val exact = all.chunked(256).flatMap { journal.confirmedCreditsForAlbums("lib", it) }
        assertEquals(1030, exact.size)
        assertEquals(emptyList<ArtistAlbumCreditBindingRecord>(), journal.confirmedCreditsForAlbums("other-lib", all.take(10)))
        assertEquals("assoc", exact.first().associationId)
    }

    @Test fun verifiedAssociationEventPrefersNewAlbumOverAlreadyCompletedAlbum() = runBlocking {
        val identity = db.artistEnrichmentDao()
        val journal = db.artistArtworkCatalogJournalDao()
        val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        identity.put(ArtistEnrichmentRecord(
            libraryId = "lib", creditKey = "artist", albumGroupKey = "identity",
            confirmedMbid = mbid, profileJson = "{\"mbid\":\"$mbid\",\"wikidataQid\":\"Q48284\"}",
            savedAtMs = 1, staleAfterMs = Long.MAX_VALUE,
            source = "musicbrainz-manual-selection", biographySourceUrl = null,
            portraitSourceUrl = null, portraitLicense = null,
            portraitLicenseUrl = null, portraitAttribution = null,
        ))
        identity.putDurableAssociation(DurableArtistAssociationRecord(
            "lib", "artist", "association", "identity", 1, 1,
        ))
        identity.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
            "lib", "album-old", "artist", "association", "USER_EXPLICIT_BINDING", 2,
        ))
        journal.insertOutboxIfAbsent(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "association", "album-old", "root", state = "READY", updatedAtMs = 3,
        ))
        assertEquals(null, journal.firstBoundAlbumKey("lib", "artist", "association", "root"))
        identity.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
            "lib", "album-new", "artist", "association", "USER_EXPLICIT_BINDING", 4,
        ))
        assertEquals("album-new", journal.firstBoundAlbumKey("lib", "artist", "association", "root"))
        assertEquals(null, journal.firstBoundAlbumKey("other-library", "artist", "association", "root"))
    }


    @Test fun backfillInsertIgnorePreservesReadyAndNegativeCooldown() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        val ready = ArtistArtworkAutoOutboxRecord("lib", "artist", "assoc", "ready-album", "root", "READY", 1234)
        val deferred = ArtistArtworkAutoOutboxRecord("lib", "artist", "assoc", "cooldown-album", "root", "PENDING", 5000)
        assertTrue(dao.insertOutboxIfAbsent(ready) >= 0L)
        assertTrue(dao.insertOutboxIfAbsent(deferred) >= 0L)
        assertEquals(-1L, dao.insertOutboxIfAbsent(ready.copy(state = "PENDING", updatedAtMs = 1)))
        assertEquals(-1L, dao.insertOutboxIfAbsent(deferred.copy(updatedAtMs = 1)))
        assertTrue(dao.pendingDispatchForRoot("lib", "root", 10, 100).isEmpty())
        assertEquals(1, dao.pendingDispatchForRoot("lib", "root", 10, 5000).size)
        assertEquals(1, dao.pending("lib", "artist", "assoc", Long.MAX_VALUE).size)
        assertEquals(5000L, dao.pending("lib", "artist", "assoc", Long.MAX_VALUE).single().updatedAtMs)
    }
    @Test fun backfillInsertIgnoreKeepsIndependentEventsForSameAlbumInTwoRoots() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        val previous = ArtistArtworkAutoOutboxRecord("lib", "artist", "assoc", "album", "old-root", "STALE_SOURCE", 100)
        assertTrue(dao.insertOutboxIfAbsent(previous) >= 0L)
        assertTrue(dao.insertOutboxIfAbsent(previous.copy(sourceId = "new-root", state = "PENDING", updatedAtMs = 200)) >= 0L)
        assertEquals(-1L, dao.insertOutboxIfAbsent(previous.copy(sourceId = "new-root", state = "PENDING", updatedAtMs = 201)))
        val rows = dao.pending("lib", "artist", "assoc", 300)
        assertEquals(listOf("new-root"), rows.map { it.sourceId })
        assertEquals(0, dao.resumeReturnedSourceBoundRow("lib", "artist", "assoc", "album", "old-root", 301))
    }

}
