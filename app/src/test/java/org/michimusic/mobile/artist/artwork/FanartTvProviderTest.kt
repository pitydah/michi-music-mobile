package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * fanart.tv grants access on written terms, so this class tests the terms as much as the
 * parser: one request per search, both keys travelling together, no licence invented and
 * nothing that could pass as verified artwork.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FanartTvProviderTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val anchor = ArtistIdentityAnchor(
        libraryId = "lib-1", creditKey = "midnight oil", albumGroupKey = "assoc-1",
        confirmedMbid = mbid, verifiedQid = "Q48284", displayCredit = "Midnight Oil",
    )

    private class Captured {
        val urls = mutableListOf<String>()
        val apiKeys = mutableListOf<String?>()
        val clientKeys = mutableListOf<String?>()
    }

    private fun provider(
        body: String,
        captured: Captured = Captured(),
        code: Int = 200,
        project: String? = "project-key",
        personal: String? = null,
    ): FanartTvProvider {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            captured.urls += chain.request().url.toString()
            captured.apiKeys += chain.request().header("api-key")
            captured.clientKeys += chain.request().header("client-key")
            Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(code).message("OK")
                .header("Content-Type", "application/json")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        return FanartTvProvider(ArtworkHttp(client), keys = { FanartTvProvider.Keys(project, personal) })
    }

    private fun catalogue(
        thumbs: Int = 1,
        backgrounds: Int = 1,
        namespaced: Boolean = true,
    ): String {
        fun images(kind: String, count: Int) = (1..count).joinToString(",") { index ->
            val url = if (namespaced) {
                "assets.fanart.tv/fanart/music/$mbid/$kind/$kind-$index.jpg"
            } else {
                "https://assets.fanart.tv/fanart/music/$mbid/$kind/$kind-$index.jpg"
            }
            """{"id":"$kind-$index","url":"$url","lang":"en","likes":"3","width":"1000","height":"562"}"""
        }
        return """{"name":"Midnight Oil","mbid_id":"$mbid","artistthumb":[${
            images("artistthumb", thumbs)
        }],"artistbackground":[${images("artistbackground", backgrounds)}]}"""
    }

    @Test
    fun `the catalogue offers review candidates that never claim a licence`() = runBlocking {
        val result = provider(catalogue()).search(anchor)
        val candidates = (result as ProviderArtworkResult.Available).items

        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.source == ArtworkSource.FANART_TV })
        assertTrue(candidates.all { it.rights == ArtworkRights.REQUIRES_REVIEW })
        assertTrue(candidates.all { it.evidence == ArtworkEvidence.UNVERIFIED })
        assertTrue(candidates.all { it.licenseId == null && it.licenseUrl == null })
        assertTrue(candidates.all { it.attribution == null })
        assertTrue(candidates.all { it.isManualEligible() })
        assertTrue(candidates.all { it.mbidOrNull() == mbid })
        assertTrue(candidates.all { it.canonicalSourcePage == "https://fanart.tv/artist/$mbid" })
        assertTrue(candidates.all { it.imageUrl.startsWith("https://assets.fanart.tv/") })
    }

    @Test
    fun `a search performs exactly one request and never walks the catalogue`() = runBlocking {
        val captured = Captured()
        provider(catalogue(thumbs = 5, backgrounds = 5), captured).search(anchor)

        // The terms forbid downloading everything we can reach, so one artist costs one
        // request and a bounded number of candidates.
        assertEquals(1, captured.urls.size)
        assertEquals("https://webservice.fanart.tv/v3.2/music/$mbid", captured.urls.single())
    }

    @Test
    fun `the personal key travels in addition to the project key`() = runBlocking {
        val captured = Captured()
        provider(catalogue(), captured, project = "project-key", personal = "personal-key").search(anchor)

        assertEquals("project-key", captured.apiKeys.single())
        assertEquals("personal-key", captured.clientKeys.single())
    }

    @Test
    fun `a personal key never replaces the project key`() = runBlocking {
        val captured = Captured()
        val result = provider(catalogue(), captured, project = null, personal = "personal-key").search(anchor)

        assertTrue(result is ProviderArtworkResult.Disabled)
        assertTrue(captured.urls.isEmpty())
    }

    @Test
    fun `without a confirmed musicbrainz identity it asks nobody`() = runBlocking {
        val captured = Captured()
        // TheMusicBrainz id is not optional in the anchor; a value that is not one is
        // what an unverified association looks like at this boundary.
        val result = provider(catalogue(), captured).search(anchor.copy(confirmedMbid = "midnight-oil"))

        assertEquals(ProviderArtworkResult.NoMatch, result)
        assertTrue(captured.urls.isEmpty())
    }

    @Test
    fun `an artist with no artwork answers not found instead of a provider failure`() = runBlocking {
        assertEquals(ProviderArtworkResult.NoMatch, provider("{}", code = 404).search(anchor))
    }

    @Test
    fun `photos hosted outside the fanart bucket are dropped`() = runBlocking {
        val body = """{"artistthumb":[{"id":"1","url":"https://evil.example/a.jpg"},
            {"id":"2","url":"assets.fanart.tv/fanart/music/$mbid/artistthumb/ok.jpg"}]}"""
        val candidates = (provider(body).search(anchor) as ProviderArtworkResult.Available).items

        assertEquals(1, candidates.size)
        assertTrue(candidates.single().imageUrl.startsWith("https://assets.fanart.tv/"))
    }

    @Test
    fun `an undeclared licence may be chosen but never passes as verified`() {
        val chosen = ArtistArtworkCandidate(
            source = ArtworkSource.FANART_TV,
            stableAssetId = "fanart:$mbid:artistthumb-1",
            confirmedMbid = mbid,
            verifiedQid = "Q48284",
            canonicalSourcePage = "https://fanart.tv/artist/$mbid",
            imageUrl = "https://assets.fanart.tv/fanart/music/$mbid/artistthumb/1.jpg",
            originalWidth = 1000,
            originalHeight = 562,
            mimeType = "image/jpeg",
            licenseId = null,
            licenseUrl = null,
            author = null,
            attribution = null,
            evidence = ArtworkEvidence.USER_SELECTED,
            rights = ArtworkRights.UNDECLARED_LICENSE,
        )
        assertTrue(allowedArtworkCandidate(chosen, anchor))
        assertFalse(allowedArtworkCandidate(chosen.copy(rights = ArtworkRights.VERIFIED_ALLOWED), anchor))
        assertFalse(
            allowedArtworkCandidate(
                chosen.copy(
                    licenseId = "CC BY-SA 4.0",
                    licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
                ),
                anchor,
            ),
        )
        assertFalse(allowedArtworkCandidate(chosen.copy(attribution = "someone"), anchor))
        assertNull(chosen.licenseId)
    }

    private fun ArtistArtworkCandidate.mbidOrNull(): String? = confirmedMbid
}
