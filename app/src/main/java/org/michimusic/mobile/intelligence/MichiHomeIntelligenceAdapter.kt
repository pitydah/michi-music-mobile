package org.michimusic.mobile.intelligence

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.intelligence.MichiGenreIntelligencePolicy
import org.michimusic.core.intelligence.MichiHomeIntelligencePolicy
import org.michimusic.core.intelligence.MichiHomeCardPreferencePolicy
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.intelligence.MichiLibraryCandidateSelector
import org.michimusic.core.intelligence.MichiMixQueuePolicy
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackAvailabilityChecker
import org.michimusic.core.models.TrackSource
import org.michimusic.data.intelligence.MichiIntelligenceRepository
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.lang.ref.WeakReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * MI-04: only consumes Home's already-authorized, in-memory library snapshot.
 * No MediaStore scan, database catalog observer, timer, worker, model or network.
 * Source/track collisions are fail-closed. Availability IO is delayed until the
 * small final set of suggested tracks (max 12), never all 128 scored candidates.
 */
class MichiHomeIntelligenceAdapter(
    private val context: Context,
    private val repository: MichiIntelligenceRepository,
    private val epochDay: () -> Long = { LocalDate.now(ZoneOffset.UTC).toEpochDay() },
) {
    data class Section(
        val title: String,
        val explanation: String,
        val tracks: List<Track>,
        private val indices: Map<MichiIntelligencePolicy.TrackKey, Int>,
        private val source: WeakReference<List<Track>>,
        val consentEpoch: Long = MichiIntelligenceConsentBarrier.epoch(),
        val kind: MichiHomeIntelligencePolicy.CardKind = MichiHomeIntelligencePolicy.CardKind.NEXT_LISTEN,
        val displayChoice: MichiHomeCardPreferencePolicy.Choice = MichiHomeCardPreferencePolicy.Choice.DEFAULT,
        // A mutable list in an upstream owner cannot silently grow while a
        // card stays visible. O(1) fail-close; immutable snapshots remain required.
        private val snapshotSize: Int = source.get()?.size ?: -1,
    ) {
        fun isAuthorized(): Boolean = MichiIntelligenceConsentBarrier.permits(consentEpoch)
        fun belongsTo(library: List<Track>): Boolean = isAuthorized() &&
            source.get() === library && snapshotSize >= 0 && library.size == snapshotSize
        /**
         * Every Intelligence card, not only Michi Mix, owns an exclusive bounded
         * queue. The caller must NEVER substitute allTracks on a normal pick.
         */
        fun verifiedRecommendedQueue(library: List<Track>, selected: Track? = null):
            Pair<List<Track>, Int>? {
            if (!belongsTo(library) || tracks.isEmpty() ||
                tracks.size > org.michimusic.core.intelligence.MichiIntelligenceQueuePolicy.MAX_QUEUE ||
                tracks.any { indexOf(it, library) == null }) return null
            val keys = tracks.map { MichiIntelligencePolicy.TrackKey(it.sourceId, it.id) }
            val selectedKey = selected?.let { MichiIntelligencePolicy.TrackKey(it.sourceId, it.id) }
            val plan = MichiMixQueuePolicy.plan(keys, selectedKey) ?: return null
            if (selected != null && tracks[plan.startIndex] !== selected) return null
            return tracks.toList() to plan.startIndex
        }

        fun verifiedMixQueue(library: List<Track>, selected: Track? = null):
            Pair<List<Track>, Int>? = if (kind == MichiHomeIntelligencePolicy.CardKind.MICHI_MIX)
                verifiedRecommendedQueue(library, selected) else null

        /** Constant-time click verification; stale library snapshots fail closed. */
        fun indexOf(track: Track, library: List<Track>): Int? {
            if (!belongsTo(library)) return null
            val index = indices[MichiIntelligencePolicy.TrackKey(track.sourceId, track.id)] ?: return null
            // Exact object must still be at the indexed position in this library.
            return index.takeIf { library.getOrNull(index) === track }
        }
    }

    data class SuggestionResult(val sections: List<Section>, val hiddenCardCount: Int)

    // Publish tracks and positions atomically; never retain an obsolete 50k-track list.
    private data class Snapshot(
        val source: WeakReference<List<Track>>,
        val tracks: Map<MichiIntelligencePolicy.TrackKey, Track>,
        val positions: Map<MichiIntelligencePolicy.TrackKey, Int>,
        val signals: MichiLibraryCandidateSelector.Signals,
        // In-memory lists can be mutated in-place without changing object identity.
        // This guards size changes AND replacement of any selected track. The
        // library owner must still publish immutable snapshots for unsampled rows.
        val sourceSize: Int,
        /** Daily age thresholds and deterministic exploration are date-sensitive. */
        val selectedAtEpochDay: Long,
    ) {
        fun matches(library: List<Track>, currentSignals: MichiLibraryCandidateSelector.Signals,
            todayEpochDay: Long): Boolean =
            source.get() === library && library.size == sourceSize &&
                selectedAtEpochDay == todayEpochDay && signals == currentSignals &&
                positions.all { (key, index) -> library.getOrNull(index) === tracks[key] }
    }
    private val cacheLock = Any()
    private var cacheRevision = 0L
    @Volatile private var cachedSnapshot: Snapshot? = null

    /**
     * Revocation and cache publication share a very short critical section:
     * an older suspendable Home request must not reinsert a private selection
     * AFTER scope/consent invalidation. No listener, timer or persistent job.
     */
    fun clearTransientSelection() {
        synchronized(cacheLock) {
            cacheRevision++
            cachedSnapshot = null
        }
    }

    suspend fun suggest(
        scopedTracks: List<Track>,
        recentTracks: List<Track>,
        currentTrack: Track?,
    ): SuggestionResult = withContext(Dispatchers.IO) {
        val readEpoch = MichiIntelligenceConsentBarrier.epoch()
        val requestedCacheRevision = synchronized(cacheLock) { cacheRevision }
        if (!MichiIntelligenceConsentBarrier.permits(readEpoch) ||
            !repository.isEnabled() || scopedTracks.isEmpty()) {
            clearTransientSelection()
            return@withContext SuggestionResult(emptyList(), 0)
        }
        val today = epochDay()
        // Read at most 384 private, hashed counters ONCE. No artist names, titles,
        // playback history or file paths are copied into the taste index.
        val signals = repository.readSelectionSignals(today)
        if (!MichiIntelligenceConsentBarrier.permits(readEpoch))
            return@withContext SuggestionResult(emptyList(), 0)
        val previouslyCached = synchronized(cacheLock) {
            cachedSnapshot.takeIf { cacheRevision == requestedCacheRevision }
        }
        val snapshot = if (previouslyCached?.matches(scopedTracks, signals, today) == true)
            previouslyCached else {
            // MI-10: examine metadata in the ALREADY LOADED library, not every
            // 390th row. A bounded 5-lane selector spans the whole snapshot,
            // with no second scanner and no 50k-item index retained in memory.
            val selected = MichiLibraryCandidateSelector.select(
                scopedTracks, signals, today,
            ) { coroutineContext.ensureActive() }
            val first = LinkedHashMap<MichiIntelligencePolicy.TrackKey, Track>()
            val positions = LinkedHashMap<MichiIntelligencePolicy.TrackKey, Int>()
            for (hit in selected.hits) {
                val track = scopedTracks[hit.index]
                first[hit.key] = track
                positions[hit.key] = hit.index
            }
            Snapshot(WeakReference(scopedTracks), first.toMap(), positions.toMap(),
                signals, scopedTracks.size, today).also {
                // A revoked generation cannot publish even an in-memory cache.
                synchronized(cacheLock) {
                    if (cacheRevision == requestedCacheRevision &&
                        MichiIntelligenceConsentBarrier.permits(readEpoch)) {
                        cachedSnapshot = it
                    }
                }
            }
        }
        if (!MichiIntelligenceConsentBarrier.permits(readEpoch))
            return@withContext SuggestionResult(emptyList(), 0)
        val sampled = snapshot.tracks
        if (sampled.isEmpty()) return@withContext SuggestionResult(emptyList(), 0)
        // Owner-aware positive counts are isolated from the legacy ID-only Room history.
        // Unknown means unknown, never inferred as no previous plays.
        val ownedCounts = repository.readOwnedTrackEvidence(sampled.keys)
        if (!MichiIntelligenceConsentBarrier.permits(readEpoch)) return@withContext SuggestionResult(emptyList(), 0)
        val candidates = sampled.map { (key, track) ->
            MichiIntelligencePolicy.Candidate(
                key = key,
                artistKey = track.artist.trim().lowercase(Locale.ROOT),
                albumKey = track.album.trim().lowercase(Locale.ROOT),
                genreKey = track.genre,
                verifiedGenreValues = track.genreValues,
                playCount = ownedCounts[key]?.completed ?: 0,
                playCountKnown = key in ownedCounts, // missing is UNKNOWN, not zero
                lastCompletedEpochDay = ownedCounts[key]?.lastCompletedEpochDay,
                isLocal = true,
                isAvailable = true, // authoritative snapshot; physical check BEFORE display
            )
        }
        val taste = repository.readAffinities(
            candidates.map { it.artistKey },
            candidates.flatMap { MichiGenreIntelligencePolicy.keys(it.genreKey, it.verifiedGenreValues) },
            today, verifiedGenreKeys = true,
        )
        val recentKeys = recentTracks.mapTo(HashSet()) {
            MichiIntelligencePolicy.TrackKey(it.sourceId, it.id)
        }
        val excluded = sampled.keys.filterTo(HashSet()) { it in recentKeys }
        // Soft feedback is explicit, owner-scoped, and expires without a timer.
        excluded.addAll(repository.readDismissedKeys(sampled.keys,
            today))
        if (!MichiIntelligenceConsentBarrier.permits(readEpoch)) return@withContext SuggestionResult(emptyList(), 0)
        // Read up to five card preferences only once per Home request.
        val preferences = repository.readCardPreferences()
        if (!MichiIntelligenceConsentBarrier.permits(readEpoch))
            return@withContext SuggestionResult(emptyList(), 0)
        val hiddenCount = preferences.values.count { it == MichiHomeCardPreferencePolicy.Choice.HIDDEN }
        val proposal = MichiHomeIntelligencePolicy.propose(
            candidates, taste.artist, taste.genre, excluded,
            nowPlaying = currentTrack?.let { MichiIntelligencePolicy.TrackKey(it.sourceId, it.id) },
            nowEpochDay = today,
        )
        if (proposal.useEditorialFallback) return@withContext SuggestionResult(emptyList(), hiddenCount)
        val physicalAvailability = HashMap<MichiIntelligencePolicy.TrackKey, Boolean>()
        // Rediscover and barely-explored music can now be presented with truthful,
        // positive owned-play evidence. Keep the UI7 Home layout at three sections.
        val priority = listOf(
            MichiHomeIntelligencePolicy.CardKind.NEXT_LISTEN,
            MichiHomeIntelligencePolicy.CardKind.REDISCOVER,
            MichiHomeIntelligencePolicy.CardKind.HIDDEN_GEMS,
            MichiHomeIntelligencePolicy.CardKind.MICHI_MIX,
            MichiHomeIntelligencePolicy.CardKind.GENRE_SPOTLIGHT,
        )
        val ordered = MichiHomeCardPreferencePolicy.arrange(
            proposal.cards.sortedBy { priority.indexOf(it.kind) }, preferences,
        )
        val visibleCards = ordered.mapNotNull { card ->
            val visible = card.tracks.mapNotNull pick@{ key ->
                val track = sampled[key] ?: return@pick null
                val available = physicalAvailability.getOrPut(key) {
                    TrackAvailabilityChecker.isTrackDownloaded(track, context)
                }
                track.takeIf { available }
            }
            if (visible.isEmpty()) return@mapNotNull null
            val title = when (card.kind) {
                MichiHomeIntelligencePolicy.CardKind.NEXT_LISTEN -> "Tu próxima escucha"
                MichiHomeIntelligencePolicy.CardKind.GENRE_SPOTLIGHT -> "Explora tus géneros"
                MichiHomeIntelligencePolicy.CardKind.REDISCOVER -> "Redescubre"
                MichiHomeIntelligencePolicy.CardKind.HIDDEN_GEMS -> "Joyas ocultas"
                MichiHomeIntelligencePolicy.CardKind.MICHI_MIX -> "Michi Mix"
            }
            val reason = when (card.kind) {
                MichiHomeIntelligencePolicy.CardKind.REDISCOVER ->
                    "Lleva al menos 30 días sin una escucha completa verificada"
                MichiHomeIntelligencePolicy.CardKind.HIDDEN_GEMS ->
                    "Una escucha completa registrada desde que activaste Intelligence"
                else -> when (card.reason) {
                MichiIntelligencePolicy.Reason.ARTIST_AFFINITY -> "Por tu afinidad con este artista"
                MichiIntelligencePolicy.Reason.GENRE_AFFINITY -> "Por tu afinidad con este género"
                MichiIntelligencePolicy.Reason.UNPLAYED -> "Música de tu biblioteca por descubrir"
                MichiIntelligencePolicy.Reason.LIBRARY_PICK -> "Selección de tu biblioteca"
                }
            }
            val positions = visible.mapNotNull { track ->
                val key = MichiIntelligencePolicy.TrackKey(track.sourceId, track.id)
                snapshot.positions[key]?.let { key to it }
            }.toMap()
            Section(title, reason, visible, positions, WeakReference(scopedTracks), readEpoch,
                card.kind, preferences[card.kind] ?: MichiHomeCardPreferencePolicy.Choice.DEFAULT)
        }
        // Fail closed if preferences were revoked while IO/ranking was in flight.
        if (MichiIntelligenceConsentBarrier.permits(readEpoch))
            SuggestionResult(visibleCards, hiddenCount) else SuggestionResult(emptyList(), 0)
    }
}
