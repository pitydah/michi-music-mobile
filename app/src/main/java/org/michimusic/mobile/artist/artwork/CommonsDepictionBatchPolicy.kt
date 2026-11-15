package org.michimusic.mobile.artist.artwork

import java.util.Locale

/** A bounded, fail-closed policy for a single Commons category discovery page. */
internal object CommonsDepictionBatchPolicy {
    const val MAX_PROBE_FILES = 12
    const val MAX_LIST_FILES = 24
    private val pageIdPattern = Regex("^[1-9][0-9]{0,11}$")

    /** Never allow an untrusted file name to inject another MediaWiki `titles` value. */
    fun selectFileTitles(names: Collection<String>, limit: Int = MAX_PROBE_FILES): List<String> = names.asSequence()
        .filter { name ->
            validCommonsFileTitle(name) && name.length <= 180 &&
                name.none { it.isISOControl() || it in "|?#<>[]{}" } &&
                !name.startsWith("File:", ignoreCase = true)
        }
        .distinctBy(::key)
        .take(limit.coerceIn(0, MAX_LIST_FILES))
        .toList()

    internal data class Page(val title: String, val pageId: String)

    /** Page-title / page-id correspondence AND the item's own P180 must both agree. */
    fun verifiedFileTitles(
        requested: Collection<String>,
        pages: Collection<Page>,
        depictsByMediaId: Map<String, Set<String>>,
        artistQid: String,
    ): Set<String> {
        if (!validWikidataQid(artistQid)) return emptySet()
        val selected = selectFileTitles(requested)
        val lookup = selected.associateBy(::key)
        val seen = HashSet<String>()
        val conflicting = HashSet<String>()
        val verified = LinkedHashSet<String>()
        pages.forEach { page ->
            if (!page.title.startsWith("File:") || !pageIdPattern.matches(page.pageId)) return@forEach
            val normalized = key(page.title.removePrefix("File:"))
            if (normalized !in lookup) return@forEach
            if (!seen.add(normalized)) {
                conflicting.add(normalized)
                verified.remove(lookup.getValue(normalized))
                return@forEach
            }
            if (normalized !in conflicting &&
                artistQid in depictsByMediaId["M${page.pageId}"].orEmpty()
            ) verified.add(lookup.getValue(normalized))
        }
        return verified
    }

    private fun key(title: String): String = title.replace('_', ' ').lowercase(Locale.ROOT)
}
