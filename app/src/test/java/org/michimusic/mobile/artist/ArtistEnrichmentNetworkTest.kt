package org.michimusic.mobile.artist

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic metadata transport tests: all requests intercepted; no external network. */
// Robolectric 4.12.2 only ships up to SDK 34, while the app targets 35. Every
// other Robolectric suite in this repository pins SDK 33 for the same reason.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ArtistEnrichmentNetworkTest {
    @Test fun throttledMusicBrainzRetriesAndPreservesUserAgent() = runBlocking {
        val attempts = AtomicInteger(0)
        val hosts = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val agents = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            hosts.add(chain.request().url.host)
            agents.add(chain.request().header("User-Agent").orEmpty())
            val number = attempts.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (number == 1) 429 else 200).message("mock")
                .body((if (number == 1) "{}" else """{"artists":[{"id":"bd6d4f63-5cd0-4d78-a292-167997794c21","name":"Midnight Oil"}]}""")
                    .toResponseBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()
        }).build()
        val candidates = ArtistEnrichmentRepository(client).findCandidates("Midnight Oil")
        assertEquals(2, attempts.get())
        assertTrue(hosts.all { it == "musicbrainz.org" })
        assertTrue(agents.all { it.contains("MichiMusicMobile") })
        assertEquals("Midnight Oil", candidates.single().name)
    }

    @Test fun unauthorizedResponseIsNotRepeated() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            attempts.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("mock")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .header("Content-Type", "application/json").build()
        }).build()
        try {
            ArtistEnrichmentRepository(client).findCandidates("Midnight Oil")
            throw AssertionError("401 must fail closed")
        } catch (e: java.io.IOException) {
            assertEquals(1, attempts.get())
        }
    }

    @Test fun malformedJsonFailsClosedInsteadOfReturningNoCandidates() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            attempts.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("mock")
                .body("""{"artists":[""".toResponseBody("application/json".toMediaType()))
                .header("Content-Type", "application/json").build()
        }).build()
        try {
            ArtistEnrichmentRepository(client).findCandidates("Midnight Oil")
            throw AssertionError("malformed JSON must fail closed")
        } catch (e: Exception) {
            assertEquals(1, attempts.get())
        }
    }

    @Test fun oversizedResponseIsRejectedBeforeParsing() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            attempts.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("mock")
                .body(("x".repeat(300_000)).toResponseBody("application/json".toMediaType()))
                .header("Content-Type", "application/json").build()
        }).build()
        try {
            ArtistEnrichmentRepository(client).findCandidates("Midnight Oil")
            throw AssertionError("oversized body must fail closed")
        } catch (e: java.io.IOException) {
            assertEquals(1, attempts.get())
        }
    }

    @Test fun temporaryServiceFailureRetriesAfterTheAnnouncedDelay() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val number = attempts.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (number == 1) 503 else 200).message("mock")
                .body((if (number == 1) "{}" else """{"artists":[{"id":"bd6d4f63-5cd0-4d78-a292-167997794c21","name":"Midnight Oil"}]}""")
                    .toResponseBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .apply { if (number == 1) header("Retry-After", "0") }
                .build()
        }).build()
        val candidates = ArtistEnrichmentRepository(client).findCandidates("Midnight Oil")
        assertEquals(2, attempts.get())
        assertEquals("Midnight Oil", candidates.single().name)
    }

    @Test fun transportFailurePropagatesInsteadOfBecomingAnEmptyIdentity() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            attempts.incrementAndGet()
            throw java.io.IOException("offline")
        }).build()
        try {
            ArtistEnrichmentRepository(client).findCandidates("Midnight Oil")
            throw AssertionError("offline must fail closed")
        } catch (e: java.io.IOException) {
            assertEquals(1, attempts.get())
        }
    }
}
