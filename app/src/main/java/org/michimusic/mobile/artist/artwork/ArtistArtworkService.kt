package org.michimusic.mobile.artist.artwork

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Process-wide single-flight coordinator; one instance owns all provider federation. */
object ArtistArtworkService {
    @Volatile private var instance: ArtistArtworkCoordinator? = null

    fun from(context: Context): ArtistArtworkCoordinator {
        val app = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: build(app).also { instance = it }
        }
    }

    private fun build(context: Context): ArtistArtworkCoordinator {
        val http = ArtworkHttp()
        val wikidata = WikidataArtworkLookup(http)
        val commons = CommonsImageInfo(http)
        return ArtistArtworkCoordinator(
            wikidata = wikidata,
            commons = commons,
            openverse = OpenverseArtworkProvider(http),
            http = http,
            downloader = ArtworkDownloader(context),
            store = ArtistArtworkPersistence.from(context),
            verifySelection = { verifyCachedArtwork(context, it) },
            // The keystore read happens lazily, inside the suspending search, so the
            // picker can call it without touching disk on the main thread.
            keys = {
                val stored = withContext(Dispatchers.IO) { ArtworkProviderKeys.fanart(context) }
                FanartTvProvider.Keys(stored.projectKey, stored.personalKey)
            },
        )
    }
}
