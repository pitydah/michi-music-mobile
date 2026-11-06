package org.michimusic.mobile.artist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Opt-in proof that a real provider portrait survives the app's Room
 * persistence key and the Coil disk cache. Enable with
 * `-e michiLiveProviders true`; never part of CI.
 */
@RunWith(AndroidJUnit4::class)
class ArtistLivePortraitPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val enabled =
        InstrumentationRegistry.getArguments().getString("michiLiveProviders") == "true"

    @Test
    fun verifiedPortraitSurvivesPersistenceAndASecondLoadComesFromDisk() = runBlocking {
        assumeTrue("live providers are opt-in", enabled)
        val repository = ArtistEnrichmentService.repository
        val candidate = repository.findCandidates("Midnight Oil")
            .firstOrNull { it.name.equals("Midnight Oil", ignoreCase = true) }
        assertNotNull("Midnight Oil must be found explicitly", candidate)
        val profile = repository.confirmedProfile(requireNotNull(candidate).mbid)
        assertEquals(ArtistMetadataOutcome.SUCCESS, profile.portraitOutcome)
        val portrait = requireNotNull(profile.portrait) { "SUCCESS must expose a portrait" }

        val store = ArtistEnrichmentPersistence.from(context)
        val libraryId = "live-qa-library"
        val albumIds = listOf("live-qa-album")
        try {
            store.clear(libraryId, "Midnight Oil", albumIds)
            store.save(libraryId, "Midnight Oil", albumIds, profile)
            val loaded = requireNotNull(store.load(libraryId, "Midnight Oil", albumIds)) {
                "Saved profile must be readable from Room"
            }
            assertEquals(profile.mbid, loaded.profile.mbid)
            assertEquals(portrait.imageUrl, loaded.profile.portrait?.imageUrl)
            assertEquals(portrait.license, loaded.profile.portrait?.license)
            println(
                "LIVE_PERSIST mbid=${loaded.profile.mbid} portrait=${loaded.profile.portrait?.imageUrl} " +
                    "license=${loaded.profile.portrait?.license} stale=${loaded.stale}",
            )

            // This test owns a fresh disk key so a previous live run cannot turn the
            // first request into a DISK hit and hide a broken network write path.
            val request = ImageRequest.Builder(context)
                .data(portrait.imageUrl)
                .diskCacheKey("live-portrait-${System.nanoTime()}")
                .build()
            val first = SingletonImageLoader.get(context).execute(request)
            if (first !is SuccessResult) {
                println("LIVE_IMAGE_ERROR ${(first as? coil3.request.ErrorResult)?.throwable}")
            }
            assertTrue("The licensed portrait must decode, not only return a URL", first is SuccessResult)
            val firstSuccess = first as SuccessResult
            println(
                "LIVE_IMAGE first dataSource=${firstSuccess.dataSource} " +
                    "diskKey=${firstSuccess.diskCacheKey} sampled=${firstSuccess.isSampled}",
            )
            assertEquals(DataSource.NETWORK, firstSuccess.dataSource)
            assertNotNull("The decoded portrait must be written to the Coil disk cache", firstSuccess.diskCacheKey)

            // A brand-new loader has an empty memory cache. Its network client
            // fails immediately, so success can only come from the persistent
            // disk cache written by the first load.
            val offline = ImageLoader.Builder(context)
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("coil").toOkioPath())
                        .maxSizeBytes(50L * 1024 * 1024)
                        .build()
                }
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = {
                        OkHttpClient.Builder().addInterceptor { throw IOException("offline proof") }.build()
                    }))
                }
                .build()
            try {
                val second = offline.execute(request)
                assertTrue("The cached portrait must render offline", second is SuccessResult)
                val secondSuccess = second as SuccessResult
                println(
                    "LIVE_IMAGE second dataSource=${secondSuccess.dataSource} " +
                        "diskKey=${secondSuccess.diskCacheKey}",
                )
                assertEquals(DataSource.DISK, secondSuccess.dataSource)
            } finally {
                offline.shutdown()
            }
        } finally {
            store.clear(libraryId, "Midnight Oil", albumIds)
        }
    }
}
