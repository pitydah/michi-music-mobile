package org.michimusic.mobile.artist

import android.util.Log

/**
 * Development-only, stable event codes for the persistence journey. Never log a
 * library name, a file path, a biography, or any personal metadata.
 */
internal object ArtistEnrichmentDiagnostics {
    private const val TAG = "MichiArtistEnrichment"

    fun event(code: String, detail: String? = null) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        if (detail.isNullOrBlank()) Log.d(TAG, code)
        else Log.d(TAG, "$code cause=$detail")
    }
}
