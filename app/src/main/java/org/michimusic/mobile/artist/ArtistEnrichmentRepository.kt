package org.michimusic.mobile.artist

import android.os.SystemClock
import android.text.Html
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Explicit, manual-only enrichment for local credits.
 * The panel persists selected profiles via a scoped Room store and may inspect embedded
 * evidence on user request; this gateway itself neither scans tags nor writes to Room.
 * A candidate never becomes an identity association without confirmation.
 */
interface ArtistEnrichmentGateway {
    suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate>
    suspend fun confirmedProfile(mbid: String): ArtistExternalProfile

    /**
     * Explicit retry from the enrichment surface. Bypasses the in-memory profile
     * cache so a transient provider failure is not replayed for an hour.
     */
    suspend fun refreshProfile(mbid: String): ArtistExternalProfile = confirmedProfile(mbid)
}

class ArtistEnrichmentRepository(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : ArtistEnrichmentGateway, ArtistDiscographyGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val musicBrainzMutex = Mutex()
    private val discographyCache = ArtistDiscographyPageCache(
        nowMs = { SystemClock.elapsedRealtime() },
    )
    private var nextMusicBrainzRequestAtMs = 0L
    private val profiles = object : LinkedHashMap<String, Pair<Long, ArtistExternalProfile>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ArtistExternalProfile>>?): Boolean = size > 32
    }

    override suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate> {
        val query = localCredit.trim().replace(Regex("[\\r\\n\\\"]"), " ").take(120)
        if (query.isBlank()) return emptyList()
        val url = api("https://musicbrainz.org/ws/2/artist", mapOf(
            "query" to "artist:\"$query\"", "fmt" to "json", "limit" to "5",
        ))
        val response = musicBrainzJson(url)
        return (response["artists"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val artist = raw as? JsonObject ?: return@mapNotNull null
            val id = canonicalArtistMbid(artist.string("id")) ?: return@mapNotNull null
            val name = artist.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ArtistLookupCandidate(
                mbid = id, name = name,
                disambiguation = artist.string("disambiguation").orEmpty(),
                type = artist.string("type").orEmpty(),
                area = (artist["area"] as? JsonObject)?.string("name").orEmpty(),
            )
        }
    }

    /** Explicit user-triggered query: never invoked by mosaic scrolling or name search. */
    override suspend fun fetchAlbumGroups(confirmedMbid: String, offset: Int): ArtistDiscographyPage {
        val id = canonicalArtistMbid(confirmedMbid) ?: throw IllegalArgumentException("MBID no válido")
        require(offset in 0..999) { "Página fuera de límite" }
        discographyCache.get(id, offset)?.let { return it }
        val response = musicBrainzJson(api("https://musicbrainz.org/ws/2/release-group", mapOf(
            "artist" to id, "type" to "album", "fmt" to "json", "limit" to "25",
            "offset" to offset.toString(),
        )))
        val page = parseMusicBrainzAlbumGroups(response, offset)
        discographyCache.put(id, offset, page)
        return page
    }

    override suspend fun confirmedProfile(mbid: String): ArtistExternalProfile =
        loadProfile(mbid, forceRefresh = false)

    override suspend fun refreshProfile(mbid: String): ArtistExternalProfile =
        loadProfile(mbid, forceRefresh = true)

    private suspend fun loadProfile(mbid: String, forceRefresh: Boolean): ArtistExternalProfile {
        val id = canonicalArtistMbid(mbid) ?: throw IllegalArgumentException("MBID no válido")
        if (!forceRefresh) {
            synchronized(profiles) {
                profiles[id]?.takeIf { SystemClock.elapsedRealtime() - it.first < 60 * 60 * 1000L }
                    ?.second?.let { return it }
            }
        }
        val artist = musicBrainzJson(api("https://musicbrainz.org/ws/2/artist/$id", mapOf(
            "inc" to "url-rels", "fmt" to "json",
        )))
        val links = (artist["relations"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun related(type: String): String? = links.firstOrNull { it.string("type") == type }
            ?.let { (it["url"] as? JsonObject)?.string("resource") }
        val wikipediaUrl = related("wikipedia")
        val wikidataUrl = related("wikidata")

        // One Wikidata read feeds both optional stages: sitelinks for the
        // biography fallback and P18 for the portrait. The stages remain
        // independent, so a portrait failure never erases a verified text.
        val wikidata = wikidataSnapshot(wikidataUrl)
        val biography = biographyStage(wikipediaUrl, wikidata)
        val portrait = portraitStage(wikidata)

        val result = ArtistExternalProfile(
            mbid = id,
            name = artist.string("name").orEmpty(),
            disambiguation = artist.string("disambiguation").orEmpty(),
            type = artist.string("type").orEmpty(),
            area = (artist["area"] as? JsonObject)?.string("name").orEmpty(),
            biography = biography.value?.text,
            biographySourceUrl = biography.value?.sourceUrl,
            portrait = portrait.value,
            biographyOutcome = biography.outcome,
            portraitOutcome = portrait.outcome,
            wikidataQid = wikidata.value?.qid,
        )
        synchronized(profiles) { profiles[id] = SystemClock.elapsedRealtime() to result }
        return result
    }

    private class WikipediaArticle(val text: String, val sourceUrl: String)

    private class WikidataSnapshot(val qid: String, val entity: JsonObject)

    private class Stage<out T>(val value: T?, val outcome: ArtistMetadataOutcome)

    /** Internal marker: the stage knows exactly why it produced no value. */
    private class MetadataFailure(
        val outcome: ArtistMetadataOutcome,
        cause: Throwable? = null,
    ) : Exception(cause)

    private suspend fun <T : Any> stage(
        emptyOutcome: ArtistMetadataOutcome,
        block: suspend () -> T?,
    ): Stage<T> = try {
        val value = block()
        Stage(value, if (value == null) emptyOutcome else ArtistMetadataOutcome.SUCCESS)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: MetadataFailure) {
        Stage(null, failure.outcome)
    } catch (error: Exception) {
        Stage(null, outcomeFor(error))
    }

    private fun outcomeFor(error: Throwable): ArtistMetadataOutcome = when {
        error is ProviderHttpException -> ArtistMetadataOutcome.HTTP_ERROR
        error is java.net.SocketTimeoutException -> ArtistMetadataOutcome.TIMEOUT
        error is java.io.InterruptedIOException -> ArtistMetadataOutcome.TIMEOUT
        error is java.io.IOException -> ArtistMetadataOutcome.NETWORK_ERROR
        error is kotlinx.serialization.SerializationException -> ArtistMetadataOutcome.DECODE_ERROR
        else -> ArtistMetadataOutcome.DECODE_ERROR
    }

    private suspend fun wikidataSnapshot(link: String?): Stage<WikidataSnapshot> {
        if (link == null) return Stage(null, ArtistMetadataOutcome.NO_IDENTITY)
        return stage(ArtistMetadataOutcome.DECODE_ERROR) {
            val qid = wikidataQid(link)
                ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
            val item = readJson(api("https://www.wikidata.org/w/api.php", mapOf(
                "action" to "wbgetentities", "ids" to qid,
                "props" to "claims|sitelinks", "sitefilter" to "eswiki|enwiki",
                "format" to "json",
            )))
            val entity = ((item["entities"] as? JsonObject)?.get(qid) as? JsonObject)
                ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
            if (entity["missing"] != null) {
                throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
            }
            WikidataSnapshot(qid, entity)
        }
    }

    private suspend fun biographyStage(
        wikipediaUrl: String?,
        wikidata: Stage<WikidataSnapshot>,
    ): Stage<WikipediaArticle> {
        val direct = if (wikipediaUrl != null) {
            stage(ArtistMetadataOutcome.NO_TEXT) { wikipediaExtract(wikipediaUrl) }
        } else {
            Stage(null, ArtistMetadataOutcome.NO_IDENTITY)
        }
        if (direct.value != null) return direct

        val snapshot = wikidata.value
        val fallback = if (snapshot == null) {
            Stage(null, wikidata.outcome)
        } else {
            val article = sitelinkArticle(snapshot.entity)
            if (article == null) Stage(null, ArtistMetadataOutcome.NO_SITELINK)
            else stage(ArtistMetadataOutcome.NO_TEXT) { wikipediaExtract(article) }
        }
        if (fallback.value != null) return fallback

        // A concrete transport/diagnostic failure is more useful than the
        // absence of a fallback link; otherwise report the fallback diagnosis.
        val informative = direct.outcome.takeIf {
            it == ArtistMetadataOutcome.HTTP_ERROR || it == ArtistMetadataOutcome.TIMEOUT ||
                it == ArtistMetadataOutcome.NETWORK_ERROR || it == ArtistMetadataOutcome.DECODE_ERROR
        }
        return Stage(null, informative ?: fallback.outcome)
    }

    private suspend fun portraitStage(wikidata: Stage<WikidataSnapshot>): Stage<CommonsPortrait> {
        val snapshot = wikidata.value ?: return Stage(null, wikidata.outcome)
        return stage(ArtistMetadataOutcome.NO_P18) { commonsPortrait(snapshot.entity) }
    }

    private fun wikidataQid(link: String): String? {
        val parsed = link.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host != "www.wikidata.org") return null
        return parsed.pathSegments.lastOrNull()
            ?.takeIf { Regex("^Q[1-9][0-9]{0,10}$").matches(it) }
    }

    /** Prefer the Spanish article, then English; both come from the verified QID. */
    private fun sitelinkArticle(entity: JsonObject): String? {
        val sitelinks = entity["sitelinks"] as? JsonObject ?: return null
        for (site in listOf("eswiki", "enwiki")) {
            val title = (sitelinks[site] as? JsonObject)?.string("title")
                ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: continue
            val url = wikipediaArticleUrl(site.removeSuffix("wiki") + ".wikipedia.org", title)
                ?: continue
            if (allowedWikipediaSource(url)) return url
        }
        return null
    }

    private fun wikipediaArticleUrl(host: String, title: String): String? =
        "https://$host/wiki/".toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment(title.replace(' ', '_'))?.build()?.toString()
            ?.takeIf(::allowedWikipediaSource)

    private suspend fun wikipediaExtract(link: String): WikipediaArticle {
        val article = link.toHttpUrlOrNull()
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        if (article.scheme != "https" ||
            !Regex("^[a-z-]{2,12}\\.wikipedia\\.org$").matches(article.host)
        ) {
            throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        }
        if (article.pathSegments.firstOrNull() != "wiki") {
            throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        }
        val title = article.pathSegments.drop(1).joinToString("/").take(160)
        if (title.isBlank()) throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        val data = readJson(api("https://${article.host}/w/api.php", mapOf(
            "action" to "query", "prop" to "extracts", "exintro" to "1",
            "explaintext" to "1", "format" to "json", "redirects" to "1",
            "titles" to title,
        )))
        val pages = ((data["query"] as? JsonObject)?.get("pages") as? JsonObject)
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        val page = pages.values.firstOrNull() as? JsonObject
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        if (page["missing"] != null) {
            throw MetadataFailure(ArtistMetadataOutcome.NO_TEXT)
        }
        val text = page.string("extract")?.trim()?.take(850)
            ?.takeIf(String::isNotBlank)
            ?: throw MetadataFailure(ArtistMetadataOutcome.NO_TEXT)
        // The API resolves redirects; the stored source must be the exact
        // article that produced the text, never the redirecting title.
        val resolvedTitle = page.string("title")?.takeIf(String::isNotBlank) ?: title
        val sourceUrl = wikipediaArticleUrl(article.host, resolvedTitle)
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        return WikipediaArticle(text, sourceUrl)
    }

    private suspend fun commonsPortrait(entity: JsonObject): CommonsPortrait {
        val claims = entity["claims"] as? JsonObject
            ?: throw MetadataFailure(ArtistMetadataOutcome.NO_P18)
        val photo = (claims["P18"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw MetadataFailure(ArtistMetadataOutcome.NO_P18)
        val claim = photo["mainsnak"] as? JsonObject
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        val data = claim["datavalue"] as? JsonObject
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        val filename = data.string("value")?.take(180)
        if (filename.isNullOrBlank() || '/' in filename || '\\' in filename) {
            throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        }
        val image = readJson(api("https://commons.wikimedia.org/w/api.php", mapOf(
            "action" to "query", "prop" to "imageinfo", "iiprop" to "url|extmetadata",
            "iiurlwidth" to "640", "titles" to "File:$filename", "format" to "json",
        )))
        val pages = ((image["query"] as? JsonObject)?.get("pages") as? JsonObject)
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        val record = pages.values.firstOrNull() as? JsonObject
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        val imageInfo = (record["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        val meta = imageInfo["extmetadata"] as? JsonObject
            ?: throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        fun metaText(field: String): String =
            ((meta[field] as? JsonObject)?.string("value")).orEmpty()
        // Commons commonly stores the literal string "None" in Attribution
        // when no special credit is required; the file author is then the
        // truthful attribution. Treating "None" as a credit would show users
        // a meaningless label and must not satisfy the attribution gate.
        fun meaningful(value: String): String = value.trim().takeUnless {
            it.isEmpty() || it.equals("none", ignoreCase = true) || it.equals("null", ignoreCase = true)
        }.orEmpty()
        val attributionHtml = meaningful(metaText("Attribution"))
            .ifBlank { metaText("Artist") }
        val plainAttribution = Html.fromHtml(attributionHtml, Html.FROM_HTML_MODE_COMPACT)
            .toString().trim().take(500)
        if (!allowedPortraitAttribution(plainAttribution)) {
            throw MetadataFailure(ArtistMetadataOutcome.BAD_ATTRIBUTION)
        }
        val portrait = CommonsPortrait(
            imageUrl = imageInfo.string("thumburl").orEmpty(),
            commonsPageUrl = imageInfo.string("descriptionurl").orEmpty(),
            license = metaText("LicenseShortName"),
            licenseUrl = metaText("LicenseUrl"),
            attribution = plainAttribution,
        )
        if (!allowedPortraitLicense(portrait.license)) {
            throw MetadataFailure(ArtistMetadataOutcome.NO_ALLOWED_LICENSE)
        }
        if (!allowedPortraitUrls(portrait)) {
            throw MetadataFailure(ArtistMetadataOutcome.DECODE_ERROR)
        }
        if (!allowedCommonsPortrait(portrait)) {
            throw MetadataFailure(ArtistMetadataOutcome.NO_ALLOWED_LICENSE)
        }
        return portrait
    }

    private suspend fun musicBrainzJson(url: String): JsonObject = musicBrainzMutex.withLock {
        // This shared repository instance serializes calls. A conservative 1.1s interval prevents
        // hitting the public MusicBrainz rate limit even when two screens search concurrently.
        val wait = nextMusicBrainzRequestAtMs - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        nextMusicBrainzRequestAtMs = SystemClock.elapsedRealtime() + 1_100
        readJson(url)
    }

    private fun api(base: String, args: Map<String, String>): String {
        val builder = requireNotNull(base.toHttpUrlOrNull()).newBuilder()
        args.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    /** Retry only throttled/transient provider replies; never retry parsing or permission errors. */
    private suspend fun readJson(url: String): JsonObject {
        var retries = 0
        while (true) {
            try {
                return readJsonOnce(url)
            } catch (e: ProviderHttpException) {
                if (e.status != 429 && e.status != 503 || retries >= 2) throw e
                // Honour Retry-After when the provider sends seconds; otherwise back off
                // exponentially. Both paths are capped so a hostile header cannot stall UI.
                val wait = e.retryAfterMs.takeIf { it > 0L } ?: (1_000L shl retries)
                delay(wait.coerceAtMost(MAX_RETRY_AFTER_MS))
                retries++
            }
        }
    }

    private class ProviderHttpException(val status: Int, val retryAfterMs: Long = 0L) :
        IOException("Proveedor HTTP $status")

    private suspend fun readJsonOnce(url: String): JsonObject = suspendCancellableCoroutine { continuation ->
        val endpoint = url.toHttpUrlOrNull()
        if (endpoint == null) {
            continuation.resumeWithException(IOException("URL inválida"))
            return@suspendCancellableCoroutine
        }
        val allowed = endpoint.scheme == "https" && (
            endpoint.host == "musicbrainz.org" || endpoint.host == "www.wikidata.org" ||
                endpoint.host == "commons.wikimedia.org" || endpoint.host.endsWith(".wikipedia.org")
        )
        if (!allowed) {
            continuation.resumeWithException(IOException("Origen de metadatos no autorizado"))
            return@suspendCancellableCoroutine
        }
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", "MichiMusicMobile/0.1 (https://github.com/pitydah/michi-music-mobile)")
            .header("Accept", "application/json")
            .build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) {
                            throw ProviderHttpException(it.code, retryAfterDelayMs(it.header("Retry-After")))
                        }
                        if (it.header("Content-Type")?.contains("json", ignoreCase = true) != true) {
                            throw IOException("Proveedor no devolvió JSON")
                        }
                        val bytes = it.body?.byteStream()?.use { stream ->
                            val buffer = ByteArray(8192)
                            val output = ByteArrayOutputStream()
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 256 * 1024) {
                                    throw IOException("Respuesta de metadatos demasiado grande")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: throw IOException("Respuesta vacía")
                        (json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject)
                            ?: throw IOException("Estructura JSON no válida")
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun JsonObject.string(field: String): String? =
        (get(field) as? JsonPrimitive)?.contentOrNull
}

/** One shared rate limiter and bounded volatile cache for all artist-detail screens. */
object ArtistEnrichmentService {
    val repository: ArtistEnrichmentRepository by lazy { ArtistEnrichmentRepository() }
}

/** Upper bound for any provider-requested wait, so a header cannot freeze the screen. */
internal const val MAX_RETRY_AFTER_MS: Long = 5_000L

/**
 * Parses `Retry-After` in seconds (the only form Wikimedia and MusicBrainz send) into a
 * bounded delay. HTTP-date values and garbage return 0, which falls back to backoff.
 */
internal fun retryAfterDelayMs(raw: String?): Long {
    val seconds = raw?.trim()?.toLongOrNull() ?: return 0L
    if (seconds <= 0L) return 0L
    return (seconds * 1_000L).coerceAtMost(MAX_RETRY_AFTER_MS)
}
