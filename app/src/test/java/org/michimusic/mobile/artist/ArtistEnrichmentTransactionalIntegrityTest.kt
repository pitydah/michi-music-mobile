package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.data.artist.ArtistEnrichmentRecord
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistEnrichmentTransactionalIntegrityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val credit = "Midnight Oil"
    private val albums = listOf("album-diesel-and-dust")
    private val mbidA = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val mbidB = "bd6d4f63-5cd0-4d78-a292-167997794c21"

    private fun open(name: String) = Room.databaseBuilder(
        context, ArtistEnrichmentCacheDatabase::class.java, name,
    ).setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .allowMainThreadQueries()
        .build()

    private fun barrierClock(barrier: CyclicBarrier): () -> Long = {
        runCatching { barrier.await(2, TimeUnit.SECONDS) }
        100L
    }

    @Test
    fun concurrentDifferentMbidConfirmationsCannotReplaceTheSameAssociation() = runBlocking {
        val name = "identity-race-${System.nanoTime()}.db"
        try {
            val database = open(name)
            val bothPassedTheUncommittedReadChecks = CyclicBarrier(2)
            val stores = listOf(mbidA, mbidB).map {
                RoomArtistEnrichmentSelectionStore(database, barrierClock(bothPassedTheUncommittedReadChecks))
            }

            val outcomes = listOf(mbidA, mbidB).zip(stores).map { (mbid, store) ->
                async(Dispatchers.IO) {
                    runCatching { store.saveIdentity("lib-a", credit, albums, mbid) }
                }
            }.awaitAll()

            val stored = requireNotNull(
                RoomArtistEnrichmentSelectionStore(database).load("lib-a", credit, albums),
            )
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, outcomes.count { it.isFailure })
            assertTrue(stored.profile.mbid == mbidA || stored.profile.mbid == mbidB)
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun concurrentSameMbidConfirmationsAreIdempotent() = runBlocking {
        val name = "identity-idempotent-${System.nanoTime()}.db"
        try {
            val database = open(name)
            val barrier = CyclicBarrier(2)
            val outcomes = List(2) {
                val store = RoomArtistEnrichmentSelectionStore(database, barrierClock(barrier))
                async(Dispatchers.IO) {
                    runCatching { store.saveIdentity("lib-a", credit, albums, mbidA) }
                }
            }.awaitAll()

            assertTrue(outcomes.all { it.isSuccess })
            val keys = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums))
            assertEquals(1, database.artistEnrichmentDao().getAllForCredit("lib-a", keys.first).size)
            assertEquals(
                mbidA,
                RoomArtistEnrichmentSelectionStore(database).load("lib-a", credit, albums)?.profile?.mbid,
            )
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun concurrentIdentityConfirmationCannotOverwriteANewerBiography() = runBlocking {
        val name = "identity-profile-race-${System.nanoTime()}.db"
        try {
            val database = open(name)
            val store = RoomArtistEnrichmentSelectionStore(database) { 10L }
            val old = ArtistExternalProfile(
                mbid = mbidA,
                name = credit,
                biography = "Biografía anterior.",
                biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
                biographyOutcome = ArtistMetadataOutcome.SUCCESS,
            )
            store.save("lib-a", credit, albums, old)
            val identityReachedWrite = CountDownLatch(1)
            val releaseIdentity = CountDownLatch(1)
            val identityStore = RoomArtistEnrichmentSelectionStore(database) {
                identityReachedWrite.countDown()
                releaseIdentity.await(2, TimeUnit.SECONDS)
                20L
            }
            val identity = async(Dispatchers.IO) {
                identityStore.saveIdentity("lib-a", credit, albums, mbidA)
            }
            assertTrue(identityReachedWrite.await(2, TimeUnit.SECONDS))
            val profile = async(Dispatchers.IO) {
                store.save(
                    "lib-a",
                    credit,
                    albums,
                    old.copy(biography = "Biografía nueva confirmada."),
                )
            }
            delay(100)
            releaseIdentity.countDown()
            identity.await()
            profile.await()

            assertEquals(
                "Biografía nueva confirmada.",
                store.load("lib-a", credit, albums)?.profile?.biography,
            )
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun explicitClearQueuedAfterConfirmationDoesNotResurrectTheAssociation() = runBlocking {
        val name = "identity-clear-race-${System.nanoTime()}.db"
        try {
            val database = open(name)
            val store = RoomArtistEnrichmentSelectionStore(database) { 10L }
            store.saveIdentity("lib-a", credit, albums, mbidA)
            val identityReachedWrite = CountDownLatch(1)
            val releaseIdentity = CountDownLatch(1)
            val identityStore = RoomArtistEnrichmentSelectionStore(database) {
                identityReachedWrite.countDown()
                releaseIdentity.await(2, TimeUnit.SECONDS)
                20L
            }
            val identity = async(Dispatchers.IO) {
                identityStore.saveIdentity("lib-a", credit, albums, mbidA)
            }
            assertTrue(identityReachedWrite.await(2, TimeUnit.SECONDS))
            val clear = async(Dispatchers.IO) { store.clear("lib-a", credit, albums) }
            delay(100)
            releaseIdentity.countDown()
            identity.await()
            clear.await()

            assertNull(store.load("lib-a", credit, albums))
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun separateLibrariesCanConfirmTheSameMbidWithoutSharingAssociations() = runBlocking {
        val name = "identity-library-scope-${System.nanoTime()}.db"
        try {
            val database = open(name)
            val store = RoomArtistEnrichmentSelectionStore(database) { 100L }
            listOf("lib-a", "lib-b").map { library ->
                async(Dispatchers.IO) { store.saveIdentity(library, credit, albums, mbidA) }
            }.awaitAll()

            assertEquals(mbidA, store.load("lib-a", credit, albums)?.profile?.mbid)
            assertEquals(mbidA, store.load("lib-b", credit, albums)?.profile?.mbid)
            assertEquals(1, database.artistEnrichmentDao().countForLibrary("lib-a"))
            assertEquals(1, database.artistEnrichmentDao().countForLibrary("lib-b"))
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun albumMembershipChangesPreserveOneCanonicalHistoricalAssociation() = runBlocking {
        val name = "identity-membership-${System.nanoTime()}.db"
        try {
            val database = open(name)
            val store = RoomArtistEnrichmentSelectionStore(database) { 100L }
            val original = store.saveIdentity("lib-a", credit, albums, mbidA)
            val group = requireNotNull(original.associationGroupKey)
            store.saveIdentity("lib-a", credit, albums + "album-red-sails", mbidA, group)
            store.saveIdentity("lib-a", credit, listOf("album-red-sails"), mbidA, group)

            val keys = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums))
            val rows = database.artistEnrichmentDao().getAllForCredit("lib-a", keys.first)
            assertEquals(1, rows.size)
            assertEquals(group, rows.single().albumGroupKey)
            assertEquals(mbidA, rows.single().confirmedMbid)
            assertEquals("[\"album-diesel-and-dust\"]", rows.single().albumIdsJson)
            assertEquals(group, rows.single().membershipFingerprint)
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun cancellationInsideRoomTransactionRollsBackTheAssociation() = runBlocking {
        val name = "identity-cancellation-${System.nanoTime()}.db"
        try {
            val database = open(name)
            val keys = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums))
            val inserted = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                database.withTransaction {
                    database.artistEnrichmentDao().put(
                        ArtistEnrichmentRecord(
                            libraryId = "lib-a",
                            creditKey = keys.first,
                            albumGroupKey = keys.second,
                            confirmedMbid = mbidA,
                            profileJson = "{\"mbid\":\"$mbidA\",\"name\":\"$credit\"}",
                            savedAtMs = 100L,
                            staleAfterMs = Long.MAX_VALUE,
                            source = "transaction-cancellation-test",
                            biographySourceUrl = null,
                            portraitSourceUrl = null,
                            portraitLicense = null,
                            portraitLicenseUrl = null,
                            portraitAttribution = null,
                            albumIdsJson = "[\"${albums.single()}\"]",
                            membershipFingerprint = keys.second,
                        ),
                    )
                    inserted.complete(Unit)
                    awaitCancellation()
                }
            }
            inserted.await()
            job.cancelAndJoin()

            assertNull(database.artistEnrichmentDao().get("lib-a", keys.first, keys.second))
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun reopeningAfterConcurrentConflictHasOneIdentityAndCleanSqlitePragmas() = runBlocking {
        val name = "identity-integrity-reopen-${System.nanoTime()}.db"
        try {
            var database = open(name)
            val barrier = CyclicBarrier(2)
            val outcomes = listOf(mbidA, mbidB).map { candidate ->
                val store = RoomArtistEnrichmentSelectionStore(database, barrierClock(barrier))
                async(Dispatchers.IO) {
                    runCatching { store.saveIdentity("lib-a", credit, albums, candidate) }
                }
            }.awaitAll()
            assertEquals(1, outcomes.count { it.isSuccess })
            database.close()

            database = open(name)
            val keys = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums))
            val rows = database.artistEnrichmentDao().getAllForCredit("lib-a", keys.first)
            assertEquals(1, rows.size)
            database.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }
}
