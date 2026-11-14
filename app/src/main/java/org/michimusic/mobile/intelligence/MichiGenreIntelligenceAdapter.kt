package org.michimusic.mobile.intelligence

import java.lang.ref.WeakReference
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.michimusic.core.intelligence.MichiGenreIntelligencePolicy
import org.michimusic.core.intelligence.MichiGenreContextPolicy
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.intelligence.MichiLibraryCandidateSelector
import org.michimusic.core.models.Track
import org.michimusic.data.intelligence.MichiIntelligenceRepository
import kotlin.coroutines.coroutineContext

/**
 * MI-11: ONE-SHOT suggestion from a scope-verified UI7 playable genre snapshot.
 * UI7's GenreLibraryViewModel remains the only genre index/owner authority.
 * Nothing runs before the genre detail opens; no second scan, observer or worker.
 */
class MichiGenreIntelligenceAdapter(private val repository: MichiIntelligenceRepository) {
    data class Section(
        val genreId: String,
        val scopeKey: String,
        val scopeGeneration: Long,
        val consentEpoch: Long,
        val tracks: List<Track>,
        val reasons: Map<MichiIntelligencePolicy.TrackKey, MichiIntelligencePolicy.Reason>,
        private val positions: Map<MichiIntelligencePolicy.TrackKey, Int>,
        private val scopeTracks: WeakReference<List<Track>>,
        private val originalSize: Int = scopeTracks.get()?.size ?: -1,
    ) {
        fun isAuthorized() = MichiIntelligenceConsentBarrier.permits(consentEpoch)

        /** No playback if the visible genre or library scope changed during IO. */
        fun playbackPlan(
            currentGenreId: String,
            currentScopeKey: String,
            currentGeneration: Long,
            currentPlayable: List<Track>,
            selected: Track? = null,
        ): Pair<List<Track>, Int>? {
            if (!isAuthorized() || currentGenreId != genreId || currentScopeKey != scopeKey ||
                currentGeneration != scopeGeneration || scopeTracks.get() !== currentPlayable ||
                originalSize < 0 || currentPlayable.size != originalSize ||
                tracks.isEmpty() || tracks.size > MichiGenreContextPolicy.MAX_GENRE_PICKS
            ) return null
            val keys = tracks.map { MichiIntelligencePolicy.TrackKey(it.sourceId, it.id) }
            if (keys.toSet().size != keys.size || tracks.map { it.id }.toSet().size != tracks.size)
                return null // ID-only Media3 receipts cannot disambiguate owners.
            for (track in tracks) {
                val key = MichiIntelligencePolicy.TrackKey(track.sourceId, track.id)
                val position = positions[key] ?: return null
                if (currentPlayable.getOrNull(position) !== track) return null
            }
            val index = if (selected == null) 0 else tracks.indexOfFirst { it === selected }
            return if (index < 0) null else tracks.toList() to index
        }

        fun reason(track: Track): String = when (
            reasons[MichiIntelligencePolicy.TrackKey(track.sourceId, track.id)]
        ) {
            MichiIntelligencePolicy.Reason.ARTIST_AFFINITY -> "Por tu afinidad con este artista"
            MichiIntelligencePolicy.Reason.GENRE_AFFINITY -> "Por tu afinidad con este género"
            else -> "Selección de tu biblioteca"
        }
    }

    suspend fun suggest(
        genreId: String,
        scopeKey: String,
        scopeGeneration: Long,
        playable: List<Track>,
        current: Track?,
    ): Section? = withContext(Dispatchers.IO) {
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        // Scope identifiers are mandatory. Empty scopes from legacy Compose tests
        // have NO authority to trigger preferences or disk access.
        if (scopeKey.isBlank() || scopeGeneration <= 0 || genreId.isBlank() ||
            playable.isEmpty() || !MichiIntelligenceConsentBarrier.permits(epoch) ||
            !repository.isEnabled()
        ) return@withContext null
        val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
        // Only the bounded PRIVATE digest snapshot and approved UI7 tracks.
        val signals = repository.readSelectionSignals(today)
        if (!MichiIntelligenceConsentBarrier.permits(epoch)) return@withContext null
        val pool = MichiLibraryCandidateSelector.select(playable, signals, today) {
            coroutineContext.ensureActive()
        }
        if (pool.hits.isEmpty()) return@withContext null
        val chosen = LinkedHashMap<MichiIntelligencePolicy.TrackKey, Track>()
        val positions = LinkedHashMap<MichiIntelligencePolicy.TrackKey, Int>()
        for (hit in pool.hits) {
            val track = playable.getOrNull(hit.index) ?: continue
            chosen[hit.key] = track
            positions[hit.key] = hit.index
        }
        val counts = repository.readOwnedTrackEvidence(chosen.keys)
        val candidates = chosen.map { (key, track) ->
            MichiIntelligencePolicy.Candidate(
                key = key,
                artistKey = track.artist.trim().lowercase(Locale.ROOT),
                albumKey = track.album.trim().lowercase(Locale.ROOT),
                genreKey = track.genre,
                verifiedGenreValues = track.genreValues,
                playCount = counts[key]?.completed ?: 0,
                playCountKnown = key in counts,
                lastCompletedEpochDay = counts[key]?.lastCompletedEpochDay,
                isLocal = true,
                isAvailable = true, // UI7 approved index; physical validation before playback
            )
        }
        val taste = repository.readAffinities(
            candidates.map { it.artistKey },
            candidates.flatMap { MichiGenreIntelligencePolicy.keys(it.genreKey, it.verifiedGenreValues) },
            today, verifiedGenreKeys = true,
        )
        val dismissed = repository.readDismissedKeys(chosen.keys, today)
        val currentKey = current?.let { MichiIntelligencePolicy.TrackKey(it.sourceId, it.id) }
        val excluded = if (currentKey == null) dismissed else dismissed + currentKey
        val proposal = MichiGenreContextPolicy.select(candidates, taste.artist, taste.genre, excluded)
        if (!proposal.hasEvidence || !MichiIntelligenceConsentBarrier.permits(epoch))
            return@withContext null
        val recommended = proposal.picks.mapNotNull { chosen[it.key] }
        if (recommended.isEmpty()) return@withContext null
        val reasons = proposal.picks.associate { it.key to it.reason }
        Section(genreId, scopeKey, scopeGeneration, epoch, recommended,
            reasons, positions, WeakReference(playable))
    }
}
