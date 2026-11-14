package org.michimusic.mobile.ui.screens

/**
 * Static optical metadata produced by the read-only offline analyser.
 *
 * The 312 approved WebPs are never re-encoded. This table records the measured
 * baked-frame insets and four corner radii so the UI consumes them in O(1)
 * instead of scanning bitmaps at runtime.
 *
 * Fault isolation: a malformed *row* is skipped and reported; it never
 * invalidates the remaining table and never disables decoding of the packaged
 * WebPs. Only a missing/invalid header yields an empty table, which simply leaves
 * every resource on its provisional contour (`mask_pending`), not on a fallback.
 */
internal const val ui7OpticalManifestHeader = "# UI7_OPTICAL_MASKS_V1"
private const val maxOpticalManifestChars = 160_000
private const val maxOpticalRows = 4_096

/**
 * @param onInvalidRow called with the 1-based data line number and a short cause
 * for diagnostics; the row is dropped but its asset stays available.
 */
internal fun parseUi7OpticalMasks(
    manifest: String,
    onInvalidRow: ((line: Int, reason: String) -> Unit)? = null,
): Map<String, Ui7OpticalMask> {
    if (manifest.length > maxOpticalManifestChars) {
        onInvalidRow?.invoke(0, "oversized_manifest")
        return emptyMap()
    }
    val rows = manifest.lineSequence().iterator()
    if (!rows.hasNext() || rows.next() != ui7OpticalManifestHeader) {
        onInvalidRow?.invoke(0, "invalid_header")
        return emptyMap()
    }
    val result = linkedMapOf<String, Ui7OpticalMask>()
    var lineNumber = 1
    for (line in rows) {
        lineNumber++
        if (line.isBlank() || line.startsWith('#')) continue
        val parts = line.split('\t')
        if (parts.size != 12) {
            onInvalidRow?.invoke(lineNumber, "columns")
            continue
        }
        val filename = parts[0]
        if (!filename.endsWith(".webp") || filename in result) {
            onInvalidRow?.invoke(lineNumber, "identity")
            continue
        }
        val width = parts[1].toIntOrNull()
        val height = parts[2].toIntOrNull()
        if (width == null || height == null || width !in 320..4096 || height !in 200..4096) {
            onInvalidRow?.invoke(lineNumber, "geometry")
            continue
        }
        val values = FloatArray(8) { index ->
            parts[index + 3].toFloatOrNull() ?: Float.NaN
        }
        if (values.any { !it.isFinite() || it < 0f || it > 120f }) {
            onInvalidRow?.invoke(lineNumber, "measurement")
            continue
        }
        val verified = when (parts[11]) {
            "1" -> true
            "0" -> false
            else -> {
                onInvalidRow?.invoke(lineNumber, "verified_flag")
                continue
            }
        }
        if (result.size >= maxOpticalRows) {
            onInvalidRow?.invoke(lineNumber, "too_many_rows")
            continue
        }
        result[filename] = Ui7OpticalMask(
            sourceWidthPx = width,
            sourceHeightPx = height,
            leftInsetPx = values[0],
            topInsetPx = values[1],
            rightInsetPx = values[2],
            bottomInsetPx = values[3],
            topLeftRadiusPx = values[4],
            topRightRadiusPx = values[5],
            bottomRightRadiusPx = values[6],
            bottomLeftRadiusPx = values[7],
            verified = verified,
        )
    }
    return result
}

/** Number of rows the table could not use, for the aggregated DEBUG report. */
internal fun ui7OpticalUnverifiedCount(masks: Map<String, Ui7OpticalMask>): Int =
    masks.values.count { !it.verified }
