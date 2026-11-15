package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** To execute with the real Android/Room Robolectric task, never as a synthetic JVM test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistEnrichmentPostCommitSignalTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"

    @Test fun confirmedProfileQidEmitsAnExactPostCommitEvent() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
        try {
            val callbacks = mutableListOf<Pair<Triple<String, String, String>, Boolean>>()
            val store = RoomArtistEnrichmentSelectionStore(db,
                afterVerifiedCommit = { library, creditKey, association, _, mayCapture ->
                    val record = db.artistEnrichmentDao().get(library, creditKey, association)
                    assertTrue(record != null) // DB already committed
                    callbacks += Triple(library, creditKey, association) to mayCapture
                })
            val confirmed = store.confirmNewIdentity("library-a", "Midnight Oil", listOf("album-a"), mbid)
            val id = requireNotNull(confirmed.associationGroupKey)
            assertEquals(listOf(Triple("library-a", "midnight oil", id) to true), callbacks) // Explicit binding is proven, identity still needs QID.
            store.refreshProfile("library-a", "Midnight Oil", id,
                ArtistExternalProfile(mbid = mbid, name = "Midnight Oil", wikidataQid = "Q48284"))
            assertEquals(listOf(Triple("library-a", "midnight oil", id) to true,
                Triple("library-a", "midnight oil", id) to false), callbacks)
            store.bindUnboundAlbumsToAssociation("library-a", "Midnight Oil", listOf("album-b"), id, mbid)
            assertEquals(3, callbacks.size)
        } finally {
            db.close()
        }
    }


    @Test fun explicitLegacyReconfirmationRequiresExistingExactBinding() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
        try {
            var now = 1_000L
            val callbacks = mutableListOf<Boolean>()
            val store = RoomArtistEnrichmentSelectionStore(db, clock = { now },
                afterVerifiedCommit = { _, _, _, _, capture -> callbacks += capture })
            val confirmed = store.confirmNewIdentity("library-a", "Artist", listOf("album-a"), mbid)
            val id = requireNotNull(confirmed.associationGroupKey)
            val dao = db.artistEnrichmentDao()
            val initial = requireNotNull(dao.getAlbumCreditBinding("library-a", "album-a", "artist"))
            assertEquals(1, dao.countConfirmedBindingsForAssociation("library-a", "artist", id))

            // A reconfirm button is NOT permission to claim an unbound album by name.
            assertTrue(runCatching { store.reconfirmExistingAlbumBinding(
                "library-a", "Artist", "album-other", id, mbid) }.isFailure)
            assertEquals(null, dao.getAlbumCreditBinding("library-a", "album-other", "artist"))
            // Nor may it steal a binding by presenting the wrong association ID.
            assertTrue(runCatching { store.reconfirmExistingAlbumBinding(
                "library-a", "Artist", "album-a", "another-association", mbid) }.isFailure)

            now = initial.confirmedAtMs + 1_000L
            val result = store.reconfirmExistingAlbumBinding(
                "library-a", "Artist", "album-a", id, mbid)
            assertEquals(id, result.associationGroupKey)
            val renewed = requireNotNull(dao.getAlbumCreditBinding("library-a", "album-a", "artist"))
            assertEquals(id, renewed.associationId)
            assertEquals(now, renewed.confirmedAtMs)
            assertEquals(1, dao.countConfirmedBindingsForAssociation("library-a", "artist", id))
            assertEquals(listOf(true, true), callbacks)
        } finally {
            db.close()
        }
    }

    @Test fun postCommitSchedulerFailureDoesNotUndoBinding() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
        try {
            val store = RoomArtistEnrichmentSelectionStore(db,
                afterVerifiedCommit = { _, _, _, _, _ -> error("Worker manager unavailable") })
            val persisted = store.confirmNewIdentity("library-a", "Artist", listOf("album-a"), mbid)
            val id = requireNotNull(persisted.associationGroupKey)
            store.refreshProfile("library-a", "Artist", id,
                ArtistExternalProfile(mbid = mbid, name = "Artist", wikidataQid = "Q12345"))
            assertEquals(1, db.artistEnrichmentDao().countConfirmedBindingsForAssociation(
                "library-a", "artist", id))
        } finally {
            db.close()
        }
    }
}
