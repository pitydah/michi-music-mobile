package org.michimusic.mobile.artist

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlinx.serialization.json.contentOrNull

/** Read only the structured MBIDs from bounded MusicBrainz recording/release lookups. */
internal object MusicBrainzCreditJson {
    private val uuid = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private fun JsonObject.id(): String? = (this["id"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf(uuid::matches)?.lowercase(Locale.ROOT)

    /** MusicBrainz interleaves join phrases (JSON strings) with artist objects.
     * Never truncate a credit array into an apparently solo artist. Reject malformed
     * objects, duplicate artist entries, absent credits, or an unbounded credit list.
     */
    private fun artistCredits(root: JsonObject): Set<String>? {
        val credits = root["artist-credit"] as? JsonArray ?: return null
        if (credits.isEmpty() || credits.size > 48) return null
        val ids = LinkedHashSet<String>()
        var artistEntries = 0
        for ((index, entry) in credits.withIndex()) {
            when (entry) {
                is JsonPrimitive -> {
                    // Join phrases are separators BETWEEN two credited artist objects.
                    // A leading/trailing/doubled separator could hide a missing
                    // collaborator and falsely produce a solo verified identity.
                    if (!entry.isString || entry.content.length > 160 ||
                        index == 0 || index == credits.lastIndex ||
                        credits[index - 1] !is JsonObject || credits[index + 1] !is JsonObject
                    ) return null
                }
                is JsonObject -> {
                    // MusicBrainz also represents joinphrase on the artist entry.
                    // A trailing nonempty joinphrase without the next artist is
                    // a truncated collaboration and cannot be accepted as solo.
                    if ("joinphrase" in entry) {
                        val join = entry["joinphrase"] as? JsonPrimitive ?: return null
                        if (!join.isString || join.content.length > 160 ||
                            (join.content.isNotEmpty() && index == credits.lastIndex)
                        ) return null
                    }
                    artistEntries++
                    if (artistEntries > 16) return null
                    val id = (entry["artist"] as? JsonObject)?.id() ?: return null
                    if (!ids.add(id)) return null // duplicates hide uncertain joint-credit shape
                }
                else -> return null
            }
        }
        return ids.takeIf { it.isNotEmpty() }
    }

    fun recording(response: JsonObject): MusicBrainzRecordingCredit? {
        val id = response.id() ?: return null
        return MusicBrainzRecordingCredit(id, artistCredits(response) ?: return null)
    }

    fun release(response: JsonObject): MusicBrainzReleaseCredit? {
        val id = response.id() ?: return null
        val artists = artistCredits(response) ?: return null
        val medias = response["media"] as? JsonArray ?: return null
        if (medias.isEmpty() || medias.size > 25) return null // oversized response: fail closed
        val recordings = LinkedHashSet<String>()
        val trackCredits = LinkedHashMap<String, Set<String>>()
        var examined = 0
        for (entry in medias) {
            val medium = entry as? JsonObject ?: return null
            val tracks = medium["tracks"] as? JsonArray ?: return null
            examined += tracks.size
            if (examined > 512) return null
            for (track in tracks) {
                val recording = (track as? JsonObject)?.get("recording") as? JsonObject ?: return null
                val trackMbid = recording.id() ?: return null
                recordings.add(trackMbid)
                // Track artist-credit can differ from the recording artist-credit.
                // When present, malformed or contradictory release credits invalidate
                // the entire proof instead of pretending they are absent.
                val trackObject = track
                if ("artist-credit" in trackObject) {
                    val parsed = artistCredits(trackObject) ?: return null
                    val prior = trackCredits.putIfAbsent(trackMbid, parsed)
                    if (prior != null && prior != parsed) return null
                }
            }
        }
        if (recordings.isEmpty()) return null
        return MusicBrainzReleaseCredit(id, artists, recordings, trackCredits)
    }
}
