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

    override suspend fun confirmedProfile(mbid: String): ArtistExternalProfile {
        val id = canonicalArtistMbid(mbid) ?: throw IllegalArgumentException("MBID no válido")
        synchronized(profiles) {
            profiles[id]?.takeIf { SystemClock.elapsedRealtime() - it.first < 60 * 60 * 1000L }
                ?.second?.let { return it }
        }
        val artist = musicBrainzJson(api("https://musicbrainz.org/ws/2/artist/$id", mapOf(
            "inc" to "url-rels", "fmt" to "json",
        )))
        val links = (artist["relations"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun related(type: String): String? = links.firstOrNull { it.string("type") == type }
            ?.let { (it["url"] as? JsonObject)?.string("resource") }
        val wikipediaUrl = related("wikipedia")
        val wikipedia = wikipediaUrl?.let { optionalMetadata { wikipediaExtract(it) } }
        val wikidataUrl = related("wikidata")
        val portrait = wikidataUrl?.let { optionalMetadata { commonsPortrait(it) } }
        val result = ArtistExternalProfile(
            mbid = id,
            name = artist.string("name").orEmpty(),
            disambiguation = artist.string("disambiguation").orEmpty(),
            type = artist.string("type").orEmpty(),
            area = (artist["area"] as? JsonObject)?.string("name").orEmpty(),
            biography = wikipedia,
            biographySourceUrl = if (wikipedia != null) wikipediaUrl else null,
            portrait = portrait,
        )
        synchronized(profiles) { profiles[id] = SystemClock.elapsedRealtime() to result }
        return result
    }

    /** Missing optional providers fail closed; coroutine cancellation must remain observable. */
    private suspend fun <T> optionalMetadata(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun musicBrainzJson(url: String): JsonObject = musicBrainzMutex.withLock {
        // This shared repository instance serializes calls. A conservative 1.1s interval prevents
        // hitting the public MusicBrainz rate limit even when two screens search concurrently.
        val wait = nextMusicBrainzRequestAtMs - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        nextMusicBrainzRequestAtMs = SystemClock.elapsedRealtime() + 1_100
        readJson(url)
    }

    private suspend fun wikipediaExtract(link: String): String? {
        val article = link.toHttpUrlOrNull() ?: return null
        if (article.scheme != "https" || !Regex("^[a-z-]{2,12}\\.wikipedia\\.org$").matches(article.host)) return null
        if (article.pathSegments.firstOrNull() != "wiki") return null
        val title = article.pathSegments.drop(1).joinToString("/").take(160)
        if (title.isBlank()) return null
        val data = readJson(api("https://${article.host}/w/api.php", mapOf(
            "action" to "query", "prop" to "extracts", "exintro" to "1",
            "explaintext" to "1", "format" to "json", "titles" to title,
        )))
        val pages = ((data["query"] as? JsonObject)?.get("pages") as? JsonObject) ?: return null
        return pages.values.firstNotNullOfOrNull { (it as? JsonObject)?.string("extract") }
            ?.trim()?.take(850)?.takeIf(String::isNotBlank)
    }

    private suspend fun commonsPortrait(wikidataLink: String): CommonsPortrait? {
        val link = wikidataLink.toHttpUrlOrNull() ?: return null
        if (link.scheme != "https" || link.host != "www.wikidata.org") return null
        val qid = link.pathSegments.lastOrNull()?.takeIf { Regex("^Q[1-9][0-9]{0,10}$").matches(it) }
            ?: return null
        val item = readJson(api("https://www.wikidata.org/w/api.php", mapOf(
            "action" to "wbgetentities", "ids" to qid, "props" to "claims", "format" to "json",
        )))
        val entity = ((item["entities"] as? JsonObject)?.get(qid) as? JsonObject) ?: return null
        val claims = entity["claims"] as? JsonObject ?: return null
        val photo = (claims["P18"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val claim = photo["mainsnak"] as? JsonObject ?: return null
        val data = claim["datavalue"] as? JsonObject ?: return null
        val filename = data.string("value")?.take(180) ?: return null
        if (filename.isBlank() || '/' in filename || '\\' in filename) return null
        val image = readJson(api("https://commons.wikimedia.org/w/api.php", mapOf(
            "action" to "query", "prop" to "imageinfo", "iiprop" to "url|extmetadata",
            "iiurlwidth" to "640", "titles" to "File:$filename", "format" to "json",
        )))
        val pages = ((image["query"] as? JsonObject)?.get("pages") as? JsonObject) ?: return null
        val record = pages.values.firstOrNull() as? JsonObject ?: return null
        val imageInfo = (record["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val meta = imageInfo["extmetadata"] as? JsonObject ?: return null
        fun metaText(field: String): String = ((meta[field] as? JsonObject)?.string("value")).orEmpty()
        val attributionHtml = metaText("Attribution").ifBlank { metaText("Artist") }
        val plainAttribution = Html.fromHtml(attributionHtml, Html.FROM_HTML_MODE_COMPACT)
            .toString().trim().take(500)
        val portrait = CommonsPortrait(
            imageUrl = imageInfo.string("thumburl").orEmpty(),
            commonsPageUrl = imageInfo.string("descriptionurl").orEmpty(),
            license = metaText("LicenseShortName"),
            licenseUrl = metaText("LicenseUrl"),
            attribution = plainAttribution,
        )
        return portrait.takeIf(::allowedCommonsPortrait)
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
