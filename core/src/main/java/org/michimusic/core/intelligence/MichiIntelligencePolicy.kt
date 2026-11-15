package org.michimusic.core.intelligence

/**
 * Michi Intelligence M0: deterministic, pure-Kotlin, opt-in policy.
 *
 * No service, database, coroutine, model, network, wakeup, audio decoding, or timer.
 * Callers own consent, durable storage, session identity, and bounded SQL queries.
 */
object MichiIntelligencePolicy {
    const val MAX_CANDIDATES = 128
    const val MAX_RESULTS = 12

    /** Nominal affinity range shared by Home, the local selector and recommendations. */
    const val AFFINITY_MIN = -32
    const val AFFINITY_MAX = 255

    /**
     * Summing several legacy alias counters can exceed the nominal range. Clamp
     * once, at the boundary that returns a score map, so every consumer sees the
     * same contract instead of relying on a later `coerceIn`.
     */
    fun clampAffinity(value: Int): Int = value.coerceIn(AFFINITY_MIN, AFFINITY_MAX)
    private const val DECAY_INTERVAL_DAYS = 30L

    enum class PlaybackOutcome { COMPLETED, EXPLICIT_SKIP, INTERRUPTED }

    data class PreferenceCounter(
        val value: Int,
        val lastObservedEpochDay: Long,
    )

    /** Composite identity is mandatory: a track ID may be repeated in another library. */
    data class TrackKey(val sourceId: String, val trackId: String)

    data class Candidate(
        val key: TrackKey,
        val artistKey: String,
        val albumKey: String = "",
        val genreKey: String = "",
        val playCount: Int = 0,
        val playCountKnown: Boolean = false,
        /** Verified positive owned completion date, never fabricated for unknown history. */
        val lastCompletedEpochDay: Long? = null,
        val isLocal: Boolean,
        val isAvailable: Boolean,
        /** C12 local tag values, not delimiter-separated raw text. */
        val verifiedGenreValues: List<String> = emptyList(),
    )

    enum class Reason { ARTIST_AFFINITY, GENRE_AFFINITY, UNPLAYED, LIBRARY_PICK }

    data class Recommendation(val key: TrackKey, val reason: Reason)

    /**
     * A single FINAL playback event, not repeated player-position callbacks.
     * Interrupted playback, foreign remote sessions and invalid inputs never train.
     * Completion permille uses integers to avoid fragile floating-point thresholds.
     */
    fun observe(
        previous: PreferenceCounter?,
        outcome: PlaybackOutcome,
        completionPermille: Int,
        nowEpochDay: Long,
        isOwnedLocalSession: Boolean,
    ): PreferenceCounter? {
        if (!isOwnedLocalSession || completionPermille !in 0..1000 ||
            outcome == PlaybackOutcome.INTERRUPTED
        ) return previous

        val delta = when (outcome) {
            PlaybackOutcome.COMPLETED -> if (completionPermille >= 700) 3 else 0
            PlaybackOutcome.EXPLICIT_SKIP -> if (completionPermille < 300) -1 else 0
            PlaybackOutcome.INTERRUPTED -> 0
        }
        if (delta == 0) return previous
        val currentValue = decayed(previous, nowEpochDay)
        return PreferenceCounter(
            value = clampAffinity(currentValue + delta),
            lastObservedEpochDay = nowEpochDay,
        )
    }

    /** Lazy decay: calculated ONLY when an event or request is processed. */
    fun decayed(counter: PreferenceCounter?, nowEpochDay: Long): Int {
        if (counter == null) return 0
        val days = (nowEpochDay - counter.lastObservedEpochDay).coerceAtLeast(0L)
        val halvings = (days / DECAY_INTERVAL_DAYS).coerceAtMost(8L).toInt()
        var value = clampAffinity(counter.value)
        repeat(halvings) { value /= 2 }
        return value
    }

    /**
     * Candidate generation MUST happen upstream in a bounded local SQL query.
     * A hard cap here prevents unbounded memory/time even on incorrect input.
     * This is a lightweight diversified ranking, not an acoustic similarity model.
     */
    fun recommend(
        candidates: List<Candidate>,
        artistAffinity: Map<String, Int>,
        genreAffinity: Map<String, Int>,
        excluded: Set<TrackKey> = emptySet(),
        limit: Int = 6,
    ): List<Recommendation> {
        if (limit <= 0) return emptyList()
        val eligible = candidates.asSequence()
            .take(MAX_CANDIDATES)
            .filter { it.isLocal && it.isAvailable && it.key.trackId.isNotBlank() }
            .filter { it.key !in excluded }
            .distinctBy { it.key }
            .toList()
        if (eligible.isEmpty()) return emptyList()

        // Parse multi-value genre metadata once, not 12 times during diversification.
        // All values are from the verified local catalog, never a guessed style.
        data class Prepared(
            val item: Candidate,
            val artist: String,
            val album: String,
            val genreScore: Int,
        )
        val prepared = eligible.map { item ->
            val genres = MichiGenreIntelligencePolicy.keys(item.genreKey, item.verifiedGenreValues)
            Prepared(
                item = item,
                artist = item.artistKey.trim().lowercase(),
                album = item.albumKey.trim().lowercase(),
                genreScore = genres.maxOfOrNull { clampAffinity(genreAffinity[it] ?: 0) } ?: 0,
            )
        }
        val target = minOf(limit, MAX_RESULTS, prepared.size)
        val chosen = ArrayList<Recommendation>(target)
        val chosenKeys = HashSet<TrackKey>(target)
        val selectedArtists = HashMap<String, Int>()
        val selectedAlbums = HashMap<String, Int>()

        repeat(target) {
            var best: Prepared? = null
            var bestScore = Int.MIN_VALUE
            for (entry in prepared) {
                val candidate = entry.item
                if (candidate.key in chosenKeys) continue
                val artistScore = if (entry.artist.isEmpty()) 0 else
                    clampAffinity(artistAffinity[entry.artist] ?: 0)
                // Positive owned counts never contain zero. Unknown is NOT "unplayed";
                // a tiny exploration weight diversifies candidates without inventing history.
                val novelty = if (!candidate.playCountKnown) 1 else 0
                val repetitionPenalty =
                    (if (entry.artist.isEmpty()) 0 else (selectedArtists[entry.artist] ?: 0) * 12) +
                    (if (entry.album.isEmpty()) 0 else (selectedAlbums[entry.album] ?: 0) * 6)
                val score = artistScore * 3 + entry.genreScore * 2 +
                    novelty - repetitionPenalty
                // Stable ordering: on ties keep the first candidate provided by SQL.
                if (score > bestScore) {
                    best = entry
                    bestScore = score
                }
            }
            val selected = best ?: return@repeat
            val artist = selected.artist
            val album = selected.album
            val artistValue = if (artist.isEmpty()) 0 else artistAffinity[artist] ?: 0
            val genreValue = selected.genreScore
            val reason = when {
                artistValue > 0 && artistValue * 3 >= genreValue * 2 ->
                    Reason.ARTIST_AFFINITY
                genreValue > 0 -> Reason.GENRE_AFFINITY
                selected.item.playCountKnown && selected.item.playCount == 0 -> Reason.UNPLAYED // reserved for future verified zero evidence
                else -> Reason.LIBRARY_PICK
            }
            chosen.add(Recommendation(selected.item.key, reason))
            chosenKeys.add(selected.item.key)
            if (artist.isNotEmpty()) selectedArtists[artist] =
                (selectedArtists[artist] ?: 0) + 1
            if (album.isNotEmpty()) selectedAlbums[album] =
                (selectedAlbums[album] ?: 0) + 1
        }
        return chosen
    }
}
