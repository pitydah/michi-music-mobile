package org.michimusic.mobile.artist.artwork

import okhttp3.HttpUrl

/**
 * Answers the two Wikidata calls the artwork ladder makes, the way Wikidata answers them.
 *
 * K48-R12: the lookup no longer dumps a whole entity (that payload exceeded the 256 KB
 * JSON ceiling on real artists). It asks for one property at a time, so a fixture that
 * only returned `{"entities": …}` would silently starve every provider. Each property
 * answers `{"claims":{}}` when absent, exactly like the live API.
 */
internal data class WikidataApiFixture(
    val qid: String = "Q48284",
    val p18: List<String> = emptyList(),
    val category: String? = null,
    val gallery: String? = null,
    val sitelinks: String = "{}",
) {
    fun response(url: HttpUrl): String = when (url.queryParameter("action")) {
        "wbgetclaims" -> {
            val property = url.queryParameter("property")
            val values = when (property) {
                "P18" -> p18
                "P373" -> listOfNotNull(category)
                "P935" -> listOfNotNull(gallery)
                else -> emptyList()
            }
            if (values.isEmpty()) {
                """{"claims":{}}"""
            } else {
                val claims = values.joinToString(",") { """{"mainsnak":{"datavalue":{"value":"$it"}}}""" }
                """{"claims":{"$property":[$claims]}}"""
            }
        }
        "wbgetentities" -> """{"entities":{"$qid":{"sitelinks":$sitelinks}}}"""
        else -> error("Unexpected Wikidata action in $url")
    }
}
