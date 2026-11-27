package org.michimusic.mobile.artist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistBulkPagedDryRunTest {
    private val stamp = ArtistBulkReadSnapshot("lib", "root", 5L, 8L, 2L)
    private fun credits(size: Int) = List(size) { i ->
        ArtistBulkCredit("lib", "Artist-%04d".format(i), listOf("album-%04d".format(i)), 1)
    }
    private class Store(val f: (Int) -> Unit = {}) : ArtistEnrichmentSelectionStore {
        var readCount = 0
        var writeCount = 0
        override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile? {
            f(++readCount)
            return null
        }
        override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) {
            writeCount++
        }
        override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) {
            writeCount++
        }
    }

    @Test fun handles129CreditsAcrossPagesWithoutDroppingAny() = runBlocking {
        val store = Store()
        var witnesses = 0
        val preview = ArtistBulkPagedDryRun(store, ArtistBulkSnapshotReader {
            witnesses++
            stamp
        }).preview(stamp, credits(129))
        assertEquals(null, preview.blocked)
        assertEquals(129, preview.rows.size)
        assertEquals(129, store.readCount)
        assertEquals(0, store.writeCount)
        assertEquals("Artist-0000", preview.rows.first().credit)
        assertEquals("Artist-0128", preview.rows.last().credit)
        assertTrue(witnesses >= 3)
        assertFalse(preview.mayExecuteWithoutConsent)
    }

    @Test fun handles257CreditsAndGlobalStableOrder() = runBlocking {
        val store = Store()
        val reader = ArtistBulkSnapshotReader { stamp }
        val shuffled = credits(257).reversed()
        val preview = ArtistBulkPagedDryRun(store, reader).preview(stamp, shuffled)
        assertEquals(null, preview.blocked)
        assertEquals(257, preview.rows.size)
        assertEquals("Artist-0000", preview.rows.first().credit)
        assertEquals("Artist-0256", preview.rows.last().credit)
        assertEquals(257, preview.rows.map { it.credit }.distinct().size)
    }

    @Test fun secondPageRootChangeDropsAllFirstPageRows() = runBlocking {
        val store = Store()
        val reader = ArtistBulkSnapshotReader { if (store.readCount >= 128) stamp.copy(rootEpoch = 6) else stamp }
        val preview = ArtistBulkPagedDryRun(store, reader).preview(stamp, credits(150))
        assertEquals(ArtistBulkPreviewBlock.SCOPE_CHANGED, preview.blocked)
        assertTrue(preview.rows.isEmpty())
        assertEquals(128, store.readCount)
    }

    @Test fun finalWitnessChangeCannotPublishPartialOrFullRows() = runBlocking {
        val store = Store()
        var reads = 0
        val reader = ArtistBulkSnapshotReader {
            reads++
            // One single-item page checks before and after its only Room lookup;
            // the wrapper's final witness is the third check.
            if (reads == 3) stamp.copy(libraryRevision = 9) else stamp
        }
        val preview = ArtistBulkPagedDryRun(store, reader).preview(stamp, credits(1))
        assertEquals(ArtistBulkPreviewBlock.SCOPE_CHANGED, preview.blocked)
        assertTrue(preview.rows.isEmpty())
        assertEquals(1, store.readCount)
    }

    @Test fun crossPageDuplicateIsRejectedBeforeAnyRoomRead() = runBlocking {
        val store = Store()
        val entries = credits(129).toMutableList()
        entries[128] = entries[0]
        val preview = ArtistBulkPagedDryRun(store, ArtistBulkSnapshotReader { stamp })
            .preview(stamp, entries)
        assertEquals(ArtistBulkPreviewBlock.DUPLICATE_INPUT, preview.blocked)
        assertTrue(preview.rows.isEmpty())
        assertEquals(0, store.readCount)
    }

    @Test fun limitExceededDoesNotReadRoom() = runBlocking {
        val store = Store()
        val preview = ArtistBulkPagedDryRun(store, ArtistBulkSnapshotReader { stamp })
            .preview(stamp, credits(2049))
        assertEquals(ArtistBulkPreviewBlock.BUDGET_EXCEEDED, preview.blocked)
        assertTrue(preview.rows.isEmpty())
        assertEquals(0, store.readCount)
    }

    @Test fun emptyLibraryStillVerifiesCurrentWitness() = runBlocking {
        val store = Store()
        val preview = ArtistBulkPagedDryRun(store, ArtistBulkSnapshotReader { stamp.copy(catalogRevision = 10) })
            .preview(stamp, emptyList())
        assertEquals(ArtistBulkPreviewBlock.SCOPE_CHANGED, preview.blocked)
        assertTrue(preview.rows.isEmpty())
        assertEquals(0, store.readCount)
    }

    @Test fun cancelledStoreReadPropagates() = runBlocking {
        val store = Store { throw CancellationException("cancel") }
        try {
            ArtistBulkPagedDryRun(store, ArtistBulkSnapshotReader { stamp }).preview(stamp, credits(2))
            throw AssertionError("Cancellation was swallowed")
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
    }

    @Test fun independentArtistVariantsRemainDistinctWithoutAutomaticMerge() = runBlocking {
        val variants = listOf(
            ArtistBulkCredit("lib", "A-ha", listOf("album-1"), 3),
            ArtistBulkCredit("lib", "a-ha", listOf("album-2"), 4),
        )
        val store = Store()
        val preview = ArtistBulkPagedDryRun(store, ArtistBulkSnapshotReader { stamp }, pageSize = 1)
            .preview(stamp, variants)
        assertEquals(null, preview.blocked)
        assertEquals(setOf("A-ha", "a-ha"), preview.rows.map { it.credit }.toSet())
        assertEquals(2, store.readCount)
        assertEquals(2, preview.rows.size)
    }

    @Test fun twoThousandFortyEightCreditsUseBoundedWitnessChecks() = runBlocking {
        val store = Store()
        var witnesses = 0
        val reader = ArtistBulkSnapshotReader { witnesses++; stamp }
        val preview = ArtistBulkPagedDryRun(store, reader).preview(stamp, credits(2048))
        assertEquals(null, preview.blocked)
        assertEquals(2048, preview.rows.size)
        assertEquals(2048, store.readCount)
        assertEquals(0, store.writeCount)
        // 16 pages, each checks once at entry and once after its local reads;
        // the wrapper performs one more terminal read. 33, not 81 (D19 stride=32).
        assertEquals(33, witnesses)
    }

    @Test fun invalidSnapshotStopsBeforeRoom() = runBlocking {
        val store = Store()
        val preview = ArtistBulkPagedDryRun(store, ArtistBulkSnapshotReader { stamp })
            .preview(stamp.copy(rootEpoch = -1), credits(1))
        assertEquals(ArtistBulkPreviewBlock.INVALID_SNAPSHOT, preview.blocked)
        assertEquals(0, store.readCount)
    }
}
