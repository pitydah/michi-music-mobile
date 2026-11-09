package org.michimusic.mobile.ui.screens

/** Provider metadata may be missing; reject any known unsafe image content. */
internal fun acceptablePlaylistImageMetadata(
    mime: String?,
    sizeBytes: Long?,
    maxBytes: Long = 25L * 1024L * 1024L,
): Boolean = mime?.lowercase()?.startsWith("image/") == true &&
    !mime.equals("image/svg+xml", ignoreCase = true) &&
    sizeBytes?.let { it in 0L..maxBytes } != false
