package org.michimusic.mobile.artist.artwork

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Read network truth at point of use, not only from the original WorkRequest. */
internal object ArtistArtworkAutoNetwork {
    data class State(val connected: Boolean, val unmetered: Boolean)

    fun snapshot(context: Context): State {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return State(false, false)
        val network = manager.activeNetwork ?: return State(false, false)
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return State(false, false)
        val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return State(connected,
            connected && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    fun permitted(context: Context): Boolean {
        val state = snapshot(context)
        return state.connected && (!ArtistArtworkAutoScheduler.unmeteredOnly(context) || state.unmetered)
    }
}
