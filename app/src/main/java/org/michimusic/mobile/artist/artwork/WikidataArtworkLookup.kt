package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class WikidataArtworkSnapshot(
    val qid: String,
    val p18: List<String>,
    val depicts: List<String>,
    val eswikiTitle: String?,
    val enwikiTitle: String?,
)

/** One bounded Wikidata read per QID serves P18, P180 and the verified sitelink. */
internal class WikidataArtworkLookup(private val http: ArtworkHttp) {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, Pair<Long, WikidataArtworkSnapshot>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, WikidataArtworkSnapshot>>?) = size > 16
    }

    suspend fun snapshot(qid: String): WikidataArtworkSnapshot? = mutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        cache[qid]?.takeIf { now - it.first < 60 * 60 * 1000L }?.second?.let { return@withLock it }
        val endpoint = requireNotNull("https://www.wikidata.org/w/api.php".toHttpUrlOrNull()).newBuilder()
            .addQueryParameter("action", "wbgetentities")
            .addQueryParameter("ids", qid)
            .addQueryParameter("props", "claims|sitelinks")
            .addQueryParameter("sitefilter", "eswiki|enwiki")
            .addQueryParameter("format", "json")
            .build()
        val response = http.readJson(endpoint.toString(), setOf("www.wikidata.org"))
        val entity = (response["entities"] as? JsonObject)?.get(qid) as? JsonObject ?: return@withLock null
        if (entity["missing"] != null) return@withLock null
        val claims = entity["claims"] as? JsonObject
        fun values(property: String): List<String> = (claims?.get(property) as? JsonArray).orEmpty()
            .mapNotNull { claim ->
                val mainsnak = (claim as? JsonObject)?.get("mainsnak") as? JsonObject ?: return@mapNotNull null
                val dataValue = mainsnak["datavalue"] as? JsonObject ?: return@mapNotNull null
                (dataValue["value"] as? JsonPrimitive)?.contentOrNull?.trim()
            }
        val sitelinks = entity["sitelinks"] as? JsonObject
        fun title(site: String): String? =
            ((sitelinks?.get(site) as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull?.trim()
        val snapshot = WikidataArtworkSnapshot(
            qid = qid,
            p18 = values("P18").filter(::validCommonsFileTitle).distinct().take(4),
            depicts = values("P180").filter(::validWikidataQid).distinct().take(4),
            eswikiTitle = title("eswiki")?.take(200),
            enwikiTitle = title("enwiki")?.take(200),
        )
        cache[qid] = now to snapshot
        snapshot
    }
}

internal fun validCommonsFileTitle(value: String): Boolean =
    value.isNotBlank() && value.length <= 180 && '/' !in value && '\\' !in value
