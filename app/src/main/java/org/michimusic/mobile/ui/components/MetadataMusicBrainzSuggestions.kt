package org.michimusic.mobile.ui.components

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.mobile.artist.ArtistEnrichmentService

/**
 * Explicitly requested recording suggestions. Existing ArtistEnrichmentRepository owns
 * the common HTTPS client, MusicBrainz User-Agent, request pacing and response bounds.
 * These are EXTERNAL PROPOSALS, never embedded evidence or permission to write tags.
 */
internal class MetadataMusicBrainzSuggestions(
    private val read: suspend (MichiGenreNormalizationPolicy.SearchIntent) -> JsonObject = {
        ArtistEnrichmentService.repository.genreSearchForExplicitAction(it)
    },
) {
    data class Choice(
        val recordingMbid: String,
        val releaseMbid: String?,
        val title: String,
        val artistCredit: String,
        val releaseTitle: String?,
        val releaseDate: String?,
        val score: Int?,
        val fields: Map<MetadataField, List<String>>,
    ) {
        val identity: String get() = "$recordingMbid/${releaseMbid.orEmpty()}"
    }

    suspend fun search(intent: MichiGenreNormalizationPolicy.SearchIntent): List<Choice> {
        require(MichiGenreNormalizationPolicy.isPreparedSearch(intent)) { "Invalid external search" }
        return parse(read(intent))
    }

    companion object {
        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        private fun uuid(s: String?): String? =
            MichiGenreNormalizationPolicy.canonicalMbid(s.orEmpty())
        private fun safe(field: MetadataField, value: String?): List<String>? =
            value?.takeIf { it == it.trim() && it.isNotBlank() &&
                it.length <= field.maxChars && it.none { c -> c.isISOControl() } }?.let(::listOf)
        private fun credit(row: JsonObject): Pair<String, List<String>>? {
            val entries = row["artist-credit"] as? JsonArray ?: return null
            if (entries.isEmpty() || entries.size > 16) return null
            val ids = mutableListOf<String>()
            val name = buildString {
                for (entry in entries) when (entry) {
                    is JsonObject -> {
                        val named = entry.str("name") ?: (entry["artist"] as? JsonObject)?.str("name")
                            ?: return null
                        append(named)
                        append(entry.str("joinphrase").orEmpty())
                        val mbid = (entry["artist"] as? JsonObject)?.str("id")?.let(::uuid)
                        if (mbid != null) ids += mbid
                    }
                    is JsonPrimitive -> append(entry.contentOrNull ?: return null)
                    else -> return null
                }
            }.trim()
            if (safe(MetadataField.ARTIST, name) == null) return null
            return name to ids.distinct()
        }
        /** Pure bounded parsing, no implicit artist merging or genre inference. */
        fun parse(json: JsonObject): List<Choice> {
            val rows = json["recordings"] as? JsonArray ?: return emptyList()
            if (rows.size > 8) return emptyList() // No concealed partial processing
            val results = mutableListOf<Choice>()
            for (node in rows) {
                val row = node as? JsonObject ?: continue
                val recId = uuid(row.str("id")) ?: continue
                val title = row.str("title")?.takeIf { safe(MetadataField.TITLE, it) != null } ?: continue
                val (artist, artistIds) = credit(row) ?: continue
                val score = row.str("score")?.toIntOrNull()?.takeIf { it in 0..100 }
                val releases = (row["releases"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                // A recording may be on many editions; never guess which release is owned.
                val candidates: List<JsonObject?> = if (releases.isEmpty()) listOf(null)
                    else releases.take(5)
                for (release in candidates) {
                    val releaseId = release?.str("id")?.let(::uuid)
                    val releaseName = release?.str("title")?.takeIf { safe(MetadataField.ALBUM, it) != null }
                    val date = release?.str("date")?.takeIf { it.matches(Regex("\\d{4}(-\\d{2}(-\\d{2})?)?")) }
                    val groupId = (release?.get("release-group") as? JsonObject)?.str("id")?.let(::uuid)
                    val fieldMap = linkedMapOf<MetadataField, List<String>>()
                    fieldMap[MetadataField.TITLE] = listOf(title)
                    fieldMap[MetadataField.ARTIST] = listOf(artist)
                    fieldMap[MetadataField.MUSICBRAINZ_RECORDING_ID] = listOf(recId)
                    if (artistIds.isNotEmpty()) fieldMap[MetadataField.MUSICBRAINZ_ARTIST_ID] = artistIds
                    if (releaseId != null && releaseName != null) {
                        fieldMap[MetadataField.ALBUM] = listOf(releaseName)
                        fieldMap[MetadataField.MUSICBRAINZ_RELEASE_ID] = listOf(releaseId)
                        if (groupId != null) fieldMap[MetadataField.MUSICBRAINZ_RELEASE_GROUP_ID] = listOf(groupId)
                        if (date != null) fieldMap[MetadataField.RELEASE_DATE] = listOf(date)
                    }
                    results += Choice(recId, releaseId, title, artist, releaseName, date, score, fieldMap)
                }
            }
            return results.distinctBy(Choice::identity).take(24)
        }

        /** Candidate is never itself evidence of absence or of a safe write capability. */
        fun reviewableFields(
            snapshot: MetadataSnapshot,
            candidate: Choice,
        ): Map<MetadataField, List<String>> = candidate.fields.filter { (field, values) ->
            field in snapshot.verifiedFields && snapshot.tagsVerified &&
                values.isNotEmpty() && values.size <= (if (field.multiple) 32 else 1) &&
                values.all { safe(field, it) != null } &&
                snapshot.fieldObservations[field].let {
                    it == null || it is org.michimusic.core.metadata.MetadataFieldObservation.Present ||
                        it == org.michimusic.core.metadata.MetadataFieldObservation.VerifiedAbsent
                }
        }
    }
}
