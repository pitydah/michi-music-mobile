package org.michimusic.mobile.ui.screens

import java.util.Locale

/**
 * UI7 "Mosaico destacado" local crops (312 approved WebP files).
 *
 * The pack is a visual inventory, never a music taxonomy: a row can only claim the
 * genre that its own approved filename suffix spells, so a hand-edited manifest
 * cannot attach artwork to a genre the pack did not name. Rows are cross-checked
 * against installed assets and the whole catalog fails closed atomically.
 */
internal data class MosaicArtworkAsset(
    val genreKey: String,
    val filename: String,
    val widthPx: Int,
    val heightPx: Int,
    /** Offline-measured baked frame; null when the optical table has no row. */
    val opticalMask: Ui7OpticalMask? = null,
    /** Verified bottom-end chip region for this resource, when recorded. */
    val counterSafeRect: NormalizedRect? = null,
) {
    /** Declared source proportions; the renderer never distorts the artwork. */
    val ratio: Float get() = widthPx.toFloat() / heightPx.toFloat()
}

internal const val mosaicManifestHeader = "# UI7_MOSAICO_MANIFEST_V1"
internal const val mosaicFolder = "ui7/mosaic"
internal const val mosaicApprovalState = "USER_CONFIRMED_COMPLETE"
private const val maxMosaicManifestChars = 96_000
private const val maxMosaicPixels = 3_686_400L
private val mosaicFilenamePattern = Regex("UI7_MOSAICO_[0-9]{3}_([A-Za-z0-9]+)\\.webp")
private val mosaicSha256Pattern = Regex("[a-f0-9]{64}")
private val mosaicIndexSeparators = Regex("[^a-z0-9]+")

/**
 * The only suffix the CamelCase tokenizer cannot split on its own. Everything else
 * must resolve from plain words; there is no fuzzy or prefix matching.
 */
private val mosaicSuffixAliases = mapOf("RnB" to "r&b")

internal fun mosaicArtworkGeometryValid(widthPx: Int, heightPx: Int): Boolean {
    if (widthPx !in 320..4096 || heightPx !in 240..4096) return false
    return widthPx.toLong() * heightPx.toLong() <= maxMosaicPixels &&
        widthPx.toDouble() / heightPx.toDouble() in 1.15..2.40
}

/** CamelCase and siglas split: SynthPop, UKGarage, EDM, LiquidDrumBass, JPop, YeYe. */
private fun mosaicCamelWords(suffix: String): List<String> {
    val words = mutableListOf<String>()
    var index = 0
    while (index < suffix.length) {
        val start = index
        if (suffix[index].isUpperCase()) {
            var run = index + 1
            while (run < suffix.length && suffix[run].isUpperCase()) run++
            if (run < suffix.length && suffix[run].isLowerCase()) {
                if (run - start > 1) {
                    words += suffix.substring(start, run - 1) // "UK" from "UKGarage"
                    index = run - 1
                } else {
                    var end = run
                    while (end < suffix.length && (suffix[end].isLowerCase() || suffix[end].isDigit())) end++
                    words += suffix.substring(start, end)
                    index = end
                }
            } else {
                words += suffix.substring(start, run) // "EDM", "NRG", trailing "B"
                index = run
            }
        } else {
            var end = start
            while (end < suffix.length && (suffix[end].isLowerCase() || suffix[end].isDigit())) end++
            words += suffix.substring(start, end)
            index = end
        }
    }
    return words
}

/**
 * Canonical genre key for a packaged crop, derived from its filename suffix only.
 * Returns null for any name the approved pack does not define.
 */
internal fun mosaicGenreKeyFromFilename(filename: String): String? {
    val suffix = mosaicFilenamePattern.matchEntire(filename)?.groupValues?.get(1) ?: return null
    val label = mosaicSuffixAliases[suffix] ?: mosaicCamelWords(suffix).joinToString(" ")
    if (label.isBlank()) return null
    return genreGroupingKey(label)
}

/** Case/punctuation-tolerant lookup form of a canonical key ("j-rock" == "j rock"). */
internal fun mosaicGenreIndexKey(genreKey: String): String =
    mosaicIndexSeparators.replace(genreKey.lowercase(Locale.ROOT), " ").trim()

/**
 * Fail closed atomically: a malformed, duplicated, unapproved or unpackaged row
 * invalidates the whole catalog. Keyed by the normalized genre index.
 * Format: genre_key, filename, sha256, width_px, height_px, approval (TAB separated).
 * Bytes and dimensions are re-verified by tools/validate_ui7_mosaic_assets.py at build time.
 */
internal fun mosaicApprovedArtwork(
    manifest: String,
    installed: Set<String>,
    onRejected: (String) -> Unit = {},
    opticalMasks: Map<String, Ui7OpticalMask> = emptyMap(),
): Map<String, MosaicArtworkAsset> {
    fun reject(reason: String): Map<String, MosaicArtworkAsset> {
        onRejected(reason)
        return emptyMap()
    }
    if (manifest.length > maxMosaicManifestChars) return reject("oversized_manifest")
    val rows = manifest.lineSequence().iterator()
    if (!rows.hasNext() || rows.next() != mosaicManifestHeader) return reject("invalid_header")
    val artwork = linkedMapOf<String, MosaicArtworkAsset>()
    val usedFiles = hashSetOf<String>()
    val usedIndexes = hashSetOf<String>()
    var lineNumber = 1
    for (line in rows) {
        lineNumber++
        if (line.isBlank() || line.startsWith('#')) continue
        val parts = line.split('\t')
        if (parts.size != 6) return reject("line_${lineNumber}_columns")
        val key = parts[0]
        val filename = parts[1]
        val sha = parts[2]
        val width = parts[3].toIntOrNull() ?: return reject("line_${lineNumber}_width")
        val height = parts[4].toIntOrNull() ?: return reject("line_${lineNumber}_height")
        val approval = parts[5]
        val index = mosaicGenreIndexKey(key)
        if (index.isEmpty() || mosaicGenreKeyFromFilename(filename) != key)
            return reject("line_${lineNumber}_genre_identity")
        if (!mosaicSha256Pattern.matches(sha) || approval != mosaicApprovalState)
            return reject("line_${lineNumber}_integrity_or_approval")
        if (!mosaicArtworkGeometryValid(width, height)) return reject("line_${lineNumber}_geometry")
        if (filename !in installed) return reject("line_${lineNumber}_asset_missing")
        if (!usedFiles.add(filename) || !usedIndexes.add(index))
            return reject("line_${lineNumber}_duplicate")
        val mask = opticalMasks[filename]?.takeIf {
            it.sourceWidthPx == width && it.sourceHeightPx == height
        }
        artwork[index] = MosaicArtworkAsset(
            genreKey = key,
            filename = filename,
            widthPx = width,
            heightPx = height,
            opticalMask = mask,
            counterSafeRect = if (mask?.verified == true) ui7MosaicCounterSafeRegion else null,
        )
    }
    // A single stray WebP must not silently publish an incomplete approved pack.
    if (installed.any { it.endsWith(".webp") && it !in usedFiles }) return reject("unexpected_webp")
    if (artwork.isEmpty()) return reject("empty_catalog")
    return artwork
}

/** The scoped library genre id is already the canonical grouping key. */
internal fun mosaicArtworkForGenre(
    genreId: String,
    artwork: Map<String, MosaicArtworkAsset>,
): MosaicArtworkAsset? {
    if (artwork.isEmpty()) return null
    val index = mosaicGenreIndexKey(genreId)
    if (index.isEmpty()) return null
    return artwork[index]
}
