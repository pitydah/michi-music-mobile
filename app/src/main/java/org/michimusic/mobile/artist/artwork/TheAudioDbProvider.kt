package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * TheAudioDB publishes photographs and fan art for an artist, but no licence and no
 * author for any of them: the response has no licence, author or copyright field at
 * all. That single fact decides how this provider is allowed to behave.
 *
 * - It never enters the automatic ladder. A photo nobody licensed cannot fill a slot
 *   on its own, however good it looks.
 * - It only answers an explicit manual search, and every candidate is born
 *   `REQUIRES_REVIEW` / `UNVERIFIED`.
 * - A chosen photo keeps `ArtworkRights.UNDECLARED_LICENSE` for ever. It can never be
 *   reported as `VERIFIED_ALLOWED`, and the copy next to it has to say the licence is
 *   undeclared rather than invent one.
 *
 * TheAudioDB also matches artists by name, which is exactly what the rest of this
 * pipeline refuses to trust. The compensating control is the picker's explicit
 * identity confirmation, which names the artist and its MBID before anything is saved.
 */
internal class TheAudioDbProvider(
    private val http: ArtworkHttp,
    private val apiKey: String = ArtworkFeatureFlags.theAudioDbApiKey,
) {
    suspend fun search(anchor: ArtistIdentityAnchor, query: String): ProviderArtworkResult = try {
        val credited = query.trim().ifBlank { anchor.displayCredit.trim() }
        if (credited.isBlank()) {
            ProviderArtworkResult.NoMatch
        } else {
            val endpoint = requireNotNull(API_URL.toHttpUrlOrNull()).newBuilder()
                .addPathSegment(apiKey)
                .addPathSegment("search.php")
                .addQueryParameter("s", credited)
                .build()
            val response = http.readJson(endpoint.toString(), HOSTS)
            val artists = (response["artists"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val artist = artists.firstOrNull { it.string("strArtist").orEmpty().sameArtistAs(anchor.displayCredit) }
            if (artist == null) ProviderArtworkResult.NoMatch else {
                val candidates = candidatesFor(artist, anchor)
                if (candidates.isEmpty()) ProviderArtworkResult.NoMatch
                else ProviderArtworkResult.Available(candidates)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        failure(error)
    }

    private fun candidatesFor(artist: JsonObject, anchor: ArtistIdentityAnchor): List<ArtistArtworkCandidate> {
        val id = artist.string("idArtist")?.takeIf(String::isNotBlank) ?: return emptyList()
        val page = "$ARTIST_PAGE$id"
        val images = buildList {
            artist.string("strArtistThumb")?.let { add("thumb" to it) }
            listOf("strArtistFanart", "strArtistFanart2", "strArtistFanart3", "strArtistFanart4")
                .forEach { field -> artist.string(field)?.let { add(field.lowercase() to it) } }
        }
        val credited = artist.string("strArtist")?.trim().orEmpty()
        return images.distinctBy { it.second }.mapNotNull { (name, url) ->
            val mime = mimeOf(url) ?: return@mapNotNull null
            val candidate = ArtistArtworkCandidate(
                source = ArtworkSource.THEAUDIODB,
                stableAssetId = "theaudiodb:$id:$name".take(220),
                confirmedMbid = anchor.confirmedMbid,
                verifiedQid = anchor.verifiedQid,
                canonicalSourcePage = page,
                imageUrl = url,
                // The catalogue never states dimensions or a licence. They are measured
                // after the download and the licence stays undeclared for ever.
                originalWidth = null,
                originalHeight = null,
                mimeType = mime,
                licenseId = null,
                licenseUrl = null,
                author = credited.take(300),
                attribution = null,
                evidence = ArtworkEvidence.UNVERIFIED,
                rights = ArtworkRights.REQUIRES_REVIEW,
            )
            candidate.takeIf {
                allowedArtworkImageUrl(it.source, it.imageUrl) &&
                    allowedArtworkSourcePage(it.source, it.canonicalSourcePage)
            }
        }
    }

    private fun mimeOf(url: String): String? = when (url.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }

    private fun JsonObject.string(field: String): String? =
        (get(field) as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val API_URL = "https://www.theaudiodb.com/api/v1/json/"
        const val ARTIST_PAGE = "https://www.theaudiodb.com/artist/"
        val HOSTS = setOf("www.theaudiodb.com")
    }
}

/**
 * TheAudioDB answers with the artist it thinks the string names, so a mismatch is a
 * different artist, not a different spelling.
 */
private fun String.sameArtistAs(credit: String): Boolean =
    filter(Char::isLetterOrDigit).lowercase() == credit.filter(Char::isLetterOrDigit).lowercase()
