package org.michimusic.core.intelligence

/**
 * MI-11. A genre recommendation is a playable selection of *verified* local
 * tracks belonging to the already-authorized UI7 genre index, not a new index.
 * This policy does not read the library, alter playback, collect listening
 * history, contact providers or claim that unknown listening history is zero.
 */
object MichiGenreContextPolicy {
    const val MAX_GENRE_PICKS = 4

    data class Result(
        val picks: List<MichiIntelligencePolicy.Recommendation>,
        val hasEvidence: Boolean,
    )

    fun select(
        candidates: List<MichiIntelligencePolicy.Candidate>,
        artistAffinity: Map<String, Int>,
        genreAffinity: Map<String, Int>,
        excluded: Set<MichiIntelligencePolicy.TrackKey> = emptySet(),
    ): Result {
        if (candidates.isEmpty() ||
            (artistAffinity.values.none { it > 0 } && genreAffinity.values.none { it > 0 })
        ) return Result(emptyList(), false)
        // Filter by positive real evidence BEFORE ranking. Otherwise a generic
        // pick can occupy one of four ranking slots and starve learned tracks.
        val learnedCandidates = candidates.asSequence()
            .take(MichiIntelligencePolicy.MAX_CANDIDATES)
            .filter { item ->
                (artistAffinity[item.artistKey.trim().lowercase(java.util.Locale.ROOT)] ?: 0) > 0 ||
                    MichiGenreIntelligencePolicy.keys(item.genreKey, item.verifiedGenreValues).any {
                        (genreAffinity[it] ?: 0) > 0
                    }
            }.toList()
        if (learnedCandidates.isEmpty()) return Result(emptyList(), false)
        val ranked = MichiIntelligencePolicy.recommend(
            candidates = learnedCandidates,
            artistAffinity = artistAffinity,
            genreAffinity = genreAffinity,
            excluded = excluded,
            limit = MAX_GENRE_PICKS,
        )
        // A neutral library pick is NEVER presented as personalized evidence.
        val learned = ranked.filter {
            it.reason == MichiIntelligencePolicy.Reason.ARTIST_AFFINITY ||
                it.reason == MichiIntelligencePolicy.Reason.GENRE_AFFINITY
        }
        return Result(learned, learned.isNotEmpty())
    }
}
