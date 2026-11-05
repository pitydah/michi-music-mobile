package org.michimusic.mobile.artist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in live-provider proof. It never runs in CI: enable it explicitly with
 * `-e michiLiveProviders true`. The assertions only require verifiable text and
 * a classified portrait outcome; a legitimate "no licensed photo" is accepted.
 */
@RunWith(AndroidJUnit4::class)
class ArtistEnrichmentLiveProviderTest {
    private val enabled =
        InstrumentationRegistry.getArguments().getString("michiLiveProviders") == "true"

    // Redacted HTTP evidence for the opt-in run: host/path/code/type/size and a
    // short body prefix only, so a real provider mismatch is diagnosable without
    // dumping user data or full response bodies.
    private val repository: ArtistEnrichmentGateway by lazy {
        val client = okhttp3.OkHttpClient.Builder()
            .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val prefix = runCatching {
                    response.peekBody(300).string().replace(Regex("\\s+"), " ")
                }.getOrDefault("<unreadable>")
                println(
                    "LIVE_HTTP ${request.url.host}${request.url.encodedPath}" +
                        "?${request.url.query} -> ${response.code}" +
                        " ct=${response.header("Content-Type")}" +
                        " len=${response.header("Content-Length")} body=$prefix",
                )
                response
            }
            .build()
        ArtistEnrichmentRepository(client)
    }

    private fun profileFor(credit: String): ArtistExternalProfile = runBlocking {
        val candidates = repository.findCandidates(credit)
        val candidate = candidates.firstOrNull { it.name.equals(credit, ignoreCase = true) }
        assertNotNull("$credit must be found by the explicit provider search", candidate)
        val confirmed = repository.confirmedProfile(requireNotNull(candidate).mbid)
        assertEquals("The confirmed profile must keep the selected MBID", candidate.mbid, confirmed.mbid)
        println(
            "LIVE credit=$credit mbid=${confirmed.mbid} type=${confirmed.type} " +
                "area=${confirmed.area} qid=${confirmed.wikidataQid} " +
                "bioOutcome=${confirmed.biographyOutcome} bioUrl=${confirmed.biographySourceUrl} " +
                "portraitOutcome=${confirmed.portraitOutcome} " +
                "license=${confirmed.portrait?.license} image=${confirmed.portrait?.imageUrl}",
        )
        confirmed
    }

    @Test
    fun midnightOilHasAVerifiableBiographyAndClassifiedPortrait() {
        assumeTrue("live providers are opt-in", enabled)
        val profile = profileFor("Midnight Oil")
        assertEquals(ArtistMetadataOutcome.SUCCESS, profile.biographyOutcome)
        assertNotNull(profile.biography)
        assertTrue(requireNotNull(profile.biography).isNotBlank())
        assertTrue(profile.biographySourceUrl.orEmpty().startsWith("https://"))
        val outcome = profile.portraitOutcome
        assertTrue(
            "portrait outcome must be SUCCESS or a classified negative, was $outcome",
            outcome == ArtistMetadataOutcome.SUCCESS ||
                outcome == ArtistMetadataOutcome.NO_P18 ||
                outcome == ArtistMetadataOutcome.NO_ALLOWED_LICENSE ||
                outcome == ArtistMetadataOutcome.BAD_ATTRIBUTION,
        )
        if (outcome == ArtistMetadataOutcome.SUCCESS) {
            assertNotNull("SUCCESS without an allowed portrait is a false claim", profile.portrait)
            assertTrue(profile.portrait!!.license.isNotBlank())
            assertTrue(profile.portrait!!.attribution.isNotBlank())
        }
    }

    @Test
    fun flockOfSeagullsRecordsALegitimatePortraitOutcome() {
        assumeTrue("live providers are opt-in", enabled)
        val profile = profileFor("A Flock Of Seagulls")
        assertNotNull(profile.name)
        val outcome = profile.portraitOutcome
        assertTrue(
            "provider failure must not masquerade as a legitimate negative, was $outcome",
            outcome == ArtistMetadataOutcome.SUCCESS ||
                outcome == ArtistMetadataOutcome.NO_P18 ||
                outcome == ArtistMetadataOutcome.NO_ALLOWED_LICENSE ||
                outcome == ArtistMetadataOutcome.BAD_ATTRIBUTION,
        )
        if (outcome == ArtistMetadataOutcome.SUCCESS) {
            assertNotNull(profile.portrait)
            assertTrue(profile.portrait!!.attribution.isNotBlank())
        }
    }
}
