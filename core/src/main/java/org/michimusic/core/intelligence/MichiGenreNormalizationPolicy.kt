package org.michimusic.core.intelligence

import java.text.Normalizer
import java.util.Locale

/**
 * MI-23: genre-only, side-effect-free decisions. This is NOT an audio classifier.
 * The caller owns a verified, immutable (sourceId, trackId, URI, size, revision) receipt.
 * No path, URI, listener history, playback score, or credential may enter SearchIntent.
 */
object MichiGenreNormalizationPolicy {
    const val MAX_GENRES = 32
    const val MAX_GENRE_CHARS = 2_048
    const val MAX_TOTAL_GENRE_CHARS = 8_192
    const val MAX_SEARCH_FIELD = 120
    private val mbidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    enum class Entity { RECORDING, RELEASE_GROUP, ARTIST }

    /**
     * Where a value came from. Only FILE/RECORDING/MANUAL classify a track;
     * RELEASE_GROUP and ARTIST are orientative context and are NEVER attributed
     * to the recording automatically.
     */
    enum class Provenance { FILE, RECORDING, RELEASE_GROUP, ARTIST, MANUAL }

    data class SearchIntent(val title: String, val artist: String, val album: String = "") {
        /** Exact per-request disclosure. Contains no local owner / identity / URI. */
        fun outgoingFields(): Map<String, String> = buildMap {
            put("recording", title)
            put("artist", artist)
            if (album.isNotEmpty()) put("release", album)
        }
    }
    data class RecordingCandidate(
        val mbid: String, val title: String, val artist: String,
        val durationMs: Long? = null, val disambiguation: String = "",
    )
    data class GenreProposal(
        val name: String, val entity: Entity, val sourceMbid: String, val votes: Int?,
        val recordingMbid: String,
    )
    sealed interface Decision {
        data class Allowed(val values: List<String>) : Decision
        data class Denied(val reason: String) : Decision
    }

    private fun comparable(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /** Shared MI-26 absence policy; `Other`/`Various` are containers, never assigned. */
    fun isPlaceholderGenre(value: String): Boolean = !MichiGenreAbsencePolicy.isAssignableLabel(value)

    fun canonicalMbid(value: String): String? = value.trim().lowercase(Locale.ROOT)
        .takeIf { mbidPattern.matches(it) }

    /** Only a recording (or the user's own file/manual edit) classifies the track. */
    fun provenanceOf(entity: Entity): Provenance = when (entity) {
        Entity.RECORDING -> Provenance.RECORDING
        Entity.RELEASE_GROUP -> Provenance.RELEASE_GROUP
        Entity.ARTIST -> Provenance.ARTIST
    }

    fun isTrackClassification(provenance: Provenance): Boolean = when (provenance) {
        Provenance.FILE, Provenance.RECORDING, Provenance.MANUAL -> true
        Provenance.RELEASE_GROUP, Provenance.ARTIST -> false
    }

    /** Orientative-only provenance; never shown as a verified track classification. */
    fun isContextOnly(provenance: Provenance): Boolean = !isTrackClassification(provenance)

    fun provenanceLabel(provenance: Provenance): String = when (provenance) {
        Provenance.FILE -> "Etiqueta del archivo"
        Provenance.RECORDING -> "Grabación MusicBrainz"
        Provenance.RELEASE_GROUP -> "Contexto: release group (orientativo)"
        Provenance.ARTIST -> "Contexto: artista (orientativo)"
        Provenance.MANUAL -> "Etiqueta local aprobada"
    }

    /** An unsafe field fails closed; never silently chop a malformed payload. */
    fun validateGenres(input: List<String>): List<String>? {
        if (input.isEmpty() || input.size > MAX_GENRES) return null
        val values = LinkedHashMap<String, String>()
        var total = 0
        for (raw in input) {
            if (raw != raw.trim() || raw.isBlank() || raw.length > MAX_GENRE_CHARS ||
                '\uFFFD' in raw || raw.any { Character.isISOControl(it) }) return null
            total += raw.length
            if (total > MAX_TOTAL_GENRE_CHARS) return null
            if (isPlaceholderGenre(raw)) return null
            values.putIfAbsent(MichiGenreDisambiguationPolicy.key(raw).ifBlank { comparable(raw) }, raw)
        }
        return values.values.toList().takeIf { it.isNotEmpty() && it.size <= MAX_GENRES }
    }

    /** No inference from album art, location, filenames or listening patterns. */
    fun prepareSearch(title: String, artist: String, album: String = ""): SearchIntent? {
        if (listOf(title, artist, album).any { s -> s.any { it.isISOControl() } }) return null
        val t = title.trim(); val a = artist.trim(); val b = album.trim()
        if (t.isEmpty() || a.isEmpty() || t.length > MAX_SEARCH_FIELD ||
            a.length > MAX_SEARCH_FIELD || b.length > MAX_SEARCH_FIELD ||
            listOf(t, a, b).any { s -> s.any { it.isISOControl() } }) return null
        return SearchIntent(t, a, b)
    }

    /**
     * SearchIntent is a public data class, not an authorization token. A caller may
     * construct it without prepareSearch(), so all network entrypoints MUST validate
     * it again before any provider IO. The exact normalized payload is disclosed.
     */
    fun isPreparedSearch(intent: SearchIntent): Boolean =
        prepareSearch(intent.title, intent.artist, intent.album) == intent

    /**
     * MI-23 4.1: a SECOND, less restrictive search without `release` is offered in
     * the UI only after the strict search returned zero matches. It is a separate
     * request that requires its own explicit confirmation; it is never automatic
     * and it never sends more fields than [SearchIntent.outgoingFields] declares.
     */
    fun prepareRelaxedSearch(title: String, artist: String): SearchIntent? =
        prepareSearch(title, artist, album = "")

    fun relaxedSearch(intent: SearchIntent): SearchIntent? =
        if (intent.album.isEmpty()) null
        else prepareRelaxedSearch(intent.title, intent.artist)

    fun isRelaxedSearch(intent: SearchIntent): Boolean =
        isPreparedSearch(intent) && intent.album.isEmpty()

    /** Candidate is a suggestion, not an automatic identity binding. */
    fun plausibleCandidate(intent: SearchIntent, candidate: RecordingCandidate,
        localDurationMs: Long? = null): Boolean {
        if (canonicalMbid(candidate.mbid) == null) return false
        if (comparable(intent.title) != comparable(candidate.title)) return false
        if (comparable(intent.artist) != comparable(candidate.artist)) return false
        val duration = candidate.durationMs
        return localDurationMs == null || localDurationMs <= 0 || duration == null || duration <= 0 ||
            kotlin.math.abs(localDurationMs - duration) <= 5_000L
    }

    /** Even a strong match MUST be explicitly selected by the user. */
    fun approvedProposal(candidate: RecordingCandidate, proposal: GenreProposal,
        selectedRecordingMbid: String): Boolean {
        // Without this nonnull check, comparisons of three malformed MBIDs could
        // accidentally all be equal (null == null) and authorize a forged source.
        val selected = canonicalMbid(selectedRecordingMbid) ?: return false
        return canonicalMbid(candidate.mbid) == selected &&
            canonicalMbid(proposal.recordingMbid) == selected &&
            proposal.entity == Entity.RECORDING &&
            canonicalMbid(proposal.sourceMbid) == selected &&
            validateGenres(listOf(proposal.name)) != null &&
            (proposal.votes == null || proposal.votes >= 0)
    }

    /** Requires an explicit button, not the existence of a remote proposal. */
    fun decideWrite(existing: List<String>, selected: List<String>,
        selectedByUser: Boolean, replaceExistingConfirmed: Boolean,
        identityStillAuthorized: Boolean, writeGrantConfirmed: Boolean): Decision {
        if (!identityStillAuthorized) return Decision.Denied("IDENTITY_OR_SCOPE_CHANGED")
        if (!writeGrantConfirmed) return Decision.Denied("WRITE_PERMISSION_REQUIRED")
        if (!selectedByUser) return Decision.Denied("NO_USER_SELECTION")
        val next = validateGenres(selected) ?: return Decision.Denied("INVALID_GENRE")
        // Invalid existing tags are NOT evidence of absence; refuse replacing them blindly.
        val existingMeaningful = existing.filterNot(::isPlaceholderGenre)
        if (existingMeaningful.isNotEmpty() && !replaceExistingConfirmed)
            return Decision.Denied("REPLACE_EXISTING_GENRE_NEEDS_CONFIRMATION")
        return Decision.Allowed(next)
    }
}
