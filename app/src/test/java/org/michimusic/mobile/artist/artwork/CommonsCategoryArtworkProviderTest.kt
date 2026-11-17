package org.michimusic.mobile.artist.artwork

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommonsCategoryArtworkProviderTest {
    private val anchor = ArtistIdentityAnchor(
        libraryId = "lib-1", creditKey = "midnight oil", albumGroupKey = "stable-1",
        confirmedMbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
        verifiedQid = "Q48284", displayCredit = "Midnight Oil",
    )

    private fun client(category: String?, depicts: Boolean, requests: MutableList<String>, secondPage: Boolean = false): OkHttpClient {
        val wikidata = WikidataApiFixture(category = category)
        return OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            requests += url.toString()
            val response = when {
                url.host == "www.wikidata.org" -> wikidata.response(url)
                url.queryParameter("list") == "search" ->
                    """{"query":{"search":[{"title":"File:Not_artist.jpg"},
                        {"title":"File:Actual_artist.jpg"}]}}"""
                url.queryParameter("list") == "categorymembers" && secondPage &&
                    url.queryParameter("cmcontinue") == null ->
                    """{"continue":{"cmcontinue":"file|next"},
                        "query":{"categorymembers":[{"ns":6,"title":"File:Not_artist.jpg"}]}}"""
                url.queryParameter("list") == "categorymembers" && secondPage ->
                    """{"query":{"categorymembers":[{"ns":6,"title":"File:Actual_artist.jpg"}]}}"""
                url.queryParameter("list") == "categorymembers" ->
                    """{"query":{"categorymembers":[{"ns":6,"title":"File:Not_artist.jpg"},
                        {"ns":6,"title":"File:Actual_artist.jpg"},
                        {"ns":14,"title":"Category:Subcategory"}]}}"""
                url.queryParameter("prop") == "info" ->
                    """{"query":{"pages":{
                        "41":{"pageid":41,"ns":6,"title":"File:Not_artist.jpg"},
                        "42":{"pageid":42,"ns":6,"title":"File:Actual_artist.jpg"}}}}"""
                url.queryParameter("ids")?.startsWith("M") == true -> {
                    val target = if (depicts) "Q48284" else "Q999999"
                    """{"entities":{
                        "M41":{"statements":{"P180":[{"mainsnak":{"datavalue":{"value":{"id":"Q999999"}}}}]}},
                        "M42":{"statements":{"P180":[{"mainsnak":{"datavalue":{"value":{"id":"$target"}}}}]}}}}"""
                }
                url.queryParameter("prop") == "imageinfo" ->
                    """{"query":{"pages":{"42":{"imageinfo":[{
                        "thumburl":"https://upload.wikimedia.org/wikipedia/commons/test.jpg",
                        "width":"900","height":"600","mime":"image/jpeg",
                        "descriptionurl":"https://commons.wikimedia.org/wiki/File:Actual_artist.jpg",
                        "extmetadata":{
                          "LicenseShortName":{"value":"CC BY-SA 4.0"},
                          "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"},
                          "Attribution":{"value":"Photo Author"}
                        }}]}}}}"""
                else -> error("Unexpected HTTP request $url")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", "application/json")
                .body(response.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    }

    private fun provider(client: OkHttpClient): CommonsCategoryArtworkProvider {
        val http = ArtworkHttp(client)
        return CommonsCategoryArtworkProvider(http, WikidataArtworkLookup(http), CommonsImageInfo(http))
    }

    @Test fun verifiedCategoryCandidateNeedsFileLevelP180AndLicense() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<String>()
        val result = provider(client("Midnight Oil (band)", depicts = true, requests))
            .findCandidates(anchor, ArtworkSlot.ARTIST_THUMB)
        assertTrue(result is ProviderArtworkResult.Available)
        val candidates = (result as ProviderArtworkResult.Available).items
        assertEquals(1, candidates.size)
        assertEquals(ArtworkSource.COMMONS_CATEGORY, candidates.single().source)
        assertEquals(ArtworkEvidence.VERIFIED_QID_REFERENCE, candidates.single().evidence)
        assertEquals(ArtworkRights.VERIFIED_ALLOWED, candidates.single().rights)
        assertTrue(requests.any { it.contains("categorymembers") && it.contains("Midnight") })
        assertEquals(1, requests.count { it.contains("imageinfo") })
        // One batched Commons MediaInfo read; the Wikidata snapshot also uses wbgetentities.
        assertEquals(1, requests.count {
            it.contains("commons.wikimedia.org") && it.contains("wbgetentities")
        })
        assertEquals(1, requests.count { it.contains("prop=info") })
    }

    @Test fun secondPageRecoversQidVerifiedFileWithoutTreatingCategoryAsProof() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val result = provider(client("Midnight Oil (band)", depicts = true,
            requests = calls, secondPage = true))
            .findCandidates(anchor, ArtworkSlot.ARTIST_HERO)
        assertTrue(result is ProviderArtworkResult.Available)
        assertEquals(1, (result as ProviderArtworkResult.Available).items.size)
        assertEquals(2, calls.count { it.contains("categorymembers") })
        assertEquals(2, calls.count { it.contains("prop=info") })
        // Two batched Commons MediaInfo reads; the Wikidata snapshot also uses wbgetentities.
        assertEquals(2, calls.count {
            it.contains("commons.wikimedia.org") && it.contains("wbgetentities")
        })
        assertEquals(1, calls.count { it.contains("imageinfo") })
        assertTrue(calls.any { it.contains("cmcontinue=file%7Cnext") })
    }

    @Test fun p180UsesOneSearchPageAndOnlyTwoBatchedIdentityRequests() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<String>()
        val client = client("Midnight Oil (band)", depicts = true, requests)
        val http = ArtworkHttp(client)
        val result = CommonsP180Provider(http, WikidataArtworkLookup(http), CommonsImageInfo(http))
            .findCandidates(anchor, ArtworkSlot.ARTIST_HERO)
        assertTrue(result is ProviderArtworkResult.Available)
        assertEquals(1, (result as ProviderArtworkResult.Available).items.size)
        assertEquals(1, requests.count { it.contains("list=search") })
        assertEquals(1, requests.count { it.contains("prop=info") })
        assertEquals(1, requests.count { it.contains("wbgetentities") })
        assertEquals(1, requests.count { it.contains("imageinfo") })
        assertTrue(requests.any { it.contains("srlimit=12") })
    }

    @Test fun p180MalformedQidDoesNotQuerySearchOrCommons() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<String>()
        val client = client("Midnight Oil (band)", depicts = true, requests)
        val http = ArtworkHttp(client)
        val result = CommonsP180Provider(http, WikidataArtworkLookup(http), CommonsImageInfo(http))
            .findCandidates(anchor.copy(verifiedQid = "Qmalformed"), ArtworkSlot.ARTIST_THUMB)
        assertEquals(ProviderArtworkResult.NoMatch, result)
        assertTrue(requests.isEmpty())
    }

    @Test fun categoryMembershipAloneNeverAuthenticatesArtist() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<String>()
        val result = provider(client("Midnight Oil (band)", depicts = false, requests))
            .findCandidates(anchor, ArtworkSlot.ARTIST_HERO)
        assertEquals(ProviderArtworkResult.NoMatch, result)
        assertTrue(requests.none { it.contains("imageinfo") })
    }

    @Test fun noCategoryOrInvalidCategoryMustNotCallCommons() = kotlinx.coroutines.runBlocking {
        for (category in listOf<String?>(null, "../bad-category")) {
            val requests = mutableListOf<String>()
            val result = provider(client(category, depicts = true, requests))
                .findCandidates(anchor, ArtworkSlot.ARTIST_THUMB)
            assertEquals(ProviderArtworkResult.NoMatch, result)
            // The lookup asks Wikidata by property, in parallel; a claim that is absent
            // needs no follow-up, so counting calls pinned an implementation detail that
            // also raced with cancellation. What this test guarantees is the boundary:
            // without a usable category, Commons is never touched.
            assertTrue(requests.any { it.contains("wikidata.org") })
            assertTrue(requests.none { it.contains("commons.wikimedia.org") })
        }
    }
}
