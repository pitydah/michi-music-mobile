package org.michimusic.mobile.ui.screens

import android.util.Log
import org.michimusic.mobile.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * DEBUG-only UI7 asset observability.
 *
 * Two independent receipt streams, matching the state model:
 * - availability (`catalog_*`, `asset_*`, `decoded`, `decode_failed`)
 * - quality debt (`mask_pending`, `aspect_debt_dp`, `counter_pending`, `variant_needed`)
 *
 * The aggregate report is one row per category and contains technical asset data
 * only: never a file path, a track title or a personal label. No telemetry, no
 * persistent process and no per-frame logging.
 */
internal object GenreArtworkDiagnostics {
    private const val TAG = "UI7Artwork"

    const val CATALOG_LOADING = "catalog_loading"
    const val CATALOG_LOADED = "catalog_loaded"
    const val MANIFEST_REJECTED = "manifest_rejected"
    const val ASSET_UNMAPPED = "asset_unmapped"
    const val ASSET_RESOLVED = "asset_resolved"
    const val DECODED = "decoded"
    const val DECODE_FAILED = "decode_failed"
    const val MASK_PENDING = "mask_pending"
    const val ASPECT_DEBT = "aspect_debt"
    const val COUNTER_PENDING = "counter_pending"
    const val VARIANT_NEEDED = "variant_needed"

    private val counts = ConcurrentHashMap<String, Int>()

    private fun record(category: String) {
        if (BuildConfig.DEBUG) counts.merge(category, 1, Int::plus)
    }

    /** Aggregated availability+quality receipt for the current process. */
    fun snapshot(): Map<String, Int> = counts.toMap()

    fun reset() {
        counts.clear()
    }

    fun catalogLoading(view: String) {
        record(CATALOG_LOADING)
        if (BuildConfig.DEBUG) Log.d(TAG, "$view $CATALOG_LOADING")
    }

    fun catalogLoaded(view: String, accepted: Int, packagedWebps: Int) {
        record(CATALOG_LOADED)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "$view $CATALOG_LOADED accepted=$accepted packaged_webps=$packagedWebps")
        }
    }

    fun catalogRejected(view: String, reason: String) {
        record(MANIFEST_REJECTED)
        if (BuildConfig.DEBUG) Log.e(TAG, "$view $MANIFEST_REJECTED=$reason")
    }

    fun catalogError(view: String, error: Throwable) {
        record(MANIFEST_REJECTED)
        if (BuildConfig.DEBUG) Log.e(TAG, "$view manifest_read_failed", error)
    }

    fun opticalRowRejected(view: String, line: Int, reason: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, "$view optical_row_rejected line=$line reason=$reason")
    }

    fun assetUnmapped(view: String) {
        record(ASSET_UNMAPPED)
        if (BuildConfig.DEBUG) Log.d(TAG, "$view $ASSET_UNMAPPED")
    }

    fun assetResolved(view: String, filename: String) {
        record(ASSET_RESOLVED)
        if (BuildConfig.DEBUG) Log.d(TAG, "$view $ASSET_RESOLVED=$filename")
    }

    fun imageSuccess(view: String, filename: String) {
        record(DECODED)
        if (BuildConfig.DEBUG) Log.d(TAG, "$view $DECODED=$filename")
    }

    fun imageFailure(view: String, filename: String, error: Throwable) {
        record(DECODE_FAILED)
        if (BuildConfig.DEBUG) Log.e(TAG, "$view $DECODE_FAILED=$filename", error)
    }

    /** Reports the artistic debt of an asset that is still painted. */
    fun quality(view: String, filename: String, flags: Ui7VisualQualityFlags) {
        if (!BuildConfig.DEBUG) return
        if (flags.opticalMaskUnverified) {
            record(MASK_PENDING)
            Log.d(TAG, "$view $MASK_PENDING=$filename")
        }
        if (flags.aspectDebtPerSideDp > 0f) {
            record(ASPECT_DEBT)
            Log.d(TAG, "$view aspect_debt_dp=$filename band=${flags.aspectDebtPerSideDp}")
        }
        if (flags.counterRegionUnverified) {
            record(COUNTER_PENDING)
            Log.d(TAG, "$view $COUNTER_PENDING=$filename")
        }
        if (flags.needsPortraitVariant) {
            record(VARIANT_NEEDED)
            Log.d(TAG, "$view $VARIANT_NEEDED=$filename")
        }
    }

    fun counterPending(view: String, filename: String) {
        record(COUNTER_PENDING)
        if (BuildConfig.DEBUG) Log.d(TAG, "$view $COUNTER_PENDING=$filename overlay_hidden=true")
    }
}
