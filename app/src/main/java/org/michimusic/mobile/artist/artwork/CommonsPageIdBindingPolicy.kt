package org.michimusic.mobile.artist.artwork

/** Do not let an unrelated or redirected Commons page supply someone else's P180. */
internal object CommonsPageIdBindingPolicy {
    private val pageId = Regex("^[1-9][0-9]{0,11}$")

    fun verifiedId(
        requestedFile: String,
        returnedTitle: String?,
        namespace: String?,
        returnedPageId: String?,
    ): String? {
        if (namespace != "6" || returnedPageId == null || !pageId.matches(returnedPageId)) {
            return null
        }
        if (returnedTitle == null || !CommonsFileSourcePolicy.matchesRequestedFile(
                requestedFile, returnedTitle, namespace, null,
            )) return null
        return returnedPageId
    }
}
