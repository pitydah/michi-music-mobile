package org.michimusic.mobile.artist

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tests the actual SharedPreferences implementation, not only the pure TTL policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AlbumEnrichmentPersistenceTest {
    private val mbid = "a5ec1cd7-52e3-4a85-a04e-62fe0c5f216f"
    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)
    private val profile = AlbumHistoricalProfile(
        mbid = mbid, title = "Diesel and Dust", firstReleaseDate = "1987-08-01",
        disambiguation = "", description = null, wikipediaUrl = null,
    )
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private var now = 1_000_000L
    private fun store() = PreferencesAlbumEnrichmentSelectionStore(ctx) { now }

    @Before fun reset() {
        now = 1_000_000L
        check(ctx.getSharedPreferences("michi-album-enrichment-v1", Context.MODE_PRIVATE)
            .edit().clear().commit())
    }

    @Test fun saveRestartAndClearHaveIndependentNamespaces() = runBlocking {
        store().save(keyA, profile)
        store().save(keyB, profile.copy(title = "Another album"))
        val resumed = store() // Fresh instance after simulated app restart.
        assertEquals("Diesel and Dust", resumed.load(keyA)?.profile?.title)
        assertEquals("Another album", resumed.load(keyB)?.profile?.title)
        resumed.clear(keyA)
        assertNull(store().load(keyA))
        assertNotNull(store().load(keyB))
    }

    @Test fun staleSevenDaysKeepsDataAndMakesRefreshOptional() = runBlocking {
        store().save(keyA, profile)
        now += ALBUM_HISTORY_TTL_MS - 1L
        assertFalse(store().load(keyA)!!.stale)
        now++
        assertTrue(store().load(keyA)!!.stale)
    }

    @Test fun futureTimestampFromClockRollbackIsInvalidated() = runBlocking {
        store().save(keyA, profile)
        now -= 1L
        assertNull(store().load(keyA))
    }

    @Test fun corruptEntryIsDeletedAndCannotPoisonAnotherAlbum() = runBlocking {
        store().save(keyB, profile)
        ctx.getSharedPreferences("michi-album-enrichment-v1", Context.MODE_PRIVATE)
            .edit().putString(keyA, "{incomplete json").commit()
        assertNull(store().load(keyA))
        assertNull(ctx.getSharedPreferences("michi-album-enrichment-v1", Context.MODE_PRIVATE)
            .getString(keyA, null))
        assertNotNull(store().load(keyB))
    }

    @Test fun invalidRemoteEvidenceCannotBePersisted() = runBlocking {
        val invalid = profile.copy(description = "No verifiable Wikipedia source")
        assertTrue(runCatching { store().save(keyA, invalid) }.isFailure)
        assertNull(store().load(keyA))
    }
}
