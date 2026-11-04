package org.michimusic.mobile.artist

/** Small TTL+LRU cache for *explicitly requested* MusicBrainz pages. No prefetch. */
class ArtistDiscographyPageCache(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 15 * 60 * 1000L,
    private val maxEntries: Int = 24,
) {
    private data class Key(val mbid: String, val offset: Int)
    private data class CacheEntry(val storedAt: Long, val page: ArtistDiscographyPage)
    private val pages = object : LinkedHashMap<Key, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CacheEntry>?): Boolean =
            size > maxEntries
    }
    @Synchronized fun get(mbid: String, offset: Int): ArtistDiscographyPage? {
        val key = Key(mbid, offset)
        val entry = pages[key] ?: return null
        if (nowMs() - entry.storedAt >= ttlMs || nowMs() < entry.storedAt) {
            pages.remove(key)
            return null
        }
        return entry.page
    }
    @Synchronized fun put(mbid: String, offset: Int, page: ArtistDiscographyPage) {
        require(offset == page.offset)
        pages[Key(mbid, offset)] = CacheEntry(nowMs(), page)
    }
}
