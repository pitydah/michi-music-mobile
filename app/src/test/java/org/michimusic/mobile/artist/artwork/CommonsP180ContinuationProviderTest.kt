package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.runBlocking
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

/** HTTP fixtures: deliberately NOT a substitute for Gradle/Robolectric execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommonsP180ContinuationProviderTest {
    private val anchor = ArtistIdentityAnchor(
        "lib", "artist", "association", "05072650-4b05-4e8b-83d0-dba2576e306a",
        "Q48284", "Midnight Oil",
    )
    @Test fun recoversSecondSearchPageOnlyAfterVerifiedDepictionAndLegalArtwork() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            requests.add(url.toString())
            val result = when {
                url.queryParameter("list") == "search" ->
                    """{"query":{"search":[{"ns":6,"title":"File:Artist_second.jpg"}]}}"""
                url.queryParameter("prop") == "info" ->
                    """{"query":{"pages":{"42":{"pageid":42,"ns":6,"title":"File:Artist_second.jpg"}}}}"""
                url.queryParameter("ids") == "M42" ->
                    """{"entities":{"M42":{"statements":{"P180":[{"mainsnak":{"datavalue":{"value":{"id":"Q48284"}}}}]}}}}"""
                url.queryParameter("prop") == "imageinfo" ->
                    """{"query":{"pages":{"42":{"ns":6,"title":"File:Artist_second.jpg","imageinfo":[{
                    "thumburl":"https://upload.wikimedia.org/wikipedia/commons/example.jpg",
                    "width":1000,"height":900,"mime":"image/jpeg",
                    "descriptionurl":"https://commons.wikimedia.org/wiki/File:Artist_second.jpg",
                    "extmetadata":{"LicenseShortName":{"value":"CC BY-SA 4.0"},
                    "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"},
                    "Attribution":{"value":"Photographer"}}}]}}}}"""
                else -> error("Unexpected request $url")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", "application/json")
                .body(result.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val http = ArtworkHttp(client)
        val result = CommonsP180ContinuationProvider(http, CommonsImageInfo(http))
            .findCandidates(anchor, ArtworkSlot.ARTIST_THUMB)
        assertTrue(result is ProviderArtworkResult.Available)
        assertEquals(1, (result as ProviderArtworkResult.Available).items.size)
        assertEquals(1, requests.count { it.contains("list=search") && it.contains("sroffset=12") })
        assertEquals(1, requests.count { it.contains("prop=info") })
        assertEquals(1, requests.count { it.contains("ids=M42") })
        assertEquals(1, requests.count { it.contains("prop=imageinfo") })
    }

    @Test fun invalidQidNeverPerformsNetwork() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor {
            error("Network must be avoided for invalid QID")
        }.build()
        val result = CommonsP180ContinuationProvider(ArtworkHttp(client), CommonsImageInfo(ArtworkHttp(client)))
            .findCandidates(anchor.copy(verifiedQid = "not-a-qid"), ArtworkSlot.ARTIST_HERO)
        assertEquals(ProviderArtworkResult.NoMatch, result)
    }
}
