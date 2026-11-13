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
}
