package org.michimusic.mobile.artist.artwork

/**
 * Bounded MediaWiki `prop=images` continuation. Both `imcontinue` and the generic
 * `continue` value are returned by the API and must be forwarded together.
 * Tokens are only ever added with OkHttp.addQueryParameter, never parsed as URLs.
 */
internal object CommonsGalleryContinuationPolicy {
    const val MAX_PAGES = 2
    const val MAX_FILES_PER_PAGE = 24
    const val MAX_PROBE_BATCHES = 3
    private const val MAX_TOKEN_CHARS = 512

    data class Tokens(val image: String, val generic: String)

    fun next(
        image: String?,
        generic: String?,
        completedPageIndex: Int,
        previouslyUsed: Tokens?,
    ): Tokens? {
        if (completedPageIndex !in 0 until MAX_PAGES - 1) return null
        fun valid(token: String?): Boolean =
            !token.isNullOrBlank() && token.length <= MAX_TOKEN_CHARS &&
                token.none(Char::isISOControl)
        if (!valid(image) || !valid(generic)) return null
        val tokens = Tokens(image!!, generic!!)
        return tokens.takeUnless { it == previouslyUsed }
    }
}
