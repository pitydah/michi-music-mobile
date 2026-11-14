package org.michimusic.mobile.ui.screens

/**
 * Static optical metadata produced by the read-only offline analyser.
 *
 * The 312 approved WebPs are never re-encoded. This table records the measured
 * baked-frame insets and four corner radii so the UI consumes them in O(1)
 * instead of scanning bitmaps at runtime. Any malformed row invalidates the whole
 * table (fail closed); a missing row leaves that asset unverified and therefore
 * on the Compose fallback path.
 */
internal const val ui7OpticalManifestHeader = "# UI7_OPTICAL_MASKS_V1"
private const val maxOpticalManifestChars = 160_000
private const val maxOpticalRows = 4_096

internal fun parseUi7OpticalMasks(manifest: String): Map<String, Ui7OpticalMask> {
    if (manifest.length > maxOpticalManifestChars) return emptyMap()
    val rows = manifest.lineSequence().iterator()
    if (!rows.hasNext() || rows.next() != ui7OpticalManifestHeader) return emptyMap()
    val result = linkedMapOf<String, Ui7OpticalMask>()
    for (line in rows) {
        if (line.isBlank() || line.startsWith('#')) continue
        val parts = line.split('\t')
        if (parts.size != 12) return emptyMap()
        val filename = parts[0]
        if (!filename.endsWith(".webp") || filename in result) return emptyMap()
        val width = parts[1].toIntOrNull() ?: return emptyMap()
        val height = parts[2].toIntOrNull() ?: return emptyMap()
        val left = parts[3].toFloatOrNull() ?: return emptyMap()
        val top = parts[4].toFloatOrNull() ?: return emptyMap()
        val right = parts[5].toFloatOrNull() ?: return emptyMap()
        val bottom = parts[6].toFloatOrNull() ?: return emptyMap()
        val rtl = parts[7].toFloatOrNull() ?: return emptyMap()
        val rtr = parts[8].toFloatOrNull() ?: return emptyMap()
        val rbr = parts[9].toFloatOrNull() ?: return emptyMap()
        val rbl = parts[10].toFloatOrNull() ?: return emptyMap()
        val verified = when (parts[11]) {
            "1" -> true
            "0" -> false
            else -> return emptyMap()
        }
        if (width !in 320..4096 || height !in 200..4096) return emptyMap()
        if (listOf(left, top, right, bottom, rtl, rtr, rbr, rbl).any {
                !it.isFinite() || it < 0f || it > 120f
            }
        ) return emptyMap()
        if (result.size >= maxOpticalRows) return emptyMap()
        result[filename] = Ui7OpticalMask(
            sourceWidthPx = width,
            sourceHeightPx = height,
            leftInsetPx = left,
            topInsetPx = top,
            rightInsetPx = right,
            bottomInsetPx = bottom,
            topLeftRadiusPx = rtl,
            topRightRadiusPx = rtr,
            bottomRightRadiusPx = rbr,
            bottomLeftRadiusPx = rbl,
            verified = verified,
        )
    }
    return result
}
