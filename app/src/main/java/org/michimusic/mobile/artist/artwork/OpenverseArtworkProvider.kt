package org.michimusic.mobile.artist.artwork

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.michimusic.mobile.artist.allowedPortraitAttribution
import org.michimusic.mobile.artist.allowedPortraitLicense

/**
 * Manual discovery only. Results never enter the automatic ranking and never download
 * before the user chooses one and the Flickr origin page confirms the licence.
 */
class OpenverseArtworkProvider(private val http: ArtworkHttp) {
    suspend fun search(anchor: ArtistIdentityAnchor, query: String): ProviderArtworkResult {
        val clean = query.trim().replace(Regex("[\\r\\n]"), " ").take(120)
        if (clean.isBlank()) return ProviderArtworkResult.NoMatch
        return try {
            val endpoint = requireNotNull("https://api.openverse.org/v1/images/".toHttpUrlOrNull()).newBuilder()
                .addQueryParameter("q", clean)
                .addQueryParameter("page_size", "12")
                .addQueryParameter("mature", "false")
                .build()
            val response = http.readJson(endpoint.toString(), setOf("api.openverse.org"))
            val items = (response["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val candidates = items.mapNotNull { item ->
                val id = item.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val origin = item.string("foreign_landing_url") ?: return@mapNotNull null
                val image = item.string("url") ?: return@mapNotNull null
                val license = item.string("license")?.lowercase() ?: return@mapNotNull null
                val version = item.string("license_version")
                val licenseUrl = item.string("license_url") ?: return@mapNotNull null
                val label = openverseLicenseLabel(license, version, licenseUrl) ?: return@mapNotNull null
                val mime = when (item.string("filetype")?.lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> item.string("mime_type")?.lowercase()
                } ?: return@mapNotNull null
                val width = item.string("width")?.toIntOrNull() ?: 0
                val height = item.string("height")?.toIntOrNull() ?: 0
                val creator = item.string("creator")?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val title = item.string("title")?.trim().orEmpty()
                val candidate = ArtistArtworkCandidate(
                    source = ArtworkSource.OPENVERSE,
                    stableAssetId = "openverse:$id",
                    confirmedMbid = anchor.confirmedMbid,
                    verifiedQid = anchor.verifiedQid,
                    canonicalSourcePage = origin,
                    imageUrl = image,
                    originalWidth = width,
                    originalHeight = height,
                    mimeType = mime,
                    licenseId = label,
                    licenseUrl = licenseUrl,
                    author = creator.take(300),
                    attribution = "$creator · $label${if (title.isBlank()) "" else " · $title"}".take(500),
                    evidence = ArtworkEvidence.UNVERIFIED,
                    rights = ArtworkRights.REQUIRES_REVIEW,
                )
                candidate.takeIf {
                    allowedArtworkImageUrl(it.source, it.imageUrl) &&
                        allowedArtworkSourcePage(it.source, it.canonicalSourcePage) &&
                        allowedArtworkLicenseUrl(it.licenseUrl) &&
                        allowedPortraitLicense(it.licenseId.orEmpty()) &&
                        allowedPortraitAttribution(it.attribution.orEmpty())
                }
            }
            if (candidates.isEmpty()) ProviderArtworkResult.NoMatch
            else ProviderArtworkResult.Available(candidates)
        } catch (error: Exception) {
            failure(error)
        }
    }

    private fun openverseLicenseLabel(license: String, version: String?, url: String): String? {
        val key = licenseKey(url)
        val candidate = when {
            license == "pdm" || key == "publicdomain" -> "Public domain"
            license == "cc0" || key?.startsWith("cc0") == true -> "CC0"
            license == "by" -> "CC BY ${version.orEmpty().ifBlank { "1.0" }}"
            license == "by-sa" -> "CC BY-SA ${version.orEmpty().ifBlank { "1.0" }}"
            else -> null
        } ?: return null
        return candidate.takeIf { allowedPortraitLicense(it) }
    }

    private fun JsonObject.string(field: String): String? =
        (get(field) as? JsonPrimitive)?.contentOrNull
}

/** Compiled adapters remain inert until an explicit release enables keys and network calls. */
class DisabledArtworkProvider(
    override val source: ArtworkSource,
    private val reason: String,
) : ArtistArtworkProvider {
    override suspend fun findCandidates(anchor: ArtistIdentityAnchor, slot: ArtworkSlot): ProviderArtworkResult =
        ProviderArtworkResult.Disabled(reason)
}

object ArtworkFeatureFlags {
    const val THEAUDIODB_ENABLED = false
    const val FANART_ENABLED = false
}
