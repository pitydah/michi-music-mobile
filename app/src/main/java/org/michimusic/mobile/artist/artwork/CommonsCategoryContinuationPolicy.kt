package org.michimusic.mobile.artist.artwork

/** MediaWiki continuation never becomes a URL, a page title or an identity claim. */
internal object CommonsCategoryContinuationPolicy {
    fun nextToken(raw: String?, pageIndex: Int, batchesUsed: Int): String? =
        raw?.takeIf { token ->
            pageIndex == 0 && batchesUsed in 0..2 &&
                token.isNotBlank() && token.length <= 512 &&
                token.none(Char::isISOControl)
        }
}
