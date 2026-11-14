package org.michimusic.mobile.ui.screens

import android.util.Log
import org.michimusic.mobile.BuildConfig

/** Debug-only UI7 asset observability. Never logs track locations, tags or user data. */
internal object GenreArtworkDiagnostics {
    private const val TAG = "UI7Artwork"

    fun catalogLoaded(view: String, accepted: Int, packagedWebps: Int) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$view manifest_accepted=$accepted packaged_webps=$packagedWebps")
    }

    fun catalogRejected(view: String, reason: String) {
        if (BuildConfig.DEBUG) Log.e(TAG, "$view manifest_rejected=$reason")
    }

    fun catalogError(view: String, error: Throwable) {
        if (BuildConfig.DEBUG) Log.e(TAG, "$view manifest_read_failed", error)
    }

    fun imageSuccess(view: String, filename: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$view decoded=$filename")
    }

    fun imageFailure(view: String, filename: String, error: Throwable) {
        if (BuildConfig.DEBUG) Log.e(TAG, "$view decode_failed=$filename", error)
    }

    fun unsupportedAspect(
        view: String,
        filename: String,
        sourceRatio: Float,
        targetWidthDp: Float,
        targetHeightDp: Float,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "$view unsupported_aspect=$filename source=$sourceRatio " +
                "target=${targetWidthDp}x${targetHeightDp} -> fallback")
        }
    }

    fun counterPlacement(view: String, filename: String, safe: Boolean) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "$view counter_placement=$filename safe=$safe")
        }
    }

    /**
     * §8.1 technical outcome of the asset decision. Debug-only: it never reaches
     * the UI and never names a genre or a file on screen.
     */
    fun renderOutcome(view: String, filename: String, outcome: Ui7RenderOutcome) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "$view render_outcome=$filename $outcome")
        }
    }
}
