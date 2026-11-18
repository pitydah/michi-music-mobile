package org.michimusic.mobile.ui.screens

import org.michimusic.core.intelligence.MichiGenreAbsencePolicy

/**
 * UI7 "Mosaico destacado" local crops (665 imported WebP files).
 *
 * The pack is a visual inventory, never a music taxonomy: a row can only claim the
 * genre that its own approved filename suffix spells, so a hand-edited manifest
 * cannot attach artwork to a genre the pack did not name. Rows are cross-checked
 * against installed assets and the whole catalog fails closed atomically.
 *
 * Two approval states are accepted and they are not interchangeable: the 312 rows
 * reviewed before 2026-11-14 keep [mosaicApprovalState], and the 353 rows imported
 * from the UI7 pending batch carry [mosaicPendingApprovalState], which means exactly
 * "bytes verified, artwork not yet reviewed". Carrying the state in the model is what
 * lets a later publisher refuse to present pending crops as approved ones.
 */
internal data class MosaicArtworkAsset(
    val genreKey: String,
    val filename: String,
    val widthPx: Int,
    val heightPx: Int,
    /** [mosaicApprovalState] or [mosaicPendingApprovalState]; never invented. */
    val approval: String = mosaicApprovalState,
    /** Offline-measured baked frame; null when the optical table has no row. */
    val opticalMask: Ui7OpticalMask? = null,
    /** Verified bottom-end chip region for this resource, when recorded. */
    val counterSafeRect: NormalizedRect? = null,
) {
    /** Declared source proportions; the renderer never distorts the artwork. */
    val ratio: Float get() = widthPx.toFloat() / heightPx.toFloat()

    /** True only for the 312 crops that passed art review. */
    val isVisuallyApproved: Boolean get() = approval == mosaicApprovalState
}

internal const val mosaicManifestHeader = "# UI7_MOSAICO_MANIFEST_V1"
internal const val mosaicFolder = "ui7/mosaic"
internal const val mosaicApprovalState = "USER_CONFIRMED_COMPLETE"
/** Bytes verified and published, artwork still awaiting visual review. */
internal const val mosaicPendingApprovalState = "IMPORTED_PENDING_VISUAL_APPROVAL"
/** Never publishable: a rejected crop has no business in the pack. */
internal const val mosaicRejectedState = "REJECTED"
/** The pack may hold both, and the row says which one applies. */
private val mosaicAcceptedStates = setOf(mosaicApprovalState, mosaicPendingApprovalState)
private const val maxMosaicManifestChars = 131_072
private const val maxMosaicPixels = 3_686_400L
private val mosaicFilenamePattern = Regex("UI7_MOSAICO_[0-9]{3}_([A-Za-z0-9]+)\\.webp")
private val mosaicSha256Pattern = Regex("[a-f0-9]{64}")

/**
 * Suffixes the CamelCase tokenizer cannot split into the exact canonical label.
 *
 * Everything else must resolve from plain words; there is no fuzzy or prefix
 * matching. These 18 entries are hand-checked one by one, because [genreGroupingKey]
 * keeps the hyphen of a label the file name spells without one: "ProtoPunk" tokenizes
 * to "proto punk", and MI-26 folds "Proto-Punk" to "proto-punk". A general rule that
 * dropped every hyphen would also merge identities that must stay apart, so the
 * exception is enumerated instead of generalised.
 */
private val mosaicSuffixAliases = mapOf(
    "RnB" to "r&b",
    "ProtoPunk" to "proto-punk",
    "LoFiHouse" to "lo-fi house",
    "AbstractHipHop" to "abstract hip-hop",
    "ExperimentalHipHop" to "experimental hip-hop",
    "SouthernHipHop" to "southern hip-hop",
    "PostBop" to "post-bop",
    "VPop" to "v-pop",
    "TPop" to "t-pop",
    "LoFiHipHop" to "lo-fi hip-hop",
    "BatuqueCaboVerdiano" to "batuque cabo-verdiano",
    "NeoPsychedelia" to "neo-psychedelia",
    "AvantGardeMetal" to "avant-garde metal",
    "GoGo" to "go-go",
    "AfroPeruvian" to "afro-peruvian",
    "AfroBrazilian" to "afro-brazilian",
    "DBeat" to "d-beat",
    "PinoyHipHop" to "pinoy hip-hop",
    // The unclassified bucket is not a genre: its label resolves to no key at all,
    // so the crop is bound to the internal identity instead of the Spanish words.
    "SinClasificar" to MichiGenreAbsencePolicy.UNCLASSIFIED_KEY,
)

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

/**
 * Fail closed atomically: a malformed, duplicated, unapproved or unpackaged row
 * invalidates the whole catalog. Keyed by the exact canonical genre identity.
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
        val canonical = genreGroupingKey(key)
        // MI-26 compares exact canonical identities: punctuation such as '/' and '&'
        // is meaningful, so "hip/hop" is not "hip-hop" and "r b" is not "r&b".
        val filenameCanonical = mosaicGenreKeyFromFilename(filename)
        if (canonical.isEmpty() || filenameCanonical != canonical)
            return reject("line_${lineNumber}_genre_identity")
        // Both published states are accepted; "approved" is not inferred from presence.
        if (!mosaicSha256Pattern.matches(sha) || approval !in mosaicAcceptedStates)
            return reject("line_${lineNumber}_integrity_or_approval")
        if (!mosaicArtworkGeometryValid(width, height)) return reject("line_${lineNumber}_geometry")
        if (filename !in installed) return reject("line_${lineNumber}_asset_missing")
        if (!usedFiles.add(filename) || !usedIndexes.add(canonical))
            return reject("line_${lineNumber}_duplicate")
        val mask = opticalMasks[filename]?.takeIf {
            it.sourceWidthPx == width && it.sourceHeightPx == height
        }
        artwork[canonical] = MosaicArtworkAsset(
            genreKey = key,
            filename = filename,
            widthPx = width,
            heightPx = height,
            approval = approval,
            opticalMask = mask,
            counterSafeRect = ui7MosaicCounterSafeRegion,
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
    val canonical = genreGroupingKey(genreId)
    if (canonical.isEmpty()) return null
    return artwork[canonical]
}
