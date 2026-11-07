package org.michimusic.mobile.artist

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkCoordinator
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkDownloader
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkHttp
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.CommonsImageInfo
import org.michimusic.mobile.artist.artwork.DownloadedArtwork
import org.michimusic.mobile.artist.artwork.OpenverseArtworkProvider
import org.michimusic.mobile.artist.artwork.WikidataArtworkLookup
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistEnrichmentOrchestratorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"

    private val imageBytes: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val url = chain.request().url
        val body = when {
            url.host == "www.wikidata.org" ->
                """{"entities":{"Q48284":{"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"Midnight Oil.jpg"}}}]},
                    "sitelinks":{}}}}"""
            url.host == "commons.wikimedia.org" && url.queryParameter("list") == "search" ->
                """{"query":{"search":[]}}"""
            url.host == "commons.wikimedia.org" && url.queryParameter("prop") == "info" ->
                """{"query":{"pages":{"1":{"pageid":1,"title":"File:Midnight Oil.jpg"}}}}"""
            url.host == "commons.wikimedia.org" && url.queryParameter("ids") == "M1" ->
                """{"entities":{"M1":{"statements":{"P180":[{"mainsnak":{"datavalue":{"value":{"id":"Q48284"}}}}]}}}}"""
            url.host == "commons.wikimedia.org" ->
                """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://thumb.wikimedia.org/Midnight_Oil.png",
                    "width":"1600","height":"900","mime":"image/png",
                    "descriptionurl":"https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg",
                    "extmetadata":{"LicenseShortName":{"value":"CC BY-SA 2.0"},
                    "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/2.0/"},
                    "Attribution":{"value":"Fab_Photo_29"}}}]}}}}"""
            url.host == "es.wikipedia.org" || url.host == "en.wikipedia.org" ->
                """{"query":{"pages":{"1":{}}}}"""
            url.host == "api.openverse.org" -> """{"results":[]}"""
            url.host == "thumb.wikimedia.org" || url.host == "upload.wikimedia.org" -> null
            else -> """{"error":"unexpected host"}"""
        }
        val responseBody = if (body == null) imageBytes.toResponseBody("image/png".toMediaType())
        else body.toResponseBody("application/json".toMediaType())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").header("Content-Type", responseBody.contentType().toString())
            .body(responseBody).build()
    }).build()

    private class Gateway : ArtistEnrichmentGateway {
        override suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate> = emptyList()
        override suspend fun confirmedProfile(mbid: String): ArtistExternalProfile = throw IOException("offline")
        override suspend fun refreshProfile(mbid: String): ArtistExternalProfile = throw IOException("offline")
    }

    private class ProfileStore : ArtistEnrichmentSelectionStore {
        var saved: PersistedArtistProfile? = null
        override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) = saved
        override suspend fun save(
            libraryId: String,
            credit: String,
            albumIds: List<String>,
            profile: ArtistExternalProfile,
        ) { saved = PersistedArtistProfile(profile, false) }
        override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) { saved = null }
    }

    private class MemoryStore(private val context: Context) : ArtistArtworkStore {
        val selections = linkedMapOf<ArtworkSlot, ArtistArtworkSelection>()
        var saves = 0
        override suspend fun load(anchor: ArtistIdentityAnchor) =
            selections.values.filter { it.confirmedMbid == anchor.confirmedMbid }
        override suspend fun loadAsset(assetId: String) = selections.values.firstOrNull { it.assetId == assetId }
        override suspend fun save(
            anchor: ArtistIdentityAnchor,
            candidate: ArtistArtworkCandidate,
            slot: ArtworkSlot,
            downloaded: DownloadedArtwork,
            focus: ArtworkFocus,
            manuallyChosen: Boolean,
        ): ArtistArtworkSelection {
            saves++
            val selection = ArtistArtworkSelection(
                slot = slot,
                assetId = "sha256:${downloaded.sha256}",
                confirmedMbid = anchor.confirmedMbid,
                localImagePath = File(context.filesDir, downloaded.relativePath).absolutePath,
                focus = focus,
                manuallyChosen = manuallyChosen,
                revision = saves.toLong(),
                sourcePageUrl = candidate.canonicalSourcePage,
                licenseId = candidate.licenseId,
                licenseUrl = candidate.licenseUrl,
                attribution = candidate.attribution,
                source = candidate.source,
                status = ArtworkLoadStatus.READY,
            )
            selections[slot] = selection
            return selection
        }
        override suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus) =
            selections.containsKey(slot)
        override suspend fun clear(anchor: ArtistIdentityAnchor) { selections.clear() }
        override suspend fun counts() = selections.size to selections.size
    }

    private fun coordinator(store: ArtistArtworkStore = MemoryStore(context)): ArtistArtworkCoordinator {
        val http = ArtworkHttp(client())
        return ArtistArtworkCoordinator(
            wikidata = WikidataArtworkLookup(http),
            commons = CommonsImageInfo(http),
            openverse = OpenverseArtworkProvider(http),
            http = http,
            downloader = ArtworkDownloader(context, client()),
            store = store,
        )
    }

    @Test
    fun confirmedIdentityIsPersistedBeforeAnImmediateProviderFailure() = runBlocking {
        val profileStore = ProfileStore()
        val orchestrator = ArtistEnrichmentOrchestrator(
            gateway = Gateway(),
            artwork = coordinator(),
            store = profileStore,
        )

        val result = orchestrator.enrich(
            artistCredit = "Midnight Oil",
            libraryId = "lib",
            albumIds = listOf("album"),
            mbid = mbid,
            forceRefresh = true,
        )

        assertEquals(ArtistPersistenceStatus.PERSISTED, result.persistenceStatus)
        assertTrue(result.persisted)
        assertEquals(mbid, result.profile?.mbid)
        assertNotNull("Explicit identity must reach persistence before HTTP", profileStore.saved)
        assertEquals(mbid, profileStore.saved?.profile?.mbid)
        assertTrue(profileStore.saved?.profile?.biography == null)
    }

    @Test
    fun providerSuccessNeverBecomesPersistenceSuccessWhenRoomSaveFails() = runBlocking {
        val http = ArtworkHttp(client())
        val artworkStore = MemoryStore(context)
        val coordinator = ArtistArtworkCoordinator(
            wikidata = WikidataArtworkLookup(http),
            commons = CommonsImageInfo(http),
            openverse = OpenverseArtworkProvider(http),
            http = http,
            downloader = ArtworkDownloader(context, client()),
            store = artworkStore,
        )
        val profile = ArtistExternalProfile(
            mbid = mbid,
            name = "Midnight Oil",
            biography = "Banda australiana.",
            biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
            biographyOutcome = ArtistMetadataOutcome.SUCCESS,
        )
        val failingStore = object : ArtistEnrichmentSelectionStore {
            override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) = null
            override suspend fun save(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
                profile: ArtistExternalProfile,
            ) {
                throw IllegalStateException("Room no disponible")
            }
            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
        }
        var providerCalls = 0
        val gateway = object : ArtistEnrichmentGateway {
            override suspend fun findCandidates(localCredit: String) = emptyList<ArtistLookupCandidate>()
            override suspend fun confirmedProfile(mbid: String): ArtistExternalProfile {
                providerCalls++
                return profile
            }
            override suspend fun refreshProfile(mbid: String): ArtistExternalProfile {
                providerCalls++
                return profile
            }
        }
        val orchestrator = ArtistEnrichmentOrchestrator(gateway, coordinator, failingStore)

        val result = orchestrator.enrich(
            artistCredit = "Midnight Oil",
            libraryId = "lib",
            albumIds = listOf("album"),
            mbid = mbid,
            forceRefresh = true,
        )

        assertEquals(ArtistPersistenceStatus.FAILED, result.persistenceStatus)
        assertTrue(!result.persisted)
        assertTrue(result.persistenceError.orEmpty().contains("Room no disponible"))
        assertEquals("Provider work must not start when identity persistence fails", 0, providerCalls)
        assertTrue(result.profile == null)
    }

    @Test
    fun musicBrainzFailureStillRepairsArtworkWithTheCachedVerifiedQid() = runBlocking {
        val http = ArtworkHttp(client())
        val artworkStore = MemoryStore(context)
        val coordinator = ArtistArtworkCoordinator(
            wikidata = WikidataArtworkLookup(http),
            commons = CommonsImageInfo(http),
            openverse = OpenverseArtworkProvider(http),
            http = http,
            downloader = ArtworkDownloader(context, client()),
            store = artworkStore,
        )
        val cached = ArtistExternalProfile(
            mbid = mbid,
            name = "Midnight Oil",
            biography = "Banda australiana.",
            biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
            biographyOutcome = ArtistMetadataOutcome.SUCCESS,
            portraitOutcome = ArtistMetadataOutcome.SUCCESS,
            wikidataQid = "Q48284",
        )
        val orchestrator = ArtistEnrichmentOrchestrator(
            gateway = Gateway(),
            artwork = coordinator,
            store = ProfileStore(),
        )

        val result = orchestrator.enrich(
            artistCredit = "Midnight Oil",
            libraryId = "lib",
            albumIds = listOf("album"),
            mbid = mbid,
            cached = cached,
            forceRefresh = true,
        )

        assertEquals(mbid, result.profile?.mbid)
        assertEquals(ArtistEnrichmentPhase.PARTIAL, result.phase)
        assertTrue(result.message.orEmpty().contains("reintentó la artwork"))
        assertTrue("Artwork must be repaired despite the profile failure", result.selections.isNotEmpty())
        assertTrue(artworkStore.saves > 0)
    }
}
