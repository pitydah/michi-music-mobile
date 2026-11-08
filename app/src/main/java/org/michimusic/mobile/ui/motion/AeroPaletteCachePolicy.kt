package org.michimusic.mobile.ui.motion

/**
 * The palette cache never stores artwork, only three presentation colors.
 * Successful decoding includes intentionally monochrome artwork. It expires
 * after a bounded interval to reflect library rescans or replacement covers.
 * A failed read (permission revoked,
 * media temporarily unmounted, corrupt stream) is NOT a permanent verdict.
 */
internal data class AeroPaletteCacheEntry(
    val palette: AeroDynamicPalette,
    val validUntilElapsedMs: Long,
)

internal const val AERO_PALETTE_RETRY_MILLIS: Long = 30_000L
internal const val AERO_PALETTE_POSITIVE_TTL_MILLIS: Long = 15L * 60_000L

internal fun aeroPaletteCacheEntry(
    palette: AeroDynamicPalette,
    decodedArtwork: Boolean,
    elapsedMs: Long,
): AeroPaletteCacheEntry {
    val ttl = if (decodedArtwork) AERO_PALETTE_POSITIVE_TTL_MILLIS else AERO_PALETTE_RETRY_MILLIS
    val expiry = elapsedMs.coerceAtMost(Long.MAX_VALUE - ttl) + ttl
    return AeroPaletteCacheEntry(palette, expiry)
}

internal fun aeroPaletteCacheUsable(entry: AeroPaletteCacheEntry?, elapsedMs: Long): Boolean =
    entry != null && elapsedMs < entry.validUntilElapsedMs

/** Never authorize network URLs as album artwork sources for palette extraction. */
internal fun aeroPaletteHasLocalSource(coverId: String?, mediaUri: String?): Boolean {
    fun isLocalUri(raw: String?, allowAbsolutePath: Boolean): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            (allowAbsolutePath && trimmed.startsWith("/") && !trimmed.startsWith("//"))
    }
    // Only a COVER ID can be a bare positive MediaStore number. A SYNCED
    // track's mediaUri = "42" must not be treated as local audio or artwork.
    val numericLocalCover = coverId?.trim()?.toLongOrNull()?.let { it > 0 } == true
    return numericLocalCover || isLocalUri(coverId, allowAbsolutePath = false) ||
        isLocalUri(mediaUri, allowAbsolutePath = true)
}

/**
 * Avoid background artwork I/O even when playback events arrive while the
 * NavHost is still composed. Returning to foreground re-runs the request.
 * The foreground value is observed from the LifecycleOwner, not inferred from
 * playback activity. No audio or network state is modified by this policy.
 */
internal fun aeroShouldLoadDynamicPalette(
    foreground: Boolean,
    highContrast: Boolean,
    lowRamDevice: Boolean,
    coverId: String?,
    mediaUri: String?,
    powerSaveMode: Boolean = false,
): Boolean = foreground && !highContrast && !lowRamDevice && !powerSaveMode &&
    aeroPaletteHasLocalSource(coverId, mediaUri)
