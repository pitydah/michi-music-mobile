package org.michimusic.mobile.artist.artwork

import android.content.Context

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
        )
    }
}
