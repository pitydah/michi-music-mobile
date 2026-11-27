package org.michimusic.mobile.artist

/** UI-only window over results already fetched, never a provider call. */
internal const val ARTIST_S3_GALLERY_PAGE_SIZE = 8

internal data class ArtistS3GalleryWindow(val offset: Int, val size: Int, val total: Int) {
    init {
        require(offset >= 0 && size >= 0 && size <= ARTIST_S3_GALLERY_PAGE_SIZE)
        require(total >= 0 && offset <= total && size <= total - offset)
    }
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = total - offset > size
    val firstNumber: Int get() = if (size == 0) 0 else offset + 1
    val lastNumber: Int get() = offset + size
    val nextOffset: Int get() = if (hasNext) offset + ARTIST_S3_GALLERY_PAGE_SIZE else offset
    val previousOffset: Int get() = (offset - ARTIST_S3_GALLERY_PAGE_SIZE).coerceAtLeast(0)
}

/** Align arbitrary offsets to a page; never compose more than eight candidates. */
internal fun artistS3GalleryWindow(total: Int, requestedOffset: Int): ArtistS3GalleryWindow {
    require(total >= 0)
    val safe = if (total == 0) 0 else requestedOffset.coerceIn(0, total - 1)
    val offset = (safe / ARTIST_S3_GALLERY_PAGE_SIZE) * ARTIST_S3_GALLERY_PAGE_SIZE
    return ArtistS3GalleryWindow(offset, (total - offset).coerceAtMost(ARTIST_S3_GALLERY_PAGE_SIZE), total)
}

internal enum class ArtistS3PickerScene { GALLERY, CONSENT, PREVIEW, CROP }

/** An ephemeral viewport identity, not persistence, HTTP consent or artist authority. */
internal data class ArtistS3ViewportKey(
    val scene: ArtistS3PickerScene,
    val pageOffset: Int = 0,
    val selectedItem: String? = null,
)
