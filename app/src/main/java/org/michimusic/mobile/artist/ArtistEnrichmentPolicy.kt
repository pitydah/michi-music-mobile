package org.michimusic.mobile.artist

import kotlinx.serialization.Serializable

/**
 * The full artist credit is NOT an identifier. Multiple credited performers can be a single
 * textual field, and two unrelated performers can have the same display name.
 */
internal fun localArtistCredit(raw: String): String = raw.trim().ifBlank { "Artista desconocido" }

private val artistMbidPattern = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)

/** Returns a normalized MusicBrainz identifier only if it has UUID syntax. */
fun canonicalArtistMbid(value: String?): String? = value?.trim()?.takeIf {
    artistMbidPattern.matches(it)
}?.lowercase(java.util.Locale.ROOT)

data class ArtistLookupCandidate(
    val mbid: String,
    val name: String,
    val disambiguation: String = "",
    val type: String = "",
    val area: String = "",
)

/**
 * A name or provider search score never confirms identity. Only explicit candidate selection
 * or trusted embedded MBID metadata can create an association.
 */
fun selectedArtistMbid(
    returnedCandidates: List<ArtistLookupCandidate>,
    explicitlySelectedMbid: String?,
): String? {
    val id = canonicalArtistMbid(explicitlySelectedMbid) ?: return null
    return id.takeIf { mbid -> returnedCandidates.any { canonicalArtistMbid(it.mbid) == mbid } }
}

@Serializable
data class CommonsPortrait(
    val imageUrl: String,
    val commonsPageUrl: String,
    val license: String,
    val licenseUrl: String,
    val attribution: String,
)

/** Fail closed on licenses, absent attribution, non-Wikimedia images and insecure URLs. */
internal fun allowedPortraitLicense(license: String): Boolean {
    val allowed = Regex("^(?:CC BY-SA|CC BY|CC0)(?: [1-9]\\.\\d)?$|^Public domain$", RegexOption.IGNORE_CASE)
    return allowed.matches(license.trim())
}

internal fun allowedPortraitAttribution(attribution: String): Boolean =
    attribution.isNotBlank() && attribution.length <= 500

internal fun allowedPortraitUrls(portrait: CommonsPortrait): Boolean {
    if (!portrait.imageUrl.startsWith("https://upload.wikimedia.org/")) return false
    if (!portrait.commonsPageUrl.startsWith("https://commons.wikimedia.org/wiki/File:")) return false
    if (!portrait.licenseUrl.startsWith("https://creativecommons.org/") &&
        !portrait.licenseUrl.startsWith("https://commons.wikimedia.org/")) return false
    return true
}

fun allowedCommonsPortrait(portrait: CommonsPortrait): Boolean =
    allowedPortraitLicense(portrait.license) &&
        allowedPortraitAttribution(portrait.attribution) &&
        allowedPortraitUrls(portrait)

/** External biography links must remain on a genuine HTTPS Wikipedia article origin. */
fun allowedWikipediaSource(link: String?): Boolean {
    if (link == null) return true
    return try {
        val uri = java.net.URI(link)
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            uri.host?.matches(Regex("[a-z-]{2,12}\\.wikipedia\\.org")) == true &&
            uri.rawPath?.let { it.startsWith("/wiki/") && it.length > "/wiki/".length } == true
    } catch (_: java.net.URISyntaxException) { false }
}

/** Biography content is never presented as verified without a genuine source link. */
fun allowedArtistBiography(biography: String?, sourceUrl: String?): Boolean =
    if (biography.isNullOrBlank()) allowedWikipediaSource(sourceUrl)
    else !sourceUrl.isNullOrBlank() && allowedWikipediaSource(sourceUrl)

@Serializable
data class ArtistExternalProfile(
    val mbid: String,
    val name: String,
    // MusicBrainz omits these for many artists, and the repository already
    // normalises the missing values with orEmpty(). Defaulting them here keeps
    // every construction site (including tests and previews) honest about the
    // fields that are genuinely optional.
    val disambiguation: String = "",
    val type: String = "",
    val area: String = "",
    val biography: String? = null,
    val biographySourceUrl: String? = null,
    val portrait: CommonsPortrait? = null,
    // Optional providers are independent: a missing portrait must not hide a
    // verified biography (or the reverse). The outcome records why a field is
    // absent instead of collapsing every failure into a generic null.
    val biographyOutcome: ArtistMetadataOutcome? = null,
    val portraitOutcome: ArtistMetadataOutcome? = null,
    val wikidataQid: String? = null,
)

/**
 * Why an optional metadata stage produced, or failed to produce, a value.
 * Persisted profiles keep the diagnosis so the ficha can distinguish an
 * artist without data from a provider/network failure.
 */
@Serializable
enum class ArtistMetadataOutcome {
    SUCCESS,
    NO_IDENTITY,
    NO_SITELINK,
    NO_TEXT,
    NO_P18,
    NO_ALLOWED_LICENSE,
    BAD_ATTRIBUTION,
    HTTP_ERROR,
    TIMEOUT,
    NETWORK_ERROR,
    DECODE_ERROR,
    CACHE_ERROR,
}
