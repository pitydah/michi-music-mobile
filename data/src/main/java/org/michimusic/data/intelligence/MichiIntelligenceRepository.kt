package org.michimusic.data.intelligence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.core.intelligence.MichiGenreIntelligencePolicy
import org.michimusic.core.intelligence.MichiHomeCardPreferencePolicy
import org.michimusic.core.intelligence.MichiHomeIntelligencePolicy
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.intelligence.MichiOwnedPlayCountPolicy
import org.michimusic.core.intelligence.MichiSuggestionFeedbackPolicy
import org.michimusic.core.intelligence.MichiLibraryCandidateSelector
import org.michimusic.core.intelligence.MichiTasteFingerprint
import org.michimusic.data.cache.AppDao
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * MI-02R: strictly on-device, opt-in listening statistics.
 *
 * The private SQLite file and any journal live in [Context.getNoBackupFilesDir],
 * never in the Room database or regular shared preferences that Android may back up.
 * NO service, observer, periodic task, network access or audio hot-path work.
 * Call this only from a suspendable, non-audio owner; session authority MUST be verified
 * by that owner before creating [PlaybackEvidence].
 */
class MichiIntelligenceRepository(
    context: Context,
    private val appDao: AppDao,
    // Injectable only for deterministic failure tests. Production uses the Android helper.
    private val deletePrivateDatabase: (File) -> Boolean = { SQLiteDatabase.deleteDatabase(it) },
    // Deterministic test interleaving only; null in production. This runs while
    // the capture transaction is open, immediately before its final consent check.
    private val captureBeforeCommitHook: (() -> Unit)? = null,
    // Reproduces opt-out during explicit opt-in; absent from production.
    private val enableBeforeCommitHook: (() -> Unit)? = null,
    // Test-only interruption after durable PENDING but before final ON commit.
    private val enableBeforeActivationHook: (() -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.noBackupFilesDir, FILE_NAME)
    private val revocationMarker: File get() = File(appContext.noBackupFilesDir, REVOKED_FILE_NAME)

    /**
     * AtomicFile may leave a .new (interrupted write) or a legacy .bak (older
     * Android implementation / interrupted backup recovery). Both represent a
     * pending privacy revocation. Never let a restored SQLite enabled=true
     * override one of these siblings after a hard process death.
     * All paths are fixed under the app's non-backed-up private directory.
     */
    private fun hasDurableRevocation(): Boolean =
        revocationMarker.exists() ||
            File(revocationMarker.path + ".new").exists() ||
            File(revocationMarker.path + ".bak").exists()

    /** An interrupted unlink may leave a journal even without the SQLite base. */
    private fun hasPrivateStoreArtifacts(): Boolean =
        file.exists() || File(file.path + "-wal").exists() ||
            File(file.path + "-shm").exists() || File(file.path + "-journal").exists()

    private fun clearRevocationAfterFreshConsent() {
        if (!hasDurableRevocation()) return
        AtomicFile(revocationMarker).delete()
        if (hasDurableRevocation())
            throw IOException("Cannot remove all durable revocation files")
    }

    /**
     * A successful user opt-out acknowledgement starts with an fsynced, independent
     * marker. SQLite may still contain enabled=true when a process dies; the marker
     * denies EVERY future read/capture until an explicit, clean re-consent succeeds.
     * The marker is written only for user actions (not in any audio callback).
     */
    private fun persistRevocationMarker() {
        val dir = appContext.noBackupFilesDir
        if ((!dir.exists() && !dir.mkdirs()) || !dir.isDirectory)
            throw IOException("Michi Intelligence private directory is unavailable")
        // AtomicFile preserves the previous denial marker if this operation
        // is interrupted. finishWrite performs the file sync before replacing it.
        val atomic = AtomicFile(revocationMarker)
        val stream = atomic.startWrite()
        try {
            stream.write(1)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun consentAllows(epoch: Long): Boolean =
        MichiIntelligenceConsentBarrier.permits(epoch) && !hasDurableRevocation()


    companion object {
        internal const val FILE_NAME = "michi-intelligence-private-v1.sqlite"
        internal const val REVOKED_FILE_NAME = "michi-intelligence-revoked-v1.flag"
        private const val TABLE = "intelligence_entries"
        private const val HISTORY_SETTING = "listening_history_enabled"
        private const val ENABLED = "enabled"
        private const val ARTIST = "a."
        private const val GENRE = "g."
        private const val RECEIPT = "r."
        private const val OWNED_TRACK = "t."
        private const val DISMISSED = "f."
        private const val CARD_PREF = "c."
        const val MAX_PREFERENCES = 256
        const val MAX_RECENT_EVENTS = 128
        private const val MAX_TOKEN_LENGTH = 128
        private const val MAX_EPOCH_DAY = 999_999_999_999L
        // Process-wide mutual exclusion: UI and playback consumers must not race when
        // creating/removing the same file. Cross-process ownership remains NO-GO.
        private val mutex = Mutex()
        // Serializes synchronous user-action revocations with the final opt-in
        // marker deletion. A new opt-out cannot be accidentally undone by an
        // older enable completing after it.
        private val revocationLock = Any()
        // No active work, thread, polling or service while idle. This scope exists
        // solely so a USER-REQUESTED wipe survives Settings navigation disposal.
        private val userEraseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    enum class CaptureResult { STORED, DUPLICATE, DISABLED, IGNORED }
    private class ConsentRevokedDuringCommit : IllegalStateException()
    private class PlaybackOwnerLostDuringCommit : IllegalStateException()

    data class PlaybackEvidence(
        val eventId: String,
        val sourceId: String,
        val trackId: String,
        val artist: String,
        val genre: String = "",
        val observedEpochDay: Long,
        val observedAtMs: Long,
        val outcome: MichiIntelligencePolicy.PlaybackOutcome,
        val completionPermille: Int,
        val ownedLocalSession: Boolean,
        /** Capture epoch from the event PRODUCER, before launching its IO coroutine. */
        val consentEpoch: Long = 0L,
        /** Mandatory current-owner check, called also just before SQLite commit. */
        val ownerStillValid: () -> Boolean,
        val verifiedGenreValues: List<String> = emptyList(),
    )

    private fun ownerStillValid(evidence: PlaybackEvidence): Boolean =
        try { evidence.ownerStillValid() } catch (_: Exception) { false }

    data class AffinitySnapshot(
        val artist: Map<String, Int> = emptyMap(),
        val genre: Map<String, Int> = emptyMap(),
    )

    /** Only an explicit UI action may call this method. OFF is the default. */
    suspend fun enableFromExplicitConsent(): Boolean {
        // A user opt-out can happen while legacy cleanup or SQLite is running.
        // Freeze its generation BEFORE suspending or entering the mutex.
        val requestEpoch = MichiIntelligenceConsentBarrier.epoch()
        return withContext(Dispatchers.IO) {
          mutex.withLock {
            if (appDao.getSetting(HISTORY_SETTING) == "false") {
                MichiIntelligenceConsentBarrier.revoke()
                if (hasPrivateStoreArtifacts()) eraseLocked()
                return@withLock false
            }
            // Remove all legacy prototype MI keys in the backup-eligible Room DB.
            // If cleanup fails, never enable a second copy of the taste profile.
            appDao.eraseLegacyIntelligenceProfile()
            // MI-06: a prior process may have died after recording OFF but before
            // deleting its database. Never re-enable those stale affinity rows.
            // Never commit a consent request revoked while waiting on legacy IO.
            if (MichiIntelligenceConsentBarrier.epoch() != requestEpoch)
                return@withLock false
            try {
                // Two-phase enable: never leave an ON flag merely because the
                // process dies after a first SQLite write but before consent is
                // accepted. A surviving "pending" state is always interpreted OFF.
                open().use { db ->
                    transaction(db, beforeCommit = {
                        enableBeforeCommitHook?.invoke()
                        if (MichiIntelligenceConsentBarrier.epoch() != requestEpoch)
                            throw ConsentRevokedDuringCommit()
                    }) {
                        db.delete(TABLE, null, null)
                        put(db, ENABLED, "pending")
                    }
                }
            } catch (_: ConsentRevokedDuringCommit) {
                return@withLock false
            }
            // This CAS-like transition prevents an older asynchronous enable from
            // reactivating the fence AFTER an explicit user revocation.
            if (!MichiIntelligenceConsentBarrier.acceptIfUnchanged(requestEpoch)) {
                eraseLocked()
                return@withLock false
            }
            val acceptedEpoch = MichiIntelligenceConsentBarrier.epoch()
            try {
                enableBeforeActivationHook?.invoke()
                open().use { db ->
                    transaction(db, beforeCommit = {
                        if (!MichiIntelligenceConsentBarrier.permits(acceptedEpoch))
                            throw ConsentRevokedDuringCommit()
                    }) {
                        put(db, ENABLED, "true")
                    }
                }
                // Success is acknowledged ONLY once both SQLite and the marker
                // agree. A killed process before this point still sees the marker.
                val stillCurrent = synchronized(revocationLock) {
                    if (!MichiIntelligenceConsentBarrier.permits(acceptedEpoch)) false
                    else {
                        clearRevocationAfterFreshConsent()
                        true
                    }
                }
                if (!stillCurrent) throw ConsentRevokedDuringCommit()
            } catch (_: ConsentRevokedDuringCommit) {
                // A user revoke after PENDING but before ON cannot silently win.
                eraseLocked()
                return@withLock false
            } catch (failure: Throwable) {
                // The fence was already made permissive for phase two. Any
                // unexpected DB/IO failure or coroutine cancellation must
                // revoke it again and clean up the surviving PENDING file.
                MichiIntelligenceConsentBarrier.revoke()
                withContext(NonCancellable) { eraseLocked() }
                throw failure
            }
            true
          }
        }
    }

    /**
     * An explicit UI opt-out must start its disk cleanup BEFORE the screen-owned
     * coroutine is launched. Otherwise navigation/disposal may cancel it before
     * disableAndErase() is entered, leaving an ON file after process restart.
     * This launches one short, user-triggered IO job; no timer or worker exists.
     */
    fun requestEraseFromUserAction(): Deferred<Unit> {
        // Explicit privacy action, not the audio hot path: persist a tiny, fsynced
        // deny marker BEFORE returning to the UI, even if the app process is then
        // killed before the asynchronous database deletion starts.
        val markerFailure = synchronized(revocationLock) {
            MichiIntelligenceConsentBarrier.revoke()
            runCatching { persistRevocationMarker() }.exceptionOrNull()
        }
        return userEraseScope.async {
            disableAndErase()
            if (markerFailure != null)
                throw IOException("The immediate durable revocation marker could not be confirmed", markerFailure)
        }
    }

    /** Wipes ONLY MI private data and any old MI settings, not library/history/playlists. */
    suspend fun disableAndErase(): Unit {
        // The synchronous in-memory fence is immediate; the persistent marker
        // precedes the destructive SQLite transaction for other callers as well.
        MichiIntelligenceConsentBarrier.revoke()
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                synchronized(revocationLock) { persistRevocationMarker() }
                eraseLocked()
            }
        }
    }

    suspend fun isEnabled(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (hasDurableRevocation()) {
                // This may be a fresh process after SIGKILL before SQLite OFF.
                // The durable marker takes precedence over enabled=true on disk.
                MichiIntelligenceConsentBarrier.revoke()
                // A crash between AtomicFile .new/.bak and the canonical marker
                // must still leave a durable deny marker after this read erases
                // the store.
                if (!revocationMarker.exists()) {
                    synchronized(revocationLock) { persistRevocationMarker() }
                }
                eraseLocked()
                return@withLock false
            }
            if (!MichiIntelligenceConsentBarrier.permits(MichiIntelligenceConsentBarrier.epoch())) {
                // Also repair an interrupted purge when Settings asks for state.
                if (hasPrivateStoreArtifacts()) {
                    MichiIntelligenceConsentBarrier.revoke()
                    synchronized(revocationLock) { persistRevocationMarker() }
                    eraseLocked()
                }
                return@withLock false
            }
            if (appDao.getSetting(HISTORY_SETTING) == "false") {
                MichiIntelligenceConsentBarrier.revoke()
                if (hasPrivateStoreArtifacts()) {
                    synchronized(revocationLock) { persistRevocationMarker() }
                    eraseLocked()
                }
                return@withLock false
            }
            if (!file.exists()) {
                // No base file can authorize consent. A leftover journal may
                // still contain private data after an interrupted unlink.
                if (hasPrivateStoreArtifacts()) {
                    MichiIntelligenceConsentBarrier.revoke()
                    synchronized(revocationLock) { persistRevocationMarker() }
                    eraseLocked()
                }
                return@withLock false
            }
            val enabled = try {
                open().use { db -> read(db, ENABLED) == "true" }
            } catch (_: SQLiteException) {
                // MI-20: a malformed private SQLite file must never prevent the
                // user from turning Intelligence OFF or block profile deletion.
                // Persist the deny marker BEFORE any attempt to remove the file;
                // a process killed during cleanup must not restore stale consent.
                MichiIntelligenceConsentBarrier.revoke()
                synchronized(revocationLock) { persistRevocationMarker() }
                eraseLocked() // may throw if removal fails: do not claim success
                return@withLock false
            }
            // A killed process may leave an OFF tombstone and a SQLite file.
            // This only happens on a user-requested read, never via an idle worker.
            if (!enabled) {
                // A corrupt-but-openable store must leave the same durable
                // deny marker as any other fail-closed read.
                MichiIntelligenceConsentBarrier.revoke()
                synchronized(revocationLock) { persistRevocationMarker() }
                eraseLocked()
                return@withLock false
            }
            enabled
        }
    }

    /** Claims a bounded event receipt and updates the artist/genre counters atomically. */
    suspend fun capture(evidence: PlaybackEvidence): CaptureResult {
        if (!consentAllows(evidence.consentEpoch))
            return CaptureResult.DISABLED
        if (!evidence.ownedLocalSession || !ownerStillValid(evidence) ||
            evidence.eventId.isBlank() ||
            evidence.eventId.length > MAX_TOKEN_LENGTH || evidence.trackId.isBlank() ||
            evidence.observedEpochDay !in 0L..MAX_EPOCH_DAY ||
            evidence.observedAtMs <= 0L || evidence.completionPermille !in 0..1000
        ) return CaptureResult.IGNORED
        // Hash only exact composite identities; never persist a raw track ID/path.
        if (evidence.sourceId.length > 1024 || evidence.trackId.length > 1024)
            return CaptureResult.IGNORED
        val artist = normalize(evidence.artist)
        val genres = MichiGenreIntelligencePolicy.keys(evidence.genre, evidence.verifiedGenreValues)
        if (artist.isEmpty() && genres.isEmpty()) return CaptureResult.IGNORED
        if (MichiIntelligencePolicy.observe(null, evidence.outcome,
                evidence.completionPermille, evidence.observedEpochDay, true) == null
        ) return CaptureResult.IGNORED

        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(evidence.consentEpoch))
                    return@withLock CaptureResult.DISABLED
                if (!ownerStillValid(evidence)) return@withLock CaptureResult.IGNORED
                if (appDao.getSetting(HISTORY_SETTING) == "false") {
                    MichiIntelligenceConsentBarrier.revoke()
                    if (hasPrivateStoreArtifacts()) eraseLocked()
                    return@withLock CaptureResult.DISABLED
                }
                if (!file.exists()) return@withLock CaptureResult.DISABLED
                try {
                    open().use { db ->
                        transaction(db, beforeCommit = {
                            captureBeforeCommitHook?.invoke()
                            // A UI revocation can happen while the SQLite transaction
                            // is in progress. Throw to ROLLBACK, not commit receipt/taste.
                            if (!consentAllows(evidence.consentEpoch)) {
                                throw ConsentRevokedDuringCommit()
                            }
                            if (!ownerStillValid(evidence))
                                throw PlaybackOwnerLostDuringCommit()
                        }) {
                            if (read(db, ENABLED) != "true") return@transaction CaptureResult.DISABLED
                            val receiptKey = RECEIPT + sha256(evidence.eventId)
                            val receiptValue = evidence.observedAtMs.toString().padStart(19, '0')
                            if (!insertIfAbsent(db, receiptKey, receiptValue)) {
                                return@transaction CaptureResult.DUPLICATE
                            }
                            if (artist.isNotEmpty()) updateCounter(db, ARTIST + sha256(artist), evidence)
                            for (genre in genres) updateCounter(db, GENRE + sha256(genre), evidence)
                            if (evidence.outcome == MichiIntelligencePolicy.PlaybackOutcome.COMPLETED) {
                                val key = OWNED_TRACK + ownedTrackHash(evidence.sourceId, evidence.trackId)
                                val next = MichiOwnedPlayCountPolicy.addCompleted(
                                    read(db, key)?.let(MichiOwnedPlayCountPolicy::decode),
                                    evidence.observedEpochDay,
                                )
                                if (next != null) put(db, key, MichiOwnedPlayCountPolicy.encode(next))
                            }
                            prune(db, RECEIPT, MAX_RECENT_EVENTS)
                            pruneOwnedCounts(db)
                            pruneCounters(db)
                            CaptureResult.STORED
                        }
                    }
                } catch (_: ConsentRevokedDuringCommit) {
                    CaptureResult.DISABLED
                } catch (_: PlaybackOwnerLostDuringCommit) {
                    CaptureResult.IGNORED
                }
            }
        }
    }

    /**
     * MI-10: bounded, consent-fenced digest index for ranking the complete
     * ALREADY LOADED library snapshot. No raw listening history or artist names
     * leave the private repository; no additional MediaStore/SAF query.
     *
     * At most 256 artist/genre counters + 128 owner-bound completion counters.
     * The selector hashes metadata already in RAM and matches these digests.
     */
    suspend fun readSelectionSignals(nowEpochDay: Long): MichiLibraryCandidateSelector.Signals {
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        if (nowEpochDay !in 0L..MAX_EPOCH_DAY ||
            !consentAllows(epoch))
            return MichiLibraryCandidateSelector.Signals()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(epoch))
                    return@withLock MichiLibraryCandidateSelector.Signals()
                if (appDao.getSetting(HISTORY_SETTING) == "false") {
                    MichiIntelligenceConsentBarrier.revoke()
                    if (hasPrivateStoreArtifacts()) eraseLocked()
                    return@withLock MichiLibraryCandidateSelector.Signals()
                }
                if (!file.exists()) return@withLock MichiLibraryCandidateSelector.Signals()
                open().use { db ->
                    if (read(db, ENABLED) != "true")
                        return@use MichiLibraryCandidateSelector.Signals()
                    val artists = HashMap<String, Int>()
                    val genres = HashMap<String, Int>()
                    val owned = HashMap<String, MichiOwnedPlayCountPolicy.Count>()
                    db.rawQuery(
                        "SELECT k,v FROM $TABLE WHERE k GLOB 'a.*' OR k GLOB 'g.*' " +
                            "OR k GLOB 't.*' ORDER BY k LIMIT ?",
                        arrayOf((MAX_PREFERENCES + MichiOwnedPlayCountPolicy.MAX_TRACK_COUNTERS).toString()),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val k = cursor.getString(0)
                            val v = cursor.getString(1)
                            when {
                                k.startsWith(ARTIST) && k.length == 66 -> {
                                    val score = decodeCounter(v)?.let {
                                        MichiIntelligencePolicy.decayed(it, nowEpochDay)
                                    } ?: 0
                                    if (score != 0) artists[k.removePrefix(ARTIST)] = score
                                }
                                k.startsWith(GENRE) && k.length == 66 -> {
                                    val score = decodeCounter(v)?.let {
                                        MichiIntelligencePolicy.decayed(it, nowEpochDay)
                                    } ?: 0
                                    if (score != 0) genres[k.removePrefix(GENRE)] = score
                                }
                                k.startsWith(OWNED_TRACK) && k.length == 66 -> {
                                    val count = MichiOwnedPlayCountPolicy.decode(v)
                                    if (count != null) owned[k.removePrefix(OWNED_TRACK)] = count
                                }
                            }
                        }
                    }
                    if (!consentAllows(epoch))
                        MichiLibraryCandidateSelector.Signals()
                    else MichiLibraryCandidateSelector.Signals(artists, genres, owned)
                }
            }
        }
    }

    /** Bounded snapshot requested by Home; never launches a scan or starts a timer. */
    suspend fun readAffinities(
        artistKeys: Collection<String>,
        genreKeys: Collection<String>,
        nowEpochDay: Long,
        verifiedGenreKeys: Boolean = false,
    ): AffinitySnapshot {
        // Preserve the producer generation across an entire asynchronous read.
        // Sampling a fresh epoch later could authorize a pre-revocation snapshot.
        val readEpoch = MichiIntelligenceConsentBarrier.epoch()
        if (nowEpochDay !in 0L..MAX_EPOCH_DAY ||
            !consentAllows(readEpoch))
            return AffinitySnapshot()
        val artists = artistKeys.asSequence().map(::normalize).filter { it.isNotEmpty() }
            .distinct().take(MichiIntelligencePolicy.MAX_CANDIDATES).toList()
        val genres = genreKeys.asSequence()
            .flatMap {
                (if (verifiedGenreKeys) MichiGenreIntelligencePolicy.keys("", listOf(it))
                 else MichiGenreIntelligencePolicy.keys(it)).asSequence()
            }
            .distinct().take(MichiIntelligencePolicy.MAX_CANDIDATES).toList()
        if (artists.isEmpty() && genres.isEmpty()) return AffinitySnapshot()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(readEpoch))
                    return@withLock AffinitySnapshot()
                if (appDao.getSetting(HISTORY_SETTING) == "false") {
                    MichiIntelligenceConsentBarrier.revoke()
                    if (hasPrivateStoreArtifacts()) eraseLocked()
                    return@withLock AffinitySnapshot()
                }
                if (!file.exists()) return@withLock AffinitySnapshot()
                open().use { db ->
                    if (read(db, ENABLED) != "true") return@use AffinitySnapshot()
                    val rows = HashMap<String, String>()
                    db.rawQuery(
                        "SELECT k,v FROM $TABLE WHERE k GLOB 'a.*' OR k GLOB 'g.*' ORDER BY k LIMIT ?",
                        arrayOf(MAX_PREFERENCES.toString()),
                    ).use { cursor ->
                        while (cursor.moveToNext()) rows[cursor.getString(0)] = cursor.getString(1)
                    }
                    fun project(keys: List<String>, prefix: String): Map<String, Int> = buildMap {
                        for (key in keys) {
                            val value = rows[prefix + sha256(key)]?.let(::decodeCounter) ?: continue
                            val decayed = MichiIntelligencePolicy.decayed(value, nowEpochDay)
                            if (decayed != 0) put(key, decayed)
                        }
                    }
                    if (!consentAllows(readEpoch))
                        AffinitySnapshot()
                    else AffinitySnapshot(project(artists, ARTIST), project(genres, GENRE))
                }
            }
        }
    }

    /**
     * Reads only POSITIVE, exact-owner completed counts from the current private
     * profile. Missing keys are unknown (never treated as zero/never-played).
     * Max 128 requested keys and 128 stored rows, without a second library scan.
     */
    suspend fun readOwnedTrackEvidence(
        trackKeys: Collection<MichiIntelligencePolicy.TrackKey>,
    ): Map<MichiIntelligencePolicy.TrackKey, MichiOwnedPlayCountPolicy.Count> {
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        if (!consentAllows(epoch)) return emptyMap()
        val requested = trackKeys.asSequence()
            .filter { it.trackId.isNotBlank() && it.trackId.length <= 1024 &&
                it.sourceId.length <= 1024 }
            .distinct().take(MichiIntelligencePolicy.MAX_CANDIDATES).toList()
        if (requested.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(epoch))
                    return@withLock emptyMap()
                if (appDao.getSetting(HISTORY_SETTING) == "false") {
                    MichiIntelligenceConsentBarrier.revoke()
                    if (hasPrivateStoreArtifacts()) eraseLocked()
                    return@withLock emptyMap()
                }
                if (!file.exists()) return@withLock emptyMap()
                open().use { db ->
                    if (read(db, ENABLED) != "true") return@use emptyMap()
                    val rows = HashMap<String, MichiOwnedPlayCountPolicy.Count>()
                    db.rawQuery(
                        "SELECT k,v FROM $TABLE WHERE k GLOB 't.*' ORDER BY k LIMIT ?",
                        arrayOf(MichiOwnedPlayCountPolicy.MAX_TRACK_COUNTERS.toString()),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val record = MichiOwnedPlayCountPolicy.decode(cursor.getString(1))
                            if (record != null) rows[cursor.getString(0)] = record
                        }
                    }
                    if (!consentAllows(epoch)) emptyMap()
                    else buildMap {
                        for (key in requested) {
                            val count = rows[OWNED_TRACK + ownedTrackHash(key.sourceId, key.trackId)]
                            if (count != null) put(key, count)
                        }
                    }
                }
            }
        }
    }

    /** Compatibility API for callers that only need verified completed totals. */
    suspend fun readOwnedTrackCounts(
        trackKeys: Collection<MichiIntelligencePolicy.TrackKey>,
    ): Map<MichiIntelligencePolicy.TrackKey, Int> =
        readOwnedTrackEvidence(trackKeys).mapValues { it.value.completed }

    private fun ownedTrackHash(sourceId: String, trackId: String): String =
        MichiTasteFingerprint.ownedTrack(sourceId, trackId)

    /** User-initiated, 30-day soft suppression of ONE verified local suggestion. */
    suspend fun dismissSuggestion(
        trackKey: MichiIntelligencePolicy.TrackKey,
        nowEpochDay: Long,
        consentEpoch: Long,
    ): Boolean {
        val day = MichiSuggestionFeedbackPolicy.normalizedDay(nowEpochDay) ?: return false
        if (trackKey.trackId.isBlank() || trackKey.trackId.length > 1024 ||
            trackKey.sourceId.length > 1024 ||
            !consentAllows(consentEpoch)) return false
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(consentEpoch) ||
                    appDao.getSetting(HISTORY_SETTING) == "false" || !file.exists())
                    return@withLock false
                try {
                    open().use { db ->
                        transaction(db, beforeCommit = {
                            if (!consentAllows(consentEpoch))
                                throw ConsentRevokedDuringCommit()
                        }) {
                            if (read(db, ENABLED) != "true") return@transaction false
                            put(db, DISMISSED + ownedTrackHash(trackKey.sourceId, trackKey.trackId), day)
                            prune(db, DISMISSED, MichiSuggestionFeedbackPolicy.MAX_DISMISSED)
                            true
                        }
                    }
                } catch (_: ConsentRevokedDuringCommit) { false }
            }
        }
    }

    /** Explicit undo of ONE recent recommendation dismissal; never trains music taste. */
    suspend fun undoDismissedSuggestion(
        trackKey: MichiIntelligencePolicy.TrackKey,
        consentEpoch: Long,
    ): Boolean {
        if (trackKey.trackId.isBlank() || trackKey.trackId.length > 1024 ||
            trackKey.sourceId.length > 1024 || !consentAllows(consentEpoch)) return false
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(consentEpoch) ||
                    appDao.getSetting(HISTORY_SETTING) == "false" || !file.exists())
                    return@withLock false
                try {
                    open().use { db ->
                        transaction(db, beforeCommit = {
                            if (!consentAllows(consentEpoch)) throw ConsentRevokedDuringCommit()
                        }) {
                            if (read(db, ENABLED) != "true") return@transaction false
                            db.delete(TABLE, "k=?",
                                arrayOf(DISMISSED + ownedTrackHash(trackKey.sourceId, trackKey.trackId)))
                            true
                        }
                    }
                } catch (_: ConsentRevokedDuringCommit) { false }
            }
        }
    }

    /** Returns only time-bounded, user-selected exclusions; no zero guesses. */
    suspend fun readDismissedKeys(
        trackKeys: Collection<MichiIntelligencePolicy.TrackKey>,
        todayEpochDay: Long,
    ): Set<MichiIntelligencePolicy.TrackKey> {
        if (todayEpochDay !in 0L..MAX_EPOCH_DAY) return emptySet()
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        if (!consentAllows(epoch)) return emptySet()
        val requested = trackKeys.asSequence().filter {
            it.trackId.isNotBlank() && it.trackId.length <= 1024 && it.sourceId.length <= 1024
        }.distinct().take(MichiIntelligencePolicy.MAX_CANDIDATES).toList()
        if (requested.isEmpty()) return emptySet()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(epoch) ||
                    appDao.getSetting(HISTORY_SETTING) == "false" || !file.exists())
                    return@withLock emptySet()
                open().use { db ->
                    if (read(db, ENABLED) != "true") return@use emptySet()
                    val rows = HashMap<String, Long>()
                    db.rawQuery(
                        "SELECT k,v FROM $TABLE WHERE k GLOB 'f.*' ORDER BY k LIMIT ?",
                        arrayOf(MichiSuggestionFeedbackPolicy.MAX_DISMISSED.toString()),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            cursor.getString(1).toLongOrNull()?.let { rows[cursor.getString(0)] = it }
                        }
                    }
                    if (!consentAllows(epoch)) emptySet()
                    else requested.filterTo(HashSet()) { key ->
                        MichiSuggestionFeedbackPolicy.suppressed(
                            rows[DISMISSED + ownedTrackHash(key.sourceId, key.trackId)], todayEpochDay)
                    }
                }
            }
        }
    }

    /** Five fixed per-card UI preferences; never reads a new library or creates a file OFF. */
    suspend fun readCardPreferences(): Map<MichiHomeIntelligencePolicy.CardKind,
        MichiHomeCardPreferencePolicy.Choice> {
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        if (!consentAllows(epoch)) return emptyMap()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(epoch) ||
                    appDao.getSetting(HISTORY_SETTING) == "false" || !file.exists())
                    return@withLock emptyMap()
                open().use { db ->
                    if (read(db, ENABLED) != "true") return@use emptyMap()
                    val result = LinkedHashMap<MichiHomeIntelligencePolicy.CardKind,
                        MichiHomeCardPreferencePolicy.Choice>()
                    // Exact five allowlisted keys, not a LIMIT over arbitrary
                    // c.* rows that a corrupt/old profile could sort ahead of
                    // real pins and hidden cards.
                    val cardKeys = MichiHomeIntelligencePolicy.CardKind.entries
                        .map { CARD_PREF + it.name }
                    val placeholders = cardKeys.joinToString(",") { "?" }
                    db.rawQuery(
                        "SELECT k,v FROM $TABLE WHERE k IN ($placeholders) ORDER BY k",
                        cardKeys.toTypedArray(),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0).removePrefix(CARD_PREF)
                            val kind = MichiHomeIntelligencePolicy.CardKind.entries
                                .firstOrNull { it.name == name } ?: continue
                            val choice = MichiHomeCardPreferencePolicy.decode(cursor.getString(1))
                            if (choice != MichiHomeCardPreferencePolicy.Choice.DEFAULT)
                                result[kind] = choice
                        }
                    }
                    if (consentAllows(epoch)) result else emptyMap()
                }
            }
        }
    }

    /** User-owned explicit pin/hide/unpin. Refuse stale UI generations. */
    suspend fun setCardPreference(
        kind: MichiHomeIntelligencePolicy.CardKind,
        choice: MichiHomeCardPreferencePolicy.Choice,
        consentEpoch: Long,
    ): Boolean {
        if (!consentAllows(consentEpoch)) return false
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(consentEpoch) ||
                    appDao.getSetting(HISTORY_SETTING) == "false" || !file.exists())
                    return@withLock false
                try {
                    open().use { db ->
                        transaction(db, beforeCommit = {
                            if (!consentAllows(consentEpoch))
                                throw ConsentRevokedDuringCommit()
                        }) {
                            if (read(db, ENABLED) != "true") return@transaction false
                            val key = CARD_PREF + kind.name
                            val encoded = MichiHomeCardPreferencePolicy.encode(choice)
                            if (encoded == null) db.delete(TABLE, "k=?", arrayOf(key))
                            else put(db, key, encoded)
                            true
                        }
                    }
                } catch (_: ConsentRevokedDuringCommit) { false }
            }
        }
    }

    /** Explicit recovery action in Home: restore hidden cards, keep existing pins. */
    suspend fun restoreHiddenCards(consentEpoch: Long): Boolean {
        if (!consentAllows(consentEpoch)) return false
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!consentAllows(consentEpoch) ||
                    appDao.getSetting(HISTORY_SETTING) == "false" || !file.exists())
                    return@withLock false
                try {
                    open().use { db ->
                        transaction(db, beforeCommit = {
                            if (!consentAllows(consentEpoch))
                                throw ConsentRevokedDuringCommit()
                        }) {
                            if (read(db, ENABLED) != "true") return@transaction false
                            db.delete(TABLE, "k GLOB 'c.*' AND v=?", arrayOf("h"))
                            true
                        }
                    }
                } catch (_: ConsentRevokedDuringCommit) { false }
            }
        }
    }

    /**
     * MI-06: crash-recoverable two-phase revocation under the same process mutex.
     * First make the file non-authorizing (enabled=false) AND remove learned rows
     * atomically; only then unlink the SQLite file and its journal companions.
     * If deletion fails, a later read remains OFF and a new opt-in starts empty.
     * The tiny interval before the first durable SQLite transaction is not
     * guaranteed against a hard process kill; Settings must never claim a
     * successful disk purge until this method has returned successfully.
     */
    private suspend fun eraseLocked() {
        // Never mistake a missing base file for completed deletion if an Android
        // journal/WAL companion survived a crash or interrupted file operation.
        val companions = listOf(
            File(file.path + "-journal"), File(file.path + "-wal"), File(file.path + "-shm"),
        )
        if (file.exists() || companions.any(File::exists)) {
            if (file.exists()) {
                try {
                    open().use { db ->
                        transaction(db) {
                            db.delete(TABLE, null, null)
                            put(db, ENABLED, "false")
                        }
                    }
                } catch (_: SQLiteException) {
                    // MI-20: corrupt SQLite cannot run a clearing transaction.
                    // The durable opt-out marker and process fence deny reads;
                    // delete ONLY the fixed noBackupFilesDir database below.
                    // On delete failure, throw without acknowledging the wipe.
                }
            }
            // Android's helper removes the DB, journal, WAL and SHM together.
            deletePrivateDatabase(file)
            if (file.exists() || companions.any(File::exists))
                throw IOException("Cannot erase Michi Intelligence private profile or journal")
        }
        appDao.eraseLegacyIntelligenceProfile()
    }

    private fun open(): SQLiteDatabase {
        val dir = appContext.noBackupFilesDir
        if ((!dir.exists() && !dir.mkdirs()) || !dir.isDirectory) {
            throw IOException("No private non-backed-up directory for Michi Intelligence")
        }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE (k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL)")
            return db
        } catch (failure: Exception) {
            db.close()
            throw failure
        }
    }

    private inline fun <T> transaction(
        db: SQLiteDatabase,
        beforeCommit: () -> Unit = {},
        block: () -> T,
    ): T {
        db.beginTransaction()
        try {
            val result = block()
            beforeCommit() // throw -> endTransaction() rolls back on Android SQLite
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun read(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT v FROM $TABLE WHERE k=? LIMIT 1", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun put(db: SQLiteDatabase, key: String, value: String) {
        val row = ContentValues(2).apply { put("k", key); put("v", value) }
        db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertIfAbsent(db: SQLiteDatabase, key: String, value: String): Boolean {
        val row = ContentValues(2).apply { put("k", key); put("v", value) }
        return db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_IGNORE) >= 0L
    }

    private fun prune(db: SQLiteDatabase, prefix: String, keep: Int) {
        db.execSQL(
            "DELETE FROM $TABLE WHERE k IN (SELECT k FROM $TABLE " +
                "WHERE k GLOB ? ORDER BY v DESC, k ASC LIMIT -1 OFFSET ?)",
            arrayOf<Any>("$prefix*", keep),
        )
    }

    /** Recency by rowid: INSERT OR REPLACE refreshes the owned counter's row. */
    private fun pruneOwnedCounts(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABLE WHERE k IN (SELECT k FROM $TABLE " +
                "WHERE k GLOB 't.*' ORDER BY rowid DESC LIMIT -1 OFFSET ?)",
            arrayOf(MichiOwnedPlayCountPolicy.MAX_TRACK_COUNTERS),
        )
    }

    private fun pruneCounters(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABLE WHERE k IN (SELECT k FROM $TABLE " +
                "WHERE k GLOB 'a.*' OR k GLOB 'g.*' " +
                "ORDER BY v DESC, k ASC LIMIT -1 OFFSET ?)",
            arrayOf(MAX_PREFERENCES),
        )
    }

    private fun updateCounter(db: SQLiteDatabase, key: String, evidence: PlaybackEvidence) {
        val previous = read(db, key)?.let(::decodeCounter)
        val next = MichiIntelligencePolicy.observe(
            previous, evidence.outcome, evidence.completionPermille,
            evidence.observedEpochDay, true,
        ) ?: return
        put(db, key, next.lastObservedEpochDay.toString().padStart(12, '0') + "|" + next.value)
    }

    private fun normalize(raw: String): String = MichiTasteFingerprint.normalizeArtist(raw)

    private fun decodeCounter(raw: String): MichiIntelligencePolicy.PreferenceCounter? {
        val separator = raw.indexOf('|')
        if (separator != 12 || raw.length > 20) return null
        val day = raw.substring(0, separator).toLongOrNull() ?: return null
        val value = raw.substring(separator + 1).toIntOrNull() ?: return null
        if (day !in 0L..MAX_EPOCH_DAY || value !in -32..255) return null
        return MichiIntelligencePolicy.PreferenceCounter(value, day)
    }

    private fun sha256(value: String): String = MichiTasteFingerprint.sha256(value)
}
