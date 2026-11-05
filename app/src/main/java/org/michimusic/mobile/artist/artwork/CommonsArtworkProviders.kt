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

    /** Verifies the file's own structured-data claim, not the artist entity. */
    suspend fun depicts(fileTitle: String, qid: String): Boolean {
        if (!validCommonsFileTitle(fileTitle)) return false
        val endpoint = requireNotNull("https://www.wikidata.org/w/api.php".toHttpUrlOrNull()).newBuilder()
            .addQueryParameter("action", "wbgetentities")
            .addQueryParameter("sites", "commonswiki")
            .addQueryParameter("titles", "File:$fileTitle")
            .addQueryParameter("props", "claims")
            .addQueryParameter("format", "json")
            .build()
        val response = http.readJson(endpoint.toString(), setOf("www.wikidata.org"))
        val entity = ((response["entities"] as? JsonObject)?.values?.firstOrNull() as? JsonObject)
            ?: return false
        val claims = (entity["claims"] as? JsonObject)?.get("P180") as? JsonArray ?: return false
        return claims.any { claim ->
            val mainsnak = (claim as? JsonObject)?.get("mainsnak") as? JsonObject
            val value = (mainsnak?.get("datavalue") as? JsonObject)?.get("value") as? JsonObject
            value?.string("id") == qid
        }
    }

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
            if (error is kotlinx.coroutines.CancellationException) throw error
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
                if (error is kotlinx.coroutines.CancellationException) throw error
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
        // Search Commons for files whose own structured data depicts the verified artist.
        // The artist entity's P180 is unrelated to image depictions and must not be used.
        val files = mutableListOf<String>()
        var offset: String? = null
        try {
            for (page in 0 until 2) {
                val endpoint = requireNotNull("https://commons.wikimedia.org/w/api.php".toHttpUrlOrNull()).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("list", "search")
                    .addQueryParameter("srsearch", "haswbstatement:P180=$qid")
                    .addQueryParameter("srnamespace", "6")
                    .addQueryParameter("srlimit", "50")
                    .addQueryParameter("format", "json")
                    .apply { offset?.let { addQueryParameter("sroffset", it) } }
                    .build()
                val response = http.readJson(endpoint.toString(), setOf("commons.wikimedia.org"))
                ((response["query"] as? JsonObject)?.get("search") as? JsonArray).orEmpty()
                    .mapNotNull { ((it as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull }
                    .mapNotNull { it.removePrefix("File:").takeIf(::validCommonsFileTitle) }
                    .forEach(files::add)
                offset = ((response["continue"] as? JsonObject)?.get("sroffset") as? JsonPrimitive)
                    ?.contentOrNull
                if (offset == null) break
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return failure(error)
        }
        val distinct = files.distinct().take(12)
        if (distinct.isEmpty()) return ProviderArtworkResult.NoMatch
        val candidates = mutableListOf<ArtistArtworkCandidate>()
        var lastFailure: Exception? = null
        for (file in distinct) {
            try {
                // The search index is a hint; the selected file must prove the same QID.
                if (!commons.depicts(file, qid)) continue
                commons.read(file).toCandidate(anchor, source, ArtworkEvidence.VERIFIED_QID_REFERENCE)
                    ?.let(candidates::add)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
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
            if (error is kotlinx.coroutines.CancellationException) throw error
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
                // The article link alone does not prove the lead image represents the
                // artist; require the file's own structured data to depict the QID.
                if (!commons.depicts(pageImage, qid)) continue
                val candidate = commons.read(pageImage)
                    .toCandidate(anchor, source, ArtworkEvidence.VERIFIED_ARTICLE_LINK)
                if (candidate != null) return ProviderArtworkResult.Available(listOf(candidate))
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
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
