package org.michimusic.data.metadata

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorColdRead
import org.michimusic.data.repository.SyncedTrackRepository

/**
 * A15 "MediaStore other UID" + A16 "consent denied/revoked", with NO mock in the
 * permission path.
 *
 * An auxiliary TEST-ONLY application (`:metadata-fixture`, package
 * `org.michimusic.metadata.fixture`) is installed inside the isolated emulator
 * and creates REAL MediaStore audio rows from its OWN UID. This test process
 * (the `:data` androidTest APK UID) therefore holds only what the platform
 * really gives it:
 *
 *  - a framework `FLAG_GRANT_READ_URI_PERMISSION` granted by the real owner, and
 *  - NO write access at all until the REAL system consent surface from
 *    `MediaStore.createWriteRequest` is accepted by the user.
 *
 * The single gate method drives every outcome end to end:
 *
 *  1. UID independence: the fixture reports a different UID and package.
 *  2. ACCEPT: `save()` returns `ConsentRequired`; UI Automator accepts the
 *     system surface (after recreating the caller activity mid-dialog); a late
 *     `reconstructPreparedSave` + `continuePreparedSave` commits real bytes.
 *     SHA-256 is compared BEFORE (owner + target view) and AFTER (owner view,
 *     target view, committed receipt, Room CAS row and applied marker).
 *  3. DENY: real negative answer, no grant, production refuses with
 *     `EXACT_GRANT_MISSING`, bytes and index untouched.
 *  4. CANCEL: real dismissal, same fail-closed assertions.
 *  5. REVOKE: the actual grantor revokes the framework grant; the prepared
 *     continuation is refused, bytes/index untouched, and the URI is
 *     revalidated by re-granting read access.
 *
 * Every branch asserts the production refusal AND that the owner-side bytes are
 * unchanged, so a silently-permissive provider can never satisfy the test.
 * No `assume`, no skip, no disabled assertion.
 */
@RunWith(AndroidJUnit4::class)
class MetadataOtherUidConsentInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private lateinit var device: UiDevice
    private lateinit var database: MichiDatabase
    private lateinit var service: MetadataSaveService
    private lateinit var repository: ExtendedTrackMetadataRepository
    private lateinit var coordinator: MetadataPreparationCoordinator
    private lateinit var fixture: MetadataOtherUidFixtureClient
    private lateinit var audioPayload: ByteArray

    private val createdUris = mutableListOf<Uri>()
    private val sourceId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID

    // Display names carry a per-run tag so a crash-retry can never collide with
    // a row a previous run of this fixture left behind (MediaProvider would
    // rename the newcomer and the URI revalidation would silently drift).
    private val runTag = UUID.randomUUID().toString().take(8)

    // The production reader resolves the cold read from the URI, not an index row.
    private val readSourceId = ""

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        runCatching { device.wakeUp() }
        audioPayload = instrumentation.context.assets.open("me13-silence.mp3")
            .use { it.readBytes() }
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = MetadataSaveService(context, database.trackDao(), database)
        repository = ExtendedTrackMetadataRepository(
            trackDao = database.trackDao(),
            context = context,
        )
        coordinator = MetadataPreparationCoordinator(context, repository)
        fixture = MetadataOtherUidFixtureClient(instrumentation.context, device)
        fixture.ensureInstalled()
    }

    @After
    fun tearDown() {
        runCatching {
            runBlocking {
                val pending = coordinator.inspectPending()
                if (pending is MetadataPreparationCoordinator.Pending.PreparedOrInterrupted) {
                    coordinator.discardPrepared(pending.transactionId, true)
                }
            }
        }
        for (uri in createdUris) {
            runCatching { fixture.delete(uri) }
        }
        runCatching { database.close() }
    }

    @Test
    fun acceptDenyAndRevokeRealSystemConsent() = runBlocking {
        assertFixtureRunsInAnotherUid()

        acceptRealSystemConsent()
        denyRealSystemConsent()
        cancelRealSystemConsent()
        revokeRealSystemConsentGrant()
    }

    // ---------------------------------------------------------------- phase 1

    private fun assertFixtureRunsInAnotherUid() {
        val ping = fixture.ping()
        assertEquals(
            MetadataOtherUidFixtureClient.FIXTURE_PACKAGE,
            ping.getString(MetadataOtherUidFixtureClient.EXTRA_PACKAGE_NAME),
        )
        val fixtureUid = ping.getInt(MetadataOtherUidFixtureClient.EXTRA_UID)
        assertTrue("the fixture must report a live uid, got $fixtureUid", fixtureUid > 0)
        assertNotEquals(
            "the fixture application MUST run in another UID, saw $fixtureUid",
            Process.myUid().toLong(),
            fixtureUid.toLong(),
        )
        assertNotEquals(
            MetadataOtherUidFixtureClient.FIXTURE_PACKAGE,
            context.packageName,
        )
    }

    // ---------------------------------------------------------------- phase 2

    private suspend fun acceptRealSystemConsent() {
        val source = createOtherUidSource(
            label = "accept",
            title = "ME15 Aceptado",
            candidateTitle = "ME15 Aceptado Editado",
        )
        val seededRevision = trackRevision(source.trackId)

        val first = service.save(
            sourceId = sourceId,
            trackId = source.trackId,
            uriString = source.uri.toString(),
            expectedBytes = source.originalBytes.size.toLong(),
            reviewInput = source.review,
            ownerCurrent = { true },
            playbackClosed = { true },
        )
        assertTrue(
            "a file owned by another UID must require the real system consent, got $first",
            first is MetadataSaveService.Outcome.ConsentRequired,
        )
        val consent = first as MetadataSaveService.Outcome.ConsentRequired
        val continuation = consent.continuation
        assertEquals(sourceId, continuation.sourceId)
        assertEquals(source.trackId, continuation.trackId)
        assertEquals(source.uri.toString(), continuation.uri)
        assertEquals(source.originalSha256, continuation.originalSha256)
        assertNotEquals(continuation.originalSha256, continuation.candidateSha256)
        assertTrue(continuation.candidateBytes > 0L)
        // The write grant still does not exist: only the prepared transaction does.
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            permissionOf(source.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        )
        assertJournalPrepared(continuation.transactionId)

        val decision = awaitConsentDecision(
            sender = consent.intent.intentSender,
            decision = ConsentDecision.ALLOW,
            recreateWhileDialogVisible = true,
        )
        assertEquals(Activity.RESULT_OK, decision)
        assertEquals(
            "the real system consent must leave an exact write grant",
            PackageManager.PERMISSION_GRANTED,
            permissionOf(source.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        )

        // "Late" continuation: the durable evidence is rebuilt from the journal,
        // not reused from the in-memory object the caller happened to keep.
        Thread.sleep(LATE_CONTINUATION_MS)
        val lookup = service.reconstructPreparedSave(
            continuation.transactionId,
            sourceId,
            source.trackId,
            source.uri.toString(),
        )
        assertTrue(
            "the durable journal must rebuild the consent continuation, got $lookup",
            lookup is MetadataSaveService.ContinuationLookup.Ready,
        )
        val rebuilt = (lookup as MetadataSaveService.ContinuationLookup.Ready).continuation
        assertEquals(continuation.transactionId, rebuilt.transactionId)
        assertEquals(continuation.candidateSha256, rebuilt.candidateSha256)
        assertEquals(continuation.candidateBytes.toLong(), rebuilt.candidateBytes.toLong())
        assertEquals(continuation.ownerDigest, rebuilt.ownerDigest)
        assertEquals(source.originalSha256, rebuilt.originalSha256)

        val continued = service.continuePreparedSave(rebuilt, { true }, { true })
        assertTrue(
            "an accepted system consent must commit the prepared save, got $continued",
            continued is MetadataSaveService.Outcome.Saved,
        )
        val saved = continued as MetadataSaveService.Outcome.Saved
        assertTrue("the verified commit must publish the index", saved.indexPublished)
        assertEquals(rebuilt.candidateSha256, saved.committedSha256)
        assertEquals(rebuilt.candidateBytes.toLong(), saved.committedBytes.toLong())

        // AFTER: independent views of the same bytes must agree.
        val targetBytes = readBytes(source.uri)
        assertEquals(saved.committedSha256, sha(targetBytes))
        assertNotEquals(
            "the committed bytes MUST differ from the original payload",
            source.originalSha256,
            sha(targetBytes),
        )
        assertOwnerDigest(source, expectedSha256 = saved.committedSha256,
            expectedBytes = saved.committedBytes)
        assertArrayEquals(
            audioPayload,
            targetBytes.copyOfRange(targetBytes.size - audioPayload.size, targetBytes.size),
        )

        // Permission and URI revalidation after the commit.
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            permissionOf(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            permissionOf(source.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        )
        assertEquals(source.displayName, displayNameOf(source.uri))

        // Index truth: the CAS publication is the only accepted source of truth.
        val row = checkNotNull(database.trackDao().getTrackBySource(sourceId, source.trackId)) {
            "the published track row disappeared"
        }
        assertEquals(source.candidateTitle, row.title)
        assertEquals(saved.committedBytes.toLong(), row.size.toLong())
        assertEquals(0L, row.verifiedAt.toLong())
        assertEquals("", row.verifiedSha256)
        assertNotEquals(seededRevision.toLong(), row.metadataRevision.toLong())
        val change = checkNotNull(saved.libraryChange) { "a verified save must publish a change" }
        assertEquals(setOf(MetadataField.TITLE), change.changedFields)
        assertEquals(source.trackId, change.trackId)
        assertEquals(source.uri.toString(), change.uri)
        assertEquals(row.metadataRevision.toLong(), change.committedRevision.toLong())
        val marker = checkNotNull(
            database.trackDao().getAppliedMetadataTransaction(change.transactionId),
        ) { "the verified transaction must be recorded in the index" }
        assertEquals(change.previousRevision.toLong(), marker.originalRevision.toLong())
        assertEquals(change.committedRevision.toLong(), marker.committedRevision.toLong())
        assertEquals(saved.committedSha256, marker.candidateSha256)
        assertEquals(saved.committedBytes.toLong(), marker.candidateBytes.toLong())

        // Cold reopen through the production reader proves the tags are real.
        val reopened = coldRead(source, saved.committedBytes)
        assertEquals(
            listOf(source.candidateTitle),
            reopened.known[MetadataField.TITLE],
        )
        assertEquals(
            MetadataPreparationCoordinator.Pending.Clear,
            coordinator.inspectPending(),
        )
    }

    // ---------------------------------------------------------------- phase 3

    private suspend fun denyRealSystemConsent() {
        val source = createOtherUidSource(
            label = "deny",
            title = "ME15 Denegado",
            candidateTitle = "ME15 Nunca Escrito",
        )
        val seededRevision = trackRevision(source.trackId)
        val consent = requestConsent(source)
        assertJournalPrepared(consent.continuation.transactionId)

        val decision = awaitConsentDecision(
            sender = consent.intent.intentSender,
            decision = ConsentDecision.DENY,
            recreateWhileDialogVisible = false,
        )
        assertEquals(Activity.RESULT_CANCELED, decision)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            permissionOf(source.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        )

        val continued = service.continuePreparedSave(consent.continuation, { true }, { true })
        assertTrue(
            "a denied consent must be refused fail-closed, got $continued",
            continued is MetadataSaveService.Outcome.Blocked,
        )
        assertEquals(
            "EXACT_GRANT_MISSING",
            (continued as MetadataSaveService.Outcome.Blocked).reason,
        )
        assertSourceUnchanged(source)
        assertTrackUnchanged(
            source = source,
            expectedRevision = seededRevision,
            expectedTitle = source.originalTitle,
            transactionId = consent.continuation.transactionId,
        )
        assertJournalDiscarded(consent.continuation.transactionId)
    }

    // ---------------------------------------------------------------- phase 4

    private suspend fun cancelRealSystemConsent() {
        val source = createOtherUidSource(
            label = "cancel",
            title = "ME15 Cancelado",
            candidateTitle = "ME15 Nunca Escrito",
        )
        val seededRevision = trackRevision(source.trackId)
        val consent = requestConsent(source)

        val decision = awaitConsentDecision(
            sender = consent.intent.intentSender,
            decision = ConsentDecision.DISMISS,
            recreateWhileDialogVisible = false,
        )
        assertEquals(Activity.RESULT_CANCELED, decision)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            permissionOf(source.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        )

        val continued = service.continuePreparedSave(consent.continuation, { true }, { true })
        assertTrue(
            "a cancelled consent must be refused fail-closed, got $continued",
            continued is MetadataSaveService.Outcome.Blocked,
        )
        assertEquals(
            "EXACT_GRANT_MISSING",
            (continued as MetadataSaveService.Outcome.Blocked).reason,
        )
        assertSourceUnchanged(source)
        assertTrackUnchanged(
            source = source,
            expectedRevision = seededRevision,
            expectedTitle = source.originalTitle,
            transactionId = consent.continuation.transactionId,
        )
        assertJournalDiscarded(consent.continuation.transactionId)
    }

    // ---------------------------------------------------------------- phase 5

    private suspend fun revokeRealSystemConsentGrant() {
        val source = createOtherUidSource(
            label = "revoke",
            title = "ME15 Revocado",
            candidateTitle = "ME15 Nunca Escrito",
        )
        val seededRevision = trackRevision(source.trackId)
        val consent = requestConsent(source)
        assertJournalPrepared(consent.continuation.transactionId)

        // The platform grant record belongs to the MediaProvider, so the only
        // honest "revoke" is the real grantor (the owner UID) removing the real
        // framework grant it granted. No test-side fabrication is involved.
        fixture.revokeRead(source.uri)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            permissionOf(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        assertTrue(
            "a revoked grant must make the source unreadable",
            runCatching { readBytes(source.uri) }.isFailure,
        )

        val continued = service.continuePreparedSave(consent.continuation, { true }, { true })
        assertTrue(
            "a revoked grant must be refused fail-closed, got $continued",
            continued is MetadataSaveService.Outcome.RecoveryRequired,
        )
        assertEquals(
            "ORIGINAL_CHANGED_KEEP_BACKUP",
            (continued as MetadataSaveService.Outcome.RecoveryRequired).reason,
        )
        assertOwnerDigest(source, expectedSha256 = source.originalSha256)
        assertTrackUnchanged(
            source = source,
            expectedRevision = seededRevision,
            expectedTitle = source.originalTitle,
            transactionId = consent.continuation.transactionId,
        )

        // Revalidation: the URI, the row and the bytes survived the refusal. Only
        // the framework grant was ever revoked, and re-granting it restores
        // access to the exact same bytes.
        fixture.grantRead(source.uri)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            permissionOf(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        assertArrayEquals(source.originalBytes, readBytes(source.uri))
        assertEquals(source.displayName, displayNameOf(source.uri))
        assertJournalDiscarded(consent.continuation.transactionId)
    }

    // ---------------------------------------------------------------- sourcing

    private data class OtherUidSource(
        val uri: Uri,
        val displayName: String,
        val trackId: String,
        val originalTitle: String,
        val candidateTitle: String,
        val originalBytes: ByteArray,
        val originalSha256: String,
        val review: MetadataReview.Ready,
    )

    /**
     * Creates a real MediaStore item through the fixture UID and seeds the local
     * index exactly like the production scan/cold-read path does.
     */
    private suspend fun createOtherUidSource(
        label: String,
        title: String,
        candidateTitle: String,
    ): OtherUidSource {
        val displayName = "me15-$runTag-$label.mp3"
        val bytes = id3(title, "Fixture Artist", "Fixture Album", "Rock", audioPayload)
        val created = fixture.create(bytes, displayName)
        val uri = Uri.parse(
            checkNotNull(created.getString(MetadataOtherUidFixtureClient.EXTRA_URI)) {
                "the fixture did not report the created uri"
            },
        )
        createdUris += uri
        val fixtureSha = checkNotNull(
            created.getString(MetadataOtherUidFixtureClient.EXTRA_SHA256),
        ) { "the fixture did not report the created digest" }
        assertEquals(
            bytes.size.toLong(),
            created.getLong(MetadataOtherUidFixtureClient.EXTRA_SIZE).toLong(),
        )
        assertEquals(sha(bytes), fixtureSha)

        // The target UID can read only because the owner really granted it, and
        // it can NOT write: that write grant is what the consent flow must add.
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            permissionOf(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            permissionOf(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        )
        assertArrayEquals(bytes, readBytes(uri))
        assertEquals(displayName, displayNameOf(uri))

        val mediaId = checkNotNull(uri.lastPathSegment).substringAfterLast('/')
        val trackId = "local_$mediaId"
        SyncedTrackRepository(trackDao = database.trackDao()).saveLibrary(
            listOf(
                TrackDto(
                    trackId,
                    title,
                    "Fixture Artist",
                    "Fixture Album",
                    size = bytes.size.toLong(),
                    format = "mp3",
                    genre = "Rock",
                ),
            ),
            sourceId = sourceId,
        )
        database.trackDao().markDownloadedVerified(
            sourceId = sourceId,
            id = trackId,
            filepath = uri.toString(),
            size = bytes.size.toLong(),
            sha256 = fixtureSha,
            verifiedAt = SEEDED_VERIFIED_AT,
            algorithm = "sha256",
        )
        val original = coldRead(uri = uri, trackId = trackId, bytes = bytes.size.toLong())
        val review = MetadataEditPreview.review(
            original,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf(candidateTitle))),
        )
        assertTrue(
            "the production review must accept the title change, got $review",
            review is MetadataReview.Ready,
        )
        return OtherUidSource(
            uri = uri,
            displayName = displayName,
            trackId = trackId,
            originalTitle = title,
            candidateTitle = candidateTitle,
            originalBytes = bytes,
            originalSha256 = fixtureSha,
            review = review as MetadataReview.Ready,
        )
    }

    private suspend fun requestConsent(
        source: OtherUidSource,
    ): MetadataSaveService.Outcome.ConsentRequired {
        val outcome = service.save(
            sourceId = sourceId,
            trackId = source.trackId,
            uriString = source.uri.toString(),
            expectedBytes = source.originalBytes.size.toLong(),
            reviewInput = source.review,
            ownerCurrent = { true },
            playbackClosed = { true },
        )
        assertTrue(
            "an other-UID source must request the real system consent, got $outcome",
            outcome is MetadataSaveService.Outcome.ConsentRequired,
        )
        return outcome as MetadataSaveService.Outcome.ConsentRequired
    }

    private suspend fun coldRead(
        source: OtherUidSource,
        observedSize: Long,
    ): MetadataSnapshot = coldRead(source.uri, source.trackId, observedSize)

    private suspend fun coldRead(
        uri: Uri,
        trackId: String,
        bytes: Long,
    ): MetadataSnapshot {
        val read = repository.loadEditorColdRead(
            readSourceId,
            trackId,
            uri.toString(),
            bytes,
        )
        return when (read) {
            is MetadataEditorColdRead.Verified -> read.snapshot
            is MetadataEditorColdRead.Partial -> read.snapshot
            else -> error("the fixture source must be cold readable but was $read")
        }
    }

    private suspend fun trackRevision(trackId: String): Long = checkNotNull(
        database.trackDao().getTrackBySource(sourceId, trackId),
    ) { "the seeded track row disappeared" }.metadataRevision

    // ---------------------------------------------------------------- consent UI

    private enum class ConsentDecision { ALLOW, DENY, DISMISS }

    /**
     * Drives the REAL system surface produced by `MediaStore.createWriteRequest`
     * through UI Automator. The caller activity is recreated while the surface is
     * visible so the result has to survive a real activity recreation.
     */
    private fun awaitConsentDecision(
        sender: IntentSender,
        decision: ConsentDecision,
        recreateWhileDialogVisible: Boolean,
    ): Int {
        val codes = AtomicInteger(NO_RESULT)
        val latch = CountDownLatch(1)
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                codes.set(resultCode)
                latch.countDown()
            }
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, MetadataConsentTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MetadataConsentTestActivity.EXTRA_INTENT_SENDER, sender)
                .putExtra(MetadataConsentTestActivity.EXTRA_RECEIVER, receiver),
        )
        assertNotNull(
            "the real system consent surface was never shown",
            awaitConsentAction(allow = true) ?: awaitConsentAction(allow = false),
        )
        assertNotEquals(
            "the consent surface must be a system surface, not the test application",
            context.packageName,
            device.currentPackageName,
        )
        if (recreateWhileDialogVisible) {
            instrumentation.runOnMainSync { activity.recreate() }
            assertNotNull(
                "the consent surface must survive caller activity recreation",
                awaitConsentAction(allow = true) ?: awaitConsentAction(allow = false),
            )
        }
        when (decision) {
            ConsentDecision.ALLOW -> clickConsentAction(allow = true)
            ConsentDecision.DENY -> clickConsentAction(allow = false)
            ConsentDecision.DISMISS -> {
                device.waitForIdle()
                device.pressBack()
                if (!latch.await(DISMISS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    // A surface that swallows BACK is still cancelled by the
                    // explicit negative action; the assertions stay exact.
                    clickConsentAction(allow = false)
                }
            }
        }
        assertTrue(
            "the consent surface never reported a decision",
            latch.await(DECISION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        return codes.get()
    }

    private fun clickConsentAction(allow: Boolean) {
        val action = checkNotNull(awaitConsentAction(allow)) {
            "the consent surface has no ${if (allow) "allow" else "deny"} action"
        }
        action.click()
        device.waitForIdle()
    }

    private fun awaitConsentAction(allow: Boolean): UiObject2? {
        val deadline = System.currentTimeMillis() + SURFACE_TIMEOUT_MS
        do {
            findConsentAction(allow)?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        } while (System.currentTimeMillis() < deadline)
        return findConsentAction(allow)
    }

    private fun findConsentAction(allow: Boolean): UiObject2? {
        for (id in if (allow) ALLOW_IDS else DENY_IDS) {
            device.findObjects(By.res(SYSTEM_PACKAGE, id))
                .firstOrNull { it.isEnabled }
                ?.let { return it }
        }
        for (label in if (allow) ALLOW_LABELS else DENY_LABELS) {
            device.findObjects(By.text(label))
                .firstOrNull { it.isEnabled }
                ?.let { return it }
        }
        val pattern = Pattern.compile(if (allow) ALLOW_PATTERN else DENY_PATTERN)
        return device.findObjects(By.text(pattern).clickable(true))
            .firstOrNull { it.isEnabled }
    }

    // ---------------------------------------------------------------- assertions

    private suspend fun assertJournalPrepared(transactionId: String) {
        val pending = coordinator.inspectPending()
        assertTrue(
            "the prepared consent transaction must be durable, saw $pending",
            pending is MetadataPreparationCoordinator.Pending.PreparedOrInterrupted,
        )
        assertEquals(
            transactionId,
            (pending as MetadataPreparationCoordinator.Pending.PreparedOrInterrupted)
                .transactionId,
        )
    }

    private suspend fun assertJournalDiscarded(transactionId: String) {
        assertTrue(
            "a private pre-mutation preparation must be discardable",
            coordinator.discardPrepared(transactionId, true),
        )
        assertEquals(
            MetadataPreparationCoordinator.Pending.Clear,
            coordinator.inspectPending(),
        )
    }

    private fun assertSourceUnchanged(source: OtherUidSource) {
        assertOwnerDigest(source, expectedSha256 = source.originalSha256)
        assertEquals(source.originalBytes.size.toLong(), sizeOf(source.uri))
    }

    private fun assertOwnerDigest(
        source: OtherUidSource,
        expectedSha256: String,
        expectedBytes: Long = source.originalBytes.size.toLong(),
    ) {
        val digest = fixture.digest(source.uri)
        assertEquals(
            expectedSha256,
            digest.getString(MetadataOtherUidFixtureClient.EXTRA_SHA256),
        )
        assertEquals(
            expectedBytes,
            digest.getLong(MetadataOtherUidFixtureClient.EXTRA_SIZE).toLong(),
        )
        assertEquals(
            source.displayName,
            digest.getString(MetadataOtherUidFixtureClient.EXTRA_DISPLAY_NAME),
        )
    }

    private suspend fun assertTrackUnchanged(
        source: OtherUidSource,
        expectedRevision: Long,
        expectedTitle: String,
        transactionId: String,
    ) {
        val row = checkNotNull(database.trackDao().getTrackBySource(sourceId, source.trackId)) {
            "the refused save must not delete the index row"
        }
        assertEquals(expectedTitle, row.title)
        assertEquals(source.originalBytes.size.toLong(), row.size.toLong())
        assertEquals(expectedRevision.toLong(), row.metadataRevision.toLong())
        assertEquals(source.originalSha256, row.verifiedSha256)
        assertEquals(SEEDED_VERIFIED_AT, row.verifiedAt.toLong())
        assertEquals(source.uri.toString(), row.filepath)
        assertNull(
            "a refused save must never record an applied metadata transaction",
            database.trackDao().getAppliedMetadataTransaction(transactionId),
        )
    }

    // ---------------------------------------------------------------- framework

    private fun permissionOf(uri: Uri, flag: Int): Int =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flag)

    private fun readBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("the source $uri is not readable")

    private fun displayNameOf(uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun sizeOf(uri: Uri): Long? = context.contentResolver
        .openFileDescriptor(uri, "r")
        ?.use { it.statSize }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun id3(
        title: String,
        artist: String,
        album: String,
        genre: String,
        audio: ByteArray,
    ): ByteArray {
        fun frame(id: String, value: String): ByteArray {
            val text = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
            val size = text.size
            return id.toByteArray(Charsets.US_ASCII) + byteArrayOf(
                ((size shr 24) and 0xFF).toByte(),
                ((size shr 16) and 0xFF).toByte(),
                ((size shr 8) and 0xFF).toByte(),
                (size and 0xFF).toByte(),
                0,
                0,
            ) + text
        }
        val frames = frame("TIT2", title) + frame("TPE1", artist) +
            frame("TALB", album) + frame("TCON", genre)
        val header = "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            3,
            0,
            0,
            ((frames.size shr 21) and 0x7F).toByte(),
            ((frames.size shr 14) and 0x7F).toByte(),
            ((frames.size shr 7) and 0x7F).toByte(),
            (frames.size and 0x7F).toByte(),
        )
        return header + frames + audio
    }

    private companion object {
        private const val NO_RESULT = Int.MIN_VALUE
        private const val DECISION_TIMEOUT_MS = 60_000L
        private const val DISMISS_TIMEOUT_MS = 15_000L
        private const val LATE_CONTINUATION_MS = 1_500L
        private const val POLL_INTERVAL_MS = 250L
        private const val SURFACE_TIMEOUT_MS = 30_000L
        private const val SEEDED_VERIFIED_AT = 1_700_000_000_000L
        private const val SYSTEM_PACKAGE = "android"

        // The consent surface is rendered by the platform/MediaProvider dialog:
        // both the platform dialog ids and the localised labels are accepted,
        // and the label patterns are anchored so a prompt sentence can never be
        // mistaken for a button.
        private val ALLOW_IDS = listOf("button1")
        private val DENY_IDS = listOf("button2")
        private val ALLOW_LABELS = listOf("Allow", "Permitir", "Allow access", "Aceptar")
        private val DENY_LABELS = listOf(
            "Don't allow",
            "Do not allow",
            "Deny",
            "No permitir",
            "Cancel",
            "Cancelar",
        )
        private const val ALLOW_PATTERN =
            "(?i)^(allow|permitir|permitir acceso|allow access|accept|aceptar)$"
        private const val DENY_PATTERN =
            "(?i)^(don'?t allow|do not allow|deny|no permitir|cancel|cancelar|reject|rechazar)$"
    }
}
