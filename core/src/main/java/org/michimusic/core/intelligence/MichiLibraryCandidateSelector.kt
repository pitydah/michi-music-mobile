package org.michimusic.core.intelligence

import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import java.util.PriorityQueue

/**
 * MI-10: deterministic, bounded retrieval from an ALREADY AUTHORIZED in-memory
 * library snapshot. Full traversal happens only on a changed library/profile,
 * off the UI thread, never through MediaStore, SAF, or a new observer.
 *
 * The 128-track budget is a RESULT CAP, not an instruction to blindly inspect
 * only the first 128 songs. Lanes consider affinity, verified listening evidence,
 * artist/genre variety and stable exploration over the entire supplied snapshot.
 */
object MichiLibraryCandidateSelector {
    const val MAX_RESULTS = MichiIntelligencePolicy.MAX_CANDIDATES
    private const val AFFINITY_SLOTS = 64
    private const val OWNED_SLOTS = 16
    private const val ARTIST_SLOTS = 12
    private const val GENRE_SLOTS = 12
    private const val EXPLORATION_SLOTS = 64 // extra headroom to backfill overlapping lanes
    private const val MAX_ARTIST_AFFINITY_SLOTS = 4

    data class Signals(
        /** Only SHA-256 digests and small decayed scores; never a track inventory. */
        val artists: Map<String, Int> = emptyMap(),
        val genres: Map<String, Int> = emptyMap(),
        val owned: Map<String, MichiOwnedPlayCountPolicy.Count> = emptyMap(),
    )

    data class Hit(val index: Int, val key: MichiIntelligencePolicy.TrackKey)
    data class Result(
        val hits: List<Hit>,
        val scanned: Int,
        val eligible: Int,
        val rejectedAmbiguous: Int,
        val matchingAffinity: Int,
    )

    private data class Ranked(
        val index: Int,
        val key: MichiIntelligencePolicy.TrackKey,
        val artist: String,
        val genre: String,
        val order: Long,
        val affinity: Int,
        val owned: Int,
    )

    /** Stable across list reorder and process restart; NOT a cryptographic hash. */
    private fun stableOrder(key: MichiIntelligencePolicy.TrackKey): Long {
        var z = (key.sourceId.hashCode().toLong() shl 32) xor
            (key.trackId.hashCode().toLong() and 0xffffffffL)
        z = (z xor (z ushr 33)) * -49064778989728563L
        z = (z xor (z ushr 33)) * -4265267296055464877L
        return (z xor (z ushr 33)) and Long.MAX_VALUE
    }

    /** Keep the smallest stable hashes with a bounded heap. */
    private class Explore(capacity: Int) {
        private val heap = PriorityQueue<Ranked>(compareByDescending<Ranked> { it.order }
            .thenByDescending { it.key.sourceId }.thenByDescending { it.key.trackId })
        private val limit = capacity
        fun add(value: Ranked) {
            if (heap.size < limit) heap.add(value)
            else if (value.order < heap.peek().order) { heap.poll(); heap.add(value) }
        }
        fun items(): List<Ranked> = heap.sortedWith(compareBy<Ranked> { it.order }
            .thenBy { it.key.sourceId }.thenBy { it.key.trackId })
    }

    /** Bounded unique buckets, never an artist/genre index covering 50k songs. */
    private class Distinct(capacity: Int) {
        private val best = HashMap<String, Ranked>(capacity)
        private val limit = capacity
        fun add(group: String, value: Ranked) {
            if (group.isBlank()) return
            val previous = best[group]
            if (previous != null) {
                if (value.order < previous.order) best[group] = value
                return
            }
            if (best.size < limit) { best[group] = value; return }
            val worst = best.entries.maxWithOrNull(compareBy<Map.Entry<String, Ranked>> { it.value.order }
                .thenBy { it.key }) ?: return
            if (value.order < worst.value.order) {
                best.remove(worst.key)
                best[group] = value
            }
        }
        fun items(): List<Ranked> = best.values.sortedBy { it.order }
    }

    /** Keep affinity-positive songs only, with a cap per artist. */
    private class Affinity {
        private val list = ArrayList<Ranked>(AFFINITY_SLOTS)
        private fun stronger(a: Ranked, b: Ranked) =
            a.affinity > b.affinity || (a.affinity == b.affinity && a.order < b.order)
        fun add(value: Ranked) {
            if (value.affinity <= 0) return
            var artistCount = 0
            var artistWorst = -1
            var globalWorst = -1
            for (i in list.indices) {
                val item = list[i]
                if (globalWorst < 0 || stronger(list[globalWorst], item)) globalWorst = i
                if (item.artist == value.artist) {
                    artistCount++
                    if (artistWorst < 0 || stronger(list[artistWorst], item)) artistWorst = i
                }
            }
            if (artistCount >= MAX_ARTIST_AFFINITY_SLOTS) {
                if (artistWorst >= 0 && stronger(value, list[artistWorst])) list[artistWorst] = value
            } else if (list.size < AFFINITY_SLOTS) list.add(value)
            else if (globalWorst >= 0 && stronger(value, list[globalWorst])) {
                list[globalWorst] = value
            }
        }
        fun items(): List<Ranked> = list.sortedWith(compareByDescending<Ranked> { it.affinity }
            .thenBy { it.order })
    }

    /** Count evidence is local-only; absent is unknown, never equivalent to zero. */
    private class Owned {
        private val list = ArrayList<Ranked>(OWNED_SLOTS)
        fun add(value: Ranked) {
            if (value.owned <= 0) return
            if (list.size < OWNED_SLOTS) { list.add(value); return }
            val worst = list.indices.minWithOrNull(compareBy<Int> { list[it].owned }
                .thenByDescending { list[it].order }) ?: return
            if (value.owned > list[worst].owned ||
                (value.owned == list[worst].owned && value.order < list[worst].order)) {
                list[worst] = value
            }
        }
        fun items(): List<Ranked> = list.sortedWith(compareByDescending<Ranked> { it.owned }
            .thenBy { it.order })
    }

    fun select(
        library: List<Track>,
        signals: Signals,
        todayEpochDay: Long,
        /** Invoked every 256 records; caller may cancel its coroutine. */
        checkCancellation: () -> Unit = {},
    ): Result {
        val affinity = Affinity()
        val owned = Owned()
        val artists = Distinct(ARTIST_SLOTS)
        val genres = Distinct(GENRE_SLOTS)
        val explore = Explore(EXPLORATION_SLOTS)
        var eligible = 0
        var matching = 0
        // Bounded memoization only for metadata already present in the snapshot.
        // Never retain the full artist or genre index of a 50k-song collection.
        val artistScores = HashMap<String, Int>(1024)
        val genreInfo = HashMap<String, Pair<List<String>, Int>>(256)
        for (index in library.indices) {
            if ((index and 255) == 0) checkCancellation()
            val track = library[index]
            if (track.source != TrackSource.LOCAL || track.id.isBlank() ||
                track.id.length > 1024 || track.sourceId.length > 1024 || track.filepath.isBlank()) continue
            eligible++
            val key = MichiIntelligencePolicy.TrackKey(track.sourceId, track.id)
            val artist = MichiTasteFingerprint.normalizeArtist(track.artist)
            val artistScore = if (artist.isNotEmpty() && signals.artists.isNotEmpty()) {
                artistScores[artist] ?: (signals.artists[MichiTasteFingerprint.sha256(artist)] ?: 0)
                    .also { if (artistScores.size < 1024) artistScores[artist] = it }
            } else 0
            val cachedGenre = if (track.genreValues.isEmpty()) genreInfo[track.genre] else null
            val (genreKeys, genreScore) = if (cachedGenre != null) cachedGenre else {
                val keys = MichiGenreIntelligencePolicy.keys(track.genre, track.genreValues)
                val score = if (signals.genres.isEmpty()) 0 else
                    keys.maxOfOrNull { signals.genres[MichiTasteFingerprint.sha256(it)] ?: 0 } ?: 0
                (keys to score).also {
                    if (track.genreValues.isEmpty() && genreInfo.size < 256) genreInfo[track.genre] = it
                }
            }
            val groupGenre = genreKeys.firstOrNull().orEmpty()
            val completion = if (signals.owned.isEmpty()) null else
                signals.owned[MichiTasteFingerprint.ownedTrack(track.sourceId, track.id)]
            val ownedScore = if (completion == null) 0 else {
                val age = todayEpochDay - completion.lastCompletedEpochDay
                when {
                    completion.completed >= 2 && age >= 30L -> 3
                    completion.completed == 1 -> 2
                    else -> 1
                }
            }
            // Keep strongly negative preferences away from exploration unless another
            // positive signal explicitly compensates for them.
            if (artistScore <= -3 && genreScore <= 0 || genreScore <= -3 && artistScore <= 0) continue
            val combined = (artistScore.coerceIn(-32,255) * 3 +
                genreScore.coerceIn(-32,255) * 2).coerceAtLeast(0)
            if (combined > 0) matching++
            val value = Ranked(index, key, artist, groupGenre, stableOrder(key), combined, ownedScore)
            affinity.add(value)
            owned.add(value)
            artists.add(artist, value)
            genres.add(groupGenre, value)
            explore.add(value)
        }
        val pool = LinkedHashMap<MichiIntelligencePolicy.TrackKey, Ranked>(MAX_RESULTS)
        // Owned evidence and strong affinity receive priority; remaining lanes
        // guarantee a deterministic spread even when the user has little history.
        for (lane in listOf(owned.items(), affinity.items(), genres.items(),
                artists.items(), explore.items())) {
            for (entry in lane) {
                if (pool.size == MAX_RESULTS) break
                pool.putIfAbsent(entry.key, entry)
            }
        }
        if (pool.isEmpty()) return Result(emptyList(), library.size, eligible, 0, matching)
        // MI-12: Media3 queue receipts may still be keyed by trackId alone.
        // Reject ANY reused ID anywhere in the authorized snapshot, even when
        // sourceIds differ or a colliding entry is remote / outside the 128 hits.
        // A (sourceId,trackId) key is safe for preference storage but cannot
        // prove that an ID-only transport maps back to the intended owner.
        // This pass is O(library size), keeps <=128 IDs, and is cancelable.
        val selectedIds = pool.keys.mapTo(HashSet<String>(pool.size)) { it.trackId }
        val seenIds = HashSet<String>(selectedIds.size)
        val ambiguousIds = HashSet<String>()
        for (index in library.indices) {
            if ((index and 255) == 0) checkCancellation()
            val id = library[index].id
            if (id in selectedIds && !seenIds.add(id)) ambiguousIds.add(id)
        }
        val hits = pool.values.filter { it.key.trackId !in ambiguousIds }
            .map { Hit(it.index, it.key) }
        val rejected = pool.size - hits.size
        return Result(hits, library.size, eligible, rejected, matching)
    }
}
