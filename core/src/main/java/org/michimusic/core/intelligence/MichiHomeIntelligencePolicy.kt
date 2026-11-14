package org.michimusic.core.intelligence

/**
 * MI-03H: bounded editorial suggestions from an already-authorized local snapshot.
 * This is a pure projection; it never fetches art, queries Room or composes UI.
 */
object MichiHomeIntelligencePolicy {
    enum class CardKind { NEXT_LISTEN, GENRE_SPOTLIGHT, REDISCOVER, HIDDEN_GEMS, MICHI_MIX }

    data class Card(
        val kind: CardKind,
        val tracks: List<MichiIntelligencePolicy.TrackKey>,
        val reason: MichiIntelligencePolicy.Reason,
    )

    data class Proposal(
        val cards: List<Card>,
        val useEditorialFallback: Boolean,
    )

    fun propose(
        candidates: List<MichiIntelligencePolicy.Candidate>,
        artistAffinity: Map<String, Int>,
        genreAffinity: Map<String, Int>,
        recentlyPlayed: Set<MichiIntelligencePolicy.TrackKey> = emptySet(),
        nowPlaying: MichiIntelligencePolicy.TrackKey? = null,
        nowEpochDay: Long? = null,
    ): Proposal {
        // Do not mislabel generic library picks as learned preferences.
        if (artistAffinity.values.none { it > 0 } && genreAffinity.values.none { it > 0 }) {
            return Proposal(emptyList(), useEditorialFallback = true)
        }
        val blocked = if (nowPlaying == null) recentlyPlayed else recentlyPlayed + nowPlaying
        val picks = MichiIntelligencePolicy.recommend(
            candidates = candidates,
            artistAffinity = artistAffinity,
            genreAffinity = genreAffinity,
            excluded = blocked,
            limit = 12,
        )
        if (picks.isEmpty() || picks.none {
                it.reason == MichiIntelligencePolicy.Reason.ARTIST_AFFINITY ||
                it.reason == MichiIntelligencePolicy.Reason.GENRE_AFFINITY
            }
        ) return Proposal(emptyList(), useEditorialFallback = true)
        val eligibleByKey = candidates.asSequence().take(MichiIntelligencePolicy.MAX_CANDIDATES)
            .filter { it.isLocal && it.isAvailable }.associateBy { it.key }
        val affinityReasons = setOf(
            MichiIntelligencePolicy.Reason.ARTIST_AFFINITY,
            MichiIntelligencePolicy.Reason.GENRE_AFFINITY,
        )
        val cards = ArrayList<Card>(5)
        val used = HashSet<MichiIntelligencePolicy.TrackKey>()
        fun unique(kind: CardKind, predicate: (MichiIntelligencePolicy.Recommendation) -> Boolean) {
            val pick = picks.firstOrNull { it.key !in used && predicate(it) } ?: return
            used.add(pick.key)
            cards.add(Card(kind, listOf(pick.key), pick.reason))
        }
        unique(CardKind.NEXT_LISTEN) { it.reason in affinityReasons }
        unique(CardKind.GENRE_SPOTLIGHT) {
            it.reason == MichiIntelligencePolicy.Reason.GENRE_AFFINITY
        }
        // KILLCRITIC: the 12-item general ranking MUST NOT starve a verified
        // rediscovery or a one-completion hidden gem retained in the 128-candidate
        // pool. Both special sections use their own bounded, evidence-gated query.
        fun ownedPick(predicate: (MichiIntelligencePolicy.Candidate) -> Boolean):
            Pair<MichiIntelligencePolicy.Candidate, MichiIntelligencePolicy.Reason>? {
            return eligibleByKey.values.asSequence()
                .filter { it.key !in used && it.key !in blocked && predicate(it) }
                .mapNotNull { item ->
                    val artistScore = artistAffinity[MichiTasteFingerprint.normalizeArtist(item.artistKey)] ?: 0
                    val genreScore = MichiGenreIntelligencePolicy.keys(item.genreKey, item.verifiedGenreValues)
                        .maxOfOrNull { genreAffinity[it] ?: 0 } ?: 0
                    val rating = artistScore * 3 + genreScore * 2
                    if (rating <= 0) null else {
                        val reason = if (artistScore > 0 && artistScore * 3 >= genreScore * 2)
                            MichiIntelligencePolicy.Reason.ARTIST_AFFINITY
                        else MichiIntelligencePolicy.Reason.GENRE_AFFINITY
                        Triple(item, reason, rating)
                    }
                }
                .sortedWith(compareByDescending<Triple<MichiIntelligencePolicy.Candidate,
                    MichiIntelligencePolicy.Reason, Int>> { it.third }
                    .thenBy { it.first.key.sourceId }.thenBy { it.first.key.trackId })
                .firstOrNull()?.let { it.first to it.second }
        }
        val rediscover = ownedPick {
            val last = it.lastCompletedEpochDay
            it.playCountKnown && it.playCount >= 2 && last != null &&
                nowEpochDay != null && nowEpochDay >= last && nowEpochDay - last >= 30L
        }
        if (rediscover != null) {
            used.add(rediscover.first.key)
            cards.add(Card(CardKind.REDISCOVER, listOf(rediscover.first.key), rediscover.second))
        }
        // Unknown is not zero; one VERIFIED completion is the only supported
        // meaning of a hidden gem until we have exhaustive history evidence.
        val hidden = ownedPick { it.playCountKnown && it.playCount == 1 }
        if (hidden != null) {
            used.add(hidden.first.key)
            cards.add(Card(CardKind.HIDDEN_GEMS, listOf(hidden.first.key), hidden.second))
        }
        val mix = picks.filter { it.key !in used }.take(6)
        if (mix.isNotEmpty()) cards.add(Card(CardKind.MICHI_MIX,
            mix.map { it.key }, mix.first().reason))
        return Proposal(cards, useEditorialFallback = cards.isEmpty())
    }
}
