package org.michimusic.mobile.artist.artwork

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.michimusic.data.artist.ArtistArtworkAutoOutboxRecord
import org.michimusic.data.artist.ArtistArtworkCatalogBaselineRecord
import org.michimusic.data.artist.ArtistArtworkCatalogTrackRecord
import org.michimusic.data.artist.ArtistTrackEvidenceRecord
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.LocalCatalogListener
import org.michimusic.data.repository.LocalCatalogObservation
import org.michimusic.data.repository.LocalStructuredTagEvidence
import org.michimusic.data.repository.LocalStructuredTagEvidencePolicy
import org.michimusic.data.repository.MusicRootPreferences
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence

/**
 * Reuses the existing media query. Exact outbox rows are staged in short, idempotent
 * Room transactions before the catalog checkpoint. A crash may replay a page, never
 * lose an association. Provider work is outside Room and the library cache mutex.
 */
class ArtistArtworkCatalogProcessor(context: Context) : LocalCatalogListener {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val scheduler = ArtistArtworkAutoScheduler(app)

    override fun isEnabled(): Boolean = ArtistArtworkAutoScheduler.isEnabled(app) &&
        !ArtistArtworkAutoScheduler.isPaused(app)

    override suspend fun onCatalogRead(observation: LocalCatalogObservation) = withContext(Dispatchers.IO) {
        if (!isEnabled() || observation.sourceId.isBlank()) return@withContext
        val enteredRoot = MusicRootPreferences(app).currentRootStamp()
        val activeSource = enteredRoot.sourceId
        // Staged SAF indexes and stale MediaStore reads cannot claim the active root.
        if (activeSource != observation.sourceId) return@withContext
        if (observation.rows.size > MAX_ROWS * 3 || observation.rows.any { it.trackId.isBlank() }) return@withContext
        // Each track may legitimately contribute album artist and track credit.
        if (observation.rows.asSequence().map { it.trackId }.distinct().take(MAX_ROWS + 1).count() > MAX_ROWS) {
            return@withContext // oversized catalogs need an explicit, measured policy
        }
        val libraryId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
        val db = ArtistEnrichmentPersistence.database(app)
        val journal = db.artistArtworkCatalogJournalDao()
        val identities = db.artistEnrichmentDao()
        val now = System.currentTimeMillis()
        mutex.withLock {
            if (MusicRootPreferences(app).currentRootStamp() != enteredRoot) return@withLock
            // These MBIDs were extracted opportunistically by the existing tag read.
            // Observation is NOT a verified artist, a binding or authority to fetch.
            // Distinct AUTO_* fields never overwrite the user's explicit tag evidence.
            persistObservedTags(observation.sourceId, observation.rows.asSequence()
                .filter { it.structuredTagsRead }
                .map { LocalStructuredTagEvidence(it.trackId,
                    it.observedArtistMbids, it.observedAlbumArtistMbids,
                    it.observedRecordingMbids, it.observedReleaseMbids) }.toList(),
                requireActiveSource = true, enteredRoot = enteredRoot)
            // CPU/hash aggregation happens on Dispatchers.IO, OUTSIDE the Room write
            // transaction: no catalog-sized crypto work can hold the DB write lock.
            val baseline = journal.baseline(libraryId)
            val changedSource = baseline == null || baseline.sourceId != observation.sourceId
            val current = ArtistArtworkCatalogDigest.plan(
                observation.sourceId, observation.rows, emptySet(),
            )
            if (changedSource || current.digest != baseline?.snapshotDigest) {
                val oldHashes = if (changedSource) emptySet() else journal.hashes(libraryId).toSet()
                val plan = if (changedSource) current else ArtistArtworkCatalogDigest.plan(
                    observation.sourceId, observation.rows, oldHashes,
                )
                // Instead of dropping all changes when >1024 credits are observed,
                // match exact confirmed album-credit rows in bounded 256-album pages.
                // This avoids N Room queries per new track and NEVER binds by name.
                // Pages are persisted before the catalog checkpoint. After a crash,
                // INSERT IGNORE makes a replay idempotent and preserves cooldowns.
                if (changedSource) {
                    // A -> B -> A: restore only this source's previously authorized
                    // outbox evidence, if the exact album-credit is in the fresh
                    // CURRENT snapshot and still bound in Room. Never add a new job
                    // for every album on a first-ever baseline.
                    val observedPairs = observation.rows.asSequence().mapNotNull { row ->
                        ArtistArtworkAutoWorkKey.creditKey(row.creditedArtist)?.let { row.albumKey to it }
                    }.toSet()
                    for (albumPage in observedPairs.asSequence().map { it.first }.distinct()
                        .chunked(MAX_ASSOCIATION_PROPOSALS)) {
                        val dormant = journal.returnedSourceBoundRowsForAlbums(
                            libraryId, observation.sourceId, albumPage,
                        ).filter { it.albumKey to it.creditKey in observedPairs }
                        if (dormant.isNotEmpty()) {
                            db.withTransaction {
                                if (!isEnabled() || MusicRootPreferences(app).currentRootStamp() != enteredRoot ||
                                    journal.baseline(libraryId) != baseline
                                ) throw CancellationException("Raíz cambiada durante retorno a fuente previa")
                                dormant.forEach { event ->
                                    if (journal.countVerifiedSourceBinding(libraryId,
                                            observation.sourceId, event.albumKey, event.creditKey,
                                            event.associationId) < 1) return@forEach
                                    journal.resumeReturnedSourceBoundRow(
                                        libraryId, event.creditKey, event.associationId,
                                        event.albumKey, observation.sourceId, now,
                                    )
                                }
                            }
                        }
                        yield()
                    }
                } else {
                    val proposals = plan.added.asSequence().mapNotNull { row ->
                        val credit = ArtistArtworkAutoWorkKey.creditKey(row.creditedArtist)
                            ?: return@mapNotNull null
                        row.albumKey to credit
                    }.toSet()
                    for (albums in proposals.asSequence().map { it.first }.distinct()
                        .chunked(MAX_ASSOCIATION_PROPOSALS)) {
                        val bound = journal.confirmedCreditsForAlbums(libraryId, albums)
                            .filter { it.albumKey to it.creditKey in proposals }
                        if (bound.isNotEmpty()) {
                            db.withTransaction {
                                if (!isEnabled() || MusicRootPreferences(app).currentRootStamp() != enteredRoot ||
                                    journal.baseline(libraryId) != baseline
                                ) throw CancellationException("El origen cambió durante la actualización de créditos")
                                bound.forEach { binding ->
                                    if (journal.countVerifiedSourceBinding(libraryId,
                                            observation.sourceId, binding.albumKey, binding.creditKey,
                                            binding.associationId) < 1) return@forEach
                                    val inserted = journal.insertOutboxIfAbsent(ArtistArtworkAutoOutboxRecord(
                                        libraryId = libraryId,
                                        creditKey = binding.creditKey,
                                        associationId = binding.associationId,
                                        albumKey = binding.albumKey,
                                        sourceId = observation.sourceId,
                                        updatedAtMs = now,
                                    ))
                                    if (inserted < 0L) {
                                        journal.reviveExactContextOnCatalogDelta(
                                            libraryId, binding.creditKey, binding.associationId,
                                            binding.albumKey, observation.sourceId, now,
                                        )
                                    }
                                }
                            }
                        }
                        yield() // Never monopolize the IO dispatcher or Room write lock.
                    }
                }
                db.withTransaction {
                    // Do not checkpoint a partial or stale catalog. A committed outbox
                    // prefix may be replayed; no bound artist is forgotten.
                    if (!isEnabled() || MusicRootPreferences(app).currentRootStamp() != enteredRoot ||
                        journal.baseline(libraryId) != baseline) return@withTransaction

                // This baseline is an observation only, never identity/membership authority.
                // The first full scan is baseline-only, never implicit whole-library backfill.
                // Write only changes. A single added album must not delete and
                // recreate a 50k-row baseline (except when the active root switches).
                if (changedSource) {
                    journal.clearHashes(libraryId)
                    plan.allTrackHashes.asSequence().chunked(HASH_BATCH).forEach { batch ->
                        journal.insertHashes(batch.map { ArtistArtworkCatalogTrackRecord(libraryId, it) })
                    }
                } else {
                    (oldHashes - plan.allTrackHashes).asSequence().chunked(HASH_BATCH).forEach { batch ->
                        journal.deleteHashes(libraryId, batch)
                    }
                    (plan.allTrackHashes - oldHashes).asSequence().chunked(HASH_BATCH).forEach { batch ->
                        journal.insertHashes(batch.map { ArtistArtworkCatalogTrackRecord(libraryId, it) })
                    }
                }
                // Exact observed pairs are metadata from the ALREADY COMPLETED scan.
                // No root ownership or artist identity is inferred from this table.
                // Rebuild on a changed digest, never on UI recomposition/cache hits.
                journal.clearSourceCatalogCredits(libraryId, observation.sourceId)
                val observedCredits = observation.rows.asSequence().mapNotNull { row ->
                    val creditKey = ArtistArtworkAutoWorkKey.creditKey(row.creditedArtist)
                    if (row.albumKey.isBlank() || creditKey == null) null
                    else org.michimusic.data.artist.ArtistArtworkSourceCatalogCreditRecord(
                        libraryId, observation.sourceId, row.albumKey, creditKey, now)
                }.distinctBy { it.albumKey to it.creditKey }
                observedCredits.chunked(HASH_BATCH).forEach { page ->
                    journal.insertSourceCatalogCredits(page)
                }
                journal.putBaseline(ArtistArtworkCatalogBaselineRecord(
                    libraryId, observation.sourceId, plan.digest, now,
                ))
                journal.invalidateOtherSources(libraryId, observation.sourceId, now)
                journal.pruneCompleted(libraryId, now - COMPLETED_RETENTION_MS)
                }
            }
        }
        // The outbox survives scheduler errors/process death. No periodic retry is started.
        if (MusicRootPreferences(app).currentRootStamp() != enteredRoot) return@withContext
        try { scheduler.dispatchPending(requiredRootStamp = enteredRoot) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* next permitted catalog event or explicit retry replays outbox */ }
    }

    override suspend fun onStructuredTagsIndexed(
        sourceId: String, tags: List<LocalStructuredTagEvidence>,
    ) = withContext(Dispatchers.IO) {
        if (!isEnabled() || tags.isEmpty()) return@withContext
        // A user-authorized SAF index can commit before the new tree becomes the
        // active root; its source-stamped observations may still be stored locally.
        mutex.withLock { persistObservedTags(sourceId, tags, requireActiveSource = false) }
    }

    private suspend fun persistObservedTags(
        sourceId: String, tags: List<LocalStructuredTagEvidence>, requireActiveSource: Boolean,
        enteredRoot: org.michimusic.data.repository.MusicRootStamp? = null,
    ) {
        val libraryId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
        val dao = ArtistEnrichmentPersistence.database(app).artistEnrichmentDao()
        if (sourceId.isBlank() || sourceId.length > 160) return
        val byTrack = tags.asSequence()
            // A malformed or forged observation is discarded, never copied to Room.
            .mapNotNull(LocalStructuredTagEvidencePolicy::normalize)
            .groupBy { it.trackId }
        for (group in byTrack.entries.chunked(64)) {
            if (!isEnabled() || (requireActiveSource &&
                (enteredRoot == null || MusicRootPreferences(app).currentRootStamp() != enteredRoot))) return
            val timestamp = System.currentTimeMillis()
            val evidence = group.flatMap { (trackId, rows) ->
                // On reread, the new authoritative observation replaces all prior
                // AUTO ordinals for this track. Manual evidence is never touched.
                val art = rows.flatMap { it.artistMbids }.distinct().take(8)
                val album = rows.flatMap { it.albumArtistMbids }.distinct().take(8)
                val recording = rows.flatMap { it.recordingMbids }.distinct().take(8)
                val release = rows.flatMap { it.releaseMbids }.distinct().take(8)
                (art.mapIndexed { ordinal, value ->
                    ArtistTrackEvidenceRecord(libraryId, trackId, "AUTO_ARTIST_MBID", ordinal,
                        value, timestamp, "EXISTING_TAG_READ:$sourceId")
                } + album.mapIndexed { ordinal, value ->
                    ArtistTrackEvidenceRecord(libraryId, trackId, "AUTO_ALBUM_ARTIST_MBID", ordinal,
                        value, timestamp, "EXISTING_TAG_READ:$sourceId")
                } + recording.mapIndexed { ordinal, value ->
                    ArtistTrackEvidenceRecord(libraryId, trackId, "AUTO_RECORDING_MBID", ordinal,
                        value, timestamp, "EXISTING_TAG_READ:$sourceId")
                } + release.mapIndexed { ordinal, value ->
                    ArtistTrackEvidenceRecord(libraryId, trackId, "AUTO_RELEASE_MBID", ordinal,
                        value, timestamp, "EXISTING_TAG_READ:$sourceId")
                })
            }
            // Room transaction: DELETE old AUTO_* + INSERT current AUTO_* is atomic.
            // Zero IDs (valid successful read) clears stale IDs without touching
            // the manual per-track evidence or another track/library.
            dao.replaceObservedArtistMbidsForBatch(
                libraryId = libraryId, trackIds = group.map { it.key }, rows = evidence,
            )
            yield()
        }
    }

    private fun activeSourceId(): String = MusicRootPreferences(app).currentRoot().let { root ->
        if (root.isDefault) DEFAULT_SOURCE else root.sourceId.orEmpty()
    }

    companion object {
        const val DEFAULT_SOURCE = "device_music_root"
        private const val HASH_BATCH = 400
        private const val MAX_ROWS = 100_000
        private const val MAX_ASSOCIATION_PROPOSALS = 256
        private const val COMPLETED_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
