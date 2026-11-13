package org.michimusic.data.repository

/**
 * One bounded index from MediaStore genre-membership rows. Data is scoped to IDs
 * already admitted by the existing root/visibility filter; no tags are guessed.
 * Neither genres nor memberships create new audio tracks.
 */
internal class MediaStoreGenreRecoveryIndex(
    private val permittedAudioIds: Set<Long>,
    private val maxMembershipRows: Int = 120_000,
    private val maxGenresPerAudio: Int = 16,
) {
    private val members = linkedMapOf<Long, LinkedHashSet<String>>()
    private var scanned = 0

    /** False means the entire recovery must be discarded, not partially published. */
    fun record(audioId: Long, rawGenre: String): Boolean {
        scanned++
        if (scanned > maxMembershipRows) return false
        val clean = rawGenre.trim()
        if (audioId !in permittedAudioIds || clean.isEmpty()) return true
        // Delimiter-bearing names cannot be encoded losslessly as Track.genre;
        // do not manufacture additional genres by splitting such a name.
        if (';' in clean || '\u0000' in clean || '\u001f' in clean) return true
        val genres = members.getOrPut(audioId) { linkedSetOf() }
        if (clean !in genres && genres.size >= maxGenresPerAudio) return false
        genres.add(clean)
        return true
    }

    fun result(): Map<Long, String> = members.mapValues { (_, names) -> names.joinToString(";") }
}

/** Prioritize the value reported for the audio row; fallback never overwrites it. */
internal fun preserveMediaStoreGenre(primary: String, recovered: String?): String =
    primary.ifBlank { recovered.orEmpty() }
