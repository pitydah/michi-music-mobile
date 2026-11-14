package org.michimusic.mobile.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.models.Track
import java.lang.ref.WeakReference
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.intelligence.MichiIntelligenceRepository
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Regression tests for constant-time click lookup and stale-library rejection. */
class MichiHomeIntelligenceAdapterIndexTest {
    private fun track(id: String = "1") = Track(
        id = id, title = "Local song", sourceId = "local", filepath = "/music/song.flac",
    )

    @Test fun revocationInvalidatesExistingCardAndIndexImmediately() {
        val barrier = MichiIntelligenceConsentBarrier
        barrier.acceptedExplicitConsent()
        val selected = track()
        val library = listOf(selected)
        val section = MichiHomeIntelligenceAdapter.Section(
            "Tu próxima escucha", "Por afinidad", listOf(selected),
            mapOf(MichiIntelligencePolicy.TrackKey("local", "1") to 0),
            WeakReference(library),
        )
        assertEquals(0, section.indexOf(selected, library))
        barrier.revoke()
        assertEquals(false, section.isAuthorized())
        assertNull(section.indexOf(selected, library))
    }

    @Test fun lookupUsesVerifiedIndexInsteadOfScanningEntireLibrary() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val selected = track()
        val library = listOf(track("0"), selected, track("2"))
        val section = MichiHomeIntelligenceAdapter.Section(
            "Tu próxima escucha", "Por afinidad", listOf(selected),
            mapOf(MichiIntelligencePolicy.TrackKey("local", "1") to 1),
            WeakReference(library),
        )
        assertEquals(1, section.indexOf(selected, library))
    }

    @Test fun staleSnapshotOrReplacedItemFailsClosedEvenWithSameIds() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val selected = track()
        val library = listOf(selected)
        val section = MichiHomeIntelligenceAdapter.Section(
            "Tu próxima escucha", "Por afinidad", listOf(selected),
            mapOf(MichiIntelligencePolicy.TrackKey("local", "1") to 0),
            WeakReference(library),
        )
        assertNull(section.indexOf(selected, mutableListOf(selected)))
        assertNull(section.indexOf(selected.copy(), library))
        assertNull(section.indexOf(track("1").copy(filepath = "/elsewhere.flac"), library))
    }

    @Test fun sameMutableListGrowingAfterSnapshotCannotReauthorizeOldCard() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val selected = track("1")
        val mutable = mutableListOf(selected)
        val section = MichiHomeIntelligenceAdapter.Section(
            "Michi Mix", "Afinidad", listOf(selected),
            mapOf(MichiIntelligencePolicy.TrackKey("local", "1") to 0),
            WeakReference(mutable),
        )
        assertEquals(0, section.indexOf(selected, mutable))
        mutable.add(track("2"))
        assertNull(section.indexOf(selected, mutable))
        assertEquals(false, section.belongsTo(mutable))
    }

    @Test fun invalidIndexOrMissingKeyDoesNotSelectAnotherTrack() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val selected = track()
        val library = listOf(selected)
        val wrongIndex = MichiHomeIntelligenceAdapter.Section(
            "Michi Mix", "Afinidad", listOf(selected),
            mapOf(MichiIntelligencePolicy.TrackKey("local", "1") to 100),
            WeakReference(library),
        )
        assertNull(wrongIndex.indexOf(selected, library))
        val missing = MichiHomeIntelligenceAdapter.Section(
            "Michi Mix", "Afinidad", listOf(selected), emptyMap(), WeakReference(library),
        )
        assertNull(missing.indexOf(selected, library))
    }
}

class MichiHomeMixQueueTest {
    private fun track(id: String) = Track(id = id, title = "Mix", sourceId = "local", filepath = "/music/$id.flac")
    @org.junit.Test fun mixPlansTheOwnQueueNotTheFullLibrary() {
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        val all = (0..25).map { track("$it") }
        val selected = listOf(all[11], all[3], all[17])
        val section = MichiHomeIntelligenceAdapter.Section(
            "Michi Mix", "Afinidad", selected,
            selected.associate { MichiIntelligencePolicy.TrackKey(it.sourceId, it.id) to all.indexOf(it) },
            WeakReference(all),
            kind = org.michimusic.core.intelligence.MichiHomeIntelligencePolicy.CardKind.MICHI_MIX,
        )
        val (queue, startIndex) = section.verifiedMixQueue(all, all[3])!!
        org.junit.Assert.assertEquals(selected, queue)
        org.junit.Assert.assertEquals(1, startIndex)
        org.junit.Assert.assertEquals(3, queue.size)
        org.junit.Assert.assertNull(section.verifiedMixQueue(all, track("3")))
        org.junit.Assert.assertNull(section.verifiedMixQueue(all.toList()))
    }
}

/** MI-21: in-flight Home IO must not re-publish a taste cache after invalidation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MichiHomeIntelligenceTransientCacheRaceTest {
    private lateinit var context: Context
    private lateinit var database: MichiDatabase
    private val roomName = "michi_mi21_home_cache_race.db"
    private lateinit var readableFile: File
    private val privateFile: File get() = File(context.noBackupFilesDir,
        "michi-intelligence-private-v1.sqlite")
    private val revokeFile: File get() = File(context.noBackupFilesDir,
        "michi-intelligence-revoked-v1.flag")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SQLiteDatabase.deleteDatabase(privateFile)
        revokeFile.delete()
        File(revokeFile.path + ".new").delete()
        File(revokeFile.path + ".bak").delete()
        context.deleteDatabase(roomName)
        readableFile = File.createTempFile("mi22-race-", ".flac", context.cacheDir)
            .apply { writeBytes(byteArrayOf(0x66, 0x4c, 0x61, 0x43)) }
        database = Room.databaseBuilder(context, MichiDatabase::class.java, roomName)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        database.close()
        readableFile.delete()
        SQLiteDatabase.deleteDatabase(privateFile)
        revokeFile.delete()
        File(revokeFile.path + ".new").delete()
        File(revokeFile.path + ".bak").delete()
        context.deleteDatabase(roomName)
    }

    @Test fun invalidationWinsOverSuspendedHomeCachePublication() = runBlocking {
        val pauseNextHistoryRead = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val delegate = database.appDao()
        val blockingDao = object : AppDao by delegate {
            override suspend fun getSetting(key: String): String? {
                if (key == "listening_history_enabled" &&
                    pauseNextHistoryRead.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    resume.await()
                }
                return delegate.getSetting(key)
            }
        }
        val repository = MichiIntelligenceRepository(context, blockingDao)
        org.junit.Assert.assertTrue(repository.enableFromExplicitConsent())
        val adapter = MichiHomeIntelligenceAdapter(context, repository)
        val songs = listOf(Track(id = "safe", title = "Track", artist = "Camel",
            genre = "Rock", sourceId = "", source = TrackSource.LOCAL,
            filepath = readableFile.absolutePath))
        org.junit.Assert.assertTrue(org.michimusic.core.models.TrackAvailabilityChecker.isTrackDownloaded(
            songs.single(), context))
        val cache = adapter.javaClass.getDeclaredField("cachedSnapshot").apply {
            isAccessible = true
        }

        pauseNextHistoryRead.set(true)
        val pending = async(Dispatchers.Default) { adapter.suggest(songs, emptyList(), null) }
        withTimeout(5_000) { entered.await() }
        adapter.clearTransientSelection() // Simulate scope/consent invalidation mid-IO.
        resume.complete(Unit)
        withTimeout(5_000) { pending.await() }
        assertNull("An older request re-published a stale cache", cache.get(adapter))

        // A NEW, authorized request can still populate the cache normally.
        adapter.suggest(songs, emptyList(), null)
        assertNotNull(cache.get(adapter))
    }
}
