package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val eswikiTitle: String?,
    val enwikiTitle: String?,
    val commonsCategories: List<String> = emptyList(),
    val commonsGallery: String? = null,
)

/**
 * Bounded Wikidata reads for one QID: the sitelink titles, plus the three claims the
 * artwork ladder actually consumes (P18, P373, P935).
 *
 * K48-R12: this used to fetch the whole entity with `props=claims|sitelinks`. Real
 * entities blow past the 256 KB JSON ceiling (Bob Dylan 359 KB, David Bowie 371 KB),
 * so the read failed and every provider that needs a snapshot — P18, Wikipedia,
 * category and gallery — reported NETWORK. Asking for one property at a time keeps
 * each response around a kilobyte, which also means every new artist costs a fraction
 * of the traffic. The entity dump was never needed: the ladder reads four values.
 *
 * The property reads are independent, so they run in parallel: a cold lookup costs two
 * round trips, with a whole entity's worth of evidence.
 */
internal class WikidataArtworkLookup(private val http: ArtworkHttp) {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, Pair<Long, WikidataArtworkSnapshot>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, WikidataArtworkSnapshot>>?) = size > 16
    }

    suspend fun snapshot(qid: String): WikidataArtworkSnapshot? = mutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        cache[qid]?.takeIf { now - it.first < CACHE_TTL_MS }?.second?.let { return@withLock it }
        val sitelinks = readSitelinks(qid) ?: return@withLock null
        val claims = coroutineScope {
            val p18 = async { claimValues(qid, "P18") }
            val category = async { claimValues(qid, "P373") }
            val gallery = async { claimValues(qid, "P935") }
            Triple(p18.await(), category.await(), gallery.await())
        }
        val snapshot = WikidataArtworkSnapshot(
            qid = qid,
            p18 = claims.first.filter(::validCommonsFileTitle).distinct().take(4),
            commonsCategories = claims.second.filter(::validCommonsCategoryName).distinct().take(1),
            commonsGallery = claims.third.firstOrNull(::validCommonsGalleryName),
            eswikiTitle = sitelinks.first?.take(200),
            enwikiTitle = sitelinks.second?.take(200),
        )
        cache[qid] = now to snapshot
        snapshot
    }

    /** Null means the entity does not exist, which is different from "has no image". */
    private suspend fun readSitelinks(qid: String): Pair<String?, String?>? {
        val endpoint = apiUrl()
            .addQueryParameter("action", "wbgetentities")
            .addQueryParameter("ids", qid)
            .addQueryParameter("props", "sitelinks")
            .addQueryParameter("sitefilter", "eswiki|enwiki")
            .addQueryParameter("format", "json")
            .build()
        val response = http.readJson(endpoint.toString(), WIKIDATA_HOSTS)
        val entity = (response["entities"] as? JsonObject)?.get(qid) as? JsonObject ?: return null
        if (entity["missing"] != null) return null
        val sitelinks = entity["sitelinks"] as? JsonObject
        fun title(site: String): String? =
            ((sitelinks?.get(site) as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull?.trim()
        return title("eswiki") to title("enwiki")
    }

    /**
     * One property for one entity. A missing property answers `{"claims":{}}`, and an
     * unusable id answers `{"error":…}`; both mean "nothing to offer", never a crash.
     */
    private suspend fun claimValues(qid: String, property: String): List<String> {
        val endpoint = apiUrl()
            .addQueryParameter("action", "wbgetclaims")
            .addQueryParameter("entity", qid)
            .addQueryParameter("property", property)
            .addQueryParameter("format", "json")
            .build()
        val response = http.readJson(endpoint.toString(), WIKIDATA_HOSTS)
        val claims = (response["claims"] as? JsonObject)?.get(property) as? JsonArray ?: return emptyList()
        return claims.mapNotNull { claim ->
            val mainsnak = (claim as? JsonObject)?.get("mainsnak") as? JsonObject ?: return@mapNotNull null
            val dataValue = mainsnak["datavalue"] as? JsonObject ?: return@mapNotNull null
            (dataValue["value"] as? JsonPrimitive)?.contentOrNull?.trim()
        }
    }

    private fun apiUrl() = requireNotNull(API_URL.toHttpUrlOrNull()).newBuilder()

    private companion object {
        const val API_URL = "https://www.wikidata.org/w/api.php"
        const val CACHE_TTL_MS = 60 * 60 * 1000L
        val WIKIDATA_HOSTS = setOf("www.wikidata.org")
    }
}

internal fun validCommonsFileTitle(value: String): Boolean =
    value.isNotBlank() && value.length <= 180 && '/' !in value && '\\' !in value

/** Wikidata P373 is a category name, not an untrusted URL or a proof of depiction. */
internal fun validCommonsCategoryName(value: String): Boolean {
    val name = value.trim()
    return name.isNotEmpty() && name.length <= 160 &&
        !name.startsWith("Category:", ignoreCase = true) &&
        !name.any { it.isISOControl() || it in "/\\|?#<>[]{}" } &&
        ".." !in name
}
