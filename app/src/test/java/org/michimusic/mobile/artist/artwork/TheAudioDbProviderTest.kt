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
 * TheAudioDB publishes photographs without a licence or an author, so the provider's job
 * is narrower than the Wikimedia ones: it may only offer review candidates, and nothing
 * it produces may ever look verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TheAudioDbProviderTest {
    private val anchor = ArtistIdentityAnchor(
        libraryId = "lib-1", creditKey = "midnight oil", albumGroupKey = "assoc-1",
        confirmedMbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
        verifiedQid = "Q48284", displayCredit = "Midnight Oil",
    )

    private fun provider(body: String, calls: MutableList<String> = mutableListOf()): TheAudioDbProvider {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            calls += chain.request().url.toString()
            Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/json")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        return TheAudioDbProvider(ArtworkHttp(client), apiKey = "123")
    }

    private fun artist(
        name: String = "Midnight Oil",
        thumb: String? = "https://r2.theaudiodb.com/images/media/artist/thumb/abc.jpg",
        fanart: String? = "https://r2.theaudiodb.com/images/media/artist/fanart/def.jpg",
    ): String {
        fun field(value: String?) = value?.let { "\"$it\"" } ?: "null"
        return """{"artists":[{"idArtist":"113094","strArtist":"$name",
            "strArtistThumb":${field(thumb)},"strArtistFanart":${field(fanart)},
            "strArtistFanart2":null,"strArtistFanart3":null,"strArtistFanart4":null}]}"""
    }

    @Test
    fun `the catalogue offers its photos as review candidates and never as verified ones`() =
        runBlocking {
            val result = provider(artist()).search(anchor, "Midnight Oil")
            val candidates = (result as ProviderArtworkResult.Available).items

            assertEquals(2, candidates.size)
            assertTrue(candidates.all { it.source == ArtworkSource.THEAUDIODB })
            assertTrue(candidates.all { it.rights == ArtworkRights.REQUIRES_REVIEW })
            assertTrue(candidates.all { it.evidence == ArtworkEvidence.UNVERIFIED })
            assertTrue(candidates.all { it.licenseId == null && it.licenseUrl == null })
            assertTrue(candidates.all { it.attribution == null })
            assertTrue(candidates.all { it.isManualEligible() })
            assertTrue(candidates.all { it.canonicalSourcePage == "https://www.theaudiodb.com/artist/113094" })
            assertTrue(candidates.all { it.mimeType == "image/jpeg" })
        }

    @Test
    fun `a search that names a different artist is not that artist`() = runBlocking {
        val result = provider(artist(name = "Midnight Oll")).search(anchor, "Midnight Oil")
        assertEquals(ProviderArtworkResult.NoMatch, result)
    }

    @Test
    fun `an empty catalogue is a clean no match`() = runBlocking {
        assertEquals(ProviderArtworkResult.NoMatch, provider("""{"artists":null}""").search(anchor, "Midnight Oil"))
    }

    @Test
    fun `a photo outside the catalogue bucket is dropped`() = runBlocking {
        val result = provider(artist(thumb = "https://evil.example/x.jpg")).search(anchor, "Midnight Oil")
        val candidates = (result as ProviderArtworkResult.Available).items
        assertEquals(1, candidates.size)
        assertEquals("https://r2.theaudiodb.com/images/media/artist/fanart/def.jpg", candidates.single().imageUrl)
    }

    @Test
    fun `an undeclared licence may be chosen but never passes as verified`() {
        val chosen = ArtistArtworkCandidate(
            source = ArtworkSource.THEAUDIODB,
            stableAssetId = "theaudiodb:113094:thumb",
            confirmedMbid = anchor.confirmedMbid,
            verifiedQid = anchor.verifiedQid,
            canonicalSourcePage = "https://www.theaudiodb.com/artist/113094",
            imageUrl = "https://r2.theaudiodb.com/images/media/artist/thumb/abc.jpg",
            originalWidth = 1000,
            originalHeight = 1000,
            mimeType = "image/jpeg",
            licenseId = null,
            licenseUrl = null,
            author = "Midnight Oil",
            attribution = null,
            evidence = ArtworkEvidence.USER_SELECTED,
            rights = ArtworkRights.UNDECLARED_LICENSE,
        )
        assertTrue(allowedArtworkCandidate(chosen, anchor))

        // The same photo pretending to be verified would need a licence it does not have.
        assertFalse(allowedArtworkCandidate(chosen.copy(rights = ArtworkRights.VERIFIED_ALLOWED), anchor))

        // Inventing one is worse: the gate refuses to let a fabricated id travel.
        assertFalse(
            allowedArtworkCandidate(
                chosen.copy(licenseId = "CC BY-SA 4.0", licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/"),
                anchor,
            ),
        )
        assertFalse(allowedArtworkCandidate(chosen.copy(attribution = "someone"), anchor))
        assertNull(chosen.licenseId)
    }
}
