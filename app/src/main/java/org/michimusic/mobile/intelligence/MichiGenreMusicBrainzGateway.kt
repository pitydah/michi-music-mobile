package org.michimusic.mobile.intelligence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy
import org.michimusic.core.intelligence.MichiGenreLookupAuthorization
import org.michimusic.mobile.artist.ArtistEnrichmentService

/**
 * MI-23 only handles the result of a user-confirmed, single-track HTTP lookup.
 * Reuses ArtistEnrichmentService.repository: same OkHttp, allowed host, User-Agent,
 * 1100ms mutex, cancellation, response bounds and retry policy. No new network engine.
 * Caller MUST present SearchIntent.outgoingFields and collect a one-shot consent.
 */
class MichiGenreMusicBrainzGateway(
    private val read: GenreMusicBrainzReadPort = SharedArtistGenrePort,
    private val authorization: MichiGenreLookupAuthorization = MichiGenreLookupAuthorization(),
) {
    interface GenreMusicBrainzReadPort {
        suspend fun search(intent: MichiGenreNormalizationPolicy.SearchIntent): JsonObject
        suspend fun recording(id: String): JsonObject
    }
    private object SharedArtistGenrePort : GenreMusicBrainzReadPort {
        override suspend fun search(intent: MichiGenreNormalizationPolicy.SearchIntent): JsonObject =
            ArtistEnrichmentService.repository.genreSearchForExplicitAction(intent)
        override suspend fun recording(id: String): JsonObject =
            ArtistEnrichmentService.repository.genreRecordingForExplicitAction(id)
    }
    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    data class Choice(
        val recording: MichiGenreNormalizationPolicy.RecordingCandidate,
        val albumPreview: String,
        val plausible: Boolean,
    )

    suspend fun search(intent: MichiGenreNormalizationPolicy.SearchIntent,
        permit: MichiGenreLookupAuthorization.Permit?, durationMs: Long? = null): List<Choice> {
        // The exact one-shot payload must be consumed BEFORE the first byte of HTTP.
        if (!authorization.consumeSearch(permit, intent)) return emptyList() // no HTTP
        val response = read.search(intent)
        val rows = response["recordings"] as? JsonArray ?: return emptyList()
        // Never publish a silently truncated or partially malformed provider result.
        if (rows.size > 8) return emptyList()
        return rows.mapNotNull { value ->
            val row = value as? JsonObject ?: return@mapNotNull null
            val id = MichiGenreNormalizationPolicy.canonicalMbid(row.str("id").orEmpty())
                ?: return@mapNotNull null
            val title = row.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val credits = (row["artist-credit"] as? JsonArray).orEmpty()
            val firstCredit = credits.firstOrNull { it is JsonObject } as? JsonObject
            val firstArtist = firstCredit?.get("artist") as? JsonObject
            val artist = firstCredit?.str("name") ?: firstArtist?.str("name")
                ?: return@mapNotNull null
            // Multiple credited artists must be reviewed; never silently bind a guest credit.
            // MusicBrainz can interleave artist-credit objects with join phrases.
            // A single object plus a trailing text token is NOT a solo credit.
            val ambiguousCredit = credits.size != 1 || credits.singleOrNull() !is JsonObject
            val candidate = MichiGenreNormalizationPolicy.RecordingCandidate(
                id, title, artist, row.str("length")?.toLongOrNull(), row.str("disambiguation").orEmpty())
            val album = ((row["releases"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.str("title").orEmpty()
            Choice(candidate, album, !ambiguousCredit &&
                MichiGenreNormalizationPolicy.plausibleCandidate(intent,candidate,durationMs))
        }.distinctBy { it.recording.mbid }
    }

    /** Never use this method without user selection of the recording. */
    suspend fun genresForSelectedRecording(
        selected: MichiGenreNormalizationPolicy.RecordingCandidate,
        permit: MichiGenreLookupAuthorization.Permit?,
    ): List<MichiGenreNormalizationPolicy.GenreProposal> {
        val id = MichiGenreNormalizationPolicy.canonicalMbid(selected.mbid)
        if (id == null || !authorization.consumeRecording(permit, id)) return emptyList()
        val response = read.recording(id)
        // A provider response about another ID is not the selected recording.
        if (MichiGenreNormalizationPolicy.canonicalMbid(response.str("id").orEmpty()) != id)
            return emptyList()
        val items = response["genres"] as? JsonArray ?: return emptyList()
        // If the provider advertises more genres than our privacy/UI budget,
        // rejecting is safer than concealing a partial selection as exhaustive.
        if (items.size > 32) return emptyList()
        val unique = LinkedHashMap<String, MichiGenreNormalizationPolicy.GenreProposal>()
        for (raw in items) {
            val obj = raw as? JsonObject ?: return emptyList()
            val name = obj.str("name") ?: return emptyList()
            if (MichiGenreNormalizationPolicy.validateGenres(listOf(name)) == null) continue
            val countPresent = "count" in obj
            val votes = obj.str("count")?.toIntOrNull()
            if (countPresent && (votes == null || votes < 0)) return emptyList()
            val proposal = MichiGenreNormalizationPolicy.GenreProposal(
                name = name, entity = MichiGenreNormalizationPolicy.Entity.RECORDING,
                sourceMbid = id, votes = votes, recordingMbid = id,
            )
            val key = MichiGenreDisambiguationPolicy.key(name)
            if (key.isBlank()) continue
            val prior = unique[key]
            if (prior == null || (votes ?: -1) > (prior.votes ?: -1))
                unique[key] = proposal
        }
        return unique.values.toList()
    }
}
