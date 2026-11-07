package org.michimusic.mobile.artist.artwork

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.michimusic.mobile.artist.allowedPortraitAttribution
import org.michimusic.mobile.artist.allowedPortraitLicense
import java.net.URI

private val qidPattern = Regex("^Q[1-9][0-9]{0,10}$")
private val allowedMime = setOf("image/jpeg", "image/png", "image/webp")
private val jsonLdScript = Regex(
    "<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val jsonLd = Json { ignoreUnknownKeys = true }

/**
 * Provider metadata is untrusted until every one of these gates passes.
 * The identity anchor, not the candidate display name, decides whose image this is.
 */
fun allowedArtworkCandidate(
    candidate: ArtistArtworkCandidate,
    anchor: ArtistIdentityAnchor,
): Boolean {
    if (candidate.confirmedMbid != anchor.confirmedMbid) return false
    if (candidate.verifiedQid != null && candidate.verifiedQid != anchor.verifiedQid) return false
    if (candidate.stableAssetId.isBlank() || candidate.stableAssetId.length > 220) return false
    if (candidate.mimeType?.lowercase() !in allowedMime) return false
    val width = candidate.originalWidth ?: return false
    val height = candidate.originalHeight ?: return false
    if (width < 320 || height < 320 || width.toLong() * height > 20_000_000L) return false
    if (candidate.rights == ArtworkRights.REJECTED) return false
    if (!allowedArtworkImageUrl(candidate.source, candidate.imageUrl)) return false
    if (!allowedArtworkSourcePage(candidate.source, candidate.canonicalSourcePage)) return false
    if (!allowedArtworkLicenseUrl(candidate.licenseUrl)) return false
    if (!allowedPortraitLicense(candidate.licenseId.orEmpty())) return false
    if (!allowedPortraitAttribution(candidate.attribution.orEmpty())) return false
    if (!allowedArtworkEvidence(candidate)) return false
    return true
}

fun allowedArtworkImageUrl(source: ArtworkSource, raw: String?): Boolean {
    val uri = httpsUri(raw) ?: return false
    if (uri.rawUserInfo != null || uri.port != -1) return false
    val host = uri.host ?: return false
    if (isIpLiteral(host)) return false
    return when (source) {
        ArtworkSource.COMMONS_P18,
        ArtworkSource.COMMONS_P180,
        ArtworkSource.WIKIPEDIA_PAGEIMAGE,
        ArtworkSource.COMMONS_CATEGORY,
        -> host == "upload.wikimedia.org" || host == "thumb.wikimedia.org"

        ArtworkSource.OPENVERSE ->
            host == "live.staticflickr.com" || host == "staticflickr.com"

        ArtworkSource.THEAUDIODB,
        ArtworkSource.FANART_TV,
        -> false
    }
}

fun allowedArtworkSourcePage(source: ArtworkSource, raw: String?): Boolean {
    val uri = httpsUri(raw) ?: return false
    if (uri.rawUserInfo != null || uri.port != -1) return false
    val host = uri.host ?: return false
    return when (source) {
        ArtworkSource.COMMONS_P18,
        ArtworkSource.COMMONS_P180,
        ArtworkSource.WIKIPEDIA_PAGEIMAGE,
        ArtworkSource.COMMONS_CATEGORY,
        -> host == "commons.wikimedia.org" &&
            uri.rawPath?.startsWith("/wiki/File:") == true

        ArtworkSource.OPENVERSE ->
            host == "www.flickr.com" && uri.rawPath?.startsWith("/photos/") == true

        ArtworkSource.THEAUDIODB,
        ArtworkSource.FANART_TV,
        -> false
    }
}

fun allowedArtworkLicenseUrl(raw: String?): Boolean {
    val uri = httpsUri(raw) ?: return false
    if (uri.rawUserInfo != null || uri.port != -1) return false
    val host = uri.host ?: return false
    return host == "creativecommons.org" || host == "commons.wikimedia.org"
}

/** Only QID-linked or explicitly user-selected evidence may become a selection. */
fun allowedArtworkEvidence(candidate: ArtistArtworkCandidate): Boolean = when (candidate.evidence) {
    ArtworkEvidence.VERIFIED_QID_REFERENCE ->
        candidate.verifiedQid != null && qidPattern.matches(candidate.verifiedQid) &&
            (candidate.source == ArtworkSource.COMMONS_P18 ||
                candidate.source == ArtworkSource.COMMONS_P180 ||
                candidate.source == ArtworkSource.COMMONS_CATEGORY)

    ArtworkEvidence.VERIFIED_ARTICLE_LINK ->
        candidate.verifiedQid != null && qidPattern.matches(candidate.verifiedQid) &&
            candidate.source == ArtworkSource.WIKIPEDIA_PAGEIMAGE

    ArtworkEvidence.USER_SELECTED -> candidate.source == ArtworkSource.OPENVERSE
    ArtworkEvidence.UNVERIFIED -> false
}

/** Openverse origin check: the page must state the exact CC licence returned by the index. */
fun originPageConfirmsLicense(
    originPageUrl: String?,
    expectedLicenseUrl: String?,
    html: String,
): Boolean {
    val origin = httpsUri(originPageUrl) ?: return false
    if (origin.host != "www.flickr.com" || !origin.rawPath.orEmpty().startsWith("/photos/")) return false
    val expected = licenseKey(expectedLicenseUrl) ?: return false
    if (html.contains("<!DOCTYPE", ignoreCase = true).not() && html.contains("<html", ignoreCase = true).not()) {
        return false
    }
    // A licence link anywhere on Flickr is not evidence for this photo. Require the
    // licence and the photo id to belong to the same schema.org ImageObject emitted
    // for this exact page. Parsing JSON-LD avoids accepting unrelated license links.
    val segments = origin.rawPath.orEmpty().trim('/').split('/')
    if (segments.size < 3) return false
    val photoId = segments[2]
    if (photoId.isBlank() || !photoId.all(Char::isDigit)) return false
    return jsonLdScript.findAll(html).any { block ->
        val root = runCatching {
            jsonLd.parseToJsonElement(block.groupValues[1])
        }.getOrNull() ?: return@any false
        root.hasImageObjectFor(photoId, expected)
    }
}

private fun JsonElement.hasImageObjectFor(photoId: String, expectedLicense: String): Boolean =
    when (this) {
        is JsonObject -> {
            val type = this["@type"]?.stringValue()
            val license = this["license"]?.stringValue()
            val acquire = this["acquireLicensePage"]?.stringValue()
            val content = this["contentUrl"]?.stringValue()
            // Bind the licence to the canonical photo page; CDN contentUrl may omit it.
            // Matching a substring would accept `519041923250` for photo `51904192325`.
            val sameImage = acquire?.let { flickrPageHasExactPhoto(it, photoId) }
                ?: content?.let { flickrContentHasExactPhoto(it, photoId) }
                ?: false
            (type == "ImageObject" && sameImage && license?.let(::licenseKey) == expectedLicense) ||
                values.any { it.hasImageObjectFor(photoId, expectedLicense) }
        }

        is JsonArray -> any { it.hasImageObjectFor(photoId, expectedLicense) }
        else -> false
    }

private fun flickrPageHasExactPhoto(raw: String, photoId: String): Boolean {
    val uri = httpsUri(raw) ?: return false
    if (uri.host != "www.flickr.com" && uri.host != "flickr.com") return false
    val segments = uri.rawPath.orEmpty().trim('/').split('/').filter(String::isNotBlank)
    return segments.lastOrNull() == photoId
}

private fun flickrContentHasExactPhoto(raw: String, photoId: String): Boolean {
    val uri = httpsUri(raw) ?: return false
    if (uri.host != "live.staticflickr.com" && uri.host != "staticflickr.com") return false
    val file = uri.rawPath.orEmpty().trim('/').substringAfterLast('/')
    return Regex(
        "^${Regex.escape(photoId)}(?:_[A-Za-z0-9]+)*\\.(?:jpe?g|png|webp)$",
        RegexOption.IGNORE_CASE,
    ).matches(file)
}

private fun JsonElement.stringValue(): String? =
    (this as? JsonPrimitive)?.contentOrNull

fun licenseKey(raw: String?): String? {
    val uri = httpsUri(raw) ?: return null
    if (uri.host != "creativecommons.org") return null
    val parts = uri.rawPath.orEmpty().trim('/').split('/')
    if (parts.size < 2) return null
    return licenseKeyFromParts(parts[0], parts[1], parts.getOrNull(2))
}

private fun licenseKeyFromParts(family: String, kind: String, version: String?): String? {
    val base = when {
        family == "publicdomain" && kind == "mark" -> "publicdomain"
        family == "publicdomain" && kind == "zero" -> "cc0"
        family == "licenses" && kind in setOf("by", "by-sa") -> kind
        else -> return null
    }
    val cleanVersion = version?.takeIf { Regex("^[0-9]+(?:\\.[0-9]+)?$").matches(it) }
    return if (base == "publicdomain") base else "$base-${cleanVersion ?: "1.0"}"
}

/** Best-effort defensive check before OkHttp resolves a name. */
fun isIpLiteral(host: String): Boolean {
    if (host.startsWith("[") || host.endsWith("]")) return true
    return Regex("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}$").matches(host)
}

fun isPrivateOrLoopbackAddress(host: String): Boolean {
    val addresses = runCatching { java.net.InetAddress.getAllByName(host).toList() }.getOrNull()
        ?: return true
    return addresses.isEmpty() || addresses.any { address ->
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
    }
}

private fun httpsUri(raw: String?): URI? {
    if (raw.isNullOrBlank() || raw.length > 2_000) return null
    return try {
        URI(raw).takeIf {
            it.scheme == "https" && it.host != null && it.rawUserInfo == null &&
                !isIpLiteral(it.host)
        }
    } catch (_: java.net.URISyntaxException) {
        null
    }
}

/** Canonical licence string used by the existing Commons portrait gate. */
fun artworkLicenseLabel(licenseId: String?, licenseUrl: String?): String? {
    val raw = licenseId?.trim().orEmpty()
    if (raw.isNotEmpty() && allowedPortraitLicense(raw)) return raw
    val key = licenseKey(licenseUrl) ?: return null
    return when {
        key == "publicdomain" -> "Public domain"
        key.startsWith("cc0") -> "CC0"
        key.startsWith("by-sa-") -> "CC BY-SA ${key.removePrefix("by-sa-")}"
        key.startsWith("by-") -> "CC BY ${key.removePrefix("by-")}"
        else -> null
    }?.takeIf { allowedPortraitLicense(it) }
}
