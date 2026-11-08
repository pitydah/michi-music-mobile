package org.michimusic.mobile.ui.motion

import androidx.compose.ui.graphics.Color
import org.michimusic.mobile.BuildConfig
import org.michimusic.core.models.TrackSource
import kotlin.math.max
import kotlin.math.min

/** Deliberately dark, bounded presentation colors. Never evidence of the audio signal. */
data class AeroDynamicPalette(
    val upper: Color,
    val center: Color,
    val lower: Color,
)

/** A palette input is always a local source, never an assumed remote cover. */
data class AeroLocalPaletteSource(val coverId: String?, val mediaUri: String?)

/**
 * LOCAL may use its MediaStore cover ID or local URI. SYNCED may use ONLY its
 * downloaded local audio URI; its remote album ID must not be reinterpreted as
 * an unrelated local MediaStore album ID. STREAMING never samples local covers.
 */
fun aeroLocalPaletteSource(
    source: TrackSource,
    coverId: String?,
    mediaUri: String?,
): AeroLocalPaletteSource? = when (source) {
    TrackSource.LOCAL -> if (aeroPaletteHasLocalSource(coverId, mediaUri))
        AeroLocalPaletteSource(coverId, mediaUri) else null
    TrackSource.SYNCED -> if (aeroPaletteHasLocalSource(null, mediaUri))
        AeroLocalPaletteSource(null, mediaUri) else null
    TrackSource.STREAMING -> null
}

val AeroDefaultDynamicPalette = AeroDynamicPalette(
    upper = Color(0xFF302038),
    center = Color(0xFF27233E),
    lower = Color(0xFF191222),
)

internal fun aeroDynamicMeshEnabled(
    buildEnabled: Boolean = BuildConfig.AERO_DYNAMIC_MESH_ENABLED,
): Boolean = buildEnabled


/** Pause/resume keeps the last sampled position instead of snapping to zero. */
fun aeroDynamicMeshNextPhase(previous: Float, enabled: Boolean): Float {
    val safe = if (previous.isFinite()) previous else 0f
    return if (enabled) (safe + 0.017f) % 6.2831855f else safe
}

/** Presentation policy is pure and testable; activity/PowerManager state is observed by the UI. */
fun aeroDynamicMeshShouldMove(
    playing: Boolean,
    foreground: Boolean,
    reducedMotion: Boolean,
    highContrast: Boolean,
    powerSaveMode: Boolean,
    lowRamDevice: Boolean,
): Boolean = playing && foreground && !reducedMotion && !highContrast &&
    !powerSaveMode && !lowRamDevice

private data class SampleBucket(
    var count: Int = 0,
    var redSum: Long = 0,
    var greenSum: Long = 0,
    var blueSum: Long = 0,
) {
    fun add(r: Int, g: Int, b: Int) {
        count++
        redSum += r
        greenSum += g
        blueSum += b
    }

    fun average(): Triple<Int, Int, Int> = Triple(
        (redSum / count).toInt(), (greenSum / count).toInt(), (blueSum / count).toInt(),
    )
}

/**
 * Extract subdued colors from a bounded, already-sampled pixel array.
 * Gray/black/white covers retain the canonical Aero palette rather than invent a hue.
 * No androidx.palette, bitmap allocation, network access or global background tinting.
 */
fun aeroDynamicPaletteFromPixels(pixels: IntArray): AeroDynamicPalette {
    if (pixels.isEmpty()) return AeroDefaultDynamicPalette
    val buckets = mutableMapOf<Int, SampleBucket>()
    for (argb in pixels) {
        if ((argb ushr 24) < 190) continue
        val r = (argb ushr 16) and 255
        val g = (argb ushr 8) and 255
        val b = argb and 255
        val bright = max(r, max(g, b))
        val dark = min(r, min(g, b))
        if (bright < 37 || bright > 246 || bright - dark < 23) continue
        // 4 bits/channel: a small histogram avoids choosing a single noisy pixel.
        val key = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
        buckets.getOrPut(key) { SampleBucket() }.add(r, g, b)
    }
    if (buckets.isEmpty()) return AeroDefaultDynamicPalette

    val ranked = buckets.values.sortedWith(
        compareByDescending<SampleBucket> { it.count }.thenByDescending {
            val (r, g, b) = it.average()
            max(r, max(g, b)) - min(r, min(g, b))
        },
    )
    val first = ranked.first().average()
    val second = ranked.firstOrNull { bucket ->
        val other = bucket.average()
        kotlin.math.abs(first.first - other.first) +
            kotlin.math.abs(first.second - other.second) +
            kotlin.math.abs(first.third - other.third) >= 105
    }?.average() ?: first

    fun shade(source: Triple<Int, Int, Int>, strength: Float): Color {
        // 0...1 bounded RGB, mixed with Obsidian. The darkest and brightest artwork
        // cannot turn the screen into a light theme or overtake white controls.
        return Color(
            red = (14f + source.first * strength).coerceIn(0f, 90f) / 255f,
            green = (11f + source.second * strength).coerceIn(0f, 78f) / 255f,
            blue = (20f + source.third * strength).coerceIn(0f, 95f) / 255f,
        )
    }
    return AeroDynamicPalette(
        upper = shade(first, 0.26f),
        center = shade(second, 0.23f),
        lower = shade(first, 0.12f),
    )
}
