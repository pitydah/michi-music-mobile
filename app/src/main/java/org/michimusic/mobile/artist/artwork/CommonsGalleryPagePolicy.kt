package org.michimusic.mobile.artist.artwork

/** P935 contains an article/gallery TITLE, not a URL and not a Commons category. */
internal fun validCommonsGalleryName(value: String): Boolean {
    val name = value.trim()
    return name.isNotEmpty() && name.length <= 160 &&
        !name.startsWith("File:", ignoreCase = true) &&
        !name.startsWith("Category:", ignoreCase = true) &&
        !name.any { it.isISOControl() || it in "/\\|?#<>[]{}" } &&
        ".." !in name
}

/** Pure check for the page-to-gallery binding, independent of image names. */
internal object CommonsGalleryPagePolicy {
    fun matchesRequestedGallery(requested: String, returned: String): Boolean =
        validCommonsGalleryName(requested) && validCommonsGalleryName(returned) &&
            requested.trim().replace('_', ' ').lowercase(java.util.Locale.ROOT) ==
            returned.trim().replace('_', ' ').lowercase(java.util.Locale.ROOT)
}
