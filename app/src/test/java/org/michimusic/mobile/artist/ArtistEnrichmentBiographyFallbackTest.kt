package org.michimusic.mobile.artist

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Verified-QID biography fallback: a MusicBrainz `wikipedia` relation is preferred,
 * but an explicit MBID whose Wikidata item has sitelinks may still produce sourced
 * text. Identity is never resolved from the artist's name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ArtistEnrichmentBiographyFallbackTest {
    private val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"

    private fun musicBrainz(vararg relations: String): String =
        """{"id":"$mbid","name":"Midnight Oil","relations":[${relations.joinToString(",")}]}"""

    private fun relation(type: String, url: String): String =
        """{"type":"$type","url":{"resource":"$url"}}"""

    private fun wikidataEntity(
        sitelinks: String = "{}",
        claims: String = "{}",
    ): String = """{"entities":{"Q123":{"claims":$claims,"sitelinks":$sitelinks}}}"""

    private fun response(request: Request, body: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .header("Content-Type", "application/json").build()

    private fun repository(handler: (Request) -> Response?): ArtistEnrichmentRepository {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            handler(chain.request()) ?: response(chain.request(), "{}")
        }).build()
        return ArtistEnrichmentRepository(client)
    }

    @Test
    fun spanishSitelinkProducesSourcedBiographyForAConfirmedMbid() = runBlocking {
        val hosts = ConcurrentLinkedQueue<String>()
        val repo = repository { request ->
            hosts.add(request.url.host)
            when {
                request.url.host == "musicbrainz.org" -> response(
                    request,
                    musicBrainz(relation("wikidata", "https://www.wikidata.org/wiki/Q123")),
                )
                request.url.host == "www.wikidata.org" -> response(
                    request,
                    wikidataEntity(sitelinks = """{"eswiki":{"title":"Midnight Oil"}}"""),
                )
                request.url.host == "es.wikipedia.org" -> response(
                    request,
                    """{"query":{"pages":{"1":{"title":"Midnight Oil","extract":"Banda australiana de rock."}}}}""",
                )
                else -> null
            }
        }

        val profile = repo.confirmedProfile(mbid)

        assertEquals("Midnight Oil", profile.name)
        assertEquals("hosts=$hosts", ArtistMetadataOutcome.SUCCESS, profile.biographyOutcome)
        assertEquals("Banda australiana de rock.", profile.biography)
        assertEquals("https://es.wikipedia.org/wiki/Midnight_Oil", profile.biographySourceUrl)
        assertEquals(ArtistMetadataOutcome.SUCCESS, profile.biographyOutcome)
        assertEquals("Q123", profile.wikidataQid)
        assertEquals(ArtistMetadataOutcome.NO_P18, profile.portraitOutcome)
        assertNull(profile.portrait)
        assertTrue(hosts.contains("es.wikipedia.org"))
        assertTrue(hosts.none { it == "en.wikipedia.org" })
    }

    @Test
    fun englishSitelinkIsOnlyUsedWhenSpanishIsAbsent() = runBlocking {
        val hosts = ConcurrentLinkedQueue<String>()
        val queries = ConcurrentLinkedQueue<String>()
        val repo = repository { request ->
            hosts.add(request.url.host)
            when {
                request.url.host == "musicbrainz.org" -> response(
                    request,
                    musicBrainz(relation("wikidata", "https://www.wikidata.org/wiki/Q123")),
                )
                request.url.host == "www.wikidata.org" -> {
                    queries.add(request.url.query)
                    response(
                        request,
                        wikidataEntity(sitelinks = """{"enwiki":{"title":"Midnight Oil (band)"}}"""),
                    )
                }
                request.url.host == "en.wikipedia.org" -> response(
                    request,
                    """{"query":{"pages":{"1":{"title":"Midnight Oil (band)","extract":"Australian rock band."}}}}""",
                )
                else -> null
            }
        }

        val profile = repo.confirmedProfile(mbid)

        assertEquals("Australian rock band.", profile.biography)
        assertEquals("https://en.wikipedia.org/wiki/Midnight_Oil_(band)", profile.biographySourceUrl)
        assertEquals(ArtistMetadataOutcome.SUCCESS, profile.biographyOutcome)
        assertTrue(hosts.none { it == "es.wikipedia.org" })
        // The single Wikidata read asks for exactly the two allowed sitelink sites.
        val query = queries.single()
        assertTrue(query.contains("sitefilter=eswiki") && query.contains("enwiki"))
    }

    @Test
    fun redirectResolutionStoresTheExactArticleThatProducedTheText() = runBlocking {
        var wikipediaQuery: String? = null
        val repo = repository { request ->
            when (request.url.host) {
                "musicbrainz.org" -> response(
                    request,
                    musicBrainz(
                        relation("wikipedia", "https://es.wikipedia.org/wiki/Midnight_Oil_band"),
                    ),
                )
                "es.wikipedia.org" -> {
                    wikipediaQuery = request.url.query
                    response(
                        request,
                        """{"query":{"pages":{"1":{"title":"Midnight Oil","extract":"Banda australiana."}}}}""",
                    )
                }
                else -> null
            }
        }

        val profile = repo.confirmedProfile(mbid)

        assertEquals("https://es.wikipedia.org/wiki/Midnight_Oil", profile.biographySourceUrl)
        assertTrue(wikipediaQuery.orEmpty().contains("redirects=1"))
        assertEquals(ArtistMetadataOutcome.NO_IDENTITY, profile.portraitOutcome)
    }

    @Test
    fun rejectedPortraitLicenseNeverErasesTheVerifiedBiography() = runBlocking {
        val commonsRequests = ConcurrentLinkedQueue<String>()
        val repo = repository { request ->
            when (request.url.host) {
                "musicbrainz.org" -> response(
                    request,
                    musicBrainz(
                        relation("wikipedia", "https://es.wikipedia.org/wiki/Midnight_Oil"),
                        relation("wikidata", "https://www.wikidata.org/wiki/Q123"),
                    ),
                )
                "es.wikipedia.org" -> response(
                    request,
                    """{"query":{"pages":{"1":{"title":"Midnight Oil","extract":"Banda australiana."}}}}""",
                )
                "www.wikidata.org" -> response(
                    request,
                    wikidataEntity(
                        claims = "{\"P18\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"Midnight Oil.jpg\"}}}]}",
                    ),
                )
                "commons.wikimedia.org" -> {
                    commonsRequests.add(request.url.toString())
                    response(
                        request,
                        """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/wikipedia/commons/x/xx/Midnight_Oil.jpg","descriptionurl":"https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg","extmetadata":{"Attribution":{"value":"Jane Doe"},"LicenseShortName":{"value":"All rights reserved"},"LicenseUrl":{"value":"https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg"}}}]}}}}""",
                    )
                }
                else -> null
            }
        }

        val profile = repo.confirmedProfile(mbid)

        assertEquals("Banda australiana.", profile.biography)
        assertNull(profile.portrait)
        assertEquals(ArtistMetadataOutcome.NO_ALLOWED_LICENSE, profile.portraitOutcome)
        assertEquals(1, commonsRequests.size)
    }

    @Test
    fun missingP18IsDiagnosedWithoutInventingAnImage() = runBlocking {
        val commonsCalls = ConcurrentLinkedQueue<String>()
        val repo = repository { request ->
            when (request.url.host) {
                "musicbrainz.org" -> response(
                    request,
                    musicBrainz(relation("wikidata", "https://www.wikidata.org/wiki/Q123")),
                )
                "www.wikidata.org" -> response(request, wikidataEntity())
                "commons.wikimedia.org" -> {
                    commonsCalls.add(request.url.toString())
                    null
                }
                else -> null
            }
        }

        val profile = repo.confirmedProfile(mbid)

        assertNull(profile.portrait)
        assertEquals(ArtistMetadataOutcome.NO_P18, profile.portraitOutcome)
        assertEquals(ArtistMetadataOutcome.NO_SITELINK, profile.biographyOutcome)
        assertTrue(commonsCalls.isEmpty())
    }

    @Test
    fun malformedWikidataIdentifierFailsClosedInsteadOfSearchingByName() = runBlocking {
        val hosts = ConcurrentLinkedQueue<String>()
        val repo = repository { request ->
            hosts.add(request.url.host)
            if (request.url.host == "musicbrainz.org") response(
                request,
                musicBrainz(relation("wikidata", "https://www.wikidata.org/wiki/NotAQid")),
            ) else null
        }

        val profile = repo.confirmedProfile(mbid)

        assertNull(profile.portrait)
        assertNull(profile.wikidataQid)
        assertEquals(ArtistMetadataOutcome.DECODE_ERROR, profile.portraitOutcome)
        // Only the identity lookup is allowed: no name-based provider search.
        assertEquals(listOf("musicbrainz.org"), hosts.toList())
    }
}
