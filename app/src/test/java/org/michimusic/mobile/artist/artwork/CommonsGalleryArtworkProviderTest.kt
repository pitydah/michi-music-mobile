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

/** Exercise the new provider through OkHttp interception without calling remote servers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommonsGalleryArtworkProviderTest {
    private val anchor = ArtistIdentityAnchor(
        libraryId = "lib-1", creditKey = "band", albumGroupKey = "assoc-1",
        confirmedMbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
        verifiedQid = "Q48284", displayCredit = "Midnight Oil",
    )

    private fun client(gallery: String?, qidMatches: Boolean, calls: MutableList<String>,
        secondPage: Boolean = false, malformedContinuation: Boolean = false,
        lateVerifiedFile: Boolean = false, foreignMetadata: Boolean = false): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            calls += url.toString()
            val wikidata = WikidataApiFixture(gallery = gallery)
            val response = when {
                url.host == "www.wikidata.org" -> wikidata.response(url)
                url.queryParameter("prop") == "images" && secondPage &&
                    url.queryParameter("imcontinue") == null -> {
                    val next = if (malformedContinuation) "bad\\ncontinuation" else "File:second|11"
                    """{"continue":{"imcontinue":"$next","continue":"-||"},
                      "query":{"pages":{"1":{"ns":0,"title":"Midnight Oil","images":[
                        {"ns":6,"title":"File:Unrelated.jpg"}]}}}}"""
                }
                url.queryParameter("prop") == "images" && secondPage ->
                    """{"query":{"pages":{"1":{"ns":0,"title":"Midnight Oil","images":[
                      {"ns":6,"title":"File:Verified.jpg"}]}}}}"""
                url.queryParameter("prop") == "images" && lateVerifiedFile -> {
                    val unrelated = (1..12).joinToString(",") {
                        """{"ns":6,"title":"File:Wrong_$it.jpg"}"""
                    }
                    """{"query":{"pages":{"1":{"ns":0,"title":"Midnight Oil","images":[
                      $unrelated,{"ns":6,"title":"File:Verified.jpg"}]}}}}"""
                }
                url.queryParameter("prop") == "images" ->
                    """{"query":{"pages":{"1":{"ns":0,"title":"Midnight Oil","images":[
                      {"ns":6,"title":"File:Unrelated.jpg"},
                      {"ns":6,"title":"File:Verified.jpg"},
                      {"ns":14,"title":"Category:Unrelated"}]}}}}"""
                url.queryParameter("prop") == "info" ->
                    """{"query":{"pages":{
                      "41":{"pageid":41,"ns":6,"title":"File:Unrelated.jpg"},
                      "42":{"pageid":42,"ns":6,"title":"File:Verified.jpg"}}}}"""
                url.queryParameter("ids")?.startsWith("M") == true -> {
                    val qid = if (qidMatches) "Q48284" else "Q999999"
                    """{"entities":{
                      "M41":{"statements":{"P180":[{"mainsnak":{"datavalue":{"value":{"id":"Q999999"}}}}]}},
                      "M42":{"statements":{"P180":[{"mainsnak":{"datavalue":{"value":{"id":"$qid"}}}}]}}}}"""
                }
                url.queryParameter("prop") == "imageinfo" && foreignMetadata ->
                    """{"query":{"pages":{"42":{"imageinfo":[{
                      "thumburl":"https://upload.wikimedia.org/wikipedia/commons/verified.jpg",
                      "width":"960","height":"720","mime":"image/jpeg",
                      "descriptionurl":"https://commons.wikimedia.org/wiki/File:Other.jpg",
                      "extmetadata":{"LicenseShortName":{"value":"CC BY-SA 4.0"},
                        "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"},
                        "Attribution":{"value":"Photo Author"}}}]}}}}"""
                url.queryParameter("prop") == "imageinfo" ->
                    """{"query":{"pages":{"42":{"imageinfo":[{
                      "thumburl":"https://upload.wikimedia.org/wikipedia/commons/verified.jpg",
                      "width":"960","height":"720","mime":"image/jpeg",
                      "descriptionurl":"https://commons.wikimedia.org/wiki/File:Verified.jpg",
                      "extmetadata":{"LicenseShortName":{"value":"CC BY-SA 4.0"},
                        "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"},
                        "Attribution":{"value":"Photo Author"}}}]}}}}"""
                else -> error("Unexpected $url")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .header("Content-Type", "application/json")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }).build()

    private fun provider(client: OkHttpClient): CommonsGalleryArtworkProvider {
        val http = ArtworkHttp(client)
        return CommonsGalleryArtworkProvider(http, WikidataArtworkLookup(http), CommonsImageInfo(http))
    }

    @Test fun acceptsOnlyFileLevelDepictionAndItsOwnLicence() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val result = provider(client("Midnight Oil", true, calls))
            .findCandidates(anchor, ArtworkSlot.ARTIST_THUMB)
        assertTrue(result is ProviderArtworkResult.Available)
        val accepted = (result as ProviderArtworkResult.Available).items
        assertEquals(1, accepted.size)
        assertEquals(ArtworkSource.COMMONS_GALLERY, accepted.single().source)
        assertEquals(ArtworkRights.VERIFIED_ALLOWED, accepted.single().rights)
        assertEquals(ArtworkEvidence.VERIFIED_QID_REFERENCE, accepted.single().evidence)
        assertEquals(1, calls.count { it.contains("prop=images") })
        assertEquals(1, calls.count { it.contains("prop=info") })
        // One batched Commons MediaInfo read; the Wikidata P935 snapshot also uses wbgetentities.
        assertEquals(1, calls.count {
            it.contains("commons.wikimedia.org") && it.contains("wbgetentities")
        })
        assertEquals(1, calls.count { it.contains("imageinfo") })
    }

    @Test fun membershipIsNotIdentityEvidence() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val result = provider(client("Midnight Oil", false, calls))
            .findCandidates(anchor, ArtworkSlot.ARTIST_HERO)
        assertEquals(ProviderArtworkResult.NoMatch, result)
        assertTrue(calls.none { it.contains("imageinfo") })
    }

    @Test fun noP935OrMalformedP935StopsWithoutCommonsRequest() = kotlinx.coroutines.runBlocking {
        for (value in listOf<String?>(null, "Category:Other", "../bad")) {
            val calls = mutableListOf<String>()
            val result = provider(client(value, true, calls))
                .findCandidates(anchor, ArtworkSlot.ARTIST_THUMB)
            assertEquals(ProviderArtworkResult.NoMatch, result)
            // K48-R12: the snapshot costs four small Wikidata reads now (sitelinks plus
            // three single properties). What must never happen is a Commons request.
            // Same boundary as the category test: no usable gallery means Commons is never
            // asked. The exact number of Wikidata calls is an implementation detail.
            assertTrue(calls.any { it.contains("wikidata.org") })
            assertTrue(calls.none { it.contains("commons.wikimedia.org") })
        }
    }
    @Test fun secondGalleryPageRecoversVerifiedFileWithBoundedMediaInfo() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val result = provider(client("Midnight Oil", true, calls, secondPage = true))
            .findCandidates(anchor, ArtworkSlot.ARTIST_HERO)
        assertTrue(result is ProviderArtworkResult.Available)
        assertEquals(1, (result as ProviderArtworkResult.Available).items.size)
        assertEquals(2, calls.count { it.contains("prop=images") })
        assertEquals(2, calls.count { it.contains("prop=info") })
        // Two batched Commons MediaInfo reads; the Wikidata P935 snapshot also uses wbgetentities.
        assertEquals(2, calls.count {
            it.contains("commons.wikimedia.org") && it.contains("wbgetentities")
        })
        assertEquals(1, calls.count { it.contains("imageinfo") })
        assertTrue(calls.any { it.contains("imcontinue=File%3Asecond%7C11") &&
            it.contains("continue=-%7C%7C") })
    }

    @Test fun thirteenthGalleryImageCanBeVerifiedWithoutNewPage() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val result = provider(client("Midnight Oil", true, calls, lateVerifiedFile = true))
            .findCandidates(anchor, ArtworkSlot.ARTIST_HERO)
        assertTrue(result is ProviderArtworkResult.Available)
        assertEquals(1, (result as ProviderArtworkResult.Available).items.size)
        assertEquals(1, calls.count { it.contains("prop=images") })
        assertEquals(2, calls.count { it.contains("prop=info") })
        assertEquals(1, calls.count { it.contains("imageinfo") })
    }

    @Test fun mismatchedImageinfoSourceCannotAuthorizeSelectedArtwork() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val result = provider(client("Midnight Oil", true, calls, foreignMetadata = true))
            .findCandidates(anchor, ArtworkSlot.ARTIST_HERO)
        assertTrue(result is ProviderArtworkResult.Failure)
        assertEquals("UNBOUND_IMAGEINFO", (result as ProviderArtworkResult.Failure).type)
        assertEquals(1, calls.count { it.contains("imageinfo") })
    }

    @Test fun malformedContinuationNeverAuthorizesFollowupPage() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val result = provider(client("Midnight Oil", true, calls,
            secondPage = true, malformedContinuation = true))
            .findCandidates(anchor, ArtworkSlot.ARTIST_THUMB)
        assertEquals(ProviderArtworkResult.NoMatch, result)
        assertEquals(1, calls.count { it.contains("prop=images") })
        assertTrue(calls.none { it.contains("imageinfo") })
    }

}
