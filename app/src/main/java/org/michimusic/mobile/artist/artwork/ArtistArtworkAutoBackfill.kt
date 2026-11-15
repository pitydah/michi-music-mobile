package org.michimusic.mobile.artist.artwork

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.data.artist.ArtistArtworkAutoOutboxRecord
import org.michimusic.data.artist.ArtistAlbumCreditBindingRecord
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.MusicRootPreferences
import org.michimusic.data.repository.MusicRootStamp
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence

/** User-initiated, bounded existing-association recovery. No MediaStore/SAF/audio query. */
internal class ArtistArtworkAutoBackfill(context: Context) {
    private val app = context.applicationContext
    private val scheduler = ArtistArtworkAutoScheduler(app)

    data class BatchResult(val inspected: Int, val queuedAssociations: Int, val exhausted: Boolean)

    /** A single explicit tap can review up to 256 existing album credits / 48 artists.
     * If the user leaves Settings, the coroutine is cancelled; the Room outbox and
     * cursor from already completed pages remain durable. No new media scan.
     */
    suspend fun reviewBoundedSession(): ArtistArtworkBackfillSession.Report = withContext(Dispatchers.IO) {
        val enteredRoot = MusicRootPreferences(app).currentRootStamp()
        require(enteredRoot.sourceId.isNotBlank()) { "No existe una raíz musical activa" }
        ArtistArtworkBackfillSession.run(
            authorized = {
                ArtistArtworkAutoScheduler.isEnabled(app) &&
                    !ArtistArtworkAutoScheduler.isPaused(app) &&
                    MusicRootPreferences(app).currentRootStamp() == enteredRoot
            },
            next = {
                val page = nextBatch(requiredRootStamp = enteredRoot)
                ArtistArtworkBackfillSession.Page(page.inspected, page.queuedAssociations, page.exhausted)
            },
        )
    }

    suspend fun nextBatch(requiredRootStamp: MusicRootStamp? = null): BatchResult = withContext(Dispatchers.IO) {
        check(ArtistArtworkAutoScheduler.isEnabled(app) && !ArtistArtworkAutoScheduler.isPaused(app)) {
            "La búsqueda automática no está autorizada"
        }
        val libraryId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
        val enteredRoot = MusicRootPreferences(app).currentRootStamp()
        if (requiredRootStamp != null && requiredRootStamp != enteredRoot) {
            throw CancellationException("La generación de raíz cambió durante la revisión autorizada")
        }
        val expectedSourceId = enteredRoot.sourceId
        require(expectedSourceId.isNotBlank()) { "No existe una raíz musical activa" }
        val storage = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = cursorKey(libraryId, expectedSourceId)
        val afterAlbum = storage.getString("${key}_album", "").orEmpty()
        val afterCredit = storage.getString("${key}_credit", "").orEmpty()
        val db = ArtistEnrichmentPersistence.database(app)
        val identity = db.artistEnrichmentDao()
        val journal = db.artistArtworkCatalogJournalDao()
        val rows = journal.provenBindingsAfter(libraryId, expectedSourceId, afterAlbum, afterCredit, MAX_EXAMINE)
        if (rows.isEmpty()) return@withContext BatchResult(0, 0, true)

        val store = ArtistArtworkPersistence.from(app)
        val candidateRows = ArrayList<ArtistAlbumCreditBindingRecord>()
        val queuedKeys = HashSet<Pair<String, String>>()
        var inspected = 0
        var lastAlbum = afterAlbum
        var lastCredit = afterCredit
        for (row in rows) {
            // Cancellation, consent change and source switch must not advance the cursor.
            if (!ArtistArtworkAutoScheduler.isEnabled(app) || ArtistArtworkAutoScheduler.isPaused(app) ||
                MusicRootPreferences(app).currentRootStamp() != enteredRoot)
                throw CancellationException("El permiso o la generación de raíz cambió")
            inspected++
            lastAlbum = row.albumKey
            lastCredit = row.creditKey
            val keyForArtist = row.creditKey to row.associationId
            if (keyForArtist in queuedKeys) continue
            // The binding is not a statement about source ownership by itself.
            if (journal.countVerifiedSourceBinding(libraryId, expectedSourceId, row.albumKey,
                    row.creditKey, row.associationId) < 1) continue
            val association = identity.getDurableAssociation(libraryId, row.creditKey, row.associationId)
                ?: continue
            val record = identity.get(libraryId, row.creditKey, association.identityAssociationId)
            val bindings = identity.countConfirmedBindingsForAssociation(libraryId, row.creditKey, row.associationId)
            val anchor = ArtistArtworkAutoEligibility.anchor(
                libraryId, row.creditKey, row.associationId, listOf(association), record, bindings,
            ) ?: continue
            val selections = store.load(anchor).map { selection ->
                try { verifyCachedArtwork(app, selection) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { selection.copy(status = ArtworkLoadStatus.CACHE_ERROR) }
            }
            val protected = selections.filter { it.manuallyChosen || it.status == ArtworkLoadStatus.READY }
                .mapTo(HashSet()) { it.slot }
            // The current profile only loads its own MBID; manual artwork from an
            // earlier artist identity must also block unnecessary external lookup.
            for (slot in ArtworkSlot.entries) {
                if (identity.countManualArtworkForSlot(libraryId, row.creditKey, row.associationId, slot.name) > 0) {
                    protected.add(slot)
                }
            }
            if (protected.size == ArtworkSlot.entries.size) continue
            queuedKeys += keyForArtist
            candidateRows += row
            if (queuedKeys.size >= MAX_QUEUE) break
        }
        // Check exact binding and consent again inside the same Room write transaction.
        // No provider calls or file reads happen in this transaction.
        val committed = db.withTransaction {
            if (!ArtistArtworkAutoScheduler.isEnabled(app) || ArtistArtworkAutoScheduler.isPaused(app) ||
                MusicRootPreferences(app).currentRootStamp() != enteredRoot)
                throw CancellationException("Cambió la generación de la biblioteca activa")
            var count = 0
            for (row in candidateRows) {
                val stillBound = identity.getAlbumCreditBinding(libraryId, row.albumKey, row.creditKey)
                if (stillBound?.associationId != row.associationId ||
                    journal.countVerifiedSourceBinding(libraryId, expectedSourceId,
                        row.albumKey, row.creditKey, row.associationId) < 1) continue
                val association = identity.getDurableAssociation(libraryId, row.creditKey, row.associationId)
                    ?: continue
                if (identity.get(libraryId, row.creditKey, association.identityAssociationId) == null) continue
                // Idempotent INSERT-IGNORE: an explicit backfill must NOT turn READY,
                // deferred negative-cache or a previously queued event into PENDING.
                // Identity-blocked events are rearmed only by the separate verified
                // association path after its own exact-key checks.
                val now = System.currentTimeMillis()
                val inserted = journal.insertOutboxIfAbsent(ArtistArtworkAutoOutboxRecord(
                    libraryId = libraryId, creditKey = row.creditKey, associationId = row.associationId,
                    albumKey = row.albumKey, sourceId = expectedSourceId, updatedAtMs = now,
                ))
                if (inserted >= 0L) count++
                else {
                    // User explicitly requested repair after local byte/slot verification.
                    // Previously READY/CACHED can be stale after cache eviction or corruption.
                    // PENDING cooldowns and identity-blocked rows are NOT rearmed here.
                    count += journal.rearmCompletedForExplicitMissingArtwork(
                        libraryId, row.creditKey, row.associationId, row.albumKey,
                        expectedSourceId, now,
                    )
                }
            }
            count
        }
        // Never advance an old root's cursor after A->B->A, even if sourceId is
        // identical again. A committed outbox row is durable and safe to replay.
        if (MusicRootPreferences(app).currentRootStamp() != enteredRoot ||
            !ArtistArtworkAutoScheduler.isEnabled(app) || ArtistArtworkAutoScheduler.isPaused(app)
        ) throw CancellationException("La generación de raíz cambió antes del checkpoint")
        // Crash between outbox commit and preference commit repeats, never loses, a batch.
        check(storage.edit().putString("${key}_album", lastAlbum)
            .putString("${key}_credit", lastCredit).commit()) { "No se pudo guardar el progreso" }
        // A root or permission transition after the Room commit must not dispatch
        // an unrelated root. The old outbox/cursor remain durable for later consent.
        if (!ArtistArtworkAutoScheduler.isEnabled(app) || ArtistArtworkAutoScheduler.isPaused(app) ||
            MusicRootPreferences(app).currentRootStamp() != enteredRoot) {
            throw CancellationException("La autorización o la generación cambió antes del despacho")
        }
        scheduler.dispatchPending(requiredRootStamp = enteredRoot)
        BatchResult(inspected, committed, inspected < MAX_EXAMINE && queuedKeys.size < MAX_QUEUE)
    }

    /** Explicit user action; never a silent restart when a page is exhausted. */
    suspend fun restartReview(): Unit = withContext(Dispatchers.IO) {
        check(ArtistArtworkAutoScheduler.isEnabled(app) && !ArtistArtworkAutoScheduler.isPaused(app)) {
            "La búsqueda automática no está autorizada"
        }
        val enteredRoot = MusicRootPreferences(app).currentRootStamp()
        require(enteredRoot.sourceId.isNotBlank()) { "No existe una raíz musical activa" }
        val key = cursorKey(MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, enteredRoot.sourceId)
        if (MusicRootPreferences(app).currentRootStamp() != enteredRoot) {
            throw CancellationException("Cambió la generación de raíz antes de reiniciar la revisión")
        }
        val storage = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(storage.edit().remove("${key}_album").remove("${key}_credit").commit()) {
            "No se pudo reiniciar la revisión del catálogo"
        }
    }

    companion object {
        private const val PREFS = "michi_artist_artwork_backfill"
        private const val MAX_EXAMINE = 64
        private const val MAX_QUEUE = 12

        internal fun cursorKey(libraryId: String, sourceId: String): String =
            ArtistArtworkBackfillCursor.key(libraryId, sourceId)
    }
}
