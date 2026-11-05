package org.michimusic.mobile.artist.artwork

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistArtworkCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val anchor = ArtistIdentityAnchor(
        libraryId = "lib-local",
        creditKey = "credit-key",
        albumGroupKey = "group-key",
        confirmedMbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
        verifiedQid = "Q48284",
        displayCredit = "Midnight Oil",
    )

    private val imageBytes: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun pngMime() = "image/png".toMediaType()

    private fun wikidata(p18: String? = "Midnight Oil.jpg", depicts: String? = "Q48284"): String {
        val p18Claims = p18?.let {
            """[{"mainsnak":{"datavalue":{"value":"$it"}}}]"""
        } ?: "[]"
        val depictsClaims = depicts?.let {
            """[{"mainsnak":{"datavalue":{"value":"$it"}}}]"""
        } ?: "[]"
        return """{"entities":{"Q48284":{"claims":{"P18":$p18Claims,"P180":$depictsClaims},
            "sitelinks":{"eswiki":{"title":"Midnight Oil"},"enwiki":{"title":"Midnight Oil"}}}}}"""
    }

    private fun commonsInfo(
        license: String = "CC BY-SA 2.0",
        licenseUrl: String = "https://creativecommons.org/licenses/by-sa/2.0/",
        attribution: String = "Fab_Photo_29",
        width: String = "1600",
        height: String = "900",
    ): String = """{"query":{"pages":{"1":{"imageinfo":[{
        "thumburl":"https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/Midnight_Oil.png/960px.png",
        "width":"$width","height":"$height","mime":"image/png",
        "descriptionurl":"https://commons.wikimedia.org/wiki/File:Midnight_Oil.png",
        "extmetadata":{
            "LicenseShortName":{"value":"$license"},
            "LicenseUrl":{"value":"$licenseUrl"},
            "Attribution":{"value":"$attribution"}
        }
    }]}}}}"""

    private fun client(
        wikidataBody: String = wikidata(),
        commonsBody: String = commonsInfo(),
        commonsSearchBody: String = """{"query":{"search":[{"title":"File:Midnight Oil.jpg"}]}}""",
        wikipediaBody: String = """{"query":{"pages":{"1":{"pageimage":"Midnight Oil.jpg"}}}}""",
        onRequest: (String) -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val url = chain.request().url
        onRequest(url.toString())
        val body = when {
            url.host == "www.wikidata.org" && url.queryParameter("sites") == "commonswiki" ->
                """{"entities":{"M1":{"claims":{"P180":[{"mainsnak":{"datavalue":{"value":{"id":"Q48284"}}}}]}}}}"""
                    .toResponseBody("application/json".toMediaType())
            url.host == "www.wikidata.org" -> wikidataBody.toResponseBody("application/json".toMediaType())
            url.host == "commons.wikimedia.org" && url.queryParameter("list") == "search" ->
                commonsSearchBody.toResponseBody("application/json".toMediaType())
            url.host == "commons.wikimedia.org" -> commonsBody.toResponseBody("application/json".toMediaType())
            url.host == "es.wikipedia.org" || url.host == "en.wikipedia.org" ->
                wikipediaBody.toResponseBody("application/json".toMediaType())
            url.host == "thumb.wikimedia.org" || url.host == "upload.wikimedia.org" ->
                imageBytes.toResponseBody(pngMime())
            else -> """{"error":"unexpected host"}""".toResponseBody("application/json".toMediaType())
        }
        val contentType = body.contentType().toString()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").header("Content-Type", contentType).body(body).build()
    }).build()

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
                localImagePath = java.io.File(context.filesDir, downloaded.relativePath).absolutePath,
                focus = focus,
                manuallyChosen = manuallyChosen,
                revision = saves.toLong(),
                sourcePageUrl = candidate.canonicalSourcePage,
                licenseId = candidate.licenseId,
                licenseUrl = candidate.licenseUrl,
                attribution = candidate.attribution,
                source = candidate.source,
            )
            selections[slot] = selection
            return selection
        }

        override suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus): Boolean {
            val current = selections[slot] ?: return false
            selections[slot] = current.copy(focus = focus.normalized())
            return true
        }

        override suspend fun clear(anchor: ArtistIdentityAnchor) = selections.clear()
        override suspend fun counts() = selections.size to selections.size
    }

    private fun coordinator(client: OkHttpClient, store: ArtistArtworkStore): ArtistArtworkCoordinator {
        val http = ArtworkHttp(client)
        return ArtistArtworkCoordinator(
            wikidata = WikidataArtworkLookup(http),
            commons = CommonsImageInfo(http),
            openverse = OpenverseArtworkProvider(http),
            http = http,
            downloader = ArtworkDownloader(context, client),
            store = store,
        )
    }

    @Test
    fun p18IsDownloadedPersistedForBothSlotsAndReusedFromCache() = runBlocking {
        val requests = mutableListOf<String>()
        val store = MemoryStore(context)
        val coordinator = coordinator(client(onRequest = { requests += it }), store)

        val report = coordinator.resolve(anchor)

        assertEquals(ArtworkLoadStatus.READY, report.status)
        assertEquals(2, report.selections.size)
        assertTrue(store.saves == 2)
        val thumb = report.selections.getValue(ArtworkSlot.ARTIST_THUMB)
        val hero = report.selections.getValue(ArtworkSlot.ARTIST_HERO)
        assertEquals(thumb.assetId, hero.assetId)
        assertEquals("CC BY-SA 2.0", thumb.licenseId)
        assertTrue(java.io.File(thumb.localImagePath!!).isFile)
        // All three automatic providers run now; identity proof adds Commons MediaInfo reads.
        assertTrue(requests.count { it.contains("www.wikidata.org") } >= 2)
        assertTrue(requests.count { it.contains("commons.wikimedia.org/w/api.php") } >= 3)
        assertEquals(1, requests.count { it.contains("thumb.wikimedia.org") })
        val networkCount = requests.size

        val cached = coordinator.resolve(anchor)
        assertTrue(cached.fromCache)
        assertEquals(ArtworkLoadStatus.READY, cached.status)
        assertEquals(networkCount, requests.size)
        assertEquals(2, store.saves)
    }

    @Test
    fun rejectedLicenseNeverBecomesAnImageAndIsReportedAsRejected() = runBlocking {
        val store = MemoryStore(context)
        val requests = mutableListOf<String>()
        val coordinator = coordinator(client(
            commonsBody = commonsInfo(
                license = "CC BY-NC 2.0",
                licenseUrl = "https://creativecommons.org/licenses/by-nc/2.0/",
            ),
            onRequest = { requests += it },
        ), store)

        val report = coordinator.resolve(anchor)

        assertEquals(ArtworkLoadStatus.REJECTED, report.status)
        assertTrue(report.selections.isEmpty())
        assertEquals(0, store.saves)
        assertTrue(requests.none { it.contains("thumb.wikimedia.org") })
        assertTrue(report.outcomes.any { it.reason == "NO_ALLOWED_LICENSE" })
    }

    @Test
    fun artistWithoutP18EndsAsNoImageWithoutInventingACandidate() = runBlocking {
        val store = MemoryStore(context)
        val requests = mutableListOf<String>()
        val coordinator = coordinator(client(
            wikidataBody = wikidata(p18 = null, depicts = null),
            commonsSearchBody = """{"query":{"search":[]}}""",
            wikipediaBody = """{"query":{"pages":{"1":{}}}}""",
            onRequest = { requests += it },
        ), store)

        val report = coordinator.resolve(anchor)

        assertEquals(ArtworkLoadStatus.NO_IMAGE, report.status)
        assertTrue(report.selections.isEmpty())
        assertEquals(0, store.saves)
        assertTrue(requests.none { it.contains("thumb.wikimedia.org") || it.contains("upload.wikimedia.org") })
    }

    @Test
    fun missingP18FallsBackToDepictsSearchAndPersistsTheCommonsFile() = runBlocking {
        val store = MemoryStore(context)
        val requests = mutableListOf<String>()
        val coordinator = coordinator(client(
            wikidataBody = wikidata(p18 = null, depicts = "Q48284"),
            onRequest = { requests += it },
        ), store)

        val report = coordinator.resolve(anchor)

        assertEquals(ArtworkLoadStatus.READY, report.status)
        val outcome = report.outcomes.first { it.source == ArtworkSource.COMMONS_P180 }
        assertEquals(ArtworkProviderStatus.AVAILABLE, outcome.status)
        assertEquals(ArtworkSource.COMMONS_P180, report.selections.getValue(ArtworkSlot.ARTIST_THUMB).source)
        assertTrue(requests.any { it.contains("haswbstatement") || it.contains("list=search") })
        assertTrue(java.io.File(report.selections.getValue(ArtworkSlot.ARTIST_HERO).localImagePath!!).isFile)
    }

    @Test
    fun missingP18AndDepictsFallsBackToTheVerifiedArticlePageImage() = runBlocking {
        val store = MemoryStore(context)
        val requests = mutableListOf<String>()
        val coordinator = coordinator(client(
            wikidataBody = wikidata(p18 = null, depicts = null),
            commonsSearchBody = """{"query":{"search":[]}}""",
            onRequest = { requests += it },
        ), store)

        val report = coordinator.resolve(anchor)

        assertEquals(ArtworkLoadStatus.READY, report.status)
        val outcome = report.outcomes.first { it.source == ArtworkSource.WIKIPEDIA_PAGEIMAGE }
        assertEquals(ArtworkProviderStatus.AVAILABLE, outcome.status)
        assertEquals(ArtworkSource.WIKIPEDIA_PAGEIMAGE,
            report.selections.getValue(ArtworkSlot.ARTIST_THUMB).source)
        assertTrue(requests.any { it.contains("es.wikipedia.org") })
        assertTrue(java.io.File(report.selections.getValue(ArtworkSlot.ARTIST_THUMB).localImagePath!!).isFile)
    }

    @Test
    fun concurrentResolutionsForTheSameAnchorShareASingleDownload() = runBlocking {
        val store = MemoryStore(context)
        val requests = mutableListOf<String>()
        val coordinator = coordinator(client(onRequest = { requests += it }), store)

        val reports = listOf(
            async { coordinator.resolve(anchor) },
            async { coordinator.resolve(anchor) },
        ).awaitAll()

        assertTrue(reports.all { it.status == ArtworkLoadStatus.READY })
        assertEquals(2, store.saves)
        assertEquals(1, requests.count { it.contains("thumb.wikimedia.org") })
    }

    @Test
    fun anAssetFromAnotherMbidNeverReachesThisArtistStore() = runBlocking {
        val store = MemoryStore(context)
        val other = anchor.copy(
            confirmedMbid = "aaa436da-b331-4e58-a0fd-3d64d090c120",
            // The fixture Wikidata snapshot is pinned to Q48284; only the MBID changes,
            // which is exactly the identity input under test.
            verifiedQid = anchor.verifiedQid,
        )
        val coordinator = coordinator(client(), store)

        val report = coordinator.resolve(other)

        assertEquals(ArtworkLoadStatus.READY, report.status)
        assertEquals(2, store.saves)
        assertTrue(store.selections.values.all { it.confirmedMbid == other.confirmedMbid })
    }

    @Test
    fun manualThumbSurvivesAutomaticHeroRecovery() = runBlocking {
        val store = MemoryStore(context)
        val manualThumb = cached(ArtworkSlot.ARTIST_THUMB, manuallyChosen = true)
        store.selections[ArtworkSlot.ARTIST_THUMB] = manualThumb
        val coordinator = coordinator(client(), store)

        val report = coordinator.resolve(anchor)

        assertEquals(ArtworkLoadStatus.READY, report.status)
        assertEquals(1, store.saves)
        assertEquals(manualThumb.assetId, store.selections.getValue(ArtworkSlot.ARTIST_THUMB).assetId)
        assertTrue(store.selections.getValue(ArtworkSlot.ARTIST_THUMB).manuallyChosen)
        assertTrue(store.selections.containsKey(ArtworkSlot.ARTIST_HERO))
    }

    @Test
    fun forceRefreshNeverOverwritesManualSlots() = runBlocking {
        val store = MemoryStore(context)
        val thumb = cached(ArtworkSlot.ARTIST_THUMB, manuallyChosen = true)
        val hero = cached(ArtworkSlot.ARTIST_HERO, manuallyChosen = true)
        store.selections[ArtworkSlot.ARTIST_THUMB] = thumb
        store.selections[ArtworkSlot.ARTIST_HERO] = hero
        val requests = mutableListOf<String>()
        val coordinator = coordinator(client(onRequest = { requests += it }), store)

        val report = coordinator.resolve(anchor, forceRefresh = true)

        assertEquals(ArtworkLoadStatus.READY, report.status)
        assertTrue(report.fromCache)
        assertEquals(0, store.saves)
        assertEquals(thumb.assetId, store.selections.getValue(ArtworkSlot.ARTIST_THUMB).assetId)
        assertEquals(hero.assetId, store.selections.getValue(ArtworkSlot.ARTIST_HERO).assetId)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun crossMbidSelectionNeverSatisfiesAnotherArtist() = runBlocking {
        val store = MemoryStore(context)
        val otherMbid = "aaa436da-b331-4e58-a0fd-3d64d090c120"
        store.selections[ArtworkSlot.ARTIST_THUMB] =
            cached(ArtworkSlot.ARTIST_THUMB, manuallyChosen = true, mbid = otherMbid)
        store.selections[ArtworkSlot.ARTIST_HERO] =
            cached(ArtworkSlot.ARTIST_HERO, manuallyChosen = true, mbid = otherMbid)
        val coordinator = coordinator(client(), store)

        val report = coordinator.resolve(anchor)

        assertEquals(ArtworkLoadStatus.READY, report.status)
        assertEquals(2, store.saves)
        assertTrue(store.selections.values.all { it.confirmedMbid == anchor.confirmedMbid })
    }

    @Test
    fun missingThumbRecoversWithoutReplacingAnAutomaticHero() = runBlocking {
        val store = MemoryStore(context)
        val hero = cached(ArtworkSlot.ARTIST_HERO, manuallyChosen = false)
        store.selections[ArtworkSlot.ARTIST_HERO] = hero
        val coordinator = coordinator(client(), store)

        val report = coordinator.resolve(anchor)

        assertEquals(ArtworkLoadStatus.READY, report.status)
        assertEquals(1, store.saves)
        assertEquals(hero.assetId, store.selections.getValue(ArtworkSlot.ARTIST_HERO).assetId)
        assertTrue(store.selections.containsKey(ArtworkSlot.ARTIST_THUMB))
    }

    private fun cached(
        slot: ArtworkSlot,
        manuallyChosen: Boolean,
        mbid: String = anchor.confirmedMbid,
    ): ArtistArtworkSelection {
        val file = File(context.filesDir, "artist-artwork/cached-${slot.name}-$mbid.png").apply {
            parentFile?.mkdirs()
            writeBytes(imageBytes)
        }
        return ArtistArtworkSelection(
            slot = slot,
            assetId = "sha256:${"a".repeat(64)}",
            confirmedMbid = mbid,
            localImagePath = file.absolutePath,
            focus = ArtworkFocus(),
            manuallyChosen = manuallyChosen,
            revision = 1L,
            sourcePageUrl = "https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg",
            licenseId = "CC BY-SA 2.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
            attribution = "Author",
            source = ArtworkSource.COMMONS_P18,
        )
    }
}
