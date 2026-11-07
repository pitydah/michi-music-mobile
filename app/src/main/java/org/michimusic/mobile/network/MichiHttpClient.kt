package org.michimusic.mobile.network

/**
 * One truthful identity for every outbound Michi request. Wikimedia's image
 * CDN rejects Coil's generic OkHttp user agent with HTTP 403, and provider
 * APIs require an identifiable client.
 */
const val MICHI_HTTP_USER_AGENT =
    "MichiMusicMobile/0.1 (https://github.com/pitydah/michi-music-mobile)"
