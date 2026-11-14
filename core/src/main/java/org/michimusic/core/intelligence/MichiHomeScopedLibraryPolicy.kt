package org.michimusic.core.intelligence

import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

/**
 * MI-18: each One-library candidate must be proven by library_tracks.
 * Combine first 256 true library members with a constant-size stratified
 * catalog sample to protect sparse and large One-library selections. Does not schedule
 * scans, load audio, or retain an unbounded set of IDs.
 *
 * Called off-main-thread once when the library snapshot or scope changes.
 * It is a bounded recommendation sample, NOT a full-library ranking.
 */
object MichiHomeScopedLibraryPolicy {
    const val MAX_MEMBER_SAMPLE = 256
    const val MAX_CANDIDATES = MAX_MEMBER_SAMPLE * 2

    fun sampleDistinctCandidates(allTracks: List<Track>, ownedPrefix: Set<String> = emptySet()): List<Track> {
        if (allTracks.isEmpty() || ownedPrefix.size > MAX_MEMBER_SAMPLE) return emptyList()
        val targetIds = LinkedHashSet<String>(MAX_CANDIDATES)
        targetIds.addAll(ownedPrefix.filter { it.isNotBlank() })
        val limit = minOf(MAX_MEMBER_SAMPLE, allTracks.size)
        for (slot in 0 until limit) {
            val index = if (limit == 1) 0 else
                ((slot.toLong() * (allTracks.size - 1L)) / (limit - 1L)).toInt()
            val track = allTracks[index]
            if (track.id.isNotBlank()) targetIds.add(track.id)
        }
        // This bounded pass also finds sparse One-library members not selected
        // by stratification and rejects duplicates anywhere in the catalog.
        val candidates = LinkedHashMap<String, Track>(targetIds.size)
        val ambiguous = HashSet<String>()
        for (track in allTracks) {
            if (track.id !in targetIds || track.id in ambiguous) continue
            if (candidates.putIfAbsent(track.id, track) != null) {
                candidates.remove(track.id)
                ambiguous.add(track.id)
            }
        }
        return candidates.values.filter {
            it.source == TrackSource.LOCAL && it.filepath.isNotBlank()
        }
    }

    /** Exactly one visible metadata row and a current SQL membership receipt. */
    fun filterUniqueOwned(candidates: List<Track>, memberIds: Set<String>): List<Track> {
        if (memberIds.isEmpty() || memberIds.size > MAX_CANDIDATES ||
            candidates.size > MAX_CANDIDATES) return emptyList()
        val unique = LinkedHashMap<String, Track>(memberIds.size)
        val ambiguous = HashSet<String>()
        for (track in candidates) {
            if (track.id !in memberIds || track.id in ambiguous ||
                track.source != TrackSource.LOCAL || track.filepath.isBlank()) continue
            if (unique.putIfAbsent(track.id, track) != null) {
                unique.remove(track.id)
                ambiguous.add(track.id)
            }
        }
        return unique.values.toList()
    }
}
