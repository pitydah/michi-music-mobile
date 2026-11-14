package org.michimusic.mobile.intelligence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackAvailabilityChecker
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.intelligence.MichiIntelligenceRepository
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MI-22: REAL Home adapter/coordinator + REAL private SQLite repository + Room DAO.
 * There is NO hypothetical repository constructor, mutable testing property, or
 * android.content.Context() instantiation. This is a Robolectric JVM test.
 *
 * All synthetic metadata entries deliberately point at the SAME nonempty local
 * test file. These tests verify candidate retrieval and physical availability,
 * not audio decoding or uniqueness of actual music files; no MediaStore scan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MichiLibraryRetrievalIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: MichiDatabase
    private lateinit var repository: MichiIntelligenceRepository
    private lateinit var readableFile: File
    private val roomName = "michi_mi22_home_integration.db"
    private val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
    private val privateFile: File get() = File(context.noBackupFilesDir,
        "michi-intelligence-private-v1.sqlite")
    private val denyFile: File get() = File(context.noBackupFilesDir,
        "michi-intelligence-revoked-v1.flag")

    private fun cleanPrivateFiles() {
        SQLiteDatabase.deleteDatabase(privateFile)
        denyFile.delete()
        File(denyFile.path + ".new").delete()
        File(denyFile.path + ".bak").delete()
    }

    @Before fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        cleanPrivateFiles()
        context.deleteDatabase(roomName)
        database = Room.databaseBuilder(context, MichiDatabase::class.java, roomName)
            .allowMainThreadQueries().build()
        readableFile = File.createTempFile("michi-mi22-", ".flac", context.cacheDir)
            .apply { writeBytes(byteArrayOf(0x66, 0x4c, 0x61, 0x43)) }
        assertTrue(TrackAvailabilityChecker.isTrackDownloaded(t(0), context))
        repository = MichiIntelligenceRepository(context, database.appDao())
        assertTrue("Explicit learning opt-in must succeed", repository.enableFromExplicitConsent())
        val result = repository.capture(MichiIntelligenceRepository.PlaybackEvidence(
            eventId = "mi22-favorite-completed",
            sourceId = "",
            trackId = "seed",
            artist = "Favorite",
            genre = "",
            observedEpochDay = today,
            observedAtMs = 1_730_000_000_000L,
            outcome = MichiIntelligencePolicy.PlaybackOutcome.COMPLETED,
            completionPermille = 950,
            ownedLocalSession = true,
            consentEpoch = MichiIntelligenceConsentBarrier.epoch(),
            ownerStillValid = { true },
        ))
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED, result)
        assertTrue("Real private repository must retain positive artist evidence",
            repository.readAffinities(listOf("favorite"), emptyList(), today).artist["favorite"]!! > 0)
    }

    @After fun tearDown() {
        database.close()
        readableFile.delete()
        cleanPrivateFiles()
        context.deleteDatabase(roomName)
        MichiIntelligenceConsentBarrier.revoke()
    }

    private fun t(i: Int): Track = Track(
        id = "track-$i", title = "Song $i", filepath = readableFile.absolutePath,
        artist = if (i >= 49900) "Favorite" else "Performer ${i % 50}",
        genre = "Rock", sourceId = "", source = TrackSource.LOCAL,
    )

    @Test fun productionAvailabilityAcceptsOnlyPresentNonemptyLocalFixtures() {
        assertTrue(TrackAvailabilityChecker.isTrackDownloaded(t(1), context))
        val missing = File(context.cacheDir, "michi-mi22-definitely-missing.flac")
        missing.delete()
        assertFalse(TrackAvailabilityChecker.isTrackDownloaded(t(2).copy(filepath = missing.path), context))
        val empty = File.createTempFile("michi-mi22-empty-", ".flac", context.cacheDir)
        try {
            assertFalse(TrackAvailabilityChecker.isTrackDownloaded(t(3).copy(filepath = empty.path), context))
        } finally { empty.delete() }
    }

    @Test fun adapterFindsLateFavoriteThroughWholeAuthorizedLibrary(): Unit = runBlocking {
        val library = (0 until 50_000).map(::t)
        val result = MichiHomeIntelligenceAdapter(context, repository)
            .suggest(library, emptyList(), null)
        assertTrue(result.sections.isNotEmpty())
        assertTrue(result.sections.flatMap { it.tracks }.any { it.artist == "Favorite" })
        assertTrue(result.sections.flatMap { it.tracks }.size <= 12)
    }

    @Test fun sameMutableLibraryGrowingInvalidatesCachedCandidateSelection(): Unit = runBlocking {
        val library = (0 until 250).map(::t).toMutableList()
        val adapter = MichiHomeIntelligenceAdapter(context, repository)
        val before = adapter.suggest(library, emptyList(), null)
        assertTrue(before.sections.flatMap { it.tracks }.none { it.artist == "Favorite" })
        library.add(t(49999))
        val after = adapter.suggest(library, emptyList(), null)
        assertTrue(after.sections.flatMap { it.tracks }.any { it.artist == "Favorite" })
    }

    @Test fun sameSizeReplacementAtSelectedPositionInvalidatesCachedSelection(): Unit = runBlocking {
        val library = (0 until 250).map(::t).toMutableList()
        library[0] = t(49999)
        val adapter = MichiHomeIntelligenceAdapter(context, repository)
        val before = adapter.suggest(library, emptyList(), null)
        assertTrue(before.sections.flatMap { it.tracks }.any { it.id == "track-49999" })
        library[0] = t(2_000)
        val after = adapter.suggest(library, emptyList(), null)
        assertTrue(after.sections.flatMap { it.tracks }.none { it.id == "track-49999" })
    }

    @Test fun aNewDayRebuildsCandidatePoolEvenWhenSignalsAndListAreUnchanged(): Unit = runBlocking {
        var clockDay = today
        val adapter = MichiHomeIntelligenceAdapter(context, repository) { clockDay }
        val library = (0 until 300).map(::t) + t(49999)
        val field = adapter.javaClass.getDeclaredField("cachedSnapshot")
            .apply { isAccessible = true }
        adapter.suggest(library, emptyList(), null)
        val first = field.get(adapter)
        assertNotNull(first)
        adapter.suggest(library, emptyList(), null)
        assertTrue(first === field.get(adapter))
        clockDay += 1L
        adapter.suggest(library, emptyList(), null)
        assertTrue(first !== field.get(adapter))
    }

    @Test fun coordinatorPublishesOnlyAuthorizedSnapshotAndInvalidatesOnRevoke(): Unit = runBlocking {
        val adapter = MichiHomeIntelligenceAdapter(context, repository)
        val coordinator = MichiHomeIntelligenceCoordinator(adapter)
        val library = (0 until 1200).map(::t) + t(49999)
        coordinator.refresh(library, emptyList(), null)
        assertTrue(coordinator.state.value.sections.isNotEmpty())
        MichiIntelligenceConsentBarrier.revoke()
        coordinator.invalidate()
        assertTrue(coordinator.state.value.sections.isEmpty())
    }

    @Test fun suppressTrackRemovesOnlySelectedSongNotEntireSection(): Unit = runBlocking {
        val coordinator = MichiHomeIntelligenceCoordinator(
            MichiHomeIntelligenceAdapter(context, repository))
        val library = (0 until 1200).map(::t) + (49900..49990).map(::t)
        coordinator.refresh(library, emptyList(), null)
        val sections = coordinator.state.value.sections
        assertTrue(sections.isNotEmpty())
        val victim = sections.flatMap { it.tracks }.first()
        val before = sections.flatMap { it.tracks }.size
        coordinator.suppressTrack(victim)
        val after = coordinator.state.value.sections.flatMap { it.tracks }
        assertTrue(after.none { it.id == victim.id && it.sourceId == victim.sourceId })
        assertTrue(after.size < before)
    }
}
