package org.michimusic.mobile.artist

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Release groups identify musical works, never the physical edition (Release MBID). */
data class AlbumHistoryCandidate(
    val mbid: String,
    val title: String,
    val creditedArtist: String,
    val firstReleaseDate: String?,
    val disambiguation: String,
)

@Serializable
data class AlbumHistoricalProfile(
    val mbid: String,
    val title: String,
    val firstReleaseDate: String?,
    val disambiguation: String,
    val description: String?,
    val wikipediaUrl: String?,
) {
    val musicBrainzUrl: String get() = "https://musicbrainz.org/release-group/$mbid"
}

internal fun canonicalAlbumMbid(raw: String?): String? = raw?.lowercase(java.util.Locale.ROOT)
    ?.takeIf { it.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) }

interface AlbumEnrichmentGateway {
    suspend fun findAlbumCandidates(title: String, artistCredit: String): List<AlbumHistoryCandidate>
    suspend fun albumHistory(confirmedMbid: String): AlbumHistoricalProfile
}

/** A remote candidate cannot be auto-selected from a fuzzy result. */
internal fun albumCandidateMatchesLocal(
    title: String,
    artistCredit: String,
    candidate: AlbumHistoryCandidate,
): Boolean {
    return normalizeAlbumCredit(candidate.title) == normalizeAlbumCredit(title) &&
        normalizeAlbumCredit(candidate.creditedArtist) == normalizeAlbumCredit(artistCredit)
}

private fun normalizeAlbumCredit(raw: String): String =
    java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)

/** A scoped *local* album identity; never uses the artist enrichment cache key. */
internal fun albumEnrichmentScopeKey(
    libraryId: String?, localAlbumId: String, localTrackIds: List<String>,
    localTitle: String, localArtistCredit: String,
): String? {
    val lib = libraryId?.trim()?.takeIf { it.isNotEmpty() && it != "SCOPE_ALL" } ?: return null
    if (localAlbumId.isBlank() || localTrackIds.isEmpty() || localTrackIds.any { it.isBlank() }) return null
    // Tag edits must not retain an earlier release-group confirmation for the same file IDs.
    val title = normalizeAlbumCredit(localTitle).takeIf { it.isNotBlank() } ?: return null
    val artist = normalizeAlbumCredit(localArtistCredit).takeIf { it.isNotBlank() } ?: return null
    val members = localTrackIds.toSortedSet().joinToString("\u0000")
    val scope = listOf("album-v2", lib, localAlbumId, title, artist, members).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Escape Lucene syntax without altering the local title used for identity matching. */
internal fun musicBrainzAlbumQuery(localTitle: String, artistCredit: String): String {
    fun escapeTerm(raw: String): String = buildString {
        for (char in raw) {
            if (char in "\\+-!(){}[]^\"~*?:/&|") append('\\')
            append(char)
        }
    }
    return "releasegroup:\"${escapeTerm(localTitle)}\" AND artist:\"${escapeTerm(artistCredit)}\""
}

/** Only an explicitly selected candidate may provide a persistent remote association. */
internal fun selectedReleaseGroupMbid(
    candidates: List<AlbumHistoryCandidate>, chosenId: String?,
): String? = canonicalAlbumMbid(chosenId)?.takeIf { id ->
    candidates.any { canonicalAlbumMbid(it.mbid) == id }
}

/** An ephemeral UI identity even when an ALL/mixed scope forbids persistent association.
 * Never use a null cache key as a Compose state key shared by homonymous albums. */
internal fun albumEnrichmentPanelKey(
    libraryId: String?, localAlbumId: String, localTrackIds: List<String>,
    localTitle: String, localArtist: String,
): String {
    val fields = listOf(
        "album-panel-v1", libraryId.orEmpty().trim(), localAlbumId,
        normalizeAlbumCredit(localTitle), normalizeAlbumCredit(localArtist),
    ) + localTrackIds.sorted()
    val material = fields.joinToString("\u0000") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Distinct public facade: reuses the SAME MusicBrainz gateway and rate limiter. */
object AlbumEnrichmentService : AlbumEnrichmentGateway {
    override suspend fun findAlbumCandidates(title: String, artistCredit: String) =
        ArtistEnrichmentService.repository.findAlbumCandidates(title, artistCredit)

    override suspend fun albumHistory(confirmedMbid: String) =
        ArtistEnrichmentService.repository.albumHistory(confirmedMbid)
}
