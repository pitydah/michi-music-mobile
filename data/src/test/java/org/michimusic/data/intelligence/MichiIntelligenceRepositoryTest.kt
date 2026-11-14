package org.michimusic.data.intelligence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.intelligence.MichiHomeCardPreferencePolicy
import org.michimusic.core.intelligence.MichiHomeIntelligencePolicy
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.intelligence.MichiTasteFingerprint
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.SettingsEntity
import org.robolectric.RobolectricTestRunner
import java.io.File

/** MI-02R: real Android SQLite under noBackupFilesDir, plus real Room history gate. */
@RunWith(RobolectricTestRunner::class)
class MichiIntelligenceRepositoryTest {
    private lateinit var context: Context
    private lateinit var mainDb: MichiDatabase
    private lateinit var repo: MichiIntelligenceRepository
    private val dbName = "michi_intelligence_mi02r_test.db"
    private val privateFile: File get() = File(context.noBackupFilesDir,
        MichiIntelligenceRepository.FILE_NAME)
    private val revokeFile: File get() = File(context.noBackupFilesDir,
        MichiIntelligenceRepository.REVOKED_FILE_NAME)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SQLiteDatabase.deleteDatabase(privateFile)
        revokeFile.delete()
        File(revokeFile.path + ".new").delete()
        File(revokeFile.path + ".bak").delete()
        context.deleteDatabase(dbName)
        reopen()
    }

    @After fun tearDown() {
        mainDb.close()
        SQLiteDatabase.deleteDatabase(privateFile)
        revokeFile.delete()
        File(revokeFile.path + ".new").delete()
        File(revokeFile.path + ".bak").delete()
        context.deleteDatabase(dbName)
    }

    private fun reopen() {
        mainDb = Room.databaseBuilder(context, MichiDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        repo = MichiIntelligenceRepository(context, mainDb.appDao())
    }

    private fun event(
        id: String,
        artist: String = "Camel",
        genre: String = "Progressive Rock",
        owned: Boolean = true,
        outcome: MichiIntelligencePolicy.PlaybackOutcome =
            MichiIntelligencePolicy.PlaybackOutcome.COMPLETED,
        permille: Int = 950,
    ) = MichiIntelligenceRepository.PlaybackEvidence(
        eventId = id,
        sourceId = "local",
        trackId = "track-$id",
        artist = artist,
        genre = genre,
        observedEpochDay = 20_000,
        observedAtMs = 1_728_000_000_000L + id.length,
        outcome = outcome,
        completionPermille = permille,
        ownedLocalSession = owned,
        consentEpoch = MichiIntelligenceConsentBarrier.epoch(),
        ownerStillValid = { true },
    )

    private fun privateCount(prefix: String): Int {
        if (!privateFile.exists()) return 0
        SQLiteDatabase.openDatabase(privateFile.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM intelligence_entries WHERE k GLOB ?",
                arrayOf("$prefix*"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                return cursor.getInt(0)
            }
        }
    }

    @Test fun orphanedSqliteWalIsNotMistakenForCompletedDeletion() = runBlocking {
        val wal = File(privateFile.path + "-wal")
        wal.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(privateFile.exists())
        repo.disableAndErase()
        assertTrue(revokeFile.exists())
        assertFalse(wal.exists())
        assertFalse(repo.isEnabled())
    }

    @Test fun orphanedPrivateJournalIsRecoveredOnExplicitConsentRead() = runBlocking {
        // A torn filesystem unlink can leave sensitive WAL/journal bytes even
        // when the base database is absent. An explicit read recovers it.
        val journal = File(privateFile.path + "-journal")
        journal.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertFalse(privateFile.exists())
        assertFalse(repo.isEnabled())
        assertFalse(journal.exists())
        assertTrue(revokeFile.exists())
    }

    @Test fun corruptPrivateSqliteCanBeErasedEvenWhenTransactionsCannotOpen() = runBlocking {
        // A damaged non-backup file must never trap the user's private profile.
        privateFile.writeText("not a sqlite database - simulated corruption")
        repo.disableAndErase()
        assertTrue(revokeFile.exists())
        assertFalse(privateFile.exists())
        assertFalse(repo.isEnabled())
    }

    @Test fun corruptPrivateSqliteCannotRestoreConsentOnNextRead() = runBlocking {
        privateFile.writeText("corrupt SQLite header")
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        assertFalse(repo.isEnabled())
        assertTrue(revokeFile.exists())
        assertFalse(privateFile.exists())
        assertFalse(MichiIntelligenceConsentBarrier.permits(MichiIntelligenceConsentBarrier.epoch()))
    }

    @Test fun failedDeletionOfCorruptStoreLeavesDurableDenyMarker() = runBlocking {
        privateFile.writeText("corrupt SQLite header")
        val blocked = MichiIntelligenceRepository(context, mainDb.appDao(),
            deletePrivateDatabase = { false })
        var failed = false
        try { blocked.disableAndErase() } catch (_: java.io.IOException) { failed = true }
        assertTrue(failed)
        assertTrue(revokeFile.exists())
        assertTrue(privateFile.exists())
        assertFalse(MichiIntelligenceConsentBarrier.permits(MichiIntelligenceConsentBarrier.epoch()))
        // A later user-driven repair is idempotent; no worker or polling.
        repo.disableAndErase()
        assertFalse(privateFile.exists())
    }

    @Test fun interruptedAtomicNewMarkerRejectsPreviouslyEnabledProfile() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val pending = event("revoked-in-new")
        // AtomicFile.startWrite may have created .new when SIGKILL occurs.
        val newMarker = File(revokeFile.path + ".new")
        newMarker.writeBytes(byteArrayOf(1))
        assertEquals(MichiIntelligenceRepository.CaptureResult.DISABLED,
            repo.capture(pending))
        // A fresh process must not confuse missing base .flag with consent.
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        assertFalse(repo.isEnabled())
        assertFalse(privateFile.exists())
        assertTrue(newMarker.exists() || revokeFile.exists())
    }

    @Test fun legacyAtomicBackupMarkerRejectsRestoredProfile() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        File(revokeFile.path + ".bak").writeBytes(byteArrayOf(1))
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        assertFalse(repo.isEnabled())
        assertFalse(privateFile.exists())
        assertTrue(File(revokeFile.path + ".bak").exists() || revokeFile.exists())
    }

    @Test fun explicitReconsentClearsEveryAtomicRevocationSibling() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        File(revokeFile.path + ".new").writeBytes(byteArrayOf(1))
        File(revokeFile.path + ".bak").writeBytes(byteArrayOf(1))
        assertTrue(repo.enableFromExplicitConsent())
        assertFalse(revokeFile.exists())
        assertFalse(File(revokeFile.path + ".new").exists())
        assertFalse(File(revokeFile.path + ".bak").exists())
        assertTrue(repo.isEnabled())
    }

    @Test fun explicitEraseSurvivesCancellationOfSettingsUiJob() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED,
            repo.capture(event("erase_after_navigation")))
        val operation = repo.requestEraseFromUserAction()
        // The deny marker is durable BEFORE requestErase returns to Settings.
        assertTrue(revokeFile.exists())
        val simulatedUiJob = launch { operation.await() }
        simulatedUiJob.cancel()
        // Only the UI observer is cancelled: the app-owned erase still completes.
        operation.await()
        assertFalse(privateFile.exists())
        assertFalse(repo.isEnabled())
        assertEquals(0, privateCount("a."))
    }

    @Test fun durableMarkerRejectsOldEnabledProfileAfterSimulatedProcessRestart() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        repo.capture(event("old_affinity"))
        assertTrue(privateFile.exists())
        // Represent power loss after marker fsync but BEFORE SQLite OFF commit.
        revokeFile.writeBytes(byteArrayOf(1))
        // Simulate a new process whose in-memory fence has been freshly accepted.
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        assertFalse(repo.isEnabled())
        assertFalse(privateFile.exists())
        assertTrue(revokeFile.exists()) // prevent resurrection on a second restart
        assertTrue(repo.readSelectionSignals(20_000).artists.isEmpty())
        assertTrue(repo.enableFromExplicitConsent())
        assertFalse(revokeFile.exists())
        assertTrue(repo.isEnabled())
        assertTrue(repo.readAffinities(listOf("Camel"), emptyList(), 20_000).artist.isEmpty())
    }

    @Test fun existingDurableMarkerDeniesCaptureBeforePrivateDatabaseIsRepaired() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val queued = event("old_queue")
        revokeFile.writeBytes(byteArrayOf(1))
        // Cached event from an earlier, now-denied process must not be accepted.
        assertEquals(MichiIntelligenceRepository.CaptureResult.DISABLED, repo.capture(queued))
        assertEquals(0, privateCount("r."))
        assertFalse(repo.isEnabled())
    }

    @Test fun newlyEnabledConsentRemovesMarkerOnlyAfterFreshProfileIsReady() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        repo.requestEraseFromUserAction().await()
        assertTrue(revokeFile.exists())
        assertFalse(repo.isEnabled())
        assertTrue(repo.enableFromExplicitConsent())
        assertFalse(revokeFile.exists())
        assertTrue(repo.isEnabled())
        assertEquals(0, privateCount("a."))
    }

    @Test fun selectorSignalsAreHashedBoundedAndRevocable() = runBlocking {
        assertTrue(repo.readSelectionSignals(20_000).artists.isEmpty())
        assertTrue(repo.enableFromExplicitConsent())
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED,
            repo.capture(event("mi10-digest", artist="The Kittens", genre="Rock\u0000Jazz")))
        val signals = repo.readSelectionSignals(20_000)
        assertEquals(3, signals.artists[MichiTasteFingerprint.sha256("the kittens")])
        assertEquals(3, signals.genres[MichiTasteFingerprint.sha256("rock")])
        assertEquals(3, signals.genres[MichiTasteFingerprint.sha256("jazz")])
        assertEquals(1, signals.owned[MichiTasteFingerprint.ownedTrack("local", "track-mi10-digest")]?.completed)
        assertTrue(signals.artists.keys.none { "kittens" in it })
        repo.disableAndErase()
        val after = repo.readSelectionSignals(20_000)
        assertTrue(after.artists.isEmpty() && after.genres.isEmpty() && after.owned.isEmpty())
    }

    @Test fun selectorSignalsNeverCreatePrivateFileWhileConsentOff() = runBlocking {
        assertFalse(privateFile.exists())
        repeat(3) { assertTrue(repo.readSelectionSignals(20_000).artists.isEmpty()) }
        assertFalse(privateFile.exists())
    }

    @Test fun revokedEnableInsideOpenTransactionDoesNotReactivateLearning() = runBlocking {
        val racing = MichiIntelligenceRepository(context, mainDb.appDao(),
            enableBeforeCommitHook = { MichiIntelligenceConsentBarrier.revoke() })
        assertFalse(racing.enableFromExplicitConsent())
        assertFalse(repo.isEnabled())
        assertEquals(0, privateCount("a."))
        assertEquals(0, privateCount("g."))
        assertEquals(0, privateCount("r."))
    }

    @Test fun revokedEnableAfterPendingCommitNeverPromotesToOn() = runBlocking {
        val racing = MichiIntelligenceRepository(context, mainDb.appDao(),
            enableBeforeActivationHook = { MichiIntelligenceConsentBarrier.revoke() })
        assertFalse(racing.enableFromExplicitConsent())
        assertFalse(repo.isEnabled())
        assertEquals(0, privateCount("a."))
        assertEquals(0, privateCount("g."))
        assertEquals(0, privateCount("r."))
    }

    @Test fun activationExceptionMustRevokeInMemoryAndErasePendingProfile() = runBlocking {
        class SimulatedSQLiteFailure : RuntimeException()
        val interrupted = MichiIntelligenceRepository(context, mainDb.appDao(),
            enableBeforeActivationHook = { throw SimulatedSQLiteFailure() })
        var caught = false
        try { interrupted.enableFromExplicitConsent() }
        catch (_: SimulatedSQLiteFailure) { caught = true }
        assertTrue(caught)
        assertFalse(MichiIntelligenceConsentBarrier.permits(MichiIntelligenceConsentBarrier.epoch()))
        assertFalse(privateFile.exists())
        assertFalse(repo.isEnabled())
    }

    @Test fun interruptedPendingEnableIsNotRestoredAsConsent() = runBlocking {
        class SimulatedProcessTermination : RuntimeException()
        val interrupted = MichiIntelligenceRepository(context, mainDb.appDao(),
            enableBeforeActivationHook = { throw SimulatedProcessTermination() })
        var caught = false
        try { interrupted.enableFromExplicitConsent() }
        catch (_: SimulatedProcessTermination) { caught = true }
        assertTrue(caught)
        // A caught RuntimeException is NOT SIGKILL; normal error cleanup erases
        // the pending file and revokes process consent immediately.
        assertFalse(MichiIntelligenceConsentBarrier.permits(MichiIntelligenceConsentBarrier.epoch()))
        assertFalse(repo.isEnabled())
        assertFalse(privateFile.exists())
    }

    @Test fun revocationInsideOpenCaptureTransactionRollsBackReceiptAndTaste() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val pending = event("mid-transaction")
        val racing = MichiIntelligenceRepository(context, mainDb.appDao(),
            captureBeforeCommitHook = { MichiIntelligenceConsentBarrier.revoke() })
        assertEquals(MichiIntelligenceRepository.CaptureResult.DISABLED, racing.capture(pending))
        assertEquals(0, privateCount("r."))
        assertEquals(0, privateCount("a."))
        assertEquals(0, privateCount("g."))
        assertFalse(repo.isEnabled())
    }

    @Test fun handoffBeforeCaptureDoesNotWritePrivateEvidence() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val nonLocal = event("handoff-before").copy(ownerStillValid = { false })
        assertEquals(MichiIntelligenceRepository.CaptureResult.IGNORED, repo.capture(nonLocal))
        assertEquals(0, privateCount("r."))
    }

    @Test fun handoffDuringTransactionRollsBackReceiptAndTaste() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        var liveOwner = true
        val racing = MichiIntelligenceRepository(context, mainDb.appDao(),
            captureBeforeCommitHook = { liveOwner = false })
        val pending = event("handoff-during").copy(ownerStillValid = { liveOwner })
        assertEquals(MichiIntelligenceRepository.CaptureResult.IGNORED, racing.capture(pending))
        assertEquals(0, privateCount("r."))
        assertEquals(0, privateCount("a."))
        assertEquals(0, privateCount("t."))
    }

    @Test fun offByDefaultCreatesNoPrivateFileAndRecordsNothing() = runBlocking {
        assertFalse(repo.isEnabled())
        assertFalse(privateFile.exists())
        assertEquals(MichiIntelligenceRepository.CaptureResult.DISABLED, repo.capture(event("1")))
        assertFalse(privateFile.exists())
        assertTrue(repo.readAffinities(listOf("Camel"), listOf("Rock"), 20_000).artist.isEmpty())
    }

    @Test fun consentCreatesFileOnlyInsideAndroidNoBackupDirectory() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        assertTrue(privateFile.exists())
        assertEquals(context.noBackupFilesDir.canonicalPath, privateFile.parentFile!!.canonicalPath)
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED, repo.capture(event("ok")))
        assertEquals(1, privateCount("r."))
        assertEquals(0, mainDb.appDao().countIntelligencePrototypeRows())
    }

    @Test fun eventIsDeduplicatedWithinSameTransaction() = runBlocking {
        repo.enableFromExplicitConsent()
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED, repo.capture(event("same")))
        assertEquals(MichiIntelligenceRepository.CaptureResult.DUPLICATE, repo.capture(event("same")))
        val scores = repo.readAffinities(listOf("Camel"), listOf("Progressive Rock"), 20_000)
        assertEquals(3, scores.artist["camel"])
        assertEquals(3, scores.genre["progressive rock"])
        assertEquals(1, privateCount("r."))
    }

    @Test fun profileSurvivesProcessStyleReopenWithoutRoomPreferenceCopy() = runBlocking {
        repo.enableFromExplicitConsent()
        repo.capture(event("reopen"))
        mainDb.close()
        reopen()
        assertTrue(repo.isEnabled())
        assertEquals(MichiIntelligenceRepository.CaptureResult.DUPLICATE, repo.capture(event("reopen")))
        assertEquals(3, repo.readAffinities(listOf("camel"), emptyList(), 20_000).artist["camel"])
        assertEquals(0, mainDb.appDao().countIntelligencePrototypeRows())
    }

    @Test fun legacyPrototypeRoomKeysArePurgedButUnrelatedDataPreserved() = runBlocking {
        mainDb.appDao().setSetting(SettingsEntity("michi_intelligence.v1.a.old", "old-sensitive"))
        mainDb.appDao().setSetting(SettingsEntity("other_setting", "keep"))
        mainDb.appDao().setSetting(SettingsEntity("michiXintelligence.v1.a.fake", "keep"))
        assertTrue(repo.enableFromExplicitConsent())
        assertEquals(0, mainDb.appDao().countIntelligencePrototypeRows())
        assertEquals("keep", mainDb.appDao().getSetting("other_setting"))
        assertEquals("keep", mainDb.appDao().getSetting("michiXintelligence.v1.a.fake"))
    }

    @Test fun optOutWipesPrivateDatabaseAndLeavesLibrarySettingsAlone() = runBlocking {
        mainDb.appDao().setSetting(SettingsEntity("other_setting", "keep"))
        repo.enableFromExplicitConsent()
        repo.capture(event("erase"))
        assertTrue(privateFile.exists())
        repo.disableAndErase()
        assertFalse(privateFile.exists())
        assertFalse(repo.isEnabled())
        assertEquals("keep", mainDb.appDao().getSetting("other_setting"))
    }

    @Test fun historyOptOutPreventsCaptureAndErasesOnNextAccess() = runBlocking {
        mainDb.appDao().setSetting(SettingsEntity("listening_history_enabled", "false"))
        assertFalse(repo.enableFromExplicitConsent())
        assertFalse(privateFile.exists())
        mainDb.appDao().setSetting(SettingsEntity("listening_history_enabled", "true"))
        repo.enableFromExplicitConsent()
        repo.capture(event("before-revoke"))
        mainDb.appDao().setSetting(SettingsEntity("listening_history_enabled", "false"))
        assertEquals(MichiIntelligenceRepository.CaptureResult.DISABLED, repo.capture(event("after-revoke")))
        assertFalse(privateFile.exists())
    }

    @Test fun readAfterHistoryRevokeAlsoErasesProfile() = runBlocking {
        repo.enableFromExplicitConsent()
        repo.capture(event("sensitive"))
        mainDb.appDao().setSetting(SettingsEntity("listening_history_enabled", "false"))
        val scores = repo.readAffinities(listOf("Camel"), emptyList(), 20_000)
        assertTrue(scores.artist.isEmpty())
        assertFalse(privateFile.exists())
    }

    @Test fun interruptedAndForeignSessionNeverWriteReceipts() = runBlocking {
        repo.enableFromExplicitConsent()
        assertEquals(MichiIntelligenceRepository.CaptureResult.IGNORED,
            repo.capture(event("remote", owned = false)))
        assertEquals(MichiIntelligenceRepository.CaptureResult.IGNORED,
            repo.capture(event("pause", outcome = MichiIntelligencePolicy.PlaybackOutcome.INTERRUPTED)))
        assertEquals(MichiIntelligenceRepository.CaptureResult.IGNORED,
            repo.capture(event("short", permille = 10)))
        assertEquals(0, privateCount("r."))
    }

    @Test fun multiValueGenresAreSeparatePreferences() = runBlocking {
        repo.enableFromExplicitConsent()
        repo.capture(event("multigenre", genre = "Rock/Pop\u0000Progressive Rock\u0000Jazz\u0000Unknown"))
        val scores = repo.readAffinities(emptyList(),
            listOf("rock/pop", "progressive rock", "jazz", "unknown"), 20_000)
        assertEquals(3, scores.genre["rock/pop"])
        assertEquals(3, scores.genre["progressive rock"])
        assertEquals(3, scores.genre["jazz"])
        assertFalse(scores.genre.containsKey("unknown"))
    }

    @Test fun storageRemainsBoundedUnderRepeatedListening() = runBlocking {
        repo.enableFromExplicitConsent()
        repeat(280) { index ->
            assertEquals(MichiIntelligenceRepository.CaptureResult.STORED,
                repo.capture(event("item-$index", artist = "artist $index", genre = "genre $index")))
        }
        assertEquals(MichiIntelligenceRepository.MAX_RECENT_EVENTS, privateCount("r."))
        assertTrue(privateCount("a.") + privateCount("g.") <=
            MichiIntelligenceRepository.MAX_PREFERENCES)
        assertEquals(0, mainDb.appDao().countIntelligencePrototypeRows())
    }
    @Test fun queuedPreRevocationEventCannotTrainOnReEnable() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val queuedBeforeRevoke = event("queued")
        repo.disableAndErase()
        assertTrue(repo.enableFromExplicitConsent())
        assertEquals(MichiIntelligenceRepository.CaptureResult.DISABLED,
            repo.capture(queuedBeforeRevoke))
        assertEquals(0, privateCount("r."))
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED,
            repo.capture(event("fresh")))
        assertEquals(1, privateCount("r."))
    }

    @Test fun failedUnlinkLeavesDurableDisabledTombstoneAndNoPreferences() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED,
            repo.capture(event("listened")))
        val failingUnlink = MichiIntelligenceRepository(context, mainDb.appDao(), deletePrivateDatabase = { false })
        var failedAsExpected = false
        try { failingUnlink.disableAndErase() }
        catch (_: java.io.IOException) { failedAsExpected = true }
        assertTrue(failedAsExpected)
        assertTrue(privateFile.exists())
        assertEquals(0, privateCount("a."))
        assertEquals(0, privateCount("g."))
        assertEquals(0, privateCount("r."))
        SQLiteDatabase.openDatabase(privateFile.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT v FROM intelligence_entries WHERE k='enabled'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("false", cursor.getString(0))
            }
        }
        // A new repository instance, same process, must not resume learning.
        repo = MichiIntelligenceRepository(context, mainDb.appDao())
        assertFalse(repo.isEnabled())
        assertFalse(privateFile.exists())
    }

    @Test fun reEnableAfterInterruptedDeletionStartsWithEmptyTaste() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED,
            repo.capture(event("old")))
        val failed = MichiIntelligenceRepository(context, mainDb.appDao(), deletePrivateDatabase = { false })
        try { failed.disableAndErase() } catch (_: java.io.IOException) {}
        assertTrue(privateFile.exists())
        // New explicit opt-in may occur before any read repairs that leftover file.
        assertTrue(repo.enableFromExplicitConsent())
        assertEquals(0, privateCount("a."))
        assertEquals(0, privateCount("r."))
        val old = repo.readAffinities(listOf("camel"), emptyList(), 20_000)
        assertTrue(old.artist.isEmpty())
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED,
            repo.capture(event("new")))
        assertEquals(1, privateCount("r."))
    }

    @Test fun disabledTombstoneRejectsQueuedCaptureEvenWithFreshEpoch() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val queued = event("queued-before-off")
        val failed = MichiIntelligenceRepository(context, mainDb.appDao(), deletePrivateDatabase = { false })
        try { failed.disableAndErase() } catch (_: java.io.IOException) {}
        // Old producer events must never restore counters, regardless of retry.
        assertEquals(MichiIntelligenceRepository.CaptureResult.DISABLED,
            repo.capture(queued))
        assertEquals(0, privateCount("r."))
    }

    @Test fun completedCountsUseExactOwnerAndDoNotInferMissingEntries() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val first = event("owner-proof").copy(sourceId = "A", trackId = "shared-id")
        val second = event("owner-proof-2").copy(sourceId = "B", trackId = "shared-id")
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED, repo.capture(first))
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED, repo.capture(second))
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED,
            repo.capture(event("again").copy(sourceId = "A", trackId = "shared-id")))
        val a = MichiIntelligencePolicy.TrackKey("A", "shared-id")
        val b = MichiIntelligencePolicy.TrackKey("B", "shared-id")
        val missing = MichiIntelligencePolicy.TrackKey("A", "unknown-id")
        val counts = repo.readOwnedTrackCounts(listOf(a, b, missing))
        assertEquals(2, counts[a])
        assertEquals(1, counts[b])
        assertFalse(counts.containsKey(missing))
        assertEquals(2, privateCount("t."))
    }

    @Test fun skipsNeverAddToOwnedCompletedCountsAndRevokeWipesThem() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val skipped = event("skip-track", outcome = MichiIntelligencePolicy.PlaybackOutcome.EXPLICIT_SKIP,
            permille = 100)
        assertEquals(MichiIntelligenceRepository.CaptureResult.STORED, repo.capture(skipped))
        val key = MichiIntelligencePolicy.TrackKey("local", "track-skip-track")
        assertTrue(repo.readOwnedTrackCounts(listOf(key)).isEmpty())
        assertEquals(0, privateCount("t."))
        repo.disableAndErase()
        assertFalse(privateFile.exists())
        assertTrue(repo.readOwnedTrackCounts(listOf(key)).isEmpty())
    }

    @Test fun ownedCompletedCountersAreBoundedAndSurviveReopen() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        repeat(150) { idx ->
            assertEquals(MichiIntelligenceRepository.CaptureResult.STORED,
                repo.capture(event("owned-$idx", artist = "Artist", genre = "Jazz")))
        }
        assertEquals(org.michimusic.core.intelligence.MichiOwnedPlayCountPolicy.MAX_TRACK_COUNTERS,
            privateCount("t."))
        mainDb.close()
        reopen()
        assertTrue(repo.isEnabled())
        val latest = MichiIntelligencePolicy.TrackKey("local", "track-owned-149")
        assertEquals(1, repo.readOwnedTrackCounts(listOf(latest))[latest])
    }


    @Test fun explicitNoInterestIsOwnerScopedAndExpiresWithoutBackgroundWorker() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val own = MichiIntelligencePolicy.TrackKey("A", "shared-track")
        val other = MichiIntelligencePolicy.TrackKey("B", "shared-track")
        assertTrue(repo.dismissSuggestion(own, 20_000, MichiIntelligenceConsentBarrier.epoch()))
        assertEquals(setOf(own), repo.readDismissedKeys(listOf(own, other), 20_000))
        assertEquals(setOf(own), repo.readDismissedKeys(listOf(own, other), 20_029))
        assertTrue(repo.readDismissedKeys(listOf(own, other), 20_030).isEmpty())
        assertEquals(1, privateCount("f."))
        repo.disableAndErase()
        assertTrue(repo.readDismissedKeys(listOf(own), 20_000).isEmpty())
    }

    @Test fun noInterestFeedbackNeverPersistsWithoutConsentAndIsBounded() = runBlocking {
        val key = MichiIntelligencePolicy.TrackKey("local", "track")
        assertFalse(repo.dismissSuggestion(key, 20_000, MichiIntelligenceConsentBarrier.epoch()))
        assertEquals(0, privateCount("f."))
        assertTrue(repo.enableFromExplicitConsent())
        repeat(90) { idx ->
            assertTrue(repo.dismissSuggestion(
                MichiIntelligencePolicy.TrackKey("local", "track-$idx"),
                20_000 + idx.toLong(), MichiIntelligenceConsentBarrier.epoch()))
        }
        assertEquals(org.michimusic.core.intelligence.MichiSuggestionFeedbackPolicy.MAX_DISMISSED,
            privateCount("f."))
        MichiIntelligenceConsentBarrier.revoke()
        assertFalse(repo.dismissSuggestion(key, 20_999, MichiIntelligenceConsentBarrier.epoch()))
    }


    @Test fun undoOneDismissedRecommendationRestoresOnlyThatOwner() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        val one = MichiIntelligencePolicy.TrackKey("phone", "same-id")
        val other = MichiIntelligencePolicy.TrackKey("usb", "same-id")
        assertTrue(repo.dismissSuggestion(one, 20_000L, epoch))
        assertTrue(repo.dismissSuggestion(other, 20_000L, epoch))
        assertTrue(one in repo.readDismissedKeys(listOf(one, other), 20_000L))
        assertTrue(repo.undoDismissedSuggestion(one, epoch))
        val remaining = repo.readDismissedKeys(listOf(one, other), 20_000L)
        assertFalse(one in remaining)
        assertTrue(other in remaining)
        repo.disableAndErase()
        assertFalse(repo.undoDismissedSuggestion(other, epoch))
    }

    @Test fun cardPrefsRemainAbsentUntilExplicitOptIn() = runBlocking {
        val kind = MichiHomeIntelligencePolicy.CardKind.NEXT_LISTEN
        val choice = MichiHomeCardPreferencePolicy.Choice.PINNED
        assertTrue(repo.readCardPreferences().isEmpty())
        assertFalse(repo.setCardPreference(kind, choice, MichiIntelligenceConsentBarrier.epoch()))
        assertFalse(privateFile.exists())
    }

    @Test fun pinnedHiddenPreferencesSurviveReopenAndRecoverOnlyHidden() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val pinned = MichiHomeIntelligencePolicy.CardKind.REDISCOVER
        val hidden = MichiHomeIntelligencePolicy.CardKind.HIDDEN_GEMS
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        assertTrue(repo.setCardPreference(pinned, MichiHomeCardPreferencePolicy.Choice.PINNED, epoch))
        assertTrue(repo.setCardPreference(hidden, MichiHomeCardPreferencePolicy.Choice.HIDDEN, epoch))
        assertEquals(2, privateCount("c."))
        mainDb.close()
        reopen()
        val saved = repo.readCardPreferences()
        assertEquals(MichiHomeCardPreferencePolicy.Choice.PINNED, saved[pinned])
        assertEquals(MichiHomeCardPreferencePolicy.Choice.HIDDEN, saved[hidden])
        assertTrue(repo.restoreHiddenCards(MichiIntelligenceConsentBarrier.epoch()))
        val restored = repo.readCardPreferences()
        assertEquals(MichiHomeCardPreferencePolicy.Choice.PINNED, restored[pinned])
        assertFalse(restored.containsKey(hidden))
        assertEquals(1, privateCount("c."))
    }

    @Test fun corruptCardPreferencePrefixCannotShadowAllFiveLegalChoices() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        val kinds = MichiHomeIntelligencePolicy.CardKind.entries
        for (kind in kinds) {
            assertTrue(repo.setCardPreference(kind, MichiHomeCardPreferencePolicy.Choice.PINNED, epoch))
        }
        SQLiteDatabase.openDatabase(privateFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val bogus = android.content.ContentValues(2).apply {
                put("k", "c.!legacy-corrupt")
                put("v", "h")
            }
            db.insertWithOnConflict("intelligence_entries", null, bogus, SQLiteDatabase.CONFLICT_REPLACE)
        }
        val preferences = repo.readCardPreferences()
        assertEquals(kinds.size, preferences.size)
        assertTrue(kinds.all { preferences[it] == MichiHomeCardPreferencePolicy.Choice.PINNED })
    }

    @Test fun hidingAndUnpinningNeverTrainMusicTasteAndWipeWithProfile() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val kind = MichiHomeIntelligencePolicy.CardKind.MICHI_MIX
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        assertTrue(repo.setCardPreference(kind, MichiHomeCardPreferencePolicy.Choice.PINNED, epoch))
        assertTrue(repo.setCardPreference(kind, MichiHomeCardPreferencePolicy.Choice.DEFAULT, epoch))
        assertEquals(0, privateCount("c."))
        assertTrue(repo.setCardPreference(kind, MichiHomeCardPreferencePolicy.Choice.HIDDEN, epoch))
        assertEquals(0, privateCount("a."))
        assertEquals(0, privateCount("g."))
        repo.disableAndErase()
        assertTrue(repo.readCardPreferences().isEmpty())
        assertFalse(privateFile.exists())
    }

    @Test fun staleConsentCannotPinHideOrRestoreAfterRevocation() = runBlocking {
        assertTrue(repo.enableFromExplicitConsent())
        val old = MichiIntelligenceConsentBarrier.epoch()
        val kind = MichiHomeIntelligencePolicy.CardKind.NEXT_LISTEN
        assertTrue(repo.setCardPreference(kind, MichiHomeCardPreferencePolicy.Choice.HIDDEN, old))
        MichiIntelligenceConsentBarrier.revoke()
        assertFalse(repo.setCardPreference(kind, MichiHomeCardPreferencePolicy.Choice.PINNED, old))
        assertFalse(repo.restoreHiddenCards(old))
        assertTrue(repo.readCardPreferences().isEmpty())
    }
}
