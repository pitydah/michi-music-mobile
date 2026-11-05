package org.michimusic.mobile.artist.artwork

import android.text.Html
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface ArtistArtworkProvider {
    val source: ArtworkSource
    suspend fun findCandidates(anchor: ArtistIdentityAnchor, slot: ArtworkSlot): ProviderArtworkResult
}

private class ArtworkCandidateRejected(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

internal class CommonsMetadata(
    val fileTitle: String,
    val thumbUrl: String,
    val originalWidth: Int,
    val originalHeight: Int,
    val mime: String,
    val sourcePageUrl: String,
    val licenseId: String,
    val licenseUrl: String,
    val attribution: String,
)

/** Commons imageinfo is the single authoritative metadata read for every Wikimedia image. */
internal class CommonsImageInfo(private val http: ArtworkHttp) {
    suspend fun read(fileTitle: String): CommonsMetadata {
        if (!validCommonsFileTitle(fileTitle)) throw ArtworkCandidateRejected("BAD_FILE_TITLE")
        val endpoint = requireNotNull("https://commons.wikimedia.org/w/api.php".toHttpUrlOrNull()).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|size|mime|extmetadata")
            .addQueryParameter("iiurlwidth", "1600")
            .addQueryParameter("titles", "File:$fileTitle")
            .addQueryParameter("format", "json")
            .build()
        val response = http.readJson(endpoint.toString(), setOf("commons.wikimedia.org"))
        val pages = (response["query"] as? JsonObject)?.get("pages") as? JsonObject
        val info = pages?.values?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("imageinfo") as? JsonArray }
            ?.firstOrNull() as? JsonObject
            ?: throw ArtworkCandidateRejected("NO_IMAGEINFO")
        val imageUrl = info.string("thumburl")?.takeIf(String::isNotBlank)
            ?: info.string("url")?.takeIf(String::isNotBlank)
            ?: throw ArtworkCandidateRejected("NO_IMAGE_URL")
        val width = info.string("width")?.toIntOrNull() ?: 0
        val height = info.string("height")?.toIntOrNull() ?: 0
        val mime = info.string("mime")?.lowercase().orEmpty()
        val metadata = info["extmetadata"] as? JsonObject
        val licenseShort = metadata?.value("LicenseShortName")
        val licenseUrl = metadata?.value("LicenseUrl")
        val license = artworkLicenseLabel(licenseShort, licenseUrl)
            ?: throw ArtworkCandidateRejected("NO_ALLOWED_LICENSE")
        val attribution = meaningful(metadata?.value("Attribution"))
            ?: meaningful(metadata?.value("Artist"))
            ?: throw ArtworkCandidateRejected("BAD_ATTRIBUTION")
        return CommonsMetadata(
            fileTitle = fileTitle.removePrefix("File:"),
            thumbUrl = imageUrl,
            originalWidth = width,
            originalHeight = height,
            mime = mime,
            sourcePageUrl = info.string("descriptionurl")
                ?: "https://commons.wikimedia.org/wiki/File:${fileTitle.replace(' ', '_')}",
            licenseId = license,
            licenseUrl = licenseUrl.orEmpty(),
            attribution = attribution,
        )
    }

    private fun JsonObject.value(field: String): String? =
        ((get(field) as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull

    private fun meaningful(raw: String?): String? {
        val plain = raw?.let { runCatching { Html.fromHtml(it, 0).toString() }.getOrDefault(it) }
            ?.replace(Regex("\\s+"), " ")?.trim()
        if (plain.isNullOrBlank() || plain.equals("none", true) || plain.equals("null", true)) return null
        return plain.take(500)
    }
}

private fun JsonObject.string(field: String): String? =
    (get(field) as? JsonPrimitive)?.contentOrNull

internal suspend fun CommonsMetadata.toCandidate(
    anchor: ArtistIdentityAnchor,
    source: ArtworkSource,
    evidence: ArtworkEvidence,
): ArtistArtworkCandidate? {
    val assetId = canonicalCommonsAssetId(fileTitle) ?: return null
    return ArtistArtworkCandidate(
        source = source,
        stableAssetId = assetId,
        confirmedMbid = anchor.confirmedMbid,
        verifiedQid = anchor.verifiedQid,
        canonicalSourcePage = sourcePageUrl,
        imageUrl = thumbUrl,
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        mimeType = mime,
        licenseId = licenseId,
        licenseUrl = licenseUrl,
        author = attribution,
        attribution = attribution,
        evidence = evidence,
        rights = ArtworkRights.VERIFIED_ALLOWED,
    ).takeIf { allowedArtworkCandidate(it, anchor) }
}

/** Wikidata P18, the artist's own declared image. Highest deterministic priority. */
internal class CommonsP18Provider(
    private val http: ArtworkHttp,
    private val wikidata: WikidataArtworkLookup,
    private val commons: CommonsImageInfo,
) : ArtistArtworkProvider {
    override val source = ArtworkSource.COMMONS_P18
    override suspend fun findCandidates(anchor: ArtistIdentityAnchor, slot: ArtworkSlot): ProviderArtworkResult {
        val qid = anchor.verifiedQid ?: return ProviderArtworkResult.NoMatch
        val snapshot = try {
            wikidata.snapshot(qid) ?: return ProviderArtworkResult.NoMatch
        } catch (error: Exception) {
            return failure(error)
        }
        if (snapshot.p18.isEmpty()) return ProviderArtworkResult.NoMatch
        return resolveFiles(snapshot.p18, anchor, source, ArtworkEvidence.VERIFIED_QID_REFERENCE)
    }

    private suspend fun resolveFiles(
        files: List<String>,
        anchor: ArtistIdentityAnchor,
        source: ArtworkSource,
        evidence: ArtworkEvidence,
    ): ProviderArtworkResult {
        val candidates = mutableListOf<ArtistArtworkCandidate>()
        var lastFailure: Exception? = null
        files.forEach { file ->
            try {
                commons.read(file).toCandidate(anchor, source, evidence)?.let(candidates::add)
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        return if (candidates.isNotEmpty()) {
            ProviderArtworkResult.Available(candidates)
        } else {
            lastFailure?.let(::failure) ?: ProviderArtworkResult.NoMatch
        }
    }
}

/** Commons files that explicitly depict the verified artist QID. */
internal class CommonsP180Provider(
    private val http: ArtworkHttp,
    private val wikidata: WikidataArtworkLookup,
    private val commons: CommonsImageInfo,
) : ArtistArtworkProvider {
    override val source = ArtworkSource.COMMONS_P180
    override suspend fun findCandidates(anchor: ArtistIdentityAnchor, slot: ArtworkSlot): ProviderArtworkResult {
        val qid = anchor.verifiedQid ?: return ProviderArtworkResult.NoMatch
        val snapshot = try {
            wikidata.snapshot(qid) ?: return ProviderArtworkResult.NoMatch
        } catch (error: Exception) {
            return failure(error)
        }
        if (snapshot.depicts.isEmpty()) return ProviderArtworkResult.NoMatch
        val files = mutableListOf<String>()
        try {
            snapshot.depicts.forEach { depicts ->
                val endpoint = requireNotNull("https://commons.wikimedia.org/w/api.php".toHttpUrlOrNull()).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("list", "search")
                    .addQueryParameter("srsearch", "haswbstatement:P180=$depicts")
                    .addQueryParameter("srnamespace", "6")
                    .addQueryParameter("srlimit", "5")
                    .addQueryParameter("format", "json")
                    .build()
                val response = http.readJson(endpoint.toString(), setOf("commons.wikimedia.org"))
                val titles = ((response["query"] as? JsonObject)?.get("search") as? JsonArray).orEmpty()
                    .mapNotNull { ((it as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull }
                    .mapNotNull { it.removePrefix("File:").takeIf(::validCommonsFileTitle) }
                files += titles
            }
        } catch (error: Exception) {
            return failure(error)
        }
        val distinct = files.distinct().take(6)
        if (distinct.isEmpty()) return ProviderArtworkResult.NoMatch
        val candidates = mutableListOf<ArtistArtworkCandidate>()
        var lastFailure: Exception? = null
        distinct.forEach { file ->
            try {
                commons.read(file).toCandidate(anchor, source, ArtworkEvidence.VERIFIED_QID_REFERENCE)
                    ?.let(candidates::add)
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        return if (candidates.isNotEmpty()) ProviderArtworkResult.Available(candidates)
        else lastFailure?.let(::failure) ?: ProviderArtworkResult.NoMatch
    }
}

/** Wikipedia PageImages is accepted only through the QID's own sitelink article. */
internal class WikipediaPageImageProvider(
    private val http: ArtworkHttp,
    private val wikidata: WikidataArtworkLookup,
    private val commons: CommonsImageInfo,
) : ArtistArtworkProvider {
    override val source = ArtworkSource.WIKIPEDIA_PAGEIMAGE
    override suspend fun findCandidates(anchor: ArtistIdentityAnchor, slot: ArtworkSlot): ProviderArtworkResult {
        val qid = anchor.verifiedQid ?: return ProviderArtworkResult.NoMatch
        val snapshot = try {
            wikidata.snapshot(qid) ?: return ProviderArtworkResult.NoMatch
        } catch (error: Exception) {
            return failure(error)
        }
        val articles = listOf("es" to snapshot.eswikiTitle, "en" to snapshot.enwikiTitle)
            .mapNotNull { (lang, title) -> title?.let { lang to it } }
        if (articles.isEmpty()) return ProviderArtworkResult.NoMatch
        var lastFailure: Exception? = null
        for ((lang, title) in articles) {
            try {
                val endpoint = requireNotNull("https://$lang.wikipedia.org/w/api.php".toHttpUrlOrNull()).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("prop", "pageimages")
                    .addQueryParameter("piprop", "name")
                    .addQueryParameter("titles", title)
                    .addQueryParameter("redirects", "1")
                    .addQueryParameter("format", "json")
                    .build()
                val response = http.readJson(endpoint.toString(), setOf("$lang.wikipedia.org"))
                val pages = (response["query"] as? JsonObject)?.get("pages") as? JsonObject
                val pageImage = pages?.values?.firstOrNull()
                    ?.let { (it as? JsonObject)?.string("pageimage") }
                    ?.takeIf(::validCommonsFileTitle)
                    ?: continue
                val candidate = commons.read(pageImage)
                    .toCandidate(anchor, source, ArtworkEvidence.VERIFIED_ARTICLE_LINK)
                if (candidate != null) return ProviderArtworkResult.Available(listOf(candidate))
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        return lastFailure?.let(::failure) ?: ProviderArtworkResult.NoMatch
    }
}

internal fun failure(error: Throwable): ProviderArtworkResult = when (error) {
    is ArtworkHttpException ->
        ProviderArtworkResult.Failure("HTTP_${error.status}", error.status == 429 || error.status == 503)
    is ArtworkCandidateRejected -> ProviderArtworkResult.Failure(error.reason, false)
    is ArtworkDecodeException -> ProviderArtworkResult.Failure("DECODE", false)
    is java.net.SocketTimeoutException, is java.io.InterruptedIOException ->
        ProviderArtworkResult.Failure("TIMEOUT", true)
    is java.io.IOException -> ProviderArtworkResult.Failure("NETWORK", true)
    else -> ProviderArtworkResult.Failure("PROVIDER", false)
}
