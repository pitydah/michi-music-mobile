package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * fanart.tv publishes artist thumbnails and backgrounds submitted by its contributors
 * and, like TheAudioDB, states no licence and no author for any of them. Every image
 * this provider returns is therefore `REQUIRES_REVIEW` / `UNVERIFIED`, and a chosen one
 * keeps `ArtworkRights.UNDECLARED_LICENSE` for ever.
 *
 * fanart.tv is also keyed by MusicBrainz id, not by name, so it cannot answer for a
 * homonym: without a confirmed MBID this provider does no work at all. That is a
 * stricter gate than the name-matching catalogue next door and the reason this one can
 * never invent an identity.
 *
 * ## The terms this class is written against
 *
 * fanart.tv grants API access on explicit conditions, and each one is honoured here
 * rather than in a policy document nobody reads:
 *
 * - *Inform your users of this website and the images you use.* The picker names
 *   fanart.tv in its disclosure and the artwork slot says the licence is undeclared.
 * - *Allow your users to input their own API key, sent in addition to your project
 *   key.* [projectKey] is the build's key and [personalKey] is the person's own; when
 *   present the second header (`client-key`) travels next to the first (`api-key`),
 *   never instead of it.
 * - *Do not use our API methods embedded in your own API for third party use.* The
 *   device calls fanart.tv directly; nothing is proxied through a Michi server.
 * - *Do not use our API for commercial use without written consent.* Michi Music
 *   Mobile is GPL-3.0 and ships no paid tier.
 * - *Do not perform more requests than are necessary... no downloading all of our
 *   content.* One request per explicit search, only for this artist, and at most
 *   [MAX_PER_KIND] images of each kind are turned into candidates. There is no bulk
 *   crawl and no background walk over the library.
 * - *Do not directly access our data without using the documented API methods.* Only
 *   `GET /v3.2/music/{mbid}` is used; no HTML is scraped.
 */
internal class FanartTvProvider(
    private val http: ArtworkHttp,
    private val keys: suspend () -> Keys = {
        Keys(project = ArtworkFeatureFlags.fanartProjectKey, personal = null)
    },
) {
    /** The project key identifies the app; the personal key belongs to the person. */
    data class Keys(val project: String?, val personal: String?)

    suspend fun search(anchor: ArtistIdentityAnchor): ProviderArtworkResult {
        val mbid = anchor.confirmedMbid.trim().lowercase().takeIf(MBID::matches)
            ?: return ProviderArtworkResult.NoMatch
        // Reading the encrypted store touches the keystore, so it happens inside the
        // suspending call the caller already runs off the main thread.
        val stored = keys()
        val project = stored.project?.trim()?.takeIf(String::isNotEmpty)
            ?: return ProviderArtworkResult.Disabled("Falta la clave del proyecto de fanart.tv")
        return try {
            val headers = buildMap {
                put("api-key", project)
                stored.personal?.trim()?.takeIf(String::isNotEmpty)?.let { put("client-key", it) }
            }
            val response = http.readJson("$API_URL$mbid", HOSTS, headers)
            val candidates = candidatesFor(response, anchor, mbid)
            if (candidates.isEmpty()) ProviderArtworkResult.NoMatch
            else ProviderArtworkResult.Available(candidates)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unknown: ArtworkHttpException) {
            // An artist nobody uploaded art for answers 404; that is an empty catalogue,
            // not a provider failure, and saying otherwise would invite a pointless retry.
            if (unknown.status == 404) ProviderArtworkResult.NoMatch else failure(unknown)
        } catch (error: Exception) {
            failure(error)
        }
    }

    private fun candidatesFor(
        response: JsonObject,
        anchor: ArtistIdentityAnchor,
        mbid: String,
    ): List<ArtistArtworkCandidate> {
        val page = "$ARTIST_PAGE$mbid"
        val thumbs = response.objects("artistthumb").take(MAX_PER_KIND)
        val backgrounds = response.objects("artistbackground").take(MAX_PER_KIND)
        return (thumbs + backgrounds).distinctBy { it.string("id") ?: it.string("url") }
            .mapNotNull { image ->
                val raw = image.string("url")?.trim().orEmpty()
                val url = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
                val mime = mimeOf(url) ?: return@mapNotNull null
                val id = image.string("id")?.takeIf(String::isNotBlank)
                    ?: url.substringAfterLast('/').substringBefore('.')
                ArtistArtworkCandidate(
                    source = ArtworkSource.FANART_TV,
                    stableAssetId = "fanart:$mbid:$id".take(220),
                    confirmedMbid = mbid,
                    verifiedQid = anchor.verifiedQid,
                    canonicalSourcePage = page,
                    imageUrl = url,
                    // v3.2 states the full-size dimensions; they are still only trusted
                    // after the download, exactly like every other provider here.
                    originalWidth = image.string("width")?.toIntOrNull(),
                    originalHeight = image.string("height")?.toIntOrNull(),
                    mimeType = mime,
                    licenseId = null,
                    licenseUrl = null,
                    author = null,
                    attribution = null,
                    evidence = ArtworkEvidence.UNVERIFIED,
                    rights = ArtworkRights.REQUIRES_REVIEW,
                ).takeIf {
                    allowedArtworkImageUrl(it.source, it.imageUrl) &&
                        allowedArtworkSourcePage(it.source, it.canonicalSourcePage)
                }
            }
    }

    private fun JsonObject.objects(field: String): List<JsonObject> =
        (get(field) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.string(field: String): String? =
        (get(field) as? JsonPrimitive)?.contentOrNull

    private fun mimeOf(url: String): String? = when (url.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }

    private companion object {
        const val API_URL = "https://webservice.fanart.tv/v3.2/music/"

        /**
         * The documented public artist page. It carries no per-image licence either, so
         * it is offered as provenance, never as proof of rights.
         */
        const val ARTIST_PAGE = "https://fanart.tv/artist/"

        /** Enough to choose from, few enough to stay within "no more than necessary". */
        const val MAX_PER_KIND = 3

        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val HOSTS = setOf("webservice.fanart.tv")
    }
}
