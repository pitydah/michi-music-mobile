package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.michimusic.link.errors.LinkException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkClientTest {
    private fun jsonClient(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    @Test
    fun fetchSyncManifest_usesNormalizedUrlAndAuthHeaders() = runBlocking {
        var requestedUrl = ""
        var authHeader = ""
        var deviceHeader = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            authHeader = request.headers[HttpHeaders.Authorization].orEmpty()
            deviceHeader = request.headers["X-Michi-Device-Id"].orEmpty()
            respond(
                content = """{"device_id":"phone 1","tracks":[{"track_id":"t1","title":"Song"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318/",
            sessionToken = "session-token",
            deviceToken = "device-token",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val result = client.fetchSyncManifest("phone 1")

        assertTrue(result.isSuccess)
        assertEquals("http://host:53318/api/v1/sync/manifest?device_id=phone+1", requestedUrl)
        assertEquals("Bearer device-token", authHeader)
        assertEquals("phone-1", deviceHeader)
        assertEquals("t1", result.getOrThrow().tracks.single().trackId)
    }

    @Test
    fun getServerInfo_parsesDesktopV1Capabilities() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "service":"michi-music-player",
                      "name":"Studio",
                      "api_version":"v1",
                      "michi_link_version":"1.0",
                      "roles":["desktop_player","library_master","sync_host"],
                      "auth":{"required":true,"strategy":"PLAYER_PASSWORD","token_refresh":false},
                      "features":{"library":true,"streaming":true,"token_refresh":false}
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318",
            httpClient = jsonClient(engine),
        )

        val info = client.getServerInfo().getOrThrow()

        assertEquals("michi-music-player", info.service)
        assertEquals("Studio", info.effectiveName)
        assertEquals(false, info.effectiveTokenRefresh)
        assertEquals(false, client.tokenRefreshSupported)
        assertTrue(info.roles.contains("sync_host"))
    }

    @Test
    fun pairConfirm_acceptsDesktopResponseWithoutRefreshToken() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "success":true,
                      "device_id":"phone-1",
                      "device_token":"device-token",
                      "permissions":["library.read","stream.read","sync.read_manifest"],
                      "server_device_id":"desktop-1",
                      "server_alias":"Studio"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318",
            httpClient = jsonClient(engine),
        )

        val result = client.pairConfirm(
            pairingId = "pair-1",
            username = "michi",
            password = "secret",
            clientDeviceId = "phone-1",
            alias = "Pixel",
            deviceModel = "Android",
        ).getOrThrow()

        assertEquals("device-token", result.deviceToken)
        assertEquals("device-token", client.deviceToken)
        assertTrue(result.refreshToken.isEmpty())
    }

    @Test
    fun fetchLibrary_normalizesDesktopTrackIds() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"tracks":[{"track_id":"desk-1","title":"Desktop Song"}],"total":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318",
            deviceToken = "device-token",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val library = client.fetchLibrary().getOrThrow()

        assertEquals(1, library.total)
        assertEquals("desk-1", library.tracks.single().id)
        assertEquals("desk-1", library.tracks.single().effectiveId)
    }

    @Test
    fun fetchSearch_normalizesDesktopTrackIds() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"results":[{"track_id":"desk-2","title":"Search Song"}],"query":"Search"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318",
            deviceToken = "device-token",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val results = client.search("Search").getOrThrow()

        assertEquals("desk-2", results.single().id)
        assertEquals("Search Song", results.single().title)
    }

    @Test
    fun fetchSyncManifestDelta_parsesDesktopModifiedAndRemovedObjects() = runBlocking {
        var requestedUrl = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                content = """
                    {
                      "manifest_id":"m2",
                      "device_id":"phone-1",
                      "since":"m1",
                      "until":"m2",
                      "added":[{"track_id":"new-1","title":"New"}],
                      "modified":[{"track_id":"mod-1","title":"Changed"}],
                      "removed":[{"track_id":"old-1","title":"Old"}],
                      "total_added":1,
                      "total_modified":1,
                      "total_removed":1
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318",
            deviceToken = "device-token",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val delta = client.fetchSyncManifestDelta("phone 1", "m1").getOrThrow()

        assertEquals("http://host:53318/api/v1/sync/manifest/delta?device_id=phone+1&cursor=m1", requestedUrl)
        assertEquals(listOf("new-1", "mod-1"), delta.effectiveTracks.map { it.trackId })
        assertEquals(listOf("old-1"), delta.effectiveRemoved)
        assertEquals(1, delta.totalModified)
    }

    @Test
    fun refreshToken_mapsDesktopNotImplemented() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"error":{"code":"NOT_IMPLEMENTED","message":"Token refresh is not supported"}}""",
                status = HttpStatusCode.NotImplemented,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val result = client.refreshToken("refresh-token", force = true)

        assertTrue(result.isFailure)
        assertEquals(LinkException.NotImplemented, result.exceptionOrNull())
        assertEquals(false, client.tokenRefreshSupported)
    }

    @Test
    fun getQueue_supportsLegacyItemsPayload() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"items":[{"track_id":"t1","title":"Legacy Track"}],"currentIndex":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318",
            deviceToken = "device-token",
            httpClient = jsonClient(engine),
        )

        val queue = client.getQueue().getOrThrow()

        assertEquals(0, queue.currentIndex)
        assertEquals("t1", queue.tracks.single().trackId)
        assertEquals("Legacy Track", queue.tracks.single().title)
    }
}
